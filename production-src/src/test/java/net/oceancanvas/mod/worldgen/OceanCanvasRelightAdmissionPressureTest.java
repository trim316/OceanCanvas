package net.oceancanvas.mod.worldgen;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
final class OceanCanvasRelightAdmissionPressureTest {
 @Test void rawBurstCannotHideBehindLowEmaOrCachedSample() {
  double ema=0.55D; boolean admittedAtWarning=false;
  for(double raw:new double[]{0.55,0.56,0.58,0.83,0.905}) {
   ema=ema*0.75+raw*0.25;
   if(raw>=0.82){assertTrue(OceanCanvasRelightAdmissionPressure.mustClamp(raw));assertTrue(ema<0.72);admittedAtWarning=true;}
  }
  assertTrue(admittedAtWarning);
 }
 @Test void boundaryAndUnknownPressureFailClosed() {
  assertFalse(OceanCanvasRelightAdmissionPressure.mustClamp(Math.nextDown(0.82)));
  assertTrue(OceanCanvasRelightAdmissionPressure.mustClamp(0.82));
  assertTrue(OceanCanvasRelightAdmissionPressure.mustClamp(Double.NaN));
  assertTrue(OceanCanvasRelightAdmissionPressure.mustClamp(Double.POSITIVE_INFINITY));
 }
}
