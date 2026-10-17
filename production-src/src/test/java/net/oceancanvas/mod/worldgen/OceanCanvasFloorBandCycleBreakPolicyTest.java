package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class OceanCanvasFloorBandCycleBreakPolicyTest {
    @Test void acceptsOverbrightCellsInsideFloorBand() {
        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(23, 2, 0, 23));
        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 1, 0, 23));
        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(26, 2, 1, 23));
    }

    @Test void rejectsCellsOutsideBoundedFloorBand() {
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(22, 2, 0, 23));
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(27, 2, 0, 23));
    }

    @Test void rejectsHealthyOrNonOverbrightCells() {
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 0, 0, 23));
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 1, 1, 23));
    }
}
