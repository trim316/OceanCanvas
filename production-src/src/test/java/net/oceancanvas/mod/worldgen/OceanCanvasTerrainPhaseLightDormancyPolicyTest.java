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

        long ordinary = 123456789L;
        long dormant = OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 137, false, false);
        require(dormant == Long.MAX_VALUE,
                "generic terrain-phase debt must use one stable non-churning deadline");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.isDormantDeadline(dormant),
                "dormant sentinel must be recognizable for explicit zero-terrain wake");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 137, true, false) == ordinary,
                "visible debt must retain its ordinary deadline exactly");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 137, false, true) == ordinary,
                "physical-repair debt must retain its ordinary deadline exactly");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(ordinary, 0, false, false) == ordinary,
                "generic debt must retain ordinary deadline after terrain reaches zero");
        require(OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(Long.MAX_VALUE - 1L, 137, false, false)
                        == Long.MAX_VALUE,
                "dormancy must not use arithmetic that can overflow near Long.MAX_VALUE");
        System.out.println("R1-136 terrain-phase light dormancy policy PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
