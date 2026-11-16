package net.oceancanvas.mod.server;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Experimental section adapter for disposable-world generation trials only.
 *
 * This operates on an UNPUBLISHED, caller-owned section. It must never be
 * applied to a live LevelChunk: section mutation alone bypasses heightmaps,
 * light engine, fluids, POIs, block entities and structure lifecycle.
 */
final class OceanUnpublishedSectionAdapter {
    private OceanUnpublishedSectionAdapter() {}

    static int writeUnpublished(LevelChunkSection section, BlockState[] states) {
        if (section == null || states == null || states.length != OceanSectionAssembler.VOLUME)
            throw new IllegalArgumentException("section and complete 4096-state payload required");
        for (BlockState state : states)
            if (state == null) throw new IllegalArgumentException("null block state");
        int changes = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    BlockState next = states[OceanSectionAssembler.index(x,y,z)];
                    if (!section.getBlockState(x,y,z).equals(next)) {
                        section.setBlockState(x,y,z,next);
                        changes++;
                    }
                }
        return changes;
    }

    static void assertExact(LevelChunkSection section, BlockState[] expected) {
        if (section == null || expected == null || expected.length != OceanSectionAssembler.VOLUME)
            throw new IllegalArgumentException("complete section required");
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
            int i = OceanSectionAssembler.index(x,y,z);
            if (!section.getBlockState(x,y,z).equals(expected[i]))
                throw new IllegalStateException("section mismatch at "+x+","+y+","+z);
        }
    }
}
