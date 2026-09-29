package net.oceancanvas.core.expansion;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic, intentionally tiny expansion plan used to prove that the
 * validated single-chunk pipeline can be composed safely without widening
 * runtime authority yet. The release runtime does not activate this plan.
 */
public record TwoChunkCanaryPlan(ChunkKey first, ChunkKey second) {
    public TwoChunkCanaryPlan {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) throw new IllegalArgumentException("canary chunks must be distinct");
        int manhattan = Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
        if (manhattan != 1) throw new IllegalArgumentException("canary chunks must share an edge");
    }

    public static TwoChunkCanaryPlan eastOf(ChunkKey origin) {
        Objects.requireNonNull(origin, "origin");
        return new TwoChunkCanaryPlan(origin, new ChunkKey(origin.x() + 1, origin.z()));
    }

    public List<ChunkKey> orderedChunks() {
        return List.of(first, second);
    }
}
