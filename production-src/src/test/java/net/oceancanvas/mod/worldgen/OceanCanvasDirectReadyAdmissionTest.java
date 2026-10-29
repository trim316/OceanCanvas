package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasDirectReadyAdmissionTest {
 @Test void exactBoundaryAndSpentFrame() {
  assertTrue(OceanCanvasDirectReadyAdmission.allowed(false,false,30_000_000,20_000_000,50_000_000));
  assertFalse(OceanCanvasDirectReadyAdmission.allowed(false,false,30_000_001,20_000_000,50_000_000));
 }
 @Test void noActiveFrameDoesNotAdmitBackgroundRecovery() {
  assertFalse(OceanCanvasDirectReadyAdmission.allowed(false,false,-1,20,50));
 }
 @Test void partialAndVisibleProgressRetainExistingBypass() {
  assertTrue(OceanCanvasDirectReadyAdmission.allowed(true,false,100,20,50));
  assertTrue(OceanCanvasDirectReadyAdmission.allowed(false,true,100,20,50));
 }
 @Test void selectionCanSpendReservationAndNextFrameRecovers() {
  assertTrue(OceanCanvasDirectReadyAdmission.allowed(false,false,10,20,50));
  assertFalse(OceanCanvasDirectReadyAdmission.allowed(false,false,31,20,50));
  assertTrue(OceanCanvasDirectReadyAdmission.allowed(false,false,0,20,50));
 }
}
