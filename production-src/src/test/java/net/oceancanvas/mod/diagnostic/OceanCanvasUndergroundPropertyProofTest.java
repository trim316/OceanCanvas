package net.oceancanvas.mod.diagnostic;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.properties.*;
final class OceanCanvasUndergroundPropertyProofTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void actualNativeSensorPhaseBelowTransitionDoesNotRestartCanvasProof(){
  var a=Blocks.SCULK_SENSOR.defaultBlockState().setValue(SculkSensorBlock.PHASE,SculkSensorPhase.COOLDOWN);
  var b=a.setValue(SculkSensorBlock.PHASE,SculkSensorPhase.INACTIVE);
  assertEquals(-26,net.oceancanvas.mod.worldgen.OceanCanvasFloorProfile.floorOffset(-896,-220,5)-25);
  assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(-31,-30,a,b));
  assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(-30,-30,a,b));
  assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(62,-30,a,b));
 }
 @Test void blockReplacementBelowProfileStillRestartsProof(){
  assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(-31,-30,Blocks.SCULK_SENSOR.defaultBlockState(),Blocks.STONE.defaultBlockState()));
 }
 @Test void lightAndFluidChangesRemainRelevantBelowProfile(){
  var lamp=Blocks.REDSTONE_LAMP.defaultBlockState();
  assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(-31,-30,lamp,lamp.setValue(RedstoneLampBlock.LIT,true)));
  var sensor=Blocks.SCULK_SENSOR.defaultBlockState();
  assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(-31,-30,sensor,sensor.setValue(SculkSensorBlock.WATERLOGGED,true)));
 }
 @Test void sensorPhaseAndPowerStatesHaveExactlyEqualNativeLightGeometry(){
  var base=Blocks.SCULK_SENSOR.defaultBlockState();
  for(var phase:SculkSensorPhase.values())for(int power=0;power<=15;power++){
   var changed=base.setValue(SculkSensorBlock.PHASE,phase).setValue(SculkSensorBlock.POWER,power);
   assertTrue(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(-31,-30,base,changed));
  }
 }
 @Test void changingSameBlockOcclusionGeometryStillInvalidatesBelowFloor(){
  var closed=Blocks.OAK_TRAPDOOR.defaultBlockState();
  assertFalse(OceanCanvasPostPhysicalMutationDiagnostics.irrelevantUndergroundPropertyChange(-31,-30,closed,closed.setValue(TrapDoorBlock.OPEN,true)));
 }
}
