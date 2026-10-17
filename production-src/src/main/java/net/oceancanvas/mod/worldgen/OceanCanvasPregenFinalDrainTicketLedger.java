package net.oceancanvas.mod.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the Java-side bookkeeping for Pregen final-drain FORCED tickets.
 *
 * <p>This is deliberately a state-only component. Minecraft ticket installation
 * and removal remain in {@link OceanCanvasSurfaceFlattener}, where the existing
 * server-thread/lifecycle guarantees and compatibility adapter calls are already
 * proven. Moving only the bookkeeping first gives the flattener one less mutable
 * subsystem to own without changing ticket semantics.</p>
 *
 * <p>The ledger preserves the pre-v253.99 concurrency model: active and pending
 * collections remain concurrent, reserve is an atomic set insertion, install and
 * release counters remain atomic, and pending CHUNK_LOAD releases are drained in
 * bounded batches at END_SERVER_TICK.</p>
 */
final class OceanCanvasPregenFinalDrainTicketLedger {
    private final OceanCanvasPrimitiveLongSet activeTickets = new OceanCanvasPrimitiveLongSet();
    private final OceanCanvasPrimitiveLongLongMap installedMillis = new OceanCanvasPrimitiveLongLongMap();
    private final OceanCanvasPrimitiveLongSet pendingRelease = new OceanCanvasPrimitiveLongSet();
    private final AtomicLong installs = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();

    boolean contains(long packed) {
        return activeTickets.contains(packed);
    }

    int activeCount() {
        return activeTickets.size();
    }

    boolean reserve(long packed) {
        return activeTickets.add(packed);
    }

    void markInstalled(long packed, long nowMillis) {
        installedMillis.put(packed, nowMillis);
        installs.incrementAndGet();
    }

    void rollbackReservation(long packed) {
        activeTickets.remove(packed);
        installedMillis.remove(packed);
    }

    long installedAt(long packed) {
        return installedMillis.get(packed);
    }

    long installedAtOr(long packed, long fallbackMillis) {
        return installedMillis.getOrDefault(packed, fallbackMillis);
    }

    /**
     * Retires Java ownership before the engine-side removal call, matching the
     * historical flattener ordering exactly.
     */
    boolean retire(long packed) {
        if (!activeTickets.remove(packed)) return false;
        installedMillis.remove(packed);
        return true;
    }

    long recordRelease() {
        return releases.incrementAndGet();
    }

    long installCount() {
        return installs.get();
    }

    long releaseCount() {
        return releases.get();
    }

    long[] activeSnapshot() { return activeTickets.snapshot(); }

    List<Entry> entrySnapshot(long fallbackMillis) {
        long[] active = activeTickets.snapshot();
        ArrayList<Entry> out = new ArrayList<>(active.length);
        for (long packed : active) {
            out.add(new Entry(packed, installedMillis.getOrDefault(packed, fallbackMillis)));
        }
        return List.copyOf(out);
    }

    void queueDeferredRelease(long packed) {
        pendingRelease.add(packed);
    }

    boolean hasDeferredReleases() {
        return !pendingRelease.isEmpty();
    }

    long[] drainDeferredReleaseBatch(int limit) {
        return pendingRelease.drainFirst(limit);
    }

    void clearActiveTickets() {
        activeTickets.clear();
        installedMillis.clear();
    }

    /** Clear Java bookkeeping only after native TicketStorage deactivation. */
    void clearAfterNativeDeactivation() {
        clearActiveTickets();
        pendingRelease.clear();
    }

    record Entry(long packed, long installedMillis) {}
}
