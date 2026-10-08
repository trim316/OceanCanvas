package net.oceancanvas.mod.worldgen;

/**
 * Owns lighting retry-lane bookkeeping.
 *
 * <p>Ordinary strict-fault retries, quarantine retries, mixed pressure parks, and
 * generic terrain-phase dormancy are physically separate deadline heaps. Generic
 * dormancy is scheduler state only: authoritative lighting debt remains elsewhere
 * and is never certified or discarded by this ledger.</p>
 */
final class OceanCanvasLightRetryLedger {
    private final OceanCanvasPrimitiveLongDeadlineHeap ordinaryDue = new OceanCanvasPrimitiveLongDeadlineHeap();
    private final OceanCanvasPrimitiveLongDeadlineHeap quarantineDue = new OceanCanvasPrimitiveLongDeadlineHeap();
    private final OceanCanvasPrimitiveLongDeadlineHeap pressureParkDue = new OceanCanvasPrimitiveLongDeadlineHeap();
    private final OceanCanvasPrimitiveLongDeadlineHeap genericDormantDue = new OceanCanvasPrimitiveLongDeadlineHeap();
    private final OceanCanvasPrimitiveLongIntMap genericDormantMembership = new OceanCanvasPrimitiveLongIntMap();
    private final OceanCanvasPrimitiveLongIntMap backoffStreaks = new OceanCanvasPrimitiveLongIntMap();

    void offerOrdinary(long packed, long dueTick) { ordinaryDue.offer(packed, dueTick); }
    void offerQuarantine(long packed, long dueTick) { quarantineDue.offer(packed, dueTick); }
    void offerPressurePark(long packed, long dueTick) { pressureParkDue.offer(packed, dueTick); }

    /**
     * Admit generic terrain-dormant debt at most once. The authoritative debt map
     * can revisit the same chunk while terrain remains; allowing every revisit to
     * append another Long.MAX_VALUE heap node would merely move the old scheduler
     * churn into the dedicated lane and could create an unbounded wake tail when
     * terrain reaches zero.
     */
    void offerGenericDormant(long packed) {
        if (genericDormantMembership.putIfAbsent(packed, 1) != OceanCanvasPrimitiveLongIntMap.ABSENT) return;
        genericDormantDue.offer(packed, OceanCanvasTerrainPhaseLightDormancyPolicy.DORMANT_DEADLINE);
    }

    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDueOrdinary(long nowTick) { return ordinaryDue.pollDue(nowTick); }
    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDueQuarantine(long nowTick) { return quarantineDue.pollDue(nowTick); }
    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDuePressurePark(long nowTick) { return pressureParkDue.pollDue(nowTick); }

    /**
     * Generic dormant work is intentionally invisible until terrain is complete.
     * At transition to zero, callers still drain one entry at a time under their
     * existing proof/headroom/light/physical budgets; this method never widens the
     * mixed pressure-park heap or accelerates recovery backoffs.
     */
    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollGenericDormant(boolean terrainComplete) {
        if (!terrainComplete) return null;
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry = genericDormantDue.pollDue(Long.MAX_VALUE);
        if (entry != null) genericDormantMembership.remove(entry.packed());
        return entry;
    }

    int ordinarySize() { return ordinaryDue.size(); }
    int quarantineSize() { return quarantineDue.size(); }
    int pressureParkSize() { return pressureParkDue.size(); }
    int genericDormantSize() { return genericDormantMembership.size(); }
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
        genericDormantDue.clear();
        genericDormantMembership.clear();
        backoffStreaks.clear();
    }
}
