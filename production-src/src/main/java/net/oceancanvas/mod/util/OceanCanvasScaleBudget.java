package net.oceancanvas.mod.util;

/** Dependency-free scale arithmetic used by diagnostics and release gates. */
public final class OceanCanvasScaleBudget {
    public static final int CHUNK_SIZE = 16;

    private OceanCanvasScaleBudget() { }

    /** Conservative chunk span touched by a block span, independent of origin alignment. */
    public static long chunkSpanForBlocks(long blockSpan) {
        if (blockSpan <= 0L) return 0L;
        return Math.floorDiv(blockSpan + CHUNK_SIZE - 1L, CHUNK_SIZE);
    }

    /** Conservative square chunk count for a square block canvas. */
    public static long squareChunkCount(long blockSpan) {
        long side = chunkSpanForBlocks(blockSpan);
        return Math.multiplyExact(side, side);
    }

    public static double requiredChunksPerSecond(long blockSpan, double targetHours) {
        if (!(targetHours > 0.0D) || !Double.isFinite(targetHours)) throw new IllegalArgumentException("targetHours must be finite and > 0");
        return squareChunkCount(blockSpan) / (targetHours * 3600.0D);
    }

    /** Bytes required for one completion bit per chunk, rounded up to complete bytes. */
    public static long completionBitsetBytes(long chunks) {
        if (chunks <= 0L) return 0L;
        return Math.floorDiv(chunks + 7L, 8L);
    }

    /** Number of row runs for one fully-filled square region when stored as one run per chunk row. */
    public static long fullSquareRowRuns(long blockSpan) {
        return chunkSpanForBlocks(blockSpan);
    }
}
