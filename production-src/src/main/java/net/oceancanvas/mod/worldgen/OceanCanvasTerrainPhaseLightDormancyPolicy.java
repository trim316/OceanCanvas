package net.oceancanvas.mod.worldgen;

/**
 * Pure policy for keeping generic LIGHT_ONLY debt dormant while terrain authoring
 * is still outstanding.
 *
 * <p>This policy does not certify, discard, or mutate lighting debt. It only says
 * whether an already-recorded generic obligation may be omitted from the hot
 * scheduler/deadline churn during the terrain phase. Player-visible work and work
 * carrying physical-repair authority are never dormant. Once terrain reaches zero,
 * every generic obligation becomes wake-eligible again.</p>
 */
final class OceanCanvasTerrainPhaseLightDormancyPolicy {
    /** Stable sentinel: dormant debt stays recorded without a near-term retry deadline. */
    static final long DORMANT_DEADLINE = Long.MAX_VALUE;

    private OceanCanvasTerrainPhaseLightDormancyPolicy() {}

    static boolean shouldDormant(
            long outstandingTerrainTargets,
            boolean playerVisible,
            boolean physicalRepairAuthorized) {
        return outstandingTerrainTargets > 0L
                && !playerVisible
                && !physicalRepairAuthorized;
    }

    static boolean shouldWake(
            long outstandingTerrainTargets,
            boolean playerVisible,
            boolean physicalRepairAuthorized) {
        return !shouldDormant(outstandingTerrainTargets, playerVisible, physicalRepairAuthorized);
    }

    /**
     * Returns a non-churning deadline only for generic terrain-phase debt. Bypass
     * cases preserve the caller's ordinary due tick exactly, so visible and
     * physical-repair work cannot be delayed by this policy.
     */
    static long schedulerDeadline(
            long ordinaryDueTick,
            long outstandingTerrainTargets,
            boolean playerVisible,
            boolean physicalRepairAuthorized) {
        return shouldDormant(outstandingTerrainTargets, playerVisible, physicalRepairAuthorized)
                ? DORMANT_DEADLINE
                : ordinaryDueTick;
    }

    static boolean isDormantDeadline(long dueTick) {
        return dueTick == DORMANT_DEADLINE;
    }
}
