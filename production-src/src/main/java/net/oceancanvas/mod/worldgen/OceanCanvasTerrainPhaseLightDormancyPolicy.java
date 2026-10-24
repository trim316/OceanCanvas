package net.oceancanvas.mod.worldgen;

/**
 * Pure policy for keeping generic LIGHT_ONLY debt dormant while terrain authoring
 * is still outstanding. Dormancy is scheduling state only: it never certifies,
 * discards, or removes authoritative lighting debt.
 */
final class OceanCanvasTerrainPhaseLightDormancyPolicy {
    static final long DORMANT_DEADLINE = Long.MAX_VALUE;

    private OceanCanvasTerrainPhaseLightDormancyPolicy() {}

    static boolean shouldDormant(long outstandingTerrainTargets, boolean playerVisible, boolean physicalRepairAuthorized) {
        return outstandingTerrainTargets > 0L && !playerVisible && !physicalRepairAuthorized;
    }

    static long schedulerDeadline(long ordinaryDueTick, long outstandingTerrainTargets,
            boolean playerVisible, boolean physicalRepairAuthorized) {
        return shouldDormant(outstandingTerrainTargets, playerVisible, physicalRepairAuthorized)
                ? DORMANT_DEADLINE : ordinaryDueTick;
    }

    static boolean isDormantDeadline(long dueTick) {
        return dueTick == DORMANT_DEADLINE;
    }
}
