package net.oceancanvas.mod.worldgen;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.loot.LootTable;
/** Captured-world relocation only. Vanilla fresh template generation keeps its normal random seeds. */
final class OceanCanvasCapturedLootSeeds {
 private OceanCanvasCapturedLootSeeds(){}
 record Seed(ResourceKey<LootTable> table,long value){
  static Optional<Seed> capture(RandomizableContainer container){
   var table=container.getLootTable();return table==null?Optional.empty():Optional.of(new Seed(table,container.getLootTableSeed()));
  }
  boolean matches(RandomizableContainer container){return table.equals(container.getLootTable());}
  boolean restore(RandomizableContainer container){
   if(!matches(container))return false;container.setLootTableSeed(value);return container.getLootTableSeed()==value;
  }
 }
 private record Entry(int dx,int dy,int dz,Block block,Seed seed){}
 static boolean place(ServerLevel world,StructureTemplate template,BlockPos source,BlockPos target,int sx,int sy,int sz,int flags){
  List<Entry> seeds=new ArrayList<>();
  for(int cx=source.getX()>>4;cx<=(source.getX()+sx-1)>>4;cx++)for(int cz=source.getZ()>>4;cz<=(source.getZ()+sz-1)>>4;cz++){
   var chunk=world.getChunkSource().getChunkNow(cx,cz);if(chunk==null)throw new IllegalStateException("Captured relocation source not resident");
   for(BlockEntity be:chunk.getBlockEntities().values()){
    var pos=be.getBlockPos();int dx=pos.getX()-source.getX(),dy=pos.getY()-source.getY(),dz=pos.getZ()-source.getZ();
    if(dx<0||dy<0||dz<0||dx>=sx||dy>=sy||dz>=sz||!(be instanceof RandomizableContainer container))continue;
    Seed.capture(container).ifPresent(seed->seeds.add(new Entry(dx,dy,dz,be.getBlockState().getBlock(),seed)));
   }
  }
  // Native placeInWorld mutates the template NBT's LootTableSeed. The primitive snapshots above
  // deliberately belong to the actual source containers, not that subsequently modified NBT.
  if(!template.placeInWorld(world,target,target,new StructurePlaceSettings(),world.getRandom(),flags))return false;
  List<BlockEntity> destinations=new ArrayList<>();
  for(Entry e:seeds){
   BlockPos pos=target.offset(e.dx,e.dy,e.dz);var chunk=world.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
   BlockEntity be=chunk==null?null:chunk.getBlockEntity(pos);
   if(be==null||!be.getBlockState().is(e.block)||!(be instanceof RandomizableContainer c)||!e.seed.matches(c)){
    net.oceancanvas.mod.OceanCanvas.LOGGER.error("Captured relocation loot destination mismatch at {}; original must remain intact",pos);return false;
   }
   destinations.add(be);
  }
  for(int i=0;i<seeds.size();i++){
   BlockEntity be=destinations.get(i);
   if(!seeds.get(i).seed.restore((RandomizableContainer)be)){
    net.oceancanvas.mod.OceanCanvas.LOGGER.error("Captured relocation loot seed restoration failed at {}; original must remain intact",be.getBlockPos());return false;
   }
   be.setChanged();world.getChunkSource().getChunkNow(be.getBlockPos().getX()>>4,be.getBlockPos().getZ()>>4).markUnsaved();
  }
  return true;
 }
}
