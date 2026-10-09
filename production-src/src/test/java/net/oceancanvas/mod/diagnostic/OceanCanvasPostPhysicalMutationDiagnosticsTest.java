package net.oceancanvas.mod.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: provenance and prewrite invalidation share the strict physical-to-light gap. */
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
    void prewriteInvalidationUsesSameFailClosedBoundary() {
        assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.shouldInvalidateProof(true, true, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldInvalidateProof(false, true, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldInvalidateProof(true, false, false, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldInvalidateProof(true, true, true, true));
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.shouldInvalidateProof(true, true, false, false));
    }

    @Test
    void authorizedAquaticScopeIsNestableAndCannotLeak() {
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.isAuthorizedAquaticMutation());
        OceanCanvasPostPhysicalMutationDiagnostics.beginAuthorizedAquaticMutation();
        assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.isAuthorizedAquaticMutation());
        OceanCanvasPostPhysicalMutationDiagnostics.beginAuthorizedAquaticMutation();
        assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.isAuthorizedAquaticMutation());
        OceanCanvasPostPhysicalMutationDiagnostics.endAuthorizedAquaticMutation();
        assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.isAuthorizedAquaticMutation());
        OceanCanvasPostPhysicalMutationDiagnostics.endAuthorizedAquaticMutation();
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.isAuthorizedAquaticMutation());
    }

    @Test
    void unmatchedEndFailsClosedToNormalDiagnostics() {
        OceanCanvasPostPhysicalMutationDiagnostics.endAuthorizedAquaticMutation();
        assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.isAuthorizedAquaticMutation());
    }
}
