package net.oceancanvas.core.geometry;

/**
 * Checked single-chunk vertical scan dimensions. Invalid configuration must
 * be rejected before allocating a backup or writing any world blocks.
 */
public record ChunkColumnScanBounds(int minY, int maxY, int height, int cells) {
    // The current single-chunk recovery preimage is bounded at 16 MiB. Keep
    // scans far below that bound and reject impossible/extreme world geometry.
    private static final int MAX_HEIGHT = 4096;

    public static ChunkColumnScanBounds checked(int minY, int maxY) {
        final int height;
        final int cells;
        try {
            height = Math.addExact(Math.subtractExact(maxY, minY), 1);
            cells = Math.multiplyExact(256, height);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("single-chunk scan dimension overflow", e);
        }
        if (height <= 0 || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("single-chunk scan height outside safe bound: " + height);
        }
        return new ChunkColumnScanBounds(minY, maxY, height, cells);
    }
}
