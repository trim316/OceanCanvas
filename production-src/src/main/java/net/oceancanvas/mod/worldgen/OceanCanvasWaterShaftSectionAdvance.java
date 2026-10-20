package net.oceancanvas.mod.worldgen;

/** Descending scan span; callers must first prove the live section is source water only. */
final class OceanCanvasWaterShaftSectionAdvance {
    private OceanCanvasWaterShaftSectionAdvance() {}

    static int boundedCells(int scanY, int minY, int remainingCellBudget) {
        if (scanY < minY || remainingCellBudget <= 0) return 0;
        long sectionBottom = Math.floorDiv((long) scanY, 16L) * 16L;
        long lowest = Math.max((long) minY, sectionBottom);
        return (int) Math.min((long) remainingCellBudget, (long) scanY - lowest + 1L);
    }
}
