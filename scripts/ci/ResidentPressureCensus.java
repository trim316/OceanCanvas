package net.oceancanvas.test;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;
import net.oceancanvas.mod.project.OceanCanvasTerrainStateData;
import java.lang.reflect.*;
import java.util.*;
/** Diagnostic census only: never invokes proof, repair, publication or chunk loading. */
public final class ResidentPressureCensus implements ModInitializer {
 private int ticks; private boolean failed;
 private static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception {
  Class<?> start=target instanceof Class<?> c?c:target.getClass();
  for(Class<?> c=start;c!=null;c=c.getSuperclass())try{
   Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target instanceof Class<?>?null:target,args);
  }catch(NoSuchMethodException ignored){}
  throw new NoSuchMethodException(name);
 }
 private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 private static boolean has(Object o,String n,long k)throws Exception{return (boolean)call(field(o,n),"contains",new Class<?>[]{long.class},k);}
 private static long get(Object o,String n,long k)throws Exception{return (long)call(field(o,n),"get",new Class<?>[]{long.class},k);}
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{
  if(failed||++ticks%200!=0)return;
  long started=System.nanoTime();
  try{
   var world=server.overworld();Class<?> c=Class.forName("net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener");
   Object f=call(c,"lightFinalizerSession",new Class<?>[0]),r=call(c,"lightRecoverySession",new Class<?>[0]),p=call(c,"pregenSession",new Class<?>[0]);
   long[] anchors=(long[])call(field(f,"relightResidencyLedger"),"activeSnapshot",new Class<?>[0]);
   if(anchors.length>48)throw new IllegalStateException("ticket bound exceeded");
   Set<Long> seen=new HashSet<>();int parked=0,lightOnly=0,resident=0,currentProof=0,noDecoration=0,noPhysicalTarget=0,noAdjacentTerrain=0,quiet=0,sealed=0,canvas=0,unprotected=0;
   for(long a:anchors)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
    long k=ChunkPos.pack(ChunkPos.getX(a)+dx,ChunkPos.getZ(a)+dz);if(!seen.add(k))continue;
    if(get(r,"pressureParkUntilTick",k)==Long.MIN_VALUE)continue;parked++;
    boolean physical=has(p,"PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED",k);
    boolean tracked=!physical&&has(p,"PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED",k);
    boolean generic=!physical&&!tracked&&!has(f,"allowPhysicalRepair",k)&&!has(p,"PREGEN_TARGET_CHUNKS",k);
    if(!tracked&&!generic)continue;lightOnly++;
    var live=world.getChunkSource().getChunkNow(ChunkPos.getX(k),ChunkPos.getZ(k));if(live==null)continue;resident++;
    if(!has(f,"preLightPhysicalAuditComplete",k))continue;currentProof++;
    if(has(f,"pendingDecorationRetry",k))continue;noDecoration++;
    if(has(p,"PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS",k)||physical||has(f,"allowPhysicalRepair",k))continue;noPhysicalTarget++;
    if((boolean)call(c,"adjacentPregenTerrainMayStillMutate",new Class<?>[]{ChunkPos.class},live.getPos()))continue;noAdjacentTerrain++;
    long last=get(f,"terrainLastMutationTick",k);if(last!=Long.MIN_VALUE&&world.getGameTime()-last<40)continue;quiet++;
    var protectedData=OceanCanvasProtectedData.get(world);
    if(!protectedData.isChunkProcessedPhysicallyVerified(live.getPos()))continue;sealed++;
    if(OceanCanvasTerrainStateData.get(world).get(live.getPos())!=OceanCanvasTerrainStateData.TerrainState.CANVAS)continue;canvas++;
    if(protectedData.pendingOriginalProtectionTouchesChunkRing(live.getPos(),1))continue;unprotected++;
   }
   System.out.println("RESIDENT_CENSUS ticks="+ticks+" anchors="+anchors.length+" scanned="+seen.size()+" parked="+parked+" lightOnly="+lightOnly+" resident="+resident+" currentProof="+currentProof+" noDecoration="+noDecoration+" noPhysicalTarget="+noPhysicalTarget+" noAdjacentTerrain="+noAdjacentTerrain+" quiet="+quiet+" sealed="+sealed+" canvas="+canvas+" unprotected="+unprotected+" nanos="+(System.nanoTime()-started)+" proofInvoked=false certificationEligible=false");
  }catch(Throwable e){failed=true;e.printStackTrace();System.out.println("RESIDENT_CENSUS_FAILED "+e);}
 });}
}
