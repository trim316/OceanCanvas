package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasPregenTickBudgetTest {
 @Test void repeatedIncompleteEntriesCannotBypassWallBudget(){
  long elapsed=0;int attempted=0;int queued=64;
  while(attempted<queued && !OceanCanvasPregenTickBudget.shouldYield(true,attempted,elapsed,20_000_000L)){
   // Model a readiness or interrupted-carve path: it consumes time, requeues, and retires nothing.
   attempted++;elapsed+=3_000_000L;
  }
  assertEquals(7,attempted);
  assertEquals(21_000_000L,elapsed); // At most the final atomic queue attempt overshoots.
 }
 @Test void boundaryAndFirstAttemptRemainLive(){
  assertFalse(OceanCanvasPregenTickBudget.shouldYield(true,0,50_000_000L,20_000_000L));
  assertFalse(OceanCanvasPregenTickBudget.shouldYield(true,1,19_999_999L,20_000_000L));
  assertTrue(OceanCanvasPregenTickBudget.shouldYield(true,1,20_000_000L,20_000_000L));
  assertFalse(OceanCanvasPregenTickBudget.shouldYield(false,64,200_000_000L,20_000_000L));
 }
}
