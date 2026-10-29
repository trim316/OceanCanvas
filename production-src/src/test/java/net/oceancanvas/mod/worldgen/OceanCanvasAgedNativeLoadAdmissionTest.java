package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
class OceanCanvasAgedNativeLoadAdmissionTest {
 @Test void exactExistingAgeBoundaryAndInvalidTimestamps() {
  assertFalse(OceanCanvasAgedNativeLoadAdmission.agedOrInvalid(3499,1000,Long.MIN_VALUE,2500));
  assertTrue(OceanCanvasAgedNativeLoadAdmission.agedOrInvalid(3500,1000,Long.MIN_VALUE,2500));
  assertTrue(OceanCanvasAgedNativeLoadAdmission.agedOrInvalid(3501,1000,Long.MIN_VALUE,2500));
  assertTrue(OceanCanvasAgedNativeLoadAdmission.agedOrInvalid(1000,1001,Long.MIN_VALUE,2500));
  assertTrue(OceanCanvasAgedNativeLoadAdmission.agedOrInvalid(1000,Long.MIN_VALUE,Long.MIN_VALUE,2500));
 }
 @Test void singleLoadHoldsAndAgeObservationIsOncePerTick() {
  var gate=new OceanCanvasAgedNativeLoadAdmission();var observations=new AtomicInteger();
  for(int i=0;i<100;i++) assertTrue(gate.hold(4,18,17,()->{observations.incrementAndGet();return true;}));
  assertEquals(1,observations.get());
  assertFalse(gate.hold(5,18,17,()->false));
 }
 @Test void drainedLoadingResumesImmediatelyAndResamplesSameTick() {
  var gate=new OceanCanvasAgedNativeLoadAdmission();
  assertTrue(gate.hold(4,18,17,()->true));
  assertFalse(gate.hold(4,18,18,()->{fail();return true;}));
  assertFalse(gate.hold(4,19,18,()->false));
 }
 @Test void invalidCountsRefuseWithoutObserving() {
  var gate=new OceanCanvasAgedNativeLoadAdmission();
  assertTrue(gate.hold(1,1,2,()->{fail();return false;}));
  assertTrue(gate.hold(1,-1,0,()->{fail();return false;}));
 }
}
