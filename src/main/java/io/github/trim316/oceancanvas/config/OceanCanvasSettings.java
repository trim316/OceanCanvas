package io.github.trim316.oceancanvas.config;

public record OceanCanvasSettings(
        boolean enabled,
        int centerX,
        int centerZ,
        int oceanRadius,
        int transitionWidth
) {
    public static final int DEFAULT_RADIUS = 10_000;
    public static final int DEFAULT_TRANSITION_WIDTH = 1_000;

    public OceanCanvasSettings {
        if (oceanRadius < 128) {
            throw new IllegalArgumentException("oceanRadius must be at least 128 blocks");
        }
        if (transitionWidth < 0) {
            throw new IllegalArgumentException("transitionWidth cannot be negative");
        }
    }

    public static OceanCanvasSettings defaults() {
        return new OceanCanvasSettings(true, 0, 0, DEFAULT_RADIUS, DEFAULT_TRANSITION_WIDTH);
    }

    public long fullOceanWidth() {
        return Math.multiplyExact((long) oceanRadius, 2L);
    }

    public long fullGeneratedWidth() {
        return Math.multiplyExact((long) oceanRadius + transitionWidth, 2L);
    }

    public RegionBand classify(int blockX, int blockZ) {
        long distance = Math.max(
                Math.abs((long) blockX - centerX),
                Math.abs((long) blockZ - centerZ)
        );

        if (!enabled) {
            return RegionBand.VANILLA;
        }
        if (distance <= oceanRadius) {
            return RegionBand.OCEAN;
        }
        if (distance <= (long) oceanRadius + transitionWidth) {
            return RegionBand.TRANSITION;
        }
        return RegionBand.VANILLA;
    }

    public enum RegionBand {
        OCEAN,
        TRANSITION,
        VANILLA
    }
}
