"""Execute the production commit callback and both retirement boundaries."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[2]
base=root/'production-src/src/main/java/net/oceancanvas/mod/worldgen'
bridge=(base/'OceanCanvasActiveTerrainOperationBridge.java').read_text(encoding='utf-8')
start=bridge.index('    public static boolean authoritativeCommit(')
method=bridge[start:bridge.index('    public static void recoveryExemptionCommitted(',start)]
source=(base/'OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
fast_start=source.index('\t\tprotectedData.markChunkLightingVerified(pos);',source.index('private static boolean tryFastCertifyResidentCanvasLighting('))
fast=source[fast_start:source.index('\n\t\tif (playerVisible)',fast_start)]
staged_start=source.index('\t\t\tOceanCanvasProtectedData.get(world).markChunkLightingVerified(live.getPos());',source.index('private static void drainPendingLightSync('))
staged=source[staged_start:source.index('\n\t\t}',staged_start)]
java="""
class DecorationReproofRetirement {
 static boolean pending, audited, verified, mutates;
 static int commits, retirements;
 static class ServerLevel {}
 static class ChunkPos {}
 static class Chunk { ChunkPos getPos(){return new ChunkPos();} }
 static class OceanCanvasProtectedData {
  static OceanCanvasProtectedData get(ServerLevel w){return new OceanCanvasProtectedData();}
  void markChunkLightingVerified(ChunkPos p){verified=true;}
 }
 static class Audit {
  void markAudited(long p){audited=true;}
  void removePending(long p){pending=false;}
 }
 static class Session { java.util.Set<Long> pendingDecorationRetry=new java.util.HashSet<>(); Audit persistedAuditSession=new Audit(); }
 static Session lightFinalizerSession(){return new Session();}
 static void retireCompletedLightState(ServerLevel w,long p){pending=false;retirements++;}
 static class OceanCanvasOceanVegetation {
  static boolean decorateCommittedChunk(ServerLevel w,ChunkPos p){
   if(mutates){pending=true;audited=false;verified=false;return false;}
   return true;
  }
 }
 static class Controller {void authoritativeCommit(ServerLevel w,ChunkPos p){commits++;}}
 static class OceanCanvasActiveTerrainOperationBridge {
  static Controller controller=new Controller();
"""+method+"""
 }
 static boolean fast(){
  var world=new ServerLevel();var pos=new ChunkPos();long packed=1L;
  var protectedData=OceanCanvasProtectedData.get(world);
"""+fast+"""
  return true;
 }
 static void staged(){
  var world=new ServerLevel();var live=new Chunk();long packed=1L;
  for(int once=0;once<1;once++) {
"""+staged+"""
  }
 }
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  for(boolean useFast:new boolean[]{true,false}) {
   pending=true;audited=false;verified=false;mutates=true;commits=0;retirements=0;
   if(useFast)check(!fast());else staged();
   check(pending && !audited && !verified && commits==0 && retirements==0);
   mutates=false;
   if(useFast)check(fast());else staged();
   check(!pending && audited && verified && commits==1 && retirements==1);
  }
 }
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'DecorationReproofRetirement.java';p.write_text(java,encoding='utf-8')
 subprocess.run(['java',str(p)],check=True)
print('Both actual completion boundaries retain decoration reproof and retire only after stable retry')
