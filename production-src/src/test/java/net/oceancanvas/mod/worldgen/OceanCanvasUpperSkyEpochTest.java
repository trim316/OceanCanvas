package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasUpperSkyEpochTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void mutationAndPhysicalHoldRemoveUpperProofCreditWhileColdStartsEmpty(){
  var s=new OceanCanvasLightFinalizerSession();long key=19;assertNull(s.upperSkyProofState.get(key));
  s.upperSkyProofState.put(key,new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,new long[]{1,0,0,0}));s.invalidateCooperativeProof(key);assertNull(s.upperSkyProofState.get(key));
  s.upperSkyProofState.put(key,new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,new long[]{1,0,0,0}));s.holdPhysicalCertification(key,1);assertNull(s.upperSkyProofState.get(key));assertNull(new OceanCanvasLightFinalizerSession().upperSkyProofState.get(key));
 }
 @Test void exactPartialSelectionCannotBorrowEarlierScopeAndInputArrayMutationCannotChangeBinding(){
  long[] original={1,0,0,0};var p=new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,original);original[0]=3;
  assertTrue(p.matches(null,new long[]{1,0,0,0}));assertFalse(p.matches(null,new long[]{3,0,0,0}));assertFalse(p.ready());
 }
}
