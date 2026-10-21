package net.oceancanvas.mod.worldgen;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Inclusive native box containment without allocating a BlockPos per terrain cell.
 * Retains live box coordinates; no cached bounds or protection authority changes. */
final class OceanCanvasStructureBoundsQuery {
    private OceanCanvasStructureBoundsQuery() { }
    static boolean contains(List<BoundingBox> boxes, int x, int y, int z) {
        for (BoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX()
                    && y >= box.minY() && y <= box.maxY()
                    && z >= box.minZ() && z <= box.maxZ()) return true;
        }
        return false;
    }
}
