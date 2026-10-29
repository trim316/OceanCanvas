package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasInitialLightSweepAdmissionTest {
 @Test void newBackgroundSweepDefersUnderNativePressureAndResumesAfterDrain() {
  assertFalse(OceanCanvasInitialLightSweepAdmission.allowed(false,false,()->true));
  assertTrue(OceanCanvasInitialLightSweepAdmission.allowed(false,false,()->false));
 }
 @Test void partialSweepNeverConsultsPressureOrLosesForwardProgress() {
  assertTrue(OceanCanvasInitialLightSweepAdmission.allowed(false,true,()->{throw new AssertionError("must not preempt partial sweep");}));
 }
 @Test void visibleSweepNeverConsultsBackgroundPressure() {
  assertTrue(OceanCanvasInitialLightSweepAdmission.allowed(true,false,()->{throw new AssertionError("visible bypass");}));
 }
}
