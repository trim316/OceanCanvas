package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasLightPhysicalCertificationTest {
    @Test void dirtyPersistedSealCannotKeepEitherCurrentProofOrItsPublicationPass() {
        var session = new OceanCanvasLightFinalizerSession();
        long chunk = 47;
        session.preLightPhysicalAuditComplete.add(chunk);
        session.prePublishPhysicalAuditComplete.add(chunk);
        session.stagedBlockFingerprint.put(chunk, 123L);
        session.pendingPasses.put(chunk, 1);
        assertTrue(session.holdPhysicalCertification(chunk, 20));
        assertFalse(session.preLightPhysicalAuditComplete.contains(chunk));
        assertFalse(session.prePublishPhysicalAuditComplete.contains(chunk));
        assertFalse(session.stagedBlockFingerprint.containsKey(chunk));
        assertEquals(0, session.pendingPasses.get(chunk));
        assertEquals(20, session.pendingTicks.get(chunk));
        assertFalse(session.allowPhysicalRepair.contains(chunk));
        assertFalse(session.postJobPhysicalRepairAuthority.contains(chunk));
    }

    @Test void repeatedMismatchRetainsDebtButReportsOnlyOnce() {
        var session = new OceanCanvasLightFinalizerSession();
        assertTrue(session.holdPhysicalCertification(47, 20));
        session.pendingTicks.remove(47);
        assertFalse(session.holdPhysicalCertification(47, 20));
        assertEquals(20, session.pendingTicks.get(47));
        assertFalse(session.allowPhysicalRepair.contains(47));
    }

    @Test void foregroundAndOtherChunksKeepTheirIndependentProofAndAuthority() {
        var session = new OceanCanvasLightFinalizerSession();
        session.visibleLightPriority.add(48);
        session.preLightPhysicalAuditComplete.add(48);
        session.allowPhysicalRepair.add(48);
        session.pendingPasses.put(48, 1);
        session.holdPhysicalCertification(47, 20);
        assertTrue(session.visibleLightPriority.contains(48));
        assertTrue(session.preLightPhysicalAuditComplete.contains(48));
        assertTrue(session.allowPhysicalRepair.contains(48));
        assertEquals(1, session.pendingPasses.get(48));
        assertFalse(session.physicalCertificationHolds.contains(48));
    }

    @Test void restartCannotReconstructEphemeralProofOrGrantRepairFromOldHold() {
        var old = new OceanCanvasLightFinalizerSession();
        old.holdPhysicalCertification(47, 20);
        var cold = new OceanCanvasLightFinalizerSession();
        assertFalse(cold.preLightPhysicalAuditComplete.contains(47));
        assertFalse(cold.prePublishPhysicalAuditComplete.contains(47));
        assertFalse(cold.allowPhysicalRepair.contains(47));
        assertTrue(cold.holdPhysicalCertification(47, 20));
    }

    @Test void invalidRetryDelayCannotEraseTheObligation() {
        var session = new OceanCanvasLightFinalizerSession();
        session.holdPhysicalCertification(47, -1);
        assertEquals(1, session.pendingTicks.get(47));
    }
    @Test void mutationDuringCooperativeAuditDiscardsBothProofsAndPublicationFingerprint() {
        var session = new OceanCanvasLightFinalizerSession();
        session.preLightPhysicalAuditComplete.add(47);
        session.prePublishPhysicalAuditComplete.add(47);
        session.stage1FingerprintVerified.add(47);
        session.initialResidentProofAttempted.add(47);
        session.preLightPhysicalAuditComplete.add(48);
        // Same invalidation method used by the real physical-mutation callback.
        session.invalidateCooperativeProof(47);
        assertFalse(session.preLightPhysicalAuditComplete.contains(47));
        assertFalse(session.prePublishPhysicalAuditComplete.contains(47));
        assertFalse(session.stage1FingerprintVerified.contains(47));
        assertFalse(session.initialResidentProofAttempted.contains(47));
        assertTrue(session.preLightPhysicalAuditComplete.contains(48));
        assertFalse(session.allowPhysicalRepair.contains(47));
    }
}
