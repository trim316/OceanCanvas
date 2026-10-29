package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasNewTargetAdmissionTest {
 @Test void batchStopsAtExistingEightLoadingBoundaryAndRetainsNextCursor() {
  int targets=0, cursor=0;
  while(cursor<20) {
   final int count=targets;
   if(!OceanCanvasNewTargetAdmission.allowed(false,()->!OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,count,0))) break;
   targets++;cursor++;
  }
  assertEquals(8,targets);assertEquals(8,cursor);
  // One becomes queued: retry the same unconsumed cursor, without discarding debt.
  final int count=targets;
  assertTrue(OceanCanvasNewTargetAdmission.allowed(false,()->!OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,count,1)));
 }
 @Test void existingOwnershipDoesNotConsultPressure() {
  assertTrue(OceanCanvasNewTargetAdmission.allowed(true,()->{throw new AssertionError("existing target must progress");}));
 }
 @Test void agedLoadHoldsNewTargetAndResumesAfterDrain() {
  assertFalse(OceanCanvasNewTargetAdmission.allowed(false,()->OceanCanvasAgedNativeLoadAdmission.agedOrInvalid(2500,0,-1,2500)));
  assertTrue(OceanCanvasNewTargetAdmission.allowed(false,()->false));
 }
 @Test void emptyRecoveryWindowCanAdmitMandatoryReplay() {
  assertTrue(OceanCanvasNewTargetAdmission.allowed(false,()->!OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,0,0)));
 }
}
