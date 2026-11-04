package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
final class OceanCanvasFullSkyLightProofTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 static int ideal(int y){return y>=0?15:Math.max(0,15+y);}
 static BlockState state(int y){return y>=0?Blocks.AIR.defaultBlockState():y<=-63?Blocks.BEDROCK.defaultBlockState():Blocks.WATER.defaultBlockState();}
 static OceanCanvasFullSkyLightProof.Probe probe(boolean corrupt,boolean protect,boolean resident){return new OceanCanvasFullSkyLightProof.Probe(){
  public boolean selected(int c){return c==0;}public boolean protectedCell(int c,int y){return protect;}
  public boolean resident(int c,int y){return resident;}
  public BlockState state(int c,int y){return OceanCanvasFullSkyLightProofTest.state(y);}
  public int light(int c,int y){return corrupt&&y<=-63?15:ideal(y);}
  public BlockState neighborState(int c,int y,Direction d){return OceanCanvasFullSkyLightProofTest.state(y+d.getStepY());}
  public int neighborLight(int c,int y,Direction d){return ideal(y+d.getStepY());}
 };}
 @Test void actual153ExplicitBedrock15CannotHideBelowUpperSkyProof(){var p=new OceanCanvasFullSkyLightProof(-64,320);while(!p.advance(4096,()->false,probe(true,false,true))){}assertEquals(384,p.samples());assertEquals(2,p.anomalies());assertEquals(new OceanCanvasFullSkyLightProof.Anomaly(0,-63,15,0),p.firstAnomalies().getFirst());}
 @Test void directAirAndAttenuatedWaterAndOpaqueBottomMatchNativeFixedPoint(){var p=new OceanCanvasFullSkyLightProof(-64,320);while(!p.advance(4096,()->false,probe(false,false,true))){}assertEquals(384,p.samples());assertEquals(0,p.anomalies());}
 @Test void preemptionBudgetAndMissingContextNeverAdvanceToCompletion(){var p=new OceanCanvasFullSkyLightProof(-64,320);assertFalse(p.advance(4096,()->true,probe(false,false,true)));assertEquals(0,p.samples());assertFalse(p.advance(14,()->false,probe(false,false,true)));assertEquals(1,p.samples());assertFalse(p.advance(4096,()->false,probe(false,false,false)));assertEquals(1,p.samples());assertFalse(p.complete());}
 @Test void protectionAndPartialSelectionDoNotGainSamplesOrRepairAuthority(){var p=new OceanCanvasFullSkyLightProof(-64,320);while(!p.advance(4096,()->false,probe(true,true,true))){}assertEquals(0,p.samples());assertEquals(0,p.anomalies());}
 @Test void freshEpochDoesNotInheritPartialOrCompletedOldProof(){var p=new OceanCanvasFullSkyLightProof(-64,320);p.advance(14,()->false,probe(false,false,true));var fresh=new OceanCanvasFullSkyLightProof(-64,320);assertEquals(0,fresh.samples());assertFalse(fresh.complete());}
 @Test void directSourceEdgeRuleMatchesActualNativeMethod()throws Exception{var method=net.minecraft.world.level.lighting.ChunkSkyLightSources.class.getDeclaredMethod("isEdgeOccluded",BlockState.class,BlockState.class);method.setAccessible(true);var states=java.util.List.of(Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.BEDROCK.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState(),Blocks.OAK_STAIRS.defaultBlockState());for(var above:states)for(var current:states)assertEquals(method.invoke(null,above,current),OceanCanvasUpperSkyProof.blocksDirectSky(above,current));}
}
