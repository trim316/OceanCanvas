package net.oceancanvas.mod.worldgen;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Small structure-geometry helpers shared by structure relocation/rescue code.
 *
 * <p>This class is intentionally stateless. Keeping piece-bound computation here avoids
 * re-implementing the same min/max walk in each structure subsystem while leaving all mutation
 * policy with the caller.</p>
 */
public final class OceanCanvasStructureGeometry {
    private OceanCanvasStructureGeometry() {}

    /** Corner-to-corner extent of every piece in a structure, or {@code null} when empty. */
    public static BoundingBox pieceExtent(StructureStart start) {
        if (start == null) return null;
        java.util.List<StructurePiece> pieces = start.getPieces();
        if (pieces == null || pieces.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            minX = Math.min(minX, box.minX()); minY = Math.min(minY, box.minY()); minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX()); maxY = Math.max(maxY, box.maxY()); maxZ = Math.max(maxZ, box.maxZ());
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
