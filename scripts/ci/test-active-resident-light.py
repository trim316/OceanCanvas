"""Execute the actual bounded active-resident proof admission branch."""
from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[2]
source = (root/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
start = source.index('\t\t\tif (pass == 0 && !session.allowPhysicalRepair.contains(packed)')
end = source.index('\n\t\t\t// The sweep needs',start)
branch = source[start:end]
assert end < source.index('if (!isLightNeighborhoodLoaded(world, cx, cz))', start)
assert 'initialResidentProofAttempted.remove(packed)' in source
assert source.count('initialResidentProofAttempted.clear()') == 2
proof = source[source.index('private static boolean tryFastCertifyResidentCanvasLighting('):source.index('private static boolean tryFastCertifyResidentCanvasLighting(')+8000]
assert proof.index('adjacentPregenTerrainMayStillMutate') < proof.index('firstPhysicalProfileMismatch')
assert 'sampleCanonicalSurfaceSkyLight(world, chunk, true)' in proof
header=r'''
import java.util.*;
class ActiveResidentRegression {
 static final long LIGHT_FINALIZER_TICK_TIME_BUDGET_NS=8_000_000L;
 static class Session {Set<Long> allowPhysicalRepair=new HashSet<>(),visibleLightPriority=new HashSet<>(),hardResetFingerprintPending=new HashSet<>(),initialResidentProofAttempted=new HashSet<>();}
 static class Recovery {Map<Long,Integer> hardSkyResetRadius1=new HashMap<>();}
 static Session session;static Recovery recovery;static boolean proof,pending;static int calls;
 static Recovery lightRecoverySession(){return recovery;}
 static boolean tryFastCertifyResidentCanvasLighting(Object w,Object live,boolean visible,long distance,String trigger){calls++;if(proof)pending=false;return proof;}
 static void setup(){session=new Session();recovery=new Recovery();proof=false;pending=true;calls=0;}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static int service(int pass,int budget,boolean expired){long packed=7;Object world=new Object(),live=new Object();int activeWorkBudget=budget;long finalizerTickStartedNanos=System.nanoTime()-(expired?LIGHT_FINALIZER_TICK_TIME_BUDGET_NS+1_000_000:0);do {
'''
footer=r'''
 }while(false);return activeWorkBudget;}
 public static void main(String[]args){
  setup();proof=true;check(service(0,3,false)==2&&!pending&&calls==1,"healthy resident was not certified through strict oracle");
  setup();check(service(0,3,false)==2&&pending&&calls==1,"failed proof lost debt");check(service(0,3,false)==3&&calls==1,"one epoch repeatedly paid for shortcut");
  setup();check(service(0,3,true)==3&&calls==0&&pending&&session.initialResidentProofAttempted.isEmpty(),"expired time budget performed proof");
  setup();check(service(0,0,false)==0&&calls==0&&pending,"empty work budget performed proof");
  setup();session.allowPhysicalRepair.add(7L);service(0,3,false);check(calls==0&&pending,"physical repair bypassed staged proof");
  setup();session.visibleLightPriority.add(7L);service(0,3,false);check(calls==0&&pending,"visible repair priority changed");
  setup();service(1,3,false);check(calls==0&&pending,"settling proof bypassed its stage");
  setup();recovery.hardSkyResetRadius1.put(7L,1);service(0,3,false);check(calls==0&&pending,"in-flight native reset bypassed");
  setup();session.hardResetFingerprintPending.add(7L);service(0,3,false);check(calls==0&&pending,"post-reset fingerprint bypassed");
  System.out.println("PASS: active resident shortcut retains strict failure debt, one-attempt epochs, work/time limits and recovery/visible/physical exclusions");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='oc-active-resident-') as tmp:
    folder=Path(tmp)
    (folder/'ActiveResidentRegression.java').write_text(header+branch+footer,encoding='utf-8')
    subprocess.run(['javac','ActiveResidentRegression.java'],cwd=folder,check=True)
    subprocess.run(['java','ActiveResidentRegression'],cwd=folder,check=True)
