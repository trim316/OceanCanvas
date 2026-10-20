package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasDeepRelightCoverageTest {
 @Test void sourceAboveZeroTailIsCoveredBeforeRepeatedTailRepair() {
  assertEquals(3, OceanCanvasDeepRelightCoverage.denseSection(46,62,0));
  assertEquals(2, OceanCanvasDeepRelightCoverage.denseSection(46,62,1));
  assertEquals(3, OceanCanvasDeepRelightCoverage.sourceBandSection(2,62));
 }
 @Test void negativeAndSectionBoundaryWaterLevelsRemainCovered() {
  for(int top=-63;top<=319;top++) {
   int bad=top-16;
   int low=Math.floorDiv(bad,16);
   int high=OceanCanvasDeepRelightCoverage.sourceBandSection(low,top);
   assertTrue((high<<4)<=top && (high<<4)+15>=top);
   assertTrue(high>=low);
   assertEquals(low,OceanCanvasDeepRelightCoverage.denseSection(bad,top,1));
  }
 }
 @Test void anAlreadyHigherAnchorIsNeverExcluded() {
  assertEquals(5,OceanCanvasDeepRelightCoverage.sourceBandSection(5,62));
 }
}
