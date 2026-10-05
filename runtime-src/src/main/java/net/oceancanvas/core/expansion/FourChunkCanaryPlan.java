package net.oceancanvas.core.expansion;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic 2x2 scale canary. This class grants no Minecraft authority by
 * itself; it only validates and freezes the exact ordered four-chunk plan.
 */
public record FourChunkCanaryPlan(ChunkKey northWest, ChunkKey northEast,
                                  ChunkKey southWest, ChunkKey southEast) {
    public FourChunkCanaryPlan {
        Objects.requireNonNull(northWest, "northWest");
        Objects.requireNonNull(northEast, "northEast");
        Objects.requireNonNull(southWest, "southWest");
        Objects.requireNonNull(southEast, "southEast");
        if (List.of(northWest, northEast, southWest, southEast).stream().distinct().count() != 4) {
            throw new IllegalArgumentException("four-chunk canary requires four distinct chunks");
        }
        long x = northWest.x();
        long z = northWest.z();
        if (northEast.x() != x + 1L || northEast.z() != z
                || southWest.x() != x || southWest.z() != z + 1L
                || southEast.x() != x + 1L || southEast.z() != z + 1L) {
            throw new IllegalArgumentException(
                    "four-chunk canary must be exact row-major 2x2 square NW,NE,SW,SE");
        }
    }

    public static FourChunkCanaryPlan squareEastSouthOf(ChunkKey northWest) {
        Objects.requireNonNull(northWest, "northWest");
        int east = Math.addExact(northWest.x(), 1);
        int south = Math.addExact(northWest.z(), 1);
        return new FourChunkCanaryPlan(
                northWest,
                new ChunkKey(east, northWest.z()),
                new ChunkKey(northWest.x(), south),
                new ChunkKey(east, south));
    }

    public List<ChunkKey> orderedChunks() {
        return List.of(northWest, northEast, southWest, southEast);
    }
}
