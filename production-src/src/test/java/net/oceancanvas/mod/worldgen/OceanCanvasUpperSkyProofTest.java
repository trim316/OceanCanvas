package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;
final class OceanCanvasUpperSkyProofTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 static OceanCanvasUpperSkyProof.Probe probe(boolean roof,boolean protectedBad){return new OceanCanvasUpperSkyProof.Probe(){
  public boolean selected(int c){return c==7;}public BlockState state(int c,int y){return roof&&y==100?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();}
  public boolean protectedCell(int c,int y){return protectedBad&&y==95;}public int sky(int c,int y){return y==95?0:15;}};}
 @Test void catchesActualY95HoleThatSurfaceOnlyProofMisses(){var p=new OceanCanvasUpperSkyProof(64,320);while(!p.advance(64,()->false,probe(false,false))){}assertTrue(p.complete());assertEquals(256,p.samples());assertEquals(1,p.anomalies());assertEquals(new OceanCanvasUpperSkyProof.Anomaly(7,95,0),p.firstAnomalies().getFirst());}
 @Test void realOverheadBlockDoesNotRequireAirBeneathRoofToHaveDirectSky(){var p=new OceanCanvasUpperSkyProof(64,320);while(!p.advance(64,()->false,probe(true,false))){}assertEquals(219,p.samples());assertEquals(0,p.anomalies());}
 @Test void protectedCellAndUnselectedPartialColumnsDoNotGainProofOrRepairAuthority(){var p=new OceanCanvasUpperSkyProof(64,320);while(!p.advance(64,()->false,probe(false,true))){}assertEquals(255,p.samples());assertEquals(0,p.anomalies());}
 @Test void budgetAndPreemptionWithholdCompletionAndColdDoesNotBorrowProgress(){var p=new OceanCanvasUpperSkyProof(64,320);assertFalse(p.advance(2,()->true,probe(false,false)));assertEquals(0,p.samples());assertFalse(p.advance(2,()->false,probe(false,false)));assertEquals(1,p.samples());assertFalse(p.complete());assertFalse(new OceanCanvasUpperSkyProof(64,320).complete());}
 @Test void nativeOpacityAndOcclusionDetermineDirectSkyEligibility(){assertFalse(OceanCanvasUpperSkyProof.blocksDirectSky(Blocks.AIR.defaultBlockState(),Blocks.AIR.defaultBlockState()));assertTrue(OceanCanvasUpperSkyProof.blocksDirectSky(Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState()));assertTrue(OceanCanvasUpperSkyProof.blocksDirectSky(Blocks.AIR.defaultBlockState(),Blocks.ICE.defaultBlockState()));assertTrue(OceanCanvasUpperSkyProof.blocksDirectSky(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()));}
 @Test void directSkyEdgeRuleMatchesActualPrivateNativeSourceOracle()throws Exception{
  var method=net.minecraft.world.level.lighting.ChunkSkyLightSources.class.getDeclaredMethod("isEdgeOccluded",BlockState.class,BlockState.class);method.setAccessible(true);
  var states=java.util.List.of(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.ICE.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState());
  for(var above:states)for(var below:states)assertEquals((boolean)method.invoke(null,above,below),OceanCanvasUpperSkyProof.blocksDirectSky(above,below));
 }
 @Test void actualNativeInclusiveWorldMaxDoesNotExcludeTopLayer(){
  var height=new net.minecraft.world.level.LevelHeightAccessor(){public int getMinY(){return -64;}public int getHeight(){return 384;}};
  assertEquals(319,height.getMaxY());var p=new OceanCanvasUpperSkyProof(64,height.getMaxY()+1);
  var probe=new OceanCanvasUpperSkyProof.Probe(){public boolean selected(int c){return c==0;}public BlockState state(int c,int y){return Blocks.AIR.defaultBlockState();}public boolean protectedCell(int c,int y){return false;}public int sky(int c,int y){return y==319?0:15;}};
  while(!p.advance(64,()->false,probe)){}assertEquals(256,p.samples());assertEquals(319,p.firstAnomalies().getFirst().y());
 }
}
