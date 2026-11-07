package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;

class OceanCanvasPhysicalReplayProofAdmissionTest {
    @Test void incidentalLightRearmWaitsForUnadmittedPhysicalReplay() {
        assertSame(OceanCanvasPhysicalReplayProofAdmission.Action.PARK_FOR_REPLAY,
                OceanCanvasPhysicalReplayProofAdmission.choose(true, false, false));
    }
    @Test void admittedTerrainKeepsItsOwnershipWhileLightProofWaits() {
        assertSame(OceanCanvasPhysicalReplayProofAdmission.Action.WAIT_ADMITTED_TERRAIN,
                OceanCanvasPhysicalReplayProofAdmission.choose(true, false, true));
    }
    @Test void actualPhysicalHandoffCanRunItsFullAuditAndRepair() {
        for (boolean admitted : new boolean[]{false, true})
            assertSame(OceanCanvasPhysicalReplayProofAdmission.Action.ALLOW_LIGHT_PROOF,
                    OceanCanvasPhysicalReplayProofAdmission.choose(true, true, admitted));
    }
    @Test void ordinaryLightOnlyAndFreshTerrainKeepTheirExistingPath() {
        for (boolean allowed : new boolean[]{false, true})
            for (boolean admitted : new boolean[]{false, true})
                assertSame(OceanCanvasPhysicalReplayProofAdmission.Action.ALLOW_LIGHT_PROOF,
                        OceanCanvasPhysicalReplayProofAdmission.choose(false, allowed, admitted));
    }
}
