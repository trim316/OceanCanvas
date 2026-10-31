package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import net.minecraft.core.Direction;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;
final class OceanCanvasBlockLightProofTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 static OceanCanvasBlockLightProof.Probe probe(BlockState target,int actual,int neighbor,boolean resident,boolean protect){return new OceanCanvasBlockLightProof.Probe(){
  public boolean selected(int c){return c==0;}public boolean protectedCell(int c,int y){return protect;}public boolean resident(int c,int y){return resident;}
  public BlockState state(int c,int y){return target;}public int light(int c,int y){return actual;}public BlockState neighborState(int c,int y,Direction d){return Blocks.WATER.defaultBlockState();}public int neighborLight(int c,int y,Direction d){return neighbor;}};}
 @Test void exactSavedSourceWaterExcessCannotPassNecessaryFullEquation(){var p=new OceanCanvasBlockLightProof(-17,-16);assertTrue(p.advance(14,()->false,probe(Blocks.WATER.defaultBlockState(),3,3,true,false)));assertEquals(1,p.anomalies());assertEquals(new OceanCanvasBlockLightProof.Anomaly(0,-17,3,2),p.firstAnomalies().getFirst());}
 @Test void missingIncomingLightAndOwnEmitterAreBothChecked(){var missing=new OceanCanvasBlockLightProof(0,1);missing.advance(14,()->false,probe(Blocks.AIR.defaultBlockState(),0,10,true,false));assertEquals(9,missing.firstAnomalies().getFirst().expected());var emitter=new OceanCanvasBlockLightProof(0,1);emitter.advance(14,()->false,probe(Blocks.LAVA.defaultBlockState(),15,0,true,false));assertEquals(0,emitter.anomalies());}
 @Test void opacityAndNativeFaceGeometryConstrainIncoming(){assertEquals(14,OceanCanvasBlockLightProof.incoming(Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState(),Direction.EAST,15));assertEquals(0,OceanCanvasBlockLightProof.incoming(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),Direction.EAST,15));assertEquals(14,OceanCanvasBlockLightProof.incoming(Blocks.STONE.defaultBlockState(),Blocks.AIR.defaultBlockState(),Direction.EAST,15));}
 @Test void boundedStencilPreemptionColdAndUnloadedNeighborCannotBorrowCompletion(){var p=new OceanCanvasBlockLightProof(0,2);var good=probe(Blocks.WATER.defaultBlockState(),2,3,true,false);assertFalse(p.advance(14,()->true,good));assertEquals(0,p.samples());assertFalse(p.advance(14,()->false,good));assertEquals(1,p.samples());assertFalse(p.advance(14,()->false,probe(Blocks.WATER.defaultBlockState(),2,3,false,false)));assertEquals(1,p.samples());assertFalse(new OceanCanvasBlockLightProof(0,2).complete());assertTrue(p.advance(14,()->false,good));assertEquals(2,p.samples());}
 @Test void protectedAndUnselectedColumnsReceiveNoAuthorityOrSamples(){var p=new OceanCanvasBlockLightProof(-64,320);assertTrue(p.advance(14,()->false,probe(Blocks.WATER.defaultBlockState(),3,3,false,true)));assertEquals(0,p.samples());assertEquals(0,p.anomalies());}
 @Test void publicStencilMatchesActualNativeProtectedOpacityAndShapeMethods()throws Exception{
  var engine=new net.minecraft.world.level.lighting.BlockLightEngine(null);
  var opacity=net.minecraft.world.level.lighting.LightEngine.class.getDeclaredMethod("getOpacity",BlockState.class);opacity.setAccessible(true);
  var shape=net.minecraft.world.level.lighting.LightEngine.class.getDeclaredMethod("shapeOccludes",BlockState.class,BlockState.class,Direction.class);shape.setAccessible(true);
  var states=java.util.List.of(Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.LAVA.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState(),Blocks.OAK_STAIRS.defaultBlockState());
  for(var source:states)for(var target:states)for(var d:Direction.values()){
   int expected=(boolean)shape.invoke(engine,source,target,d)?0:Math.max(0,15-(int)opacity.invoke(engine,target));
   assertEquals(expected,OceanCanvasBlockLightProof.incoming(source,target,d,15));
  }
 }
}
