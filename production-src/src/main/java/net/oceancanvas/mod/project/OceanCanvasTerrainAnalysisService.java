package net.oceancanvas.mod.project;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Read-only analysis helpers used by Design Mode. These methods never place or remove blocks. */
public final class OceanCanvasTerrainAnalysisService {
    private OceanCanvasTerrainAnalysisService() { }

    public record CrossSectionPoint(int x, int z, int surfaceY, double distance) { }
    public record TravelEstimate(double blocks, double walkMinutes, double sprintMinutes, double horseMinutes,
                                 double boatMinutes, double elytraMinutes) { }

    /** Samples a terrain profile without force-generating intermediate chunks. Missing chunks are reported with Y=Integer.MIN_VALUE. */
    public static List<CrossSectionPoint> crossSection(ServerLevel world, int x0, int z0, int x1, int z1, int samples) {
        int n = Math.max(2, Math.min(2048, samples));
        double dx = x1 - x0, dz = z1 - z0;
        double total = Math.hypot(dx, dz);
        List<CrossSectionPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            int x = (int)Math.round(x0 + dx * t), z = (int)Math.round(z0 + dz * t);
            int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
            int y = Integer.MIN_VALUE;
            if (world.getChunkSource().getChunkNow(cx, cz) != null) {
                y = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
            out.add(new CrossSectionPoint(x, z, y, total * t));
        }
        return List.copyOf(out);
    }

    /** Approximate grade between two loaded surface samples, expressed as rise/run. */
    public static double slope(ServerLevel world, int x0, int z0, int x1, int z1) {
        double run = Math.hypot(x1 - x0, z1 - z0);
        if (run < 1.0) return 0.0;
        int y0 = world.getHeight(Heightmap.Types.WORLD_SURFACE, x0, z0);
        int y1 = world.getHeight(Heightmap.Types.WORLD_SURFACE, x1, z1);
        return (y1 - y0) / run;
    }

    /**
     * Human-scale planning estimates. Speeds are intentionally documented assumptions, not promises:
     * walk 4.317 b/s, sprint 5.612 b/s, horse 9.5 b/s, boat 8 b/s, conservative sustained elytra 25 b/s.
     */
    public static TravelEstimate travel(double blocks) {
        double b = Math.max(0, blocks);
        return new TravelEstimate(b, minutes(b,4.317), minutes(b,5.612), minutes(b,9.5), minutes(b,8.0), minutes(b,25.0));
    }

    private static double minutes(double blocks, double blocksPerSecond) { return blocks / blocksPerSecond / 60.0; }

    /** Returns true if the top surface at a loaded column would be submerged at the preview sea level. */
    public static boolean submergedAt(ServerLevel world, int x, int z, int previewSeaLevel) {
        if (world.getChunkSource().getChunkNow(Math.floorDiv(x,16), Math.floorDiv(z,16)) == null) return false;
        return world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) <= previewSeaLevel;
    }
}
