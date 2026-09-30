package net.oceancanvas.core.expansion;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic row-major 4x4 scale plan. This is pure core geometry only and
 * grants no Minecraft mutation authority.
 */
public record SixteenChunkCanaryPlan(List<ChunkKey> orderedChunks) {
    public SixteenChunkCanaryPlan {
        Objects.requireNonNull(orderedChunks, "orderedChunks");
        orderedChunks = List.copyOf(orderedChunks);
        if (orderedChunks.size() != 16) {
            throw new IllegalArgumentException("sixteen-chunk canary requires exactly sixteen chunks");
        }
        if (orderedChunks.stream().distinct().count() != 16) {
            throw new IllegalArgumentException("sixteen-chunk canary requires sixteen distinct chunks");
        }

        ChunkKey northWest = orderedChunks.get(0);
        long baseX = northWest.x();
        long baseZ = northWest.z();
        for (int index = 0; index < 16; index++) {
            int row = index / 4;
            int column = index % 4;
            long expectedX = baseX + column;
            long expectedZ = baseZ + row;
            ChunkKey actual = orderedChunks.get(index);
            if (actual.x() != expectedX || actual.z() != expectedZ) {
                throw new IllegalArgumentException(
                        "sixteen-chunk canary must be exact row-major 4x4 square");
            }
        }
    }

    public static SixteenChunkCanaryPlan squareEastSouthOf(ChunkKey northWest) {
        Objects.requireNonNull(northWest, "northWest");
        ArrayList<ChunkKey> chunks = new ArrayList<>(16);
        for (int row = 0; row < 4; row++) {
            int z = Math.addExact(northWest.z(), row);
            for (int column = 0; column < 4; column++) {
                int x = Math.addExact(northWest.x(), column);
                chunks.add(new ChunkKey(x, z));
            }
        }
        return new SixteenChunkCanaryPlan(chunks);
    }
}
