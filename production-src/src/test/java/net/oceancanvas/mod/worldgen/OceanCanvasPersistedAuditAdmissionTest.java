package net.oceancanvas.mod.worldgen;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasPersistedAuditAdmissionTest {
 @Test void boundaryAndInvalidSamplesFailClosed() {
  assertTrue(OceanCanvasPersistedAuditAdmission.canAttempt(.819999D,.82D));
  for(double h:new double[]{.82D,.9D,1D,Double.NaN,Double.POSITIVE_INFINITY,-.1D})
   assertFalse(OceanCanvasPersistedAuditAdmission.canAttempt(h,.82D));
  for(double boundary:new double[]{0D,1.1D,Double.NaN,Double.NEGATIVE_INFINITY})
   assertFalse(OceanCanvasPersistedAuditAdmission.canAttempt(.4D,boundary));
 }
 @Test void pressureDefersWithoutPollingAndRecoveredHeapResumesAllDebt() {
  var due=new ArrayDeque<Integer>();for(int i=0;i<16;i++)due.add(i);
  int verified=0;
  for(double heap:new double[]{.9D,.87D,.82D,.4D,.5D}) {
   if(!OceanCanvasPersistedAuditAdmission.canAttempt(heap,.82D)) {assertEquals(16,due.size());continue;}
   while(!due.isEmpty()){assertEquals(verified,due.removeFirst());verified++;}
  }
  assertEquals(16,verified);assertTrue(due.isEmpty());
 }
}
