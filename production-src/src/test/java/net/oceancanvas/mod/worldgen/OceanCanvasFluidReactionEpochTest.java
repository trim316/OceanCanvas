package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasFluidReactionEpochTest {
 @Test void zeroWriteScanMayBeReusedOnlyUntilMutationEpochChanges(){
  var s=new OceanCanvasLightFinalizerSession();assertTrue(s.needsFluidReactionScan(7));
  s.noteFluidReactionScanResult(7,0);assertFalse(s.needsFluidReactionScan(7));
  for(int i=0;i<100;i++){s.pendingTicks.put(7,1);assertFalse(s.needsFluidReactionScan(7));}
  s.invalidateCooperativeProof(7);assertTrue(s.needsFluidReactionScan(7));
 }
 @Test void actualRepairsFailedScansAndColdSessionNeverSupplyReuseCredit(){
  var s=new OceanCanvasLightFinalizerSession();s.noteFluidReactionScanResult(8,1);assertTrue(s.needsFluidReactionScan(8));
  s.noteFluidReactionScanResult(8,-1);assertTrue(s.needsFluidReactionScan(8));
  assertTrue(new OceanCanvasLightFinalizerSession().needsFluidReactionScan(8));
 }
 @Test void profileHoldRevokesReuseWithoutGrantingPhysicalAuthorityOrCertification(){
  var s=new OceanCanvasLightFinalizerSession();s.noteFluidReactionScanResult(9,0);s.holdPhysicalCertification(9,1);
  assertTrue(s.needsFluidReactionScan(9));assertFalse(s.allowPhysicalRepair.contains(9));
  assertFalse(s.preLightPhysicalAuditComplete.contains(9));assertFalse(s.prePublishPhysicalAuditComplete.contains(9));
 }
}
