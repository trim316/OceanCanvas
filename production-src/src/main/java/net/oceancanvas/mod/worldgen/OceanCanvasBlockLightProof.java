package net.oceancanvas.mod.worldgen;
import java.util.*;import java.util.function.BooleanSupplier;
import net.minecraft.core.Direction;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.world.level.lighting.LightEngine;import net.minecraft.world.phys.shapes.Shapes;
/** Ephemeral read-only fixed-point detector. Caller must bind epoch, policy, scope and residency. */
final class OceanCanvasBlockLightProof {
 interface Probe {
  boolean selected(int column);boolean protectedCell(int column,int y);
  /** Must establish all six actual neighbors without loading or substituting missing values. */
  boolean resident(int column,int y);
  BlockState state(int column,int y);int light(int column,int y);
  BlockState neighborState(int column,int y,Direction direction);int neighborLight(int column,int y,Direction direction);
 }
 record Anomaly(int column,int y,int actual,int expected){}
 private final int minY,maxY;private int column,y,samples,bad;private final List<Anomaly> first=new ArrayList<>();
 OceanCanvasBlockLightProof(int minInclusive,int maxExclusive){if(minInclusive>=maxExclusive)throw new IllegalArgumentException();minY=minInclusive;maxY=maxExclusive;y=minY;}
 static int incoming(BlockState source,BlockState target,Direction sourceToTarget,int sourceLight){
  if(sourceLight<0||sourceLight>15)throw new IllegalArgumentException("native light range");
  if(Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(source,sourceToTarget),LightEngine.getOcclusionShape(target,sourceToTarget.getOpposite())))return 0;
  return Math.max(0,sourceLight-Math.max(1,target.getLightDampening()));
 }
 private void next(){if(++y==maxY){column++;y=minY;}}
 boolean advance(int readBudget,BooleanSupplier yield,Probe p){
  // Each indivisible cell uses two center reads and twelve neighbor reads. Scope/policy/residency are caller metadata checks.
  if(readBudget<14)throw new IllegalArgumentException("one complete native stencil requires14reads");int used=0;
  while(column<256){
   if(yield.getAsBoolean())return false;
   if(!p.selected(column)){column++;y=minY;continue;}
   if(p.protectedCell(column,y)){next();continue;}
   if(used+14>readBudget||!p.resident(column,y))return false;
   BlockState current=p.state(column,y);int actual=p.light(column,y);if(actual<0||actual>15)throw new IllegalArgumentException("native light range");
   int expected=current.getLightEmission();
   for(Direction d:Direction.values())expected=Math.max(expected,incoming(p.neighborState(column,y,d),current,d.getOpposite(),p.neighborLight(column,y,d)));
   used+=14;samples++;if(actual!=expected){bad++;if(first.size()<64)first.add(new Anomaly(column,y,actual,expected));}next();
  }
  return true;
 }
 boolean complete(){return column==256;}int samples(){return samples;}int anomalies(){return bad;}List<Anomaly> firstAnomalies(){return List.copyOf(first);}
}
