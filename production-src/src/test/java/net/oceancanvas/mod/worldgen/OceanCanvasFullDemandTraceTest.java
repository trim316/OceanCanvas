package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasFullDemandTraceTest {
 @Test void timestampsExposeQueueCallWaitAndDeliveryWithoutRegressing() {
  var t=new OceanCanvasFullDemandTrace(10);t.advance(OceanCanvasFullDemandTrace.Phase.CALLING_NATIVE,20);t.advance(OceanCanvasFullDemandTrace.Phase.WAITING_NATIVE,30);
  assertTrue(t.snapshot(80).contains("phase=WAITING_NATIVE ageNs=70 phaseAgeNs=50"));
  t.advance(OceanCanvasFullDemandTrace.Phase.CALLING_NATIVE,90);assertTrue(t.snapshot(100).contains("WAITING_NATIVEAtNs=30"));
  t.advance(OceanCanvasFullDemandTrace.Phase.HANDOFF_QUEUED,110);assertTrue(t.snapshot(120).contains("phase=HANDOFF_QUEUED"));
 }
 @Test void terminalCancellationOrFailureCannotBeReopenedByLateCallback() {
  var t=new OceanCanvasFullDemandTrace(10);t.advance(OceanCanvasFullDemandTrace.Phase.TERMINAL,20);t.advance(OceanCanvasFullDemandTrace.Phase.NATIVE_COMPLETED,30);
  assertTrue(t.snapshot(40).contains("phase=TERMINAL"));
 }
 @Test void oldRequestCleanupCannotRemoveReplacementTrace() {
  var m=new java.util.concurrent.ConcurrentHashMap<Long,OceanCanvasFullDemandTrace>();var old=new OceanCanvasFullDemandTrace(1);var next=new OceanCanvasFullDemandTrace(2);m.put(9L,next);
  assertFalse(m.remove(9L,old));assertSame(next,m.get(9L));assertTrue(m.remove(9L,next));
 }
 @Test void backwardClockIsReportedInvalid() {var t=new OceanCanvasFullDemandTrace(100);assertTrue(t.snapshot(99).contains("ageNs=-1"));}
}
