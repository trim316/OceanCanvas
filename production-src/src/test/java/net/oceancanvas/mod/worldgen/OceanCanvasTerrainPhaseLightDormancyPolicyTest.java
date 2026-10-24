package net.oceancanvas.mod.worldgen;

/** Standalone deterministic regression; intentionally has no Minecraft dependency. */
public final class OceanCanvasTerrainPhaseLightDormancyPolicyTest {
    public static void main(String[] args) {
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(137, false, false), "generic debt must dormant while terrain remains");
        require(!OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(137, true, false), "visible debt must bypass dormancy");
        require(!OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(137, false, true), "physical-repair debt must bypass dormancy");
        require(!OceanCanvasTerrainPhaseLightDormancyPolicy.shouldDormant(0, false, false), "generic debt must not dormant after terrain reaches zero");
        long ordinary = 123456789L;
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 137, false, false) == Long.MAX_VALUE,
                "generic terrain-phase debt must use stable dormant sentinel");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 137, true, false) == ordinary,
                "visible debt must preserve ordinary deadline");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 137, false, true) == ordinary,
                "physical-repair debt must preserve ordinary deadline");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 0, false, false) == ordinary,
                "generic debt must preserve ordinary deadline after terrain completion");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.isDormantDeadline(Long.MAX_VALUE), "sentinel must be recognizable");
        System.out.println("R1-136 terrain-phase light dormancy policy PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
