package net.oceancanvas.probe.managed;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.oceancanvas.mod.worldgen.OceanCanvasActiveTerrainOperationBridge;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
/** Read-only observer at the actual product capture boundary, after its ready-footprint guard. */
public final class ManagedShipwreckSnapshot implements ModInitializer {
 private static boolean beforeSaved,afterSaved;
 @Override public void onInitialize() { }
 private static boolean selected(StructureStart start){return start.getChunkPos().x()==83&&start.getChunkPos().z()==110&&OceanCanvasActiveTerrainOperationBridge.pregenIncludesChunk(83,110);}
 public static void before(ServerLevel world,StructureStart start){
  if(beforeSaved||System.getProperty("oceancanvas.fixture.managedEvidence")==null||!selected(start))return;
  BoundingBox box=BoundingBox.encapsulatingBoxes(start.getPieces().stream().map(StructurePiece::getBoundingBox).toList()).orElseThrow();
  snapshot(world,box,"before");beforeSaved=true;
 }
 public static void after(ServerLevel world,StructureStart start,BoundingBox target){
  if(!beforeSaved||afterSaved||target==null||!selected(start))return;
  snapshot(world,target,"after-paste");afterSaved=true;
  System.out.println("MANAGED-SHIPWRECK-PASTE-SNAPSHOT sourceCaptured=true destinationCaptured=true nativeStart=83,110 action=await-normal-physical-light-completion");
 }
 private static void snapshot(ServerLevel world,BoundingBox box,String label){
  int sx=box.maxX()-box.minX()+1,sy=box.maxY()-box.minY()+1,sz=box.maxZ()-box.minZ()+1;
  if((long)sx*sy*sz>65536)throw new IllegalStateException("Unexpected fixture structure volume");
  int ship=0,chests=0,entities=0;
  for(int x=box.minX();x<=box.maxX();x++)for(int y=box.minY();y<=box.maxY();y++)for(int z=box.minZ();z<=box.maxZ();z++){
   var p=new BlockPos(x,y,z);var state=world.getBlockState(p);String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
   if(name.contains("planks")||name.contains("stairs")||name.contains("fence")||name.contains("trapdoor")||state.is(Blocks.CHEST))ship++;
   if(state.is(Blocks.CHEST))chests++;
   if(world.getBlockEntity(p)!=null)entities++;
  }
  if(ship<50||chests<1||entities<1)throw new IllegalStateException("Missing actual generated ship/hull/chest before conservation proof: "+label+" ship="+ship+" chests="+chests);
  StructureTemplate template=new StructureTemplate();template.fillFromWorld(world,new BlockPos(box.minX(),box.minY(),box.minZ()),new Vec3i(sx,sy,sz),true,List.of());
  try{
   Path root=Path.of(System.getProperty("oceancanvas.fixture.managedEvidence"));Files.createDirectories(root);
   NbtIo.writeCompressed(template.save(new CompoundTag()),root.resolve(label+".nbt"));
   Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("bounds",List.of(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()));metadata.put("shipBlocks",ship);metadata.put("chests",chests);metadata.put("blockEntities",entities);metadata.put("world",net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardshipData.get(world).identity().worldUuid());metadata.put("scope","read-only actual native managed capture boundary; not scale PASS");
   Files.writeString(root.resolve(label+".json"),new Gson().toJson(metadata),StandardOpenOption.CREATE_NEW);
  }catch(java.io.IOException e){throw new IllegalStateException("Fixture snapshot write failed",e);}
 }
}
