package net.oceancanvas.mod.worldgen;
import java.util.*;import java.util.function.BooleanSupplier;
import net.minecraft.core.Direction;import net.minecraft.world.level.block.Blocks;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.world.level.lighting.LightEngine;import net.minecraft.world.phys.shapes.Shapes;
/** Bounded current-epoch direct-sky detection only. No repair or certificate authority. */
final class OceanCanvasUpperSkyProof {
 interface Probe {boolean selected(int column);BlockState state(int column,int y);boolean protectedCell(int column,int y);int sky(int column,int y);}
 record Anomaly(int column,int y,int actual){}
 private final int maxY,minY;private int column,y;private BlockState above=Blocks.AIR.defaultBlockState();
 private int samples,bad;private final List<Anomaly> first=new ArrayList<>();
 OceanCanvasUpperSkyProof(int minInclusive,int maxExclusive){if(minInclusive>=maxExclusive)throw new IllegalArgumentException();minY=minInclusive;maxY=maxExclusive;y=maxY-1;}
 static boolean blocksDirectSky(BlockState above,BlockState current){
  // Exactly the native ChunkSkyLightSources.isEdgeOccluded rule, using public APIs.
  return current.getLightDampening()!=0||Shapes.faceShapeOccludes(
      LightEngine.getOcclusionShape(above,Direction.DOWN),LightEngine.getOcclusionShape(current,Direction.UP));
 }
 private void nextColumn(){column++;y=maxY-1;above=Blocks.AIR.defaultBlockState();}
 boolean advance(int readBudget,BooleanSupplier yield,Probe probe){
  if(readBudget<2)throw new IllegalArgumentException("state+light read budget required");int used=0;
  while(column<256){
   if(yield.getAsBoolean()||used+2>readBudget)return false;
   if(!probe.selected(column)){nextColumn();continue;}
   BlockState state=probe.state(column,y);used++;
   if(blocksDirectSky(above,state)){nextColumn();continue;}
   if(state.isAir()&&!probe.protectedCell(column,y)){
    int sky=probe.sky(column,y);used++;samples++;
    if(sky!=15){bad++;if(first.size()<64)first.add(new Anomaly(column,y,sky));}
   }
   above=state;if(--y<minY)nextColumn();
  }
  return true;
 }
 boolean complete(){return column==256;}int samples(){return samples;}int anomalies(){return bad;}List<Anomaly> firstAnomalies(){return List.copyOf(first);}
}
