package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasNewCarveAdmissionTest {
 @Test void newOwnedWorkDefersThenResumesWithPressure() {
  assertFalse(OceanCanvasNewCarveAdmission.allowed(true,false,false,()->true));
  assertTrue(OceanCanvasNewCarveAdmission.allowed(true,false,false,()->false));
 }
 @Test void partiallyAuthoredCarveKeepsProgressDespitePressure() {
  assertTrue(OceanCanvasNewCarveAdmission.allowed(true,true,false,()->{throw new AssertionError("partial work cannot be preempted");}));
 }
 @Test void nonownedGameplayNeverConsultsPregenPressure() {
  assertTrue(OceanCanvasNewCarveAdmission.allowed(false,false,false,()->{throw new AssertionError("outside pregen");}));
 }
 @Test void visibleWorkKeepsEligibility() {
  assertTrue(OceanCanvasNewCarveAdmission.allowed(true,false,true,()->{throw new AssertionError("visible bypass");}));
 }
}
