package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasResidentProofReadinessTest {
    @Test void coldUnreadyVisitsCannotSpendTheAttemptNeededAfterPhysicalAudit() {
        var s=new OceanCanvasLightFinalizerSession();
        for(int i=0;i<8;i++)assertFalse(s.claimInitialResidentProof(9,0,false));
        assertFalse(s.initialResidentProofAttempted.contains(9));
        s.preLightPhysicalAuditComplete.add(9);
        assertTrue(s.claimInitialResidentProof(9,0,false));
        assertFalse(s.claimInitialResidentProof(9,0,false));
    }
    @Test void physicalMismatchCannotEnterResidentLightProof() {
        var s=new OceanCanvasLightFinalizerSession();
        s.holdPhysicalCertification(9,20);
        assertFalse(s.claimInitialResidentProof(9,0,false));
        assertFalse(s.allowPhysicalRepair.contains(9));
        assertEquals(20,s.pendingTicks.get(9));
    }
    @Test void failedNativeProofGetsOneAttemptPerPhysicalEpochNotPerCaller() {
        var s=new OceanCanvasLightFinalizerSession();s.preLightPhysicalAuditComplete.add(9);
        assertTrue(s.claimInitialResidentProof(9,0,false));
        // Both scheduler call sites use this same claim, even after a native miss.
        assertFalse(s.claimInitialResidentProof(9,0,false));
        s.invalidateCooperativeProof(9);
        assertFalse(s.claimInitialResidentProof(9,0,false));
        s.preLightPhysicalAuditComplete.add(9);
        assertTrue(s.claimInitialResidentProof(9,0,false));
    }
    @Test void stagedVisiblePhysicalRepairAndResetLanesRemainIndependent() {
        for(int guard=0;guard<5;guard++) {
            var s=new OceanCanvasLightFinalizerSession();s.preLightPhysicalAuditComplete.add(9);
            if(guard==0)s.allowPhysicalRepair.add(9);
            if(guard==1)s.visibleLightPriority.add(9);
            if(guard==2)s.hardResetFingerprintPending.add(9);
            assertFalse(s.claimInitialResidentProof(9,guard==3?1:0,guard==4));
            assertFalse(s.initialResidentProofAttempted.contains(9));
            assertTrue(s.preLightPhysicalAuditComplete.contains(9));
            s.preLightPhysicalAuditComplete.add(10);
            assertTrue(s.claimInitialResidentProof(10,0,false));
        }
    }
}
