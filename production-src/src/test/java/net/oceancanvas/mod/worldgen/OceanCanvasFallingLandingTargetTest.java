package net.oceancanvas.mod.worldgen;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import net.minecraft.world.level.block.Blocks;
final class OceanCanvasFallingLandingTargetTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void sourceAndEveryAquaticPlantStateAreLandingProtectedTargets(){
  assertTrue(OceanCanvasSurfaceFlattener.isAuthoredFallingLandingTarget(Blocks.WATER.defaultBlockState()));
  for(var block:java.util.List.of(Blocks.SEAGRASS,Blocks.TALL_SEAGRASS,Blocks.KELP,Blocks.KELP_PLANT))
   for(var state:block.getStateDefinition().getPossibleStates())assertTrue(OceanCanvasSurfaceFlattener.isAuthoredFallingLandingTarget(state));
 }
 @Test void flowingWaterOpaqueAndUnrelatedWaterloggedBlocksRemainExcluded(){
  for(var state:Blocks.WATER.getStateDefinition().getPossibleStates())assertEquals(state.getFluidState().isSource(),OceanCanvasSurfaceFlattener.isAuthoredFallingLandingTarget(state));
  for(var block:java.util.List.of(Blocks.STONE,Blocks.CHEST,Blocks.OAK_FENCE,Blocks.ICE,Blocks.AIR))
   for(var state:block.getStateDefinition().getPossibleStates())assertFalse(OceanCanvasSurfaceFlattener.isAuthoredFallingLandingTarget(state));
 }
}
