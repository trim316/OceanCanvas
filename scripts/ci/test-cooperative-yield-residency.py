"""Run the actual delayed-entry residency decision for every cooperative phase."""
from pathlib import Path
import subprocess
import tempfile

source=(Path(__file__).resolve().parents[2]/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
start=source.index('\tprivate static void releaseDelayedProofResidency(')
end=source.index('\n\tprivate static void drainPendingLightSync(',start)
method=source[start:end]
fields=['preLightPhysicalAuditState','prePublishPhysicalAuditState','lightSweepState','strictSkyProofState','stage0FingerprintState','stage1FingerprintState','postProofFingerprintState','hardResetFingerprintState']
header='''import java.util.*;
class CooperativeYieldResidency {
 static class ServerLevel {}
 static class Ledger {Set<Long> owned=new HashSet<>();boolean contains(long p){return owned.contains(p);}}
 static class OceanCanvasLightFinalizerSession {
  Ledger relightResidencyLedger=new Ledger();Set<Long> residencyLoadWait=new HashSet<>();Set<Long> pendingDecorationRetry=new HashSet<>();Map<Long,Integer> pendingTicks=new HashMap<>();
'''+''.join(' Map<Long,Object> '+f+'=new HashMap<>();\n' for f in fields)+'''
 }
 static int releases;
 static void releaseLightRelightResidencyTicket(ServerLevel w,long p){releases++;}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args) throws Exception {
  for(String phase: new String[]{'''+','.join('"'+f+'"' for f in fields)+'''}) {
   for(int delay: new int[]{1,2,20}) {
    var s=new OceanCanvasLightFinalizerSession();s.relightResidencyLedger.owned.add(7L);s.pendingTicks.put(7L,delay);
    ((Map<Long,Object>)s.getClass().getDeclaredField(phase).get(s)).put(7L,new Object());
    releases=0;releaseDelayedProofResidency(new ServerLevel(),s,7L);
    check(releases==(delay==1?0:1));check(s.pendingTicks.get(7L)==delay);
   }
  }
  var s=new OceanCanvasLightFinalizerSession();s.pendingTicks.put(7L,1);s.relightResidencyLedger.owned.add(7L);
  releases=0;releaseDelayedProofResidency(new ServerLevel(),s,7L);check(releases==1);
  s.pendingDecorationRetry.add(7L);releases=0;releaseDelayedProofResidency(new ServerLevel(),s,7L);check(releases==0);
  s.pendingDecorationRetry.clear();
  s.residencyLoadWait.add(7L);releases=0;releaseDelayedProofResidency(new ServerLevel(),s,7L);check(releases==0);
  s.pendingTicks.put(7L,2);releases=0;releaseDelayedProofResidency(new ServerLevel(),s,7L);check(releases==1);
  s.relightResidencyLedger.owned.clear();releases=0;releaseDelayedProofResidency(new ServerLevel(),s,7L);check(releases==0);
 }
'''
with tempfile.TemporaryDirectory() as d:
    path=Path(d)/'CooperativeYieldResidency.java'
    path.write_text(header+method+'\n}',encoding='utf-8')
    subprocess.run(['java',str(path)],check=True)
assert source.count('releaseDelayedProofResidency(world, session, packed);')==3
assert source.count('session.residencyLoadWait.add(packed);')==2
assert source.count('lightFinalizerSession().residencyLoadWait.remove(packed);')==2
assert 'session.residencyLoadWait.remove(packed);' in source
print('Cooperative yield residency: eight phases, longer waits, idle and unowned cases passed')
