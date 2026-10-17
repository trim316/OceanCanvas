package net.oceancanvas.mod.project;

import net.oceancanvas.mod.config.OceanCanvasConfig;

/** Pure/read-only expansion math. Safe to call from commands and future map preview UI. */
public final class OceanCanvasExpansionPlanner {
    private OceanCanvasExpansionPlanner() {}

    public record Plan(int currentSize, int requestedSize, long currentChunks, long targetChunks,
                       long newRingChunks, boolean valid, String warning) { }

    public static Plan plan(OceanCanvasConfig config, int requestedSize) {
        int current = config.canvasSize();
        if (requestedSize <= current) {
            return new Plan(current, requestedSize, chunksForSize(current), chunksForSize(Math.max(1, requestedSize)),
                    0L, false, "Requested size must be larger than the current canvas.");
        }
        if ((requestedSize & 1) != 0) {
            return new Plan(current, requestedSize, chunksForSize(current), chunksForSize(requestedSize),
                    0L, false, "Canvas size must be even so it remains centered exactly.");
        }
        long oldChunks = chunksForSize(current);
        long newChunks = chunksForSize(requestedSize);
        return new Plan(current, requestedSize, oldChunks, newChunks, Math.max(0L, newChunks - oldChunks), true, "");
    }

    private static long chunksForSize(int size) {
        long side = (long)Math.ceil(size / 16.0D);
        return side * side;
    }
}
