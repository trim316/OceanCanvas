package net.oceancanvas.core.production;

import java.util.Set;

/** Pure policy kernel for the v1 production scheduler. */
public final class ProductionScalePolicy {
    public static final int TERRAIN_ACTIVE_LIMIT = 128;
    public static final int LIGHT_ACTIVE_LIMIT = 64;
    public static final int LIGHT_BACKLOG_LIMIT = 512;
    public static final long CHECKPOINT_INTERVAL = 2048L;

    private ProductionScalePolicy() {}

    public static boolean checkpointDue(long durablePrefix, long verifiedFrontier) {
        if (durablePrefix < 0 || verifiedFrontier < durablePrefix) {
            throw new IllegalArgumentException("invalid production checkpoint frontier");
        }
        return verifiedFrontier - durablePrefix >= CHECKPOINT_INTERVAL;
    }

    public static boolean canAdmitTerrain(int terrainActive, int lightPending, int lightActive) {
        if (terrainActive < 0 || lightPending < 0 || lightActive < 0) {
            throw new IllegalArgumentException("negative scheduler population");
        }
        return terrainActive < TERRAIN_ACTIVE_LIMIT
                && lightPending + lightActive < LIGHT_BACKLOG_LIMIT;
    }

    /**
     * Advance only through the contiguous verified prefix. Out-of-order work
     * beyond the first gap is deliberately replayable and must never be skipped
     * by a durable checkpoint after a crash.
     */
    public static long advanceVerifiedFrontier(long frontier, Set<Long> completed) {
        if (frontier < 0) throw new IllegalArgumentException("negative frontier");
        while (completed.remove(frontier)) frontier++;
        return frontier;
    }
}
