package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry.ChunkBounds;

/**
 * Stable x-fastest, then z row-major cursor carried forward from the proven
 * pregen lineage. It is lazy and stores only the next index.
 */
public final class RowMajorCursor {
    private final ChunkBounds bounds;
    private final long total;
    private long nextIndex;

    public RowMajorCursor(ChunkBounds bounds) {
        this(bounds, 0L);
    }

    public RowMajorCursor(ChunkBounds bounds, long nextIndex) {
        if (bounds == null || bounds.isEmpty()) throw new IllegalArgumentException("non-empty bounds required");
        this.bounds = bounds;
        this.total = bounds.count();
        if (nextIndex < 0 || nextIndex > total) throw new IllegalArgumentException("cursor outside bounds");
        this.nextIndex = nextIndex;
    }

    public boolean hasNext() { return nextIndex < total; }
    public long nextIndex() { return nextIndex; }
    public long total() { return total; }
    public long remaining() { return total - nextIndex; }

    public ChunkKey peek() {
        if (!hasNext()) throw new IllegalStateException("cursor exhausted");
        long width = (long) bounds.maxX() - bounds.minX() + 1L;
        int x = bounds.minX() + (int) (nextIndex % width);
        int z = bounds.minZ() + (int) (nextIndex / width);
        return new ChunkKey(x, z);
    }

    public ChunkKey take() {
        ChunkKey key = peek();
        nextIndex++;
        return key;
    }
}
