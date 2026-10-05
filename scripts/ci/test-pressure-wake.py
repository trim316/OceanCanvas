#!/usr/bin/env python3
"""Execute the actual production wake method against controlled scheduler fixtures."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[2]
s=(root/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
a=s.index('public static int wakePressureParkedRecoveryForPressure');b=s.index('/** Shared activation mechanics',a)
method=s[a:b].replace('net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets()', 'terrain')
header='''import java.util.*;
public class PressureRegression {
 static int terrain; static final int LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER=64;
 static class ServerLevel {long getGameTime(){return 100;}}
 static class OceanCanvasPrimitiveLongLongMap extends HashMap<Long,Long> { static final long ABSENT=Long.MIN_VALUE; public Long get(Object k){return getOrDefault(k,ABSENT);} }
 static class OceanCanvasPrimitiveLongDeadlineHeap {record DueEntry(long packed,long dueTick) {}}
 static class Ledger {PriorityQueue<OceanCanvasPrimitiveLongDeadlineHeap.DueEntry> heap=new PriorityQueue<>(Comparator.comparingLong(e->e.dueTick())); void offerPressurePark(long p,long d){heap.add(new OceanCanvasPrimitiveLongDeadlineHeap.DueEntry(p,d));} OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDuePressurePark(long now){return !heap.isEmpty()&&heap.peek().dueTick()<=now?heap.poll():null;}}
 static class Recovery {OceanCanvasPrimitiveLongLongMap pressureParkUntilTick=new OceanCanvasPrimitiveLongLongMap();}
 static class Finalizer {Set<Long> allowPhysicalRepair=new HashSet<>();Map<Long,Integer> pendingTicks=new HashMap<>();Ledger retryLedger=new Ledger();}
 static class Pregen {Set<Long> PREGEN_TARGET_CHUNKS=new HashSet<>(),PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED=new HashSet<>(),PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED=new HashSet<>();}
 static class Counter {long value;void addAndGet(long n){value+=n;}}
 static class Telemetry {Counter LIGHT_DIAG_FOREVER_WORLD_PRESSURE_WAKES=new Counter();}
 static Recovery recovery;static Finalizer fin;static Pregen preg;static Telemetry tel;static List<Long> activated;
 static Recovery lightRecoverySession(){return recovery;}static Finalizer lightFinalizerSession(){return fin;}static Pregen pregenSession(){return preg;}static Telemetry lightTelemetrySession(){return tel;}
 static int activeLightOnlyRecoveryWorkCount(){return 0;}static int activePhysicalRecoveryWorkCount(){return 0;}
 static void markLightDebtMembershipMutation(){}static void activateDormantLightRepair(ServerLevel w,long p,boolean persistent){activated.add(p);fin.pendingTicks.put(p,1);}
 static void setup(){recovery=new Recovery();fin=new Finalizer();preg=new Pregen();tel=new Telemetry();activated=new ArrayList<>();terrain=0;}
 static void park(long p){recovery.pressureParkUntilTick.put(p,90L);fin.retryLedger.offerPressurePark(p,90);}
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
'''
footer='''
 public static void main(String[] args){
 setup();park(1);fin.allowPhysicalRepair.add(1L);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==1,"fresh physical repair not woken");check(activated.equals(List.of(1L))&&recovery.pressureParkUntilTick.isEmpty(),"fresh debt discarded");
 setup();park(2);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==1,"cold generic debt stolen by shortcut");
 setup();park(3);fin.allowPhysicalRepair.add(3L);preg.PREGEN_TARGET_CHUNKS.add(3L);wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4);check(activated.isEmpty()&&recovery.pressureParkUntilTick.containsKey(3L)&&!fin.retryLedger.heap.isEmpty(),"live terrain ownership lost debt");
 setup();park(4);terrain=1;wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4);check(activated.isEmpty()&&recovery.pressureParkUntilTick.containsKey(4L),"generic work raced terrain");
 setup();for(long p=1;p<=10;p++)park(p);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==4&&recovery.pressureParkUntilTick.size()==6,"wake budget not bounded");
 System.out.println("PASS: fresh physical wake, cold light-only wake, retained terrain ownership, terrain deferral, bounded wake budget");
 }
}
'''
workspace=tempfile.TemporaryDirectory(prefix='oc-pressure-regression-');p=Path(workspace.name);(p/'PressureRegression.java').write_text(header+method+footer)
java='java'
subprocess.run(['javac','PressureRegression.java'],cwd=p,check=True)
subprocess.run([java,'PressureRegression'],cwd=p,check=True)

workspace.cleanup()
