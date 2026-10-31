package net.oceancanvas.mod.worldgen;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
/** Read-only scheduling fence, never terrain authority or a physical/light certificate. */
final class OceanCanvasStructureContextFence {
 enum ChunkState { UNLOADED, OWNED_UNPROVED, STABLE }
 enum Result { WAITING, YIELDED, READY }
 @FunctionalInterface interface Context { ChunkState inspect(int x,int z); }
 private final int minX,maxX,minZ,maxZ;
 private long epoch=Long.MIN_VALUE,cursor;
 OceanCanvasStructureContextFence(BoundingBox box){
  // Shape and waterlogging depend on immediate neighbors, not a whole chunk ring.
  minX=Math.toIntExact(Math.floorDiv((long)box.minX()-1,16));maxX=Math.toIntExact(Math.floorDiv((long)box.maxX()+1,16));
  minZ=Math.toIntExact(Math.floorDiv((long)box.minZ()-1,16));maxZ=Math.toIntExact(Math.floorDiv((long)box.maxZ()+1,16));
 }
 Result advance(long currentEpoch,int budget,Context context){
  if(budget<1)throw new IllegalArgumentException("positive inspection budget required");
  if(epoch!=currentEpoch){epoch=currentEpoch;cursor=0;}
  long width=(long)maxX-minX+1,total=width*((long)maxZ-minZ+1);
  for(int inspected=0;inspected<budget&&cursor<total;inspected++){
   int x=Math.toIntExact(minX+cursor%width),z=Math.toIntExact(minZ+cursor/width);
   // No missing/unowned/partial chunk is silently credited: caller must prove stable
   // current context. STABLE may include selected partial columns after exact proof;
   // no whole-chunk seal is required or inferred here.
   if(context.inspect(x,z)!=ChunkState.STABLE)return Result.WAITING;
   cursor++;
  }
  return cursor==total?Result.READY:Result.YIELDED;
 }
 void invalidate(){epoch=Long.MIN_VALUE;cursor=0;}
}
