package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasDormantProofAdmissionTest {
    @Test void dormantWorkReturnsScarceAdmissionWithoutRetiringItsDebt() {
        var session = new OceanCanvasLightFinalizerSession();
        session.pendingPasses.put(1L, 1);
        session.allowPhysicalRepair.add(1L);
        assertTrue(session.blockProofAdmission.acquire(1L, 1));
        assertFalse(session.blockProofAdmission.acquire(2L, 1));
        session.discardDormantBlockLightProof(1L);
        assertTrue(session.blockProofAdmission.acquire(2L, 1));
        assertEquals(1, session.pendingPasses.getOrDefault(1L, -1));
        assertTrue(session.allowPhysicalRepair.contains(1L));
        assertFalse(session.pendingTicks.containsKey(1L));
    }

    @Test void cooperativePendingDelayKeepsAdmissionUntilActuallyParked() {
        var session = new OceanCanvasLightFinalizerSession();
        session.pendingTicks.put(1L, 1);
        assertTrue(session.blockProofAdmission.acquire(1L, 1));
        session.discardDormantBlockLightProof(1L);
        assertFalse(session.blockProofAdmission.acquire(2L, 1));
        session.pendingTicks.remove(1L);
        session.discardDormantBlockLightProof(1L);
        assertTrue(session.blockProofAdmission.acquire(2L, 1));
        session.discardDormantBlockLightProof(1L);
        assertFalse(session.blockProofAdmission.acquire(3L, 1));
    }
}
