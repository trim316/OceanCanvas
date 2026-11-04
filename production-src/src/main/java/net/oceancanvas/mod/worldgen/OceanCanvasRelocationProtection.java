package net.oceancanvas.mod.worldgen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
/** Pre-mutation absolute-authority check. Does not confer protection or completion. */
public final class OceanCanvasRelocationProtection {
 private OceanCanvasRelocationProtection(){}
 @FunctionalInterface public interface AbsoluteCell { boolean protectedAt(int x,int y,int z); }
 public static final class Collision extends IllegalStateException {
  public Collision(String stage,BlockPos at){super("Absolute protection blocks structure relocation "+stage+" at "+at+"; original and destination retained, operation must fail with checkpoint");}
 }
 public static void requireUnprotected(BoundingBox source,BoundingBox target,AbsoluteCell authority){
  check(target,null,"destination",authority);
  check(source,target,"source-clear",authority);
 }
 private static void check(BoundingBox box,BoundingBox excluded,String stage,AbsoluteCell authority){
  for(int x=box.minX();;x++){
   for(int z=box.minZ();;z++){
    for(int y=box.minY();;y++){
     if((excluded==null||!excluded.isInside(x,y,z))&&authority.protectedAt(x,y,z))throw new Collision(stage,new BlockPos(x,y,z));
     if(y==box.maxY())break;
    }
    if(z==box.maxZ())break;
   }
   if(x==box.maxX())break;
  }
 }
}
