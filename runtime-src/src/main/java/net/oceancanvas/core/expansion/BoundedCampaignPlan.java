package net.oceancanvas.core.expansion;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded row-major square campaign geometry for scale proof beyond G16.
 * This class grants no Minecraft mutation authority.
 */
public record BoundedCampaignPlan(int sideChunks, List<ChunkKey> orderedChunks) {
    public static final int MIN_SIDE_CHUNKS = 5;
    public static final int MAX_SIDE_CHUNKS = 16;

    public BoundedCampaignPlan {
        if (sideChunks < MIN_SIDE_CHUNKS || sideChunks > MAX_SIDE_CHUNKS) {
            throw new IllegalArgumentException("campaign side must be "
                    + MIN_SIDE_CHUNKS + ".." + MAX_SIDE_CHUNKS + " chunks");
        }
        Objects.requireNonNull(orderedChunks, "orderedChunks");
        orderedChunks = List.copyOf(orderedChunks);
        int expectedCount = Math.multiplyExact(sideChunks, sideChunks);
        if (orderedChunks.size() != expectedCount) {
            throw new IllegalArgumentException("campaign requires exactly " + expectedCount + " chunks");
        }
        if (orderedChunks.stream().distinct().count() != expectedCount) {
            throw new IllegalArgumentException("campaign chunks must be distinct");
        }
        ChunkKey northWest = orderedChunks.get(0);
        long baseX = northWest.x();
        long baseZ = northWest.z();
        for (int index = 0; index < expectedCount; index++) {
            int row = index / sideChunks;
            int column = index % sideChunks;
            long expectedX = baseX + column;
            long expectedZ = baseZ + row;
            ChunkKey actual = orderedChunks.get(index);
            if (actual.x() != expectedX || actual.z() != expectedZ) {
                throw new IllegalArgumentException("campaign must be exact row-major square");
            }
        }
    }

    public static BoundedCampaignPlan squareEastSouthOf(
            ChunkKey northWest, int sideChunks) {
        Objects.requireNonNull(northWest, "northWest");
        if (sideChunks < MIN_SIDE_CHUNKS || sideChunks > MAX_SIDE_CHUNKS) {
            throw new IllegalArgumentException("campaign side must be "
                    + MIN_SIDE_CHUNKS + ".." + MAX_SIDE_CHUNKS + " chunks");
        }
        ArrayList<ChunkKey> chunks =
                new ArrayList<>(Math.multiplyExact(sideChunks, sideChunks));
        for (int row = 0; row < sideChunks; row++) {
            int z = Math.addExact(northWest.z(), row);
            for (int column = 0; column < sideChunks; column++) {
                int x = Math.addExact(northWest.x(), column);
                chunks.add(new ChunkKey(x, z));
            }
        }
        return new BoundedCampaignPlan(sideChunks, chunks);
    }
}
