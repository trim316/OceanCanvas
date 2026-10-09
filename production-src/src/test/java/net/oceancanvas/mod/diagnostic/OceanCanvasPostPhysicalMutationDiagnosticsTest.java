package net.oceancanvas.mod.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: both provenance observers share the same fail-closed admission boundary. */
final class OceanCanvasPostPhysicalMutationDiagnosticsTest {
    @Test
    void recordsOnlySuccessfulMutationInsideUncertifiedCanvasGap() {
        assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, true, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(false, true, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, false, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, true, true, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(true, true, false, false));
    }

    @Test
    void directChunkObserverDoesNotWidenAdmissionPolicy() {
        boolean[][] cases = {
                {true, true, false, true},
                {false, true, false, true},
                {true, false, false, true},
                {true, true, true, true},
                {true, true, false, false}
        };
        for (boolean[] c : cases) {
            boolean expected = c[0] && c[1] && !c[2] && c[3];
            assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.shouldRecord(c[0], c[1], c[2], c[3]) == expected);
        }
    }
}
