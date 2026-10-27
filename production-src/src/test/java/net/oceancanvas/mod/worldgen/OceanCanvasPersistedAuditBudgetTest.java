package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasPersistedAuditBudgetTest {
 @Test void yieldingBeforeClaimPreservesAllRemainingDebtAndLaterDrains(){
  var due=new ArrayDeque<Integer>(); for(int i=0;i<16;i++) due.add(i);
  int verified=0, ticks=0;
  while(!due.isEmpty()) {
   long elapsed=0;int claimed=0;ticks++;
   while(claimed<16 && !due.isEmpty()) {
    if(OceanCanvasPregenTickBudget.shouldYield(true,claimed,elapsed,20_000_000L)) break;
    assertEquals(verified,due.removeFirst());claimed++;verified++;elapsed+=7_000_000L;
   }
   assertTrue(claimed>0);assertTrue(claimed<=3);
   assertEquals(16,verified+due.size());
  }
  assertEquals(16,verified);assertEquals(6,ticks);
 }
}
