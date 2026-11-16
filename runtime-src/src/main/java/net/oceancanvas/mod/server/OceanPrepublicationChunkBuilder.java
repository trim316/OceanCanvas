package net.oceancanvas.mod.server;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Prepublication chunk-section preparation contract.
 *
 * Callers must provide freshly allocated, unpublished sections. This is a
 * staging adapter, NOT a worldgen hook and NOT proof of a valid persisted chunk.
 * The generation lifecycle must subsequently rebuild heightmaps, initialize
 * biomes, integrate structures, run lighting, save, restart and independently
 * verify before declaring a chunk complete.
 */
final class OceanPrepublicationChunkBuilder {
    interface SectionFactory {
        LevelChunkSection newSection(int sectionY);
    }
    interface SectionSink {
        void accept(int sectionY, LevelChunkSection section);
    }

    private OceanPrepublicationChunkBuilder() {}

    static int build(int chunkX, int chunkZ, int minSectionY, int maxSectionYExclusive,
                     int waterSurfaceY, OceanSectionAssembler.FloorSampler floor,
                     SectionFactory factory, SectionSink sink) {
        if (minSectionY >= maxSectionYExclusive || floor == null || factory == null || sink == null)
            throw new IllegalArgumentException("invalid section range or missing collaborator");
        // Bound memory: assemble and publish one section at a time.
        int blocksWritten = 0;
        for (int sectionY = minSectionY; sectionY < maxSectionYExclusive; sectionY++) {
            BlockState[] target = OceanSectionAssembler.assemble(chunkX, chunkZ, sectionY,
                                                                  waterSurfaceY, floor);
            LevelChunkSection section = factory.newSection(sectionY);
            if (section == null) throw new IllegalStateException("section factory returned null");
            OceanUnpublishedSectionAdapter.writeUnpublished(section, target);
            OceanUnpublishedSectionAdapter.assertExact(section, target);
            sink.accept(sectionY, section);
            blocksWritten = Math.addExact(blocksWritten, OceanSectionAssembler.VOLUME);
        }
        return blocksWritten;
    }
}
