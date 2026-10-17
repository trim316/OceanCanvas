package net.oceancanvas.mod.worldgen;

/** Placement callbacks remain mandatory at every boundary of a bulk water fill. */
final class OceanCanvasBulkWaterPlacementPolicy {
    private OceanCanvasBulkWaterPlacementPolicy() {}

    static boolean skipOnPlace(boolean wholeUnprotectedInterior, boolean canonicalSourceWater,
            int localX, int localZ, int y, int floorY, int surfaceY) {
        return wholeUnprotectedInterior && canonicalSourceWater
                && localX > 0 && localX < 15 && localZ > 0 && localZ < 15
                && y > floorY + 1 && y < surfaceY;
    }
}
