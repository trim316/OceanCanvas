"""Execute actual cooperative placement retries without issuing proof or commit."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[2]
s=(root/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
a=s.index('\tprivate static boolean advancePendingDecorationRetry(')
b=s.index('\tprivate static void releaseDelayedProofResidency(',a)
method=s[a:b]
java="""
import java.util.*;
class DecorationConvergence {
 static class ServerLevel {long tick;long getGameTime(){return tick;}}
 static class LevelChunk {Object getPos(){return this;}}
 static class LongMap {Map<Long,Long> m=new HashMap<>();long get(long k){return m.getOrDefault(k,Long.MIN_VALUE);}void put(long k,long v){m.put(k,v);}void remove(long k){m.remove(k);}}
 static class OceanCanvasLightFinalizerSession {
  Set<Long> pendingDecorationRetry=new HashSet<>();LongMap decorationRetryLastTick=new LongMap();
  Map<Long,Integer> pendingPasses=new HashMap<>(),pendingTicks=new HashMap<>();Map<Long,Long> stagedBlockFingerprint=new HashMap<>();
 }
 static OceanCanvasLightFinalizerSession session=new OceanCanvasLightFinalizerSession();
 static OceanCanvasLightFinalizerSession lightFinalizerSession(){return session;}
 static int attempts, clears, mutationsLeft=4;static boolean currentProof=true;
 static void clearCooperativeFinalizerState(long k){clears++;currentProof=false;}
 static class OceanCanvasOceanVegetation {
  static boolean decorateCommittedChunk(ServerLevel w,Object p){attempts++;return mutationsLeft--<=0;}
 }
"""+method+"""
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  var w=new ServerLevel();var live=new LevelChunk();long key=7;
  check(!advancePendingDecorationRetry(w,live,key));check(attempts==0);
  // A first mutating commit attempt already established the retry obligation.
  session.pendingDecorationRetry.add(key);
  for(int i=0;i<4;i++) {
   w.tick=i;currentProof=true;session.stagedBlockFingerprint.put(key,123L);session.pendingPasses.put(key,1);
   check(advancePendingDecorationRetry(w,live,key));
   check(session.pendingDecorationRetry.contains(key));
   check(!currentProof && session.pendingPasses.get(key)==0 && session.pendingTicks.get(key)==1);
   check(!session.stagedBlockFingerprint.containsKey(key));
   check(advancePendingDecorationRetry(w,live,key));check(attempts==i+1); // same tick cannot place twice
  }
  w.tick=4;currentProof=true;check(advancePendingDecorationRetry(w,live,key));
  check(attempts==5 && !session.pendingDecorationRetry.contains(key) && !currentProof);
  check(!advancePendingDecorationRetry(w,live,key)); // only now may the ordinary proof path run
 }
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'DecorationConvergence.java';p.write_text(java,encoding='utf-8');subprocess.run(['java',str(p)],check=True)
assert 'authoritativeCommit' not in method
assert s.index('if (advancePendingDecorationRetry(world, live, packed)) continue;') > s.index('activeWorkBudget--;')
fast=s[s.index('private static boolean tryFastCertifyResidentCanvasLighting('):s.index('/**',s.index('private static boolean tryFastCertifyResidentCanvasLighting(')+100)]
assert 'pendingDecorationRetry.contains(packed)) return false' in fast
print('Actual decoration convergence: one call/tick, all mutations withheld, stable retry invalidates proof before normal pipeline')
