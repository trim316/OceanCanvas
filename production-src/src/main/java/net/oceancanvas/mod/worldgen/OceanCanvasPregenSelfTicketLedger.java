package net.oceancanvas.mod.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns Java-side bookkeeping and the multi-map ownership transaction for
 * Pregen's bounded self-ticket pool.
 *
 * <p>The ledger is intentionally Minecraft-free. Engine ticket mutation is
 * supplied by the flattener as a callback and is executed while the same
 * ownership monitor is held. That preserves the historical C2ME-sensitive
 * target -> anchor -> refcount -> active-ticket transaction without moving any
 * compatibility-sensitive Minecraft calls into this component.</p>
 *
 * <p>The active-anchor cap and radius remain caller-owned policy. Install and
 * release telemetry are committed only after the supplied engine operation
 * succeeds, matching the pre-extraction ordering. A server-stop native ticket
 * deactivation can clear the Java-side ownership state in one operation.</p>
 */
final class OceanCanvasPregenSelfTicketLedger {
    private final OceanCanvasPrimitiveLongSet activeAnchors = new OceanCanvasPrimitiveLongSet();
    private final OceanCanvasPrimitiveLongLongMap targetAnchors = new OceanCanvasPrimitiveLongLongMap();
    private final OceanCanvasPrimitiveLongIntMap anchorRefs = new OceanCanvasPrimitiveLongIntMap();
    private final AtomicLong installs = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();
    private boolean capLogged;

    @FunctionalInterface
    interface TicketAction {
        void run() throws Throwable;
    }

    @FunctionalInterface
    interface AnchorTicketAction {
        void run(long anchor) throws Throwable;
    }

    enum InstallResult {
        ALREADY_OWNED,
        ATTACHED_EXISTING,
        INSTALLED_NEW,
        CAP_REJECTED_FIRST,
        CAP_REJECTED
    }

    enum ReleaseResult {
        NOT_OWNED,
        SHARED_RETAINED,
        BOOKKEEPING_ONLY,
        RETIRED_WITHOUT_ENGINE,
        ENGINE_REMOVED
    }

    synchronized boolean hasOwnership(long targetPacked) {
        long anchor = targetAnchors.get(targetPacked);
        return anchor != OceanCanvasPrimitiveLongLongMap.ABSENT && activeAnchors.contains(anchor)
                && anchorRefs.getOrDefault(anchor, 0) > 0;
    }

    synchronized int activeAnchorCount() {
        return activeAnchors.size();
    }

    synchronized long installCount() {
        return installs.get();
    }

    synchronized long releaseCount() {
        return releases.get();
    }

    /**
     * Establish target ownership while preserving the historical one-monitor
     * ownership transaction. The engine add callback is invoked only for a new
     * anchor and runs before Java ownership is committed.
     */
    synchronized InstallResult install(
            long targetPacked,
            long anchor,
            int maxAnchors,
            TicketAction engineInstall) throws Throwable {
        long existing = targetAnchors.get(targetPacked);
        if (existing != OceanCanvasPrimitiveLongLongMap.ABSENT && activeAnchors.contains(existing)
                && anchorRefs.getOrDefault(existing, 0) > 0) {
            return InstallResult.ALREADY_OWNED;
        }

        // Preserve the historical stale-bookkeeping repair: detach only the
        // stale target mapping here. Any separately orphaned anchor is retired by
        // the existing bounded orphan cleanup path rather than by admission.
        if (existing != OceanCanvasPrimitiveLongLongMap.ABSENT) targetAnchors.remove(targetPacked);

        if (activeAnchors.contains(anchor)) {
            targetAnchors.put(targetPacked, anchor);
            anchorRefs.put(anchor, anchorRefs.getOrDefault(anchor, 0) + 1);
            return InstallResult.ATTACHED_EXISTING;
        }

        if (activeAnchors.size() >= maxAnchors) {
            if (!capLogged) {
                capLogged = true;
                return InstallResult.CAP_REJECTED_FIRST;
            }
            return InstallResult.CAP_REJECTED;
        }

        try {
            engineInstall.run();
            activeAnchors.add(anchor);
            anchorRefs.put(anchor, 1);
            targetAnchors.put(targetPacked, anchor);
            installs.incrementAndGet();
            return InstallResult.INSTALLED_NEW;
        } catch (Throwable t) {
            targetAnchors.remove(targetPacked);
            activeAnchors.remove(anchor);
            anchorRefs.remove(anchor);
            throw t;
        }
    }

    /**
     * Retire one target reference. If it owns the final reference to an active
     * anchor, the supplied engine removal runs while the ownership monitor is
     * still held. Passing {@code null} intentionally performs bookkeeping-only
     * retirement, matching the historical world-null path.
     */
    synchronized ReleaseResult release(long targetPacked, AnchorTicketAction engineRemove) throws Throwable {
        long anchor = targetAnchors.get(targetPacked);
        if (anchor == OceanCanvasPrimitiveLongLongMap.ABSENT) return ReleaseResult.NOT_OWNED;
        int refs = anchorRefs.getOrDefault(anchor, 0);
        if (refs > 1) {
            targetAnchors.remove(targetPacked);
            anchorRefs.put(anchor, refs - 1);
            return ReleaseResult.SHARED_RETAINED;
        }
        if (!activeAnchors.contains(anchor)) {
            targetAnchors.remove(targetPacked);
            anchorRefs.remove(anchor);
            return ReleaseResult.BOOKKEEPING_ONLY;
        }
        if (engineRemove == null) {
            targetAnchors.remove(targetPacked);
            anchorRefs.remove(anchor);
            activeAnchors.remove(anchor);
            return ReleaseResult.RETIRED_WITHOUT_ENGINE;
        }

        // Engine-first final-reference retirement: if native removal throws, retain
        // the complete ownership transaction so bounded cleanup can retry safely.
        engineRemove.run(anchor);
        targetAnchors.remove(targetPacked);
        anchorRefs.remove(anchor);
        activeAnchors.remove(anchor);
        releases.incrementAndGet();
        return ReleaseResult.ENGINE_REMOVED;
    }

    synchronized long[] targetSnapshot() { return targetAnchors.keysSnapshot(); }

    /** Stable diagnostic snapshot of active anchors and their target owners. */
    synchronized List<AnchorEntry> anchorSnapshot() {
        Map<Long, ArrayList<Long>> targetsByAnchor = new HashMap<>();
        for (long target : targetAnchors.keysSnapshot()) {
            long anchor = targetAnchors.get(target);
            if (anchor == OceanCanvasPrimitiveLongLongMap.ABSENT) continue;
            targetsByAnchor.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(target);
        }
        long[] anchors = activeAnchors.snapshot();
        ArrayList<AnchorEntry> out = new ArrayList<>(anchors.length);
        for (long anchor : anchors) {
            List<Long> targets = targetsByAnchor.get(anchor);
            out.add(new AnchorEntry(anchor, anchorRefs.getOrDefault(anchor, 0), targets == null ? List.of() : List.copyOf(targets)));
        }
        return List.copyOf(out);
    }

    /**
     * Clear bookkeeping only after native TicketStorage deactivation. The
     * historical one-shot cap warning remains process-lifetime state and is not
     * reset here.
     *
     * @return number of active anchors discarded from Java bookkeeping
     */
    synchronized int clearAfterNativeDeactivation() {
        int abandoned = activeAnchors.size();
        targetAnchors.clear();
        anchorRefs.clear();
        activeAnchors.clear();
        return abandoned;
    }

    record AnchorEntry(long anchor, int refs, List<Long> targets) {}
}
