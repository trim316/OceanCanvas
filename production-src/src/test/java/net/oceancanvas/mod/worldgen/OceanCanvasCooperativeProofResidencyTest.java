package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCooperativeProofResidencyTest {
 @Test void newUpperAndFullNativeProofSlicesRetainTheirAlreadyOwnedResidency() {
  assertTrue(OceanCanvasCooperativeProofResidency.retain(1,false,true,false));
  assertTrue(OceanCanvasCooperativeProofResidency.retain(1,false,false,true));
 }
 @Test void realBackoffStillReleasesEvenWhenProofStateRemains() {
  for(int delay:new int[]{0,2,4,20,200})assertFalse(OceanCanvasCooperativeProofResidency.retain(delay,true,true,true));
 }
 @Test void oldCooperativeWorkKeepsItsPolicyAndEmptyWorkDoesNotPinTickets() {
  assertTrue(OceanCanvasCooperativeProofResidency.retain(1,true,false,false));
  assertFalse(OceanCanvasCooperativeProofResidency.retain(1,false,false,false));
 }
}
