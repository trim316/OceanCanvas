package net.oceancanvas.mod.worldgen;
import java.lang.reflect.Proxy;import java.util.*;
import net.minecraft.core.*;import net.minecraft.world.level.*;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasNativeNeighborShapeTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 private static LevelAccessor neighborhood(Map<BlockPos,BlockState> cells){
  return (LevelAccessor)Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),new Class<?>[]{LevelAccessor.class},(proxy,m,a)->switch(m.getName()){
   case "getBlockState"->cells.getOrDefault((BlockPos)a[0],Blocks.WATER.defaultBlockState());
   case "getFluidState"->cells.getOrDefault((BlockPos)a[0],Blocks.WATER.defaultBlockState()).getFluidState();
   case "getRandom"->RandomSource.create(1);
   case "scheduleTick"->null;
   case "getMinY"->-64;case "getHeight"->384;
   default->throw new AssertionError("Unexpected world operation: "+m.getName());
  });
 }
 @Test void staleFenceConnectionsAreRemovedAgainstActualWaterNeighbors(){
  var old=Blocks.SPRUCE_FENCE.defaultBlockState().setValue(FenceBlock.NORTH,true).setValue(FenceBlock.SOUTH,true).setValue(FenceBlock.EAST,true).setValue(FenceBlock.WEST,true).setValue(FenceBlock.WATERLOGGED,true);
  var actual=OceanCanvasStructureEnvironmentService.neighborValidatedState(old,neighborhood(Map.of()),BlockPos.ZERO);
  assertFalse(actual.getValue(FenceBlock.NORTH));assertFalse(actual.getValue(FenceBlock.SOUTH));assertFalse(actual.getValue(FenceBlock.EAST));assertFalse(actual.getValue(FenceBlock.WEST));assertTrue(actual.getValue(FenceBlock.WATERLOGGED));
 }
 @Test void legitimateSolidNeighborConnectionSurvivesAndNoWorldWriteOccurs(){
  var old=Blocks.SPRUCE_FENCE.defaultBlockState().setValue(FenceBlock.NORTH,false).setValue(FenceBlock.SOUTH,true);
  var actual=OceanCanvasStructureEnvironmentService.neighborValidatedState(old,neighborhood(Map.of(BlockPos.ZERO.north(),Blocks.STONE.defaultBlockState())),BlockPos.ZERO);
  assertTrue(actual.getValue(FenceBlock.NORTH));assertFalse(actual.getValue(FenceBlock.SOUTH));assertSame(actual.getBlock(),old.getBlock());
 }
 @Test void nativeStairShapeRecomputedWithoutChangingFacingHalfOrWaterlogging(){
  var old=Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH).setValue(StairBlock.SHAPE,StairsShape.INNER_LEFT).setValue(StairBlock.WATERLOGGED,true);
  var actual=OceanCanvasStructureEnvironmentService.neighborValidatedState(old,neighborhood(Map.of()),BlockPos.ZERO);
  assertEquals(StairsShape.STRAIGHT,actual.getValue(StairBlock.SHAPE));assertEquals(old.getValue(StairBlock.FACING),actual.getValue(StairBlock.FACING));assertEquals(old.getValue(StairBlock.HALF),actual.getValue(StairBlock.HALF));assertTrue(actual.getValue(StairBlock.WATERLOGGED));
 }
 @Test void unrelatedBlocksKeepExistingBehaviorWithoutNewNeighborQueries(){
  for(var state:List.of(Blocks.RAIL.defaultBlockState(),Blocks.CHEST.defaultBlockState(),Blocks.KELP.defaultBlockState()))
   assertSame(state,OceanCanvasStructureEnvironmentService.neighborValidatedState(state,null,BlockPos.ZERO));
 }
}
