package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasFreshProofCheckpointAuthorityTest {
    private static LongLinkedOpenHashSet checkpoint(OceanCanvasLightFinalizerSession s) {
        var out=new LongLinkedOpenHashSet();s.appendAuthoredCheckpointPhysicalAuthority(out::add);return out;
    }
    @Test void freshlyCompletedPhysicalProofRetainsExistingLeaseInTheColdSnapshot() {
        var s=new OceanCanvasLightFinalizerSession();s.allowPhysicalRepair.add(9);
        s.recordFreshPhysicalProof(9);
        assertTrue(s.preLightPhysicalAuditComplete.contains(9));
        assertTrue(s.authoredWaterLandingGuard.contains(9));
        assertTrue(checkpoint(s).contains(9));
    }
    @Test void historicalPermissionWithoutThisSessionsAuthoringGuardRemainsExcluded() {
        var s=new OceanCanvasLightFinalizerSession();s.allowPhysicalRepair.add(9);
        assertFalse(checkpoint(s).contains(9));
    }
    @Test void proofWithoutExistingPermissionCannotCreateARepairLease() {
        var s=new OceanCanvasLightFinalizerSession();s.recordFreshPhysicalProof(9);
        assertTrue(s.preLightPhysicalAuditComplete.contains(9));
        assertFalse(s.allowPhysicalRepair.contains(9));
        assertFalse(s.authoredWaterLandingGuard.contains(9));
        assertFalse(checkpoint(s).contains(9));
    }
    @Test void releasedPermissionCannotBeResurrectedByAStaleGuard() {
        var s=new OceanCanvasLightFinalizerSession();s.allowPhysicalRepair.add(9);s.recordFreshPhysicalProof(9);
        s.allowPhysicalRepair.remove(9);
        assertFalse(checkpoint(s).contains(9));
    }
    @Test void exactChunkIntersectionPreventsUnrelatedAuthorityFanout() {
        var s=new OceanCanvasLightFinalizerSession();s.allowPhysicalRepair.add(9);s.allowPhysicalRepair.add(10);
        s.recordFreshPhysicalProof(9);s.recordFreshPhysicalProof(11);
        var result=checkpoint(s);assertEquals(1,result.size());assertTrue(result.contains(9));
        assertFalse(result.contains(10));assertFalse(result.contains(11));
    }
}
