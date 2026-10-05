package net.oceancanvas.core.production;

import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;

import java.util.HashSet;
import java.util.Set;

public final class ProductionScalePolicySelfTest {
    public static void main(String[] args) {
        var canvas = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(20_000, 0, 0);
        eq(1_562_500L, canvas.count(), "20k chunk count");

        check(!ProductionScalePolicy.checkpointDue(0, 2047), "checkpoint must not advance early");
        check(ProductionScalePolicy.checkpointDue(0, 2048), "checkpoint due at 2048 verified chunks");
        check(ProductionScalePolicy.checkpointDue(4096, 6144), "checkpoint interval is relative to durable prefix");

        check(ProductionScalePolicy.canAdmitTerrain(127, 447, 64), "bounded queues accept one more terrain worker below caps");
        check(!ProductionScalePolicy.canAdmitTerrain(128, 0, 0), "terrain cap enforced");
        check(!ProductionScalePolicy.canAdmitTerrain(0, 448, 64), "combined light backlog cap enforced");

        Set<Long> completed = new HashSet<>();
        completed.add(100L);
        completed.add(102L);
        long frontier = ProductionScalePolicy.advanceVerifiedFrontier(100L, completed);
        eq(101L, frontier, "frontier stops at first verification gap");
        check(completed.contains(102L), "out-of-order completion retained for later frontier advance");
        completed.add(101L);
        frontier = ProductionScalePolicy.advanceVerifiedFrontier(frontier, completed);
        eq(103L, frontier, "frontier consumes contiguous tail after gap closes");

        boolean rejected = false;
        try { ProductionScalePolicy.checkpointDue(10, 9); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "backward checkpoint frontier rejected");

        System.out.println("OceanCanvas production-scale policy self-test PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }
}
