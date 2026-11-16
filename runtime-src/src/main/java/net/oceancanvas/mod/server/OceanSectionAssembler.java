package net.oceancanvas.mod.server;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Pure section-sized ocean assembly for a future version-pinned chunk generator.
 *
 * Produces canonical 16x16x16 states without touching a live chunk. A separate
 * adapter must handle biome data, heightmaps, lighting, structures, serialization,
 * and post-restart physical verification before any result is accepted.
 */
final class OceanSectionAssembler {
    static final int EDGE = 16;
    static final int VOLUME = EDGE * EDGE * EDGE;

    interface FloorSampler {
        int floorY(int worldX, int worldZ);
    }

    private OceanSectionAssembler() {}

    static BlockState[] assemble(int chunkX, int chunkZ, int sectionY,
                                 int waterSurfaceY, FloorSampler floor) {
        BlockState[] states = new BlockState[VOLUME];
        int baseX = Math.multiplyExact(chunkX, EDGE);
        int baseZ = Math.multiplyExact(chunkZ, EDGE);
        int baseY = Math.multiplyExact(sectionY, EDGE);
        for (int z = 0; z < EDGE; z++) {
            for (int x = 0; x < EDGE; x++) {
                int floorY = floor.floorY(baseX + x, baseZ + z);
                if (floorY > waterSurfaceY)
                    throw new IllegalArgumentException("floor above water surface at " + (baseX + x) + "," + (baseZ + z));
                for (int y = 0; y < EDGE; y++) {
                    states[index(x, y, z)] = OceanTerrainTarget.blockAt(baseY + y, floorY, waterSurfaceY);
                }
            }
        }
        return states;
    }

    static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= EDGE || y >= EDGE || z >= EDGE)
            throw new IndexOutOfBoundsException("section-local coordinate");
        return (y << 8) | (z << 4) | x;
    }
}
