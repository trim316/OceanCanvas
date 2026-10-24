"""Execute actual cooperative placement retries without issuing proof or commit."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[2]
s=(root/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
a=s.index('\tprivate static boolean advancePendingDecorationRetry(')
b=s.index('\tprivate static void releaseDelayedProofResidency(',a)
initial=s[s.index("\tprivate static boolean beginInitialDecorationBeforeLightProof("):s.index("\t/** One actual placement retry",s.index("\tprivate static boolean beginInitialDecorationBeforeLightProof("))]
method=s[a:b]
java="""
import java.util.*;
class DecorationConvergence {
 static class ServerLevel {long tick;long getGameTime(){return tick;}}
 static class LevelChunk {Object getPos(){return this;}}
 static class LongMap {Map<Long,Long> m=new HashMap<>();long get(long k){return m.getOrDefault(k,Long.MIN_VALUE);}void put(long k,long v){m.put(k,v);}void remove(long k){m.remove(k);}}
 static class OceanCanvasLightFinalizerSession {
  Set<Long> pendingDecorationRetry=new HashSet<>(), initialDecorationAttempted=new HashSet<>(), allowPhysicalRepair=new HashSet<>(), preLightPhysicalAuditComplete=new HashSet<>();LongMap decorationRetryLastTick=new LongMap();
  Map<Long,Integer> pendingPasses=new HashMap<>(),pendingTicks=new HashMap<>();Map<Long,Long> stagedBlockFingerprint=new HashMap<>();
 }
 static OceanCanvasLightFinalizerSession session=new OceanCanvasLightFinalizerSession();
 static OceanCanvasLightFinalizerSession lightFinalizerSession(){return session;}
 static int attempts, clears, mutationsLeft=4;static boolean currentProof=true;
 static void clearCooperativeFinalizerState(long k){clears++;currentProof=false;session.preLightPhysicalAuditComplete.remove(k);}
 static class OceanCanvasOceanVegetation {
  static boolean decorateCommittedChunk(ServerLevel w,Object p){attempts++;return mutationsLeft--<=0;}
 }
"""+initial+method+"""
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
  long fresh=8;w.tick=5;int before=attempts;
  check(!beginInitialDecorationBeforeLightProof(w,live,fresh,0)); // no owner or current proof
  session.allowPhysicalRepair.add(fresh);
  check(!beginInitialDecorationBeforeLightProof(w,live,fresh,0)); // ownership alone is insufficient
  session.preLightPhysicalAuditComplete.add(fresh);
  check(!beginInitialDecorationBeforeLightProof(w,live,fresh,1)); // cannot mutate during settle
  check(attempts==before);
  mutationsLeft=1;currentProof=true;session.pendingPasses.put(fresh,0);session.stagedBlockFingerprint.put(fresh,55L);
  check(beginInitialDecorationBeforeLightProof(w,live,fresh,0));
  check(attempts==before+1 && session.initialDecorationAttempted.contains(fresh));
  check(session.pendingDecorationRetry.contains(fresh) && !currentProof);
  check(!session.preLightPhysicalAuditComplete.contains(fresh) && !session.stagedBlockFingerprint.containsKey(fresh));
  check(session.pendingTicks.get(fresh)==1 && session.pendingPasses.get(fresh)==0);
  check(advancePendingDecorationRetry(w,live,fresh));check(attempts==before+1); // same-tick dedupe
  w.tick++;check(advancePendingDecorationRetry(w,live,fresh));
  check(attempts==before+2 && !session.pendingDecorationRetry.contains(fresh));
  session.preLightPhysicalAuditComplete.add(fresh); // next complete physical audit
  check(!beginInitialDecorationBeforeLightProof(w,live,fresh,0)); // scheduling history does not place again
  check(attempts==before+2);
 }
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'DecorationConvergence.java';p.write_text(java,encoding='utf-8');subprocess.run(['java',str(p)],check=True)
assert 'authoritativeCommit' not in method+initial
assert 'authoredWaterLandingGuard' not in initial
assert s.count('initialDecorationAttempted.clear();')==3
assert 'initialDecorationAttempted.remove(packed);' in s
call=s.index('if (beginInitialDecorationBeforeLightProof(world, live, packed, pass)) continue;')
assert s.index('if (physicalBeforeLight != null) {',s.index('private static void drainPendingLightSync(')) < call < s.index('if (sweepBudget-- <= 0)',call)
assert s.index('if (advancePendingDecorationRetry(world, live, packed)) continue;') > s.index('activeWorkBudget--;')
fast=s[s.index('private static boolean tryFastCertifyResidentCanvasLighting('):s.index('/**',s.index('private static boolean tryFastCertifyResidentCanvasLighting(')+100)]
assert 'pendingDecorationRetry.contains(packed)) return false' in fast
print('Actual decoration convergence: one call/tick, all mutations withheld, stable retry invalidates proof before normal pipeline')
