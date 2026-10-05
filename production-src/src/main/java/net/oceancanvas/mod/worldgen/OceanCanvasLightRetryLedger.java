package net.oceancanvas.mod.worldgen;

/**
 * Owns persistent lighting retry-lane bookkeeping.
 *
 * <p>Ordinary strict-fault retries, quarantine retries, and scheduler-only pressure parks
 * are intentionally physically separate deadline heaps. Pressure parking is not evidence
 * that a chunk failed the skylight oracle and therefore must never advance or reset strict-
 * fault backoff identity. Backoff streak identity belongs only to the ordinary/quarantine
 * fault lanes.</p>
 */
final class OceanCanvasLightRetryLedger {
    // v253.125.36 removes the now-dead boxed ConcurrentLinkedQueue compatibility
    // mirrors. Since .35 all active wake paths use deadline heaps and authoritative
    // due-time maps. Keeping a second boxed node per retry only increased allocation
    // and made successful retirement scan queues that were never consumed.
    private final OceanCanvasPrimitiveLongDeadlineHeap ordinaryDue = new OceanCanvasPrimitiveLongDeadlineHeap();
    private final OceanCanvasPrimitiveLongDeadlineHeap quarantineDue = new OceanCanvasPrimitiveLongDeadlineHeap();
    private final OceanCanvasPrimitiveLongDeadlineHeap pressureParkDue = new OceanCanvasPrimitiveLongDeadlineHeap();
    private final OceanCanvasPrimitiveLongIntMap backoffStreaks = new OceanCanvasPrimitiveLongIntMap();

    void offerOrdinary(long packed, long dueTick) { ordinaryDue.offer(packed, dueTick); }
    void offerQuarantine(long packed, long dueTick) { quarantineDue.offer(packed, dueTick); }
    void offerPressurePark(long packed, long dueTick) { pressureParkDue.offer(packed, dueTick); }
    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDueOrdinary(long nowTick) { return ordinaryDue.pollDue(nowTick); }
    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDueQuarantine(long nowTick) { return quarantineDue.pollDue(nowTick); }
    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDuePressurePark(long nowTick) { return pressureParkDue.pollDue(nowTick); }
    int ordinarySize() { return ordinaryDue.size(); }
    int quarantineSize() { return quarantineDue.size(); }
    int pressureParkSize() { return pressureParkDue.size(); }
    boolean queuesEmpty() { return ordinaryDue.isEmpty() && quarantineDue.isEmpty(); }

    int incrementBackoffStreak(long packed, int max) {
        int previous = backoffStreaks.getOrDefault(packed, 0);
        int next = Math.min(max, previous + 1);
        backoffStreaks.put(packed, next);
        return next;
    }

    int backoffStreak(long packed) { return backoffStreaks.getOrDefault(packed, 0); }
    void clearBackoffStreak(long packed) { backoffStreaks.remove(packed); }

    void clear() {
        ordinaryDue.clear();
        quarantineDue.clear();
        pressureParkDue.clear();
        backoffStreaks.clear();
    }
}
