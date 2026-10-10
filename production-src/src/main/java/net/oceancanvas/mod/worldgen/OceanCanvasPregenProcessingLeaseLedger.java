package net.oceancanvas.mod.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns Java-side bookkeeping for the bounded Pregen CARVE lane and its optional
 * physical recovery leases.
 *
 * <p>This component intentionally has no Minecraft/runtime dependency. The
 * flattener remains authoritative for readiness, promotion timing, self-ticket
 * coverage, and every actual engine ticket add/remove. The ledger only owns the
 * mutable identity/timestamp/counter state that those proven call sites mutate.</p>
 *
 * <p>Logical CARVE-lane ownership is distinct from physical recovery-ticket
 * ownership. Since v228.1 most promoted targets already have radius-3 shared
 * self ownership and therefore need no duplicate FORCED ticket. Timestamps are
 * retained for every logical promotion so stale-neighbor diagnostics preserve
 * their historical semantics.</p>
 */
final class OceanCanvasPregenProcessingLeaseLedger {
    private final OceanCanvasPrimitiveLongSet carveLaneTargets = new OceanCanvasPrimitiveLongSet();
    private final OceanCanvasPrimitiveLongSet physicalTickets = new OceanCanvasPrimitiveLongSet();
    private final OceanCanvasPrimitiveLongLongMap installedMillis = new OceanCanvasPrimitiveLongLongMap();
    private final OceanCanvasPrimitiveLongSet staleLogged = new OceanCanvasPrimitiveLongSet();
    private final AtomicLong installs = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();

    boolean isCarveLaneTarget(long packed) {
        return carveLaneTargets.contains(packed);
    }

    int carveLaneCount() {
        return carveLaneTargets.size();
    }

    /**
     * Reserve one logical lane slot and establish its diagnostic age.
     * Returns false for an existing target or when the caller-supplied cap is full.
     */
    boolean promote(long packed, long nowMillis, int maxActive) {
        if (carveLaneTargets.contains(packed)) return false;
        if (carveLaneTargets.size() >= maxActive) return false;
        carveLaneTargets.add(packed);
        if (!installedMillis.containsKey(packed)) installedMillis.put(packed, nowMillis);
        staleLogged.remove(packed);
        return true;
    }

    boolean hasPhysicalTicket(long packed) {
        return physicalTickets.contains(packed);
    }

    /** Record physical ownership only after the engine ticket add succeeds. */
    void markPhysicalInstalled(long packed, long nowMillis) {
        physicalTickets.add(packed);
        installedMillis.put(packed, nowMillis);
        staleLogged.remove(packed);
        installs.incrementAndGet();
    }

    /** Roll back a failed engine install without producing release telemetry. */
    void rollbackPromotion(long packed) {
        physicalTickets.remove(packed);
        carveLaneTargets.remove(packed);
        installedMillis.remove(packed);
        staleLogged.remove(packed);
    }

    /**
     * Retire logical ownership first and return whether an engine ticket must be
     * removed. This preserves the flattener's historical release ordering.
     */
    boolean retireLogical(long packed) {
        carveLaneTargets.remove(packed);
        staleLogged.remove(packed);
        boolean physical = physicalTickets.contains(packed);
        if (!physical) installedMillis.remove(packed);
        return physical;
    }

    /** Commit physical bookkeeping only after native ticket removal succeeds. */
    boolean commitPhysicalRetirement(long packed) {
        if (!physicalTickets.remove(packed)) return false;
        installedMillis.remove(packed);
        return true;
    }

    long installedAt(long packed) {
        return installedMillis.get(packed);
    }

    long installedAtOr(long packed, long fallbackMillis) {
        return installedMillis.getOrDefault(packed, fallbackMillis);
    }

    /** Return true once, the first time this promotion is reported stale. */
    boolean markStaleLogged(long packed) {
        return staleLogged.add(packed);
    }

    long recordRelease() {
        return releases.incrementAndGet();
    }

    int physicalTicketCount() {
        return physicalTickets.size();
    }

    long installCount() {
        return installs.get();
    }

    long releaseCount() {
        return releases.get();
    }

    long[] carveLaneSnapshot() { return carveLaneTargets.snapshot(); }

    long[] physicalTicketSnapshot() { return physicalTickets.snapshot(); }

    List<Entry> physicalEntrySnapshot(long fallbackMillis) {
        long[] active = physicalTickets.snapshot();
        ArrayList<Entry> out = new ArrayList<>(active.length);
        for (long packed : active) {
            out.add(new Entry(packed, installedMillis.getOrDefault(packed, fallbackMillis)));
        }
        return List.copyOf(out);
    }

    /** Clear bookkeeping only after native TicketStorage deactivation. */
    void clearAfterNativeDeactivation() {
        carveLaneTargets.clear();
        physicalTickets.clear();
        installedMillis.clear();
        staleLogged.clear();
    }

    record Entry(long packed, long installedMillis) {}
}
