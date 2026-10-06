"""Execute the production pre-residency gate and authoritative ownership/quiet helpers."""
from pathlib import Path
import subprocess
import tempfile
source=(Path(__file__).resolve().parents[2]/"production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
def method(signature):
 start=source.index(signature);opening=source.index("{",start);depth=1;end=opening+1
 while depth:
  depth += (source[end]=="{")-(source[end]=="}");end+=1
 return source[start:end]
methods="\n".join(method(signature) for signature in [
 "private static boolean holdStageZeroTerrainBeforeResidency(",
 "private static boolean adjacentPregenTerrainMayStillMutate(",
 "private static int terrainQuietTicksFor(",
 "private static void releaseQuietWaitResidencyIfOwned("])
start=source.index("private static void drainPendingLightSync(")
loop=source[start:source.index("private static boolean ensureLightRelightResidencyTicket(",start)]
gate=loop.index("if (holdStageZeroTerrainBeforeResidency(world, packed, pass)) continue;")
assert loop.index("if (remaining > 0)") < gate, "due countdown changed"
assert gate < loop.index("LevelChunk live = world.getChunkSource().getChunkNow(cx, cz);"), "gate follows native chunk lookup"
assert gate < loop.index("ensureLightRelightResidencyTicket(world, packed, cx, cz)"), "gate follows native ticket installation"
assert "allowPhysicalRepair.contains(packed) && adjacentPregenTerrainMayStillMutate(live.getPos())" in loop, "late admitted-neighbor safety check removed"
assert "world.getGameTime() - lastTerrainMutation < requiredTerrainQuietTicks" in loop, "late quiet check removed"
header=r"""
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public class EarlyLightQuiescenceRegression {
 static final int LIGHT_TERRAIN_QUIET_TICKS=40,LIGHT_TERRAIN_QUIET_MAX_TICKS=320;
 record ChunkPos(int x,int z){static long pack(int x,int z){return ((long)z<<32)|(x&0xffffffffL);}static int getX(long p){return (int)p;}static int getZ(long p){return (int)(p>>32);}}
 static class ServerLevel {long tick;long getGameTime(){return tick;}}
 static class OceanCanvasPrimitiveLongLongMap extends HashMap<Long,Long>{static final long ABSENT=Long.MIN_VALUE;public Long get(Object key){return getOrDefault(key,ABSENT);}}
 static class Session {Set<Long> allowPhysicalRepair=new HashSet<>(),relightResidencyLedger=new HashSet<>();Map<Long,Integer> pendingTicks=new HashMap<>(),terrainInstabilityStreak=new HashMap<>();OceanCanvasPrimitiveLongLongMap terrainLastMutationTick=new OceanCanvasPrimitiveLongLongMap();}
 static class Pregen {Set<Long> PREGEN_TARGET_CHUNKS=new HashSet<>(),deferred=new HashSet<>();}
 static class Telemetry {AtomicLong LIGHT_DIAG_TERRAIN_BARRIER_TICKS=new AtomicLong(),LIGHT_DIAG_TERRAIN_BARRIER_TICKET_RELEASES=new AtomicLong(),LIGHT_DIAG_ADAPTIVE_QUIET_TICKET_RELEASES=new AtomicLong();}
 static Session session;static Pregen pregen;static Telemetry telemetry;static int loads,installs,releases;
 static Session lightFinalizerSession(){return session;}static Pregen pregenSession(){return pregen;}static Telemetry lightTelemetrySession(){return telemetry;}
 static void releaseLightRelightResidencyTicket(ServerLevel world,long packed){check(session.relightResidencyLedger.remove(packed),"released unowned ticket");releases++;}
 static void setup(long packed){session=new Session();pregen=new Pregen();telemetry=new Telemetry();session.pendingTicks.put(packed,1);session.allowPhysicalRepair.add(packed);loads=installs=releases=0;}
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static boolean attempt(ServerLevel world,long packed,int pass){if(holdStageZeroTerrainBeforeResidency(world,packed,pass))return false;loads++;installs++;return true;}
 static void retained(long packed){check(session.pendingTicks.containsKey(packed)&&session.allowPhysicalRepair.contains(packed),"early hold dropped proof debt/authority");}
"""
footer=r"""
 public static void main(String[] args){
 ServerLevel world=new ServerLevel();world.tick=100;long packed=ChunkPos.pack(-30,31),neighbor=ChunkPos.pack(-31,32);
 setup(packed);session.terrainLastMutationTick.put(packed,90L);check(!attempt(world,packed,0),"quiet chunk entered native loading");check(loads==0&&installs==0,"quiet hold installed ticket");retained(packed);check(session.pendingTicks.get(packed)==1,"quiet retry handling changed");
 session.relightResidencyLedger.add(packed);check(!attempt(world,packed,0)&&releases==1,"existing quiet ticket not released");check(telemetry.LIGHT_DIAG_ADAPTIVE_QUIET_TICKET_RELEASES.get()==1,"quiet release telemetry missing");retained(packed);
 world.tick=130;check(attempt(world,packed,0)&&installs==1,"quiet deadline did not resume");
 setup(packed);pregen.PREGEN_TARGET_CHUNKS.add(neighbor);check(!attempt(world,packed,0)&&installs==0,"admitted diagonal neighbor entered native loading");retained(packed);session.relightResidencyLedger.add(packed);check(!attempt(world,packed,0)&&releases==1,"neighbor hold pinned ticket");check(telemetry.LIGHT_DIAG_TERRAIN_BARRIER_TICKET_RELEASES.get()==1,"neighbor release telemetry missing");pregen.PREGEN_TARGET_CHUNKS.clear();check(attempt(world,packed,0),"retired neighbor did not resume");
 setup(packed);pregen.deferred.add(neighbor);check(attempt(world,packed,0),"unadmitted future terrain created circular wait");
 setup(packed);pregen.PREGEN_TARGET_CHUNKS.add(packed);check(attempt(world,packed,0),"self ownership counted as adjacent terrain");
 setup(packed);session.terrainLastMutationTick.put(packed,100L);session.terrainInstabilityStreak.put(packed,2);world.tick=259;check(!attempt(world,packed,0),"adaptive quiet ended early");world.tick=260;check(attempt(world,packed,0),"adaptive quiet deadline failed");
 setup(packed);pregen.PREGEN_TARGET_CHUNKS.add(neighbor);session.terrainLastMutationTick.put(packed,world.tick);check(attempt(world,packed,1),"early gate changed later stages");
 setup(packed);session.allowPhysicalRepair.clear();pregen.PREGEN_TARGET_CHUNKS.add(neighbor);session.terrainLastMutationTick.put(packed,world.tick);check(attempt(world,packed,0),"light-only/protected historical path changed");
 System.out.println("PASS: early stage0 quiet/owned-neighbor holds install no tickets, retain debt and resume; future/light-only/later-stage paths unchanged");
 }
}
"""
with tempfile.TemporaryDirectory(prefix="oc-early-quiescence-") as tmp:
 folder=Path(tmp);(folder/"EarlyLightQuiescenceRegression.java").write_text(header+methods+footer)
 subprocess.run(["javac","-J-Xmx96m","EarlyLightQuiescenceRegression.java"],cwd=folder,check=True)
 subprocess.run(["java","-Xmx32m","EarlyLightQuiescenceRegression"],cwd=folder,check=True)
