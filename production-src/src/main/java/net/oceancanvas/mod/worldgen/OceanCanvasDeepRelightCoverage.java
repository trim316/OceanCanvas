package net.oceancanvas.mod.worldgen;

/** Vertical native-check coverage only; grants no residency or certificate authority. */
final class OceanCanvasDeepRelightCoverage {
    private OceanCanvasDeepRelightCoverage() {}
    static int lowestObservedSection(int anchorSection, int lowestObservedY) {
        return Math.min(anchorSection, Math.floorDiv(lowestObservedY, 16));
    }
    static int sourceBandSection(int anomalySection, int waterTop) {
        return Math.max(anomalySection, Math.floorDiv(waterTop, 16));
    }
    static int denseSection(int anomalyY, int waterTop, int completedPasses) {
        int tailSection = Math.floorDiv(anomalyY, 16);
        return completedPasses == 0 ? sourceBandSection(tailSection, waterTop) : tailSection;
    }
}
