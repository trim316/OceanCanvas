package net.oceancanvas.mod.worldgen;

import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasUnsupportedCrystalPolicyTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 private static final BlockPos POS=new BlockPos(-515,25,783);
 private LevelReader world(BlockPos support,List<BlockPos> reads) {
  return (LevelReader)Proxy.newProxyInstance(LevelReader.class.getClassLoader(),new Class<?>[]{LevelReader.class},(proxy,m,args)->{
   if(m.getName().equals("getBlockState")) {BlockPos p=((BlockPos)args[0]).immutable();reads.add(p);return p.equals(support)?Blocks.STONE.defaultBlockState():Blocks.WATER.defaultBlockState();}
   throw new AssertionError("Unexpected native world query: "+m.getName());
  });
 }
 private BlockState wet(Block block,Direction facing){return block.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true).setValue(BlockStateProperties.FACING,facing);}
 @Test void actualFailedNorthBudUsesItsSouthSupportAndVanillaSaysUnsupported(){
  var reads=new ArrayList<BlockPos>();var w=world(null,reads);var state=wet(Blocks.SMALL_AMETHYST_BUD,Direction.NORTH);
  assertTrue(OceanCanvasUnsupportedCrystalPolicy.nativeUnsupportedWaterCrystal(state,w,POS));
  assertEquals(List.of(POS.south()),reads);assertEquals(Blocks.WATER.defaultBlockState(),state.getFluidState().createLegacyBlock());
 }
 @Test void supportedNormalGrowthIsExcludedInEveryDirection(){
  for(var d:Direction.values())for(var block:List.of(Blocks.SMALL_AMETHYST_BUD,Blocks.MEDIUM_AMETHYST_BUD,Blocks.LARGE_AMETHYST_BUD,Blocks.AMETHYST_CLUSTER))
   assertFalse(OceanCanvasUnsupportedCrystalPolicy.nativeUnsupportedWaterCrystal(wet(block,d),world(POS.relative(d.getOpposite()),new ArrayList<>()),POS));
 }
 @Test void dryOrOtherWaterloggedBlocksCannotAcquireThisReconciliation(){
  var w=world(null,new ArrayList<>());
  assertFalse(OceanCanvasUnsupportedCrystalPolicy.nativeUnsupportedWaterCrystal(Blocks.SMALL_AMETHYST_BUD.defaultBlockState(),w,POS));
  assertFalse(OceanCanvasUnsupportedCrystalPolicy.nativeUnsupportedWaterCrystal(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true),w,POS));
  assertFalse(OceanCanvasUnsupportedCrystalPolicy.nativeUnsupportedWaterCrystal(Blocks.WATER.defaultBlockState(),w,POS));
 }
 @Test void everyScopeAndAuthorityPrerequisiteIsRequired(){
  assertTrue(OceanCanvasUnsupportedCrystalPolicy.permits(true,true,true,true,true,true,false));
  for(int n=0;n<6;n++){boolean[] f={true,true,true,true,true,true};f[n]=false;assertFalse(OceanCanvasUnsupportedCrystalPolicy.permits(f[0],f[1],f[2],f[3],f[4],f[5],false));}
 }
 @Test void playerOrWholeStructureProtectionAlwaysVetoes(){assertFalse(OceanCanvasUnsupportedCrystalPolicy.permits(true,true,true,true,true,true,true));}
 @Test void correctWaterReplacementDoesNotMakeTheBudACanonicalAuditedBlock(){
  var state=wet(Blocks.SMALL_AMETHYST_BUD,Direction.NORTH);
  assertFalse(OceanCanvasSurfaceFlattener.isCanonicalCanvasWaterAuditState(state,25,62));
  assertTrue(OceanCanvasSurfaceFlattener.isCanonicalCanvasWaterAuditState(state.getFluidState().createLegacyBlock(),25,62));
 }
}
