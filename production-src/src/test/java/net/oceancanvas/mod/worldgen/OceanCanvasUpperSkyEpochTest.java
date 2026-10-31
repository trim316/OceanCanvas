package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasUpperSkyEpochTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void mutationAndPhysicalHoldRemoveUpperProofCreditWhileColdStartsEmpty(){
  var s=new OceanCanvasLightFinalizerSession();long key=19;assertNull(s.upperSkyProofState.get(key));
  s.upperSkyProofState.put(key,new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,new long[]{1,0,0,0},0));s.invalidateCooperativeProof(key);assertNull(s.upperSkyProofState.get(key));
  s.upperSkyProofState.put(key,new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,new long[]{1,0,0,0},0));s.holdPhysicalCertification(key,1);assertNull(s.upperSkyProofState.get(key));assertNull(new OceanCanvasLightFinalizerSession().upperSkyProofState.get(key));
 }
 @Test void exactPartialSelectionCannotBorrowEarlierScopeAndInputArrayMutationCannotChangeBinding(){
  long[] original={1,0,0,0};var p=new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,original,0);original[0]=3;
  assertTrue(p.matches(null,new long[]{1,0,0,0},0));assertFalse(p.matches(null,new long[]{3,0,0,0},0));assertFalse(p.ready());
 }
 @Test void absentUpperReadinessDoesNotSpendResidentAttempt(){
  var s=new OceanCanvasLightFinalizerSession();long key=21;s.preLightPhysicalAuditComplete.add(key);
  assertFalse(s.claimInitialResidentProof(key,0,false,false));assertFalse(s.initialResidentProofAttempted.contains(key));
  assertTrue(s.claimInitialResidentProof(key,0,false,true));assertFalse(s.claimInitialResidentProof(key,0,false,true));
 }
 @Test void actualZoneEditsInvalidateBindingButSavingAndRejectedEditsDoNot(){
  var zones=new OceanCanvasPlayerZones();long revision=zones.protectionProofRevision();long[] columns={1,0,0,0};
  var proof=new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,columns,revision);
  assertNull(zones.define("roof",new net.minecraft.world.level.levelgen.structure.BoundingBox(0,90,0,2,100,2),null,null));
  assertTrue(zones.protectionProofRevision()>revision);assertFalse(proof.matches(null,columns,zones.protectionProofRevision()));
  long edited=zones.protectionProofRevision();zones.setDirty(false);assertEquals(edited,zones.protectionProofRevision());
  assertNotNull(zones.define("roof",new net.minecraft.world.level.levelgen.structure.BoundingBox(0,90,0,2,100,2),null,null));assertEquals(edited,zones.protectionProofRevision());
  assertNull(zones.remove("roof",null,true));assertTrue(zones.protectionProofRevision()>edited);
 }
 @Test void integratedUpperProofIncludesAirImmediatelyAboveIceSurface(){
  var state=new OceanCanvasSurfaceFlattener.UpperSkyProofState(null,320,new long[]{1,0,0,0},0);
  var probe=new OceanCanvasUpperSkyProof.Probe(){public boolean selected(int c){return c==0;}public net.minecraft.world.level.block.state.BlockState state(int c,int y){return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();}public boolean protectedCell(int c,int y){return false;}public int sky(int c,int y){return y==63?0:15;}};
  while(!state.proof.advance(64,()->false,probe)){}assertFalse(state.ready());assertEquals(63,state.proof.firstAnomalies().getFirst().y());
 }
}
