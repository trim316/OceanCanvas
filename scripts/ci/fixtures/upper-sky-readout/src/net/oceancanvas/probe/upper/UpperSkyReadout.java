package net.oceancanvas.probe.upper;
import net.fabricmc.api.ModInitializer;import net.fabricmc.fabric.api.event.lifecycle.v1.*;import net.minecraft.server.level.ServerLevel;import net.minecraft.world.level.ChunkPos;import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.oceancanvas.mod.worldgen.*;import net.oceancanvas.probe.upper.mixin.LightOnlyScheduleInvoker;import java.util.*;import java.nio.file.*;import com.google.gson.*;
/** Test-only exact-scope driver. It never starts a job, owns a terrain target, loads a chunk, or writes blocks/light arrays. */
public final class UpperSkyReadout implements ModInitializer {
 private static final int MIN=-63,SIDE=126,TOTAL=SIDE*SIDE,WINDOW=8;
 private static ServerLevel world;private static Path output;private static String worldId;private static final BitSet complete=new BitSet(TOTAL);private static final Set<Long> inflight=new HashSet<>();private static int cursor,ticks;private static boolean done;
 private static boolean selected(int x,int z){return x>=-1000&&x<1000&&z>=-1000&&z<1000;}
 private static int index(ChunkPos p){int x=p.x()-MIN,z=p.z()-MIN;return x<0||x>=SIDE||z<0||z>=SIDE?-1:z*SIDE+x;}
 private static void checkpoint(){try{Files.createDirectories(output);JsonObject j=new JsonObject();j.addProperty("sourceCommit",System.getProperty("oceancanvas.readoutSource"));j.addProperty("runtimeBuild",net.oceancanvas.mod.OceanCanvas.VERSION);j.addProperty("worldUuid",worldId);j.addProperty("completed",complete.cardinality());j.addProperty("total",TOTAL);j.addProperty("cursor",cursor);j.addProperty("completedBits",Base64.getEncoder().encodeToString(complete.toByteArray()));j.addProperty("scaleAccepted",false);j.addProperty("scope","Scheduling receipt only; all-selected native saved-field oracle is mandatory");Path temp=output.resolve("readout-progress.json.tmp");Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(j));Files.move(temp,output.resolve("readout-progress.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException("Readout scheduling checkpoint failed",e);}}
 @Override public void onInitialize(){
  ServerLifecycleEvents.SERVER_STARTED.register(server->{
   world=server.overworld();output=Path.of(System.getProperty("oceancanvas.readoutOutput"));
   worldId=net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardshipData.get(world).identity().worldUuid();
   if(!worldId.equals(System.getProperty("oceancanvas.readoutExpectedWorld")))throw new IllegalStateException("Unexpected copied-world identity");
   try {var previous=output.resolve("readout-progress.json");if(Files.exists(previous)){
    var prior=JsonParser.parseString(Files.readString(previous)).getAsJsonObject();
    if(!worldId.equals(prior.get("worldUuid").getAsString())||!System.getProperty("oceancanvas.readoutSource").equals(prior.get("sourceCommit").getAsString())||prior.get("total").getAsInt()!=TOTAL)throw new IllegalStateException("Readout checkpoint binding mismatch");
    complete.or(BitSet.valueOf(Base64.getDecoder().decode(prior.get("completedBits").getAsString())));if(complete.length()>TOTAL||complete.cardinality()!=prior.get("completed").getAsInt())throw new IllegalStateException("Invalid scheduling receipt");
   }}catch(java.io.IOException e){throw new IllegalStateException("Readout checkpoint unreadable",e);}
   // Restart always rewinds outstanding work; a cursor is never a completion certificate.
   cursor=0;inflight.clear();
   // Scope only: no PREGEN_TARGET_CHUNKS, job/cursor, terrain lease or generation admission.
   OceanCanvasActiveTerrainOperationBridge.install(new OceanCanvasActiveTerrainOperationBridge.Controller(){
    public boolean coversStructureFootprint(BoundingBox b){return selected(b.minX(),b.minZ())&&selected(b.maxX(),b.maxZ());}
    public boolean columnInMutationScope(int cx,int cz,int x,int z){return selected(x,z);}
    public boolean fullyCoversChunkBlocks(int cx,int cz){return selected(cx*16,cz*16)&&selected(cx*16+15,cz*16+15);}
    public boolean pregenIncludesChunk(int x,int z){return false;}
    public boolean pregenChunkMayStillMutate(int x,int z){return false;}
    public void authoritativeCommit(ServerLevel level,ChunkPos p){if(level!=world)return;int i=index(p);if(i>=0){complete.set(i);inflight.remove(ChunkPos.pack(p.x(),p.z()));}}
    public void recoveryExemptionCommitted(ServerLevel l,ChunkPos p){}
    public void recoveryLightOnlyEnteredFinalizer(ServerLevel l,ChunkPos p){}
   });
   System.out.println("UPPER-SKY-READOUT-START total="+TOTAL+" mode=LIGHT_ONLY noJob=true exactSelection=-1000..999 window="+WINDOW);
  });
  ServerTickEvents.END_SERVER_TICK.register(server->{
   if(world==null||done)return;ticks++;
   if(OceanCanvasSurfaceFlattener.outstandingPregenTargetCount()!=0||OceanCanvasSurfaceFlattener.trackedPhysicalRecoveryWorkCount()!=0)throw new IllegalStateException("Readout must never own terrain work");
   while(cursor<TOTAL&&complete.get(cursor))cursor++;
   if(cursor<TOTAL&&inflight.size()<WINDOW&&OceanCanvasSurfaceFlattener.pendingLightSyncCount()<OceanCanvasSurfaceFlattener.lightFinalizationBackpressureLowWater()){
    int i=cursor++;var p=new ChunkPos(MIN+i%SIDE,MIN+i/SIDE);long key=ChunkPos.pack(p.x(),p.z());
    if(!complete.get(i)){inflight.add(key);OceanCanvasSurfaceFlattener.markPregenCrashRecoveryTarget(world,p,false);LightOnlyScheduleInvoker.schedule(world,p,false);}
   }
   if(ticks%200==0){checkpoint();System.out.println("UPPER-SKY-READOUT-PROGRESS completed="+complete.cardinality()+" inflight="+inflight.size()+" cursor="+cursor+" terrainOwned=0");}
   if(complete.cardinality()==TOTAL){checkpoint();done=true;System.out.println("UPPER-SKY-READOUT-DONE completed="+TOTAL+" nativeSavedFieldOracleRequired=true scaleAccepted=false");}
  });
  ServerLifecycleEvents.SERVER_STOPPING.register(server->{if(world!=null)checkpoint();});
 }
}
