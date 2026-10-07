package net.oceancanvas.mod.worldgen;

/** Standalone deterministic regression; intentionally has no Minecraft dependency. */
public final class OceanCanvasTerrainPhaseLightDormancyPolicyTest {
    public static void main(String[] args) {
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(137, false, false),
                "generic LIGHT_ONLY debt must be dormant while terrain remains");
        require(!OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(137, true, false),
                "visible debt must bypass terrain-phase dormancy");
        require(!OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(137, false, true),
                "physical-repair debt must bypass terrain-phase dormancy");
        require(!OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(0, false, false),
                "generic debt must wake when terrain reaches zero");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.shouldWake(0, false, false),
                "zero-terrain transition must make generic debt wake-eligible");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.shouldWake(137, true, false),
                "visible debt must always be wake-eligible");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.shouldWake(137, false, true),
                "physical-repair debt must always be wake-eligible");
        System.out.println("R1-136 terrain-phase light dormancy policy PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
