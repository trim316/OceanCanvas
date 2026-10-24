package net.oceancanvas.mod.worldgen;

/**
 * Pure deterministic Ocean Canvas floor-profile kernel.
 *
 * <p>This class is deliberately Minecraft-free so diagnostics, previews, and terrain mutation
 * share exactly one floor-height implementation without depending on the flattener runtime.</p>
 */
public final class OceanCanvasFloorProfile {
    private static final double CELL_SIZE = 48.0;

    private OceanCanvasFloorProfile() {}

    public static int floorOffset(int x, int z, int amplitude) {
        if (amplitude <= 0) return 0;
        double n = valueNoise2D(x / CELL_SIZE, z / CELL_SIZE);
        return (int) Math.round(n * amplitude);
    }

    static double valueNoise2D(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        double tx = smoothstep(x - x0);
        double tz = smoothstep(z - z0);
        double v00 = gridHash(x0, z0);
        double v10 = gridHash(x1, z0);
        double v01 = gridHash(x0, z1);
        double v11 = gridHash(x1, z1);
        double vx0 = v00 + (v10 - v00) * tx;
        double vx1 = v01 + (v11 - v01) * tx;
        return vx0 + (vx1 - vx0) * tz;
    }

    private static double smoothstep(double t) { return t * t * (3.0 - 2.0 * t); }

    private static double gridHash(int x, int z) {
        long h = x * 374761393L + z * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h = h ^ (h >>> 16);
        return ((h & 0xFFFFFF) / (double) 0xFFFFFF) * 2.0 - 1.0;
    }
}
