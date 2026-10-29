package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCurrentFrameAdmissionTest {
 @Test void exactPhaseReservationBoundary() {assertTrue(OceanCanvasCurrentFrameAdmission.allows(29_999_999,20_000_000,50_000_000));assertTrue(OceanCanvasCurrentFrameAdmission.allows(30_000_000,20_000_000,50_000_000));assertFalse(OceanCanvasCurrentFrameAdmission.allows(30_000_001,20_000_000,50_000_000));}
 @Test void invalidOrOverflowingInputsNeverAdmit() {assertFalse(OceanCanvasCurrentFrameAdmission.allows(-1,20,50));assertFalse(OceanCanvasCurrentFrameAdmission.allows(0,-1,50));assertFalse(OceanCanvasCurrentFrameAdmission.allows(0,51,50));assertFalse(OceanCanvasCurrentFrameAdmission.allows(Long.MAX_VALUE,20,50));}
 @Test void rawTimingIsUnavailableAfterEndAndResetsEveryTick() {var t=new net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.TickTiming();assertEquals(-1,t.elapsedNanos(5));t.begin(10);assertEquals(5,t.elapsedNanos(15));assertEquals(-1,t.elapsedNanos(9));t.end(20);assertEquals(-1,t.elapsedNanos(21));t.begin(100);assertEquals(1,t.elapsedNanos(101));}
}
