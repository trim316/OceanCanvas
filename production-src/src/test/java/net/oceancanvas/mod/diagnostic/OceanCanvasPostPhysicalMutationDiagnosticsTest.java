package net.oceancanvas.mod.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: provenance is emitted only inside the physical-to-light certificate gap. */
final class OceanCanvasPostPhysicalMutationDiagnosticsTest {
    @Test
    void recordsOnlySuccessfulMutationInsideUncertifiedCanvasGap() {
        assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, true, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(false, true, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, false, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, true, true, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, true, false, false));
    }
}
