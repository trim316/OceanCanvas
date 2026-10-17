package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class OceanCanvasPostJobIntegrityTest {
    @Test void physicalMismatchCannotBeReportedAsClean() {
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,0,1,0,0).loadedLightingClean());
    }
    @Test void heightMismatchCannotBeReportedAsClean() {
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,1,0,0,0).loadedLightingClean());
    }
    @Test void allVisualGatesMustDrainBeforeCompletion() {
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,1,0,0,0,0).loadedLightingClean());
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,0,0,1,0).loadedLightingClean());
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,0,0,0,1).loadedLightingClean());
        assertTrue(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,0,0,0,0).loadedLightingClean());
    }
}
