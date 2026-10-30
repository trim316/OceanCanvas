package net.oceancanvas.mod.worldgen;

/** The existing tracked, sealed Canvas fluid-survivor boundary, evaluated per cell. */
final class OceanCanvasTrackedFluidCellPolicy {
    private OceanCanvasTrackedFluidCellPolicy() { }
    static boolean permits(boolean activePregen, boolean selectedColumn, boolean canvas,
            boolean physicalSeal, boolean aboveSurface, boolean pureFluid, boolean protectedCell) {
        return activePregen && selectedColumn && canvas && physicalSeal
                && aboveSurface && pureFluid && !protectedCell;
    }
}
