package net.oceancanvas.core.geometry;

/** Pure deterministic floor-height kernel carried forward from the proven legacy profile. */
public final class OceanFloorProfile {
    private static final double CELL_SIZE = 48.0;
    private OceanFloorProfile() {}

    public static int floorOffset(int x, int z, int amplitude) {
        if (amplitude <= 0) return 0;
        double n = valueNoise2D(x / CELL_SIZE, z / CELL_SIZE);
        return (int) Math.round(n * amplitude);
    }

    static double valueNoise2D(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        int x1 = x0 + 1, z1 = z0 + 1;
        double tx = smoothstep(x - x0), tz = smoothstep(z - z0);
        double v00 = gridHash(x0, z0), v10 = gridHash(x1, z0);
        double v01 = gridHash(x0, z1), v11 = gridHash(x1, z1);
        double vx0 = v00 + (v10 - v00) * tx, vx1 = v01 + (v11 - v01) * tx;
        return vx0 + (vx1 - vx0) * tz;
    }

    private static double smoothstep(double t) { return t * t * (3.0 - 2.0 * t); }
    private static double gridHash(int x, int z) {
        long h = x * 374761393L + z * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return ((h & 0xFFFFFF) / (double) 0xFFFFFF) * 2.0 - 1.0;
    }
}
