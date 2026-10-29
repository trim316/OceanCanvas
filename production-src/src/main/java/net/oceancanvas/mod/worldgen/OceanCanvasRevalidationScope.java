package net.oceancanvas.mod.worldgen;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
/** Scheduling scope matches the existing structure light-rearm footprint, including its one-chunk ring. */
final class OceanCanvasRevalidationScope {
    static boolean affectsChunk(Iterable<BoundingBox> pending, int chunkX, int chunkZ) {
        for (BoundingBox box : pending) {
            if (box == null) return true;
            long minX=(long)Math.floorDiv(box.minX(),16)-1;
            long maxX=(long)Math.floorDiv(box.maxX(),16)+1;
            long minZ=(long)Math.floorDiv(box.minZ(),16)-1;
            long maxZ=(long)Math.floorDiv(box.maxZ(),16)+1;
            if(chunkX>=minX && chunkX<=maxX && chunkZ>=minZ && chunkZ<=maxZ) return true;
        }
        return false;
    }
}
