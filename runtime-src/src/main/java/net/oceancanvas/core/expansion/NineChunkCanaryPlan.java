package net.oceancanvas.core.expansion;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic row-major 3x3 scale plan. This is pure core geometry only and
 * grants no Minecraft mutation authority.
 */
public record NineChunkCanaryPlan(List<ChunkKey> orderedChunks) {
    public NineChunkCanaryPlan {
        Objects.requireNonNull(orderedChunks, "orderedChunks");
        orderedChunks = List.copyOf(orderedChunks);
        if (orderedChunks.size() != 9) {
            throw new IllegalArgumentException("nine-chunk canary requires exactly nine chunks");
        }
        if (orderedChunks.stream().distinct().count() != 9) {
            throw new IllegalArgumentException("nine-chunk canary requires nine distinct chunks");
        }

        ChunkKey northWest = orderedChunks.get(0);
        long baseX = northWest.x();
        long baseZ = northWest.z();
        for (int index = 0; index < 9; index++) {
            int row = index / 3;
            int column = index % 3;
            long expectedX = baseX + column;
            long expectedZ = baseZ + row;
            ChunkKey actual = orderedChunks.get(index);
            if (actual.x() != expectedX || actual.z() != expectedZ) {
                throw new IllegalArgumentException(
                        "nine-chunk canary must be exact row-major 3x3 square");
            }
        }
    }

    public static NineChunkCanaryPlan squareEastSouthOf(ChunkKey northWest) {
        Objects.requireNonNull(northWest, "northWest");
        ArrayList<ChunkKey> chunks = new ArrayList<>(9);
        for (int row = 0; row < 3; row++) {
            int z = Math.addExact(northWest.z(), row);
            for (int column = 0; column < 3; column++) {
                int x = Math.addExact(northWest.x(), column);
                chunks.add(new ChunkKey(x, z));
            }
        }
        return new NineChunkCanaryPlan(chunks);
    }
}
