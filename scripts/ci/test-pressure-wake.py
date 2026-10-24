#!/usr/bin/env python3
"""Execute the actual production wake method against controlled scheduler fixtures."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[2]
s=(root/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
a=s.index('public static int wakePressureParkedRecoveryForPressure');b=s.index('/** Shared activation mechanics',a)
method=s[a:b].replace('net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets()', 'terrain')
probe_start=s.index('private static int fastCertifyResidentPressureParked(')
probe_end=s.index('\n\t/**',probe_start)
probe=s[probe_start:probe_end].replace('fastCertifyResidentPressureParked(', 'exerciseResidentProbe(').replace('net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets()', 'terrain')
header='''import java.util.*;
public class PressureRegression {
 static boolean probeEnabled; static long tick; static int terrain; static final int LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER=256;
 static final int LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER=512, LIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER=449;
 static class ServerLevel {long getGameTime(){return tick;} Source getChunkSource(){return new Source();}}
 static class Source {LevelChunk getChunkNow(int x,int z){return null;}}
 static class LevelChunk {}
 static class ChunkPos {static int getX(long p){return 0;}static int getZ(long p){return 0;}}
 static boolean tryFastCertifyResidentCanvasLighting(ServerLevel w,LevelChunk c,boolean physical,long priority,String reason){return false;}
 static class OceanCanvasPrimitiveLongLongMap extends HashMap<Long,Long> { static final long ABSENT=Long.MIN_VALUE; public Long get(Object k){return getOrDefault(k,ABSENT);} }
 static class OceanCanvasPrimitiveLongDeadlineHeap {record DueEntry(long packed,long dueTick) {}}
 static class OceanCanvasTerrainPhaseLightDormancyPolicy {static final long DORMANT_DEADLINE=Long.MAX_VALUE;}
 static class OceanCanvasPressureProofCadence {
  enum Lane {TICKET_NEIGHBORHOOD, GENERIC}
  Lane claim(long now,boolean terrainComplete,boolean hasResident){return Lane.GENERIC;}
 }
 static class RelightResidencyLedger {int activeCount(){return 0;}}
 static class Ledger {
  PriorityQueue<OceanCanvasPrimitiveLongDeadlineHeap.DueEntry> heap=new PriorityQueue<>(Comparator.comparingLong(e->e.dueTick()));
  ArrayDeque<OceanCanvasPrimitiveLongDeadlineHeap.DueEntry> generic=new ArrayDeque<>();
  void offerPressurePark(long p,long d){heap.add(new OceanCanvasPrimitiveLongDeadlineHeap.DueEntry(p,d));}
  OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDuePressurePark(long now){return !heap.isEmpty()&&heap.peek().dueTick()<=now?heap.poll():null;}
  void offerGenericDormant(long p){generic.addLast(new OceanCanvasPrimitiveLongDeadlineHeap.DueEntry(p,OceanCanvasTerrainPhaseLightDormancyPolicy.DORMANT_DEADLINE));}
  OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollGenericDormant(boolean terrainComplete){return terrainComplete?generic.pollFirst():null;}
 }
 static class Recovery {OceanCanvasPrimitiveLongLongMap pressureParkUntilTick=new OceanCanvasPrimitiveLongLongMap();}
 static class PendingMap extends HashMap<Long,Integer> {int sumKeys(java.util.function.LongToIntFunction f){int total=0;for(long key:keySet())total+=f.applyAsInt(key);return total;}}
 static class Finalizer {Set<Long> allowPhysicalRepair=new HashSet<>();PendingMap pendingTicks=new PendingMap();Ledger retryLedger=new Ledger();OceanCanvasPressureProofCadence pressureProofCadence=new OceanCanvasPressureProofCadence();RelightResidencyLedger relightResidencyLedger=new RelightResidencyLedger();}
 static class Pregen {Set<Long> PREGEN_TARGET_CHUNKS=new HashSet<>(),PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED=new HashSet<>(),PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED=new HashSet<>();}
 static class Counter {long value;void addAndGet(long n){value+=n;}void incrementAndGet(){value++;}}
 static class Telemetry {Counter LIGHT_DIAG_FOREVER_WORLD_PRESSURE_WAKES=new Counter(),LIGHT_DIAG_PRESSURE_FAST_CERTIFIED=new Counter();}
 static Recovery recovery;static Finalizer fin;static Pregen preg;static Telemetry tel;static List<Long> activated;
 static Recovery lightRecoverySession(){return recovery;}static Finalizer lightFinalizerSession(){return fin;}static Pregen pregenSession(){return preg;}static Telemetry lightTelemetrySession(){return tel;}
 static int activeLightOnlyRecoveryWorkCount(){return 0;}static int activePhysicalRecoveryWorkCount(){return 0;}
 // These fixtures exercise wake admission after resident proof declines the debt.
 // The real resident proof implementation remains covered by production tests.
 static int fastCertifyResidentPressureParkedInTicketNeighborhoods(ServerLevel w,int budget){return 0;}
 static int fastCertifyResidentPressureParked(ServerLevel w,int budget){return probeEnabled?exerciseResidentProbe(w,budget):0;}
 static void markLightDebtMembershipMutation(){}static void activateDormantLightRepair(ServerLevel w,long p,boolean persistent){activated.add(p);fin.pendingTicks.put(p,1);}
 static void setup(){recovery=new Recovery();fin=new Finalizer();preg=new Pregen();tel=new Telemetry();activated=new ArrayList<>();terrain=0;probeEnabled=false;tick=100;}
 static void park(long p){recovery.pressureParkUntilTick.put(p,90L);fin.retryLedger.offerPressurePark(p,90);}
 static void pending(int count,int delay){for(long p=1000;p<1000+count;p++)fin.pendingTicks.put(p,delay);}
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
'''
footer='''
 public static void main(String[] args){
 setup();park(1);fin.allowPhysicalRepair.add(1L);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==1,"fresh physical repair not woken");check(activated.equals(List.of(1L))&&recovery.pressureParkUntilTick.isEmpty(),"fresh debt discarded");
 setup();park(2);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==1,"cold generic debt stolen by shortcut");
 setup();park(3);fin.allowPhysicalRepair.add(3L);preg.PREGEN_TARGET_CHUNKS.add(3L);wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4);check(activated.isEmpty()&&recovery.pressureParkUntilTick.containsKey(3L)&&!fin.retryLedger.heap.isEmpty(),"live terrain ownership lost debt");
 setup();park(4);terrain=1;wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4);check(activated.isEmpty()&&recovery.pressureParkUntilTick.containsKey(4L)&&recovery.pressureParkUntilTick.get(4L)==OceanCanvasTerrainPhaseLightDormancyPolicy.DORMANT_DEADLINE&&!fin.retryLedger.generic.isEmpty(),"generic work raced terrain or failed dormant handoff");
 setup();for(long p=1;p<=10;p++)park(p);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==4&&recovery.pressureParkUntilTick.size()==6,"wake budget not bounded");
 setup();pending(457,0);park(1);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==0&&recovery.pressureParkUntilTick.containsKey(1L),"executable pressure ceiling exceeded");
 setup();pending(457,1);park(1);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==1&&activated.equals(List.of(1L)),"delayed debt incorrectly blocked due wake");
 setup();pending(511,1);park(1);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==0&&recovery.pressureParkUntilTick.containsKey(1L),"raw pending ceiling exceeded");
 setup();pending(510,1);park(1);park(2);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==1&&fin.pendingTicks.size()==511&&recovery.pressureParkUntilTick.size()==1,"raw headroom failed to bound batch");
 setup();probeEnabled=true;for(long p=1;p<=171;p++)park(p);check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==4,"resident scan starved the entire due tail");check(activated.size()==4&&recovery.pressureParkUntilTick.size()==167,"tail activation lost debt or exceeded budget");
 setup();probeEnabled=true;park(1);fin.allowPhysicalRepair.add(1L);exerciseResidentProbe(new ServerLevel(),64);check(recovery.pressureParkUntilTick.containsKey(1L)&&!fin.retryLedger.heap.isEmpty(),"resident scan orphaned physical debt");tick=120;check(wakePressureParkedRecoveryForPressure(new ServerLevel(),64,64,4,4)==1&&activated.equals(List.of(1L)),"physical debt never returned to repair lane");
 System.out.println("PASS: fresh physical wake, cold light-only wake, retained terrain ownership, terrain dormant handoff, bounded wake budget");
 }
}
'''
workspace=tempfile.TemporaryDirectory(prefix='oc-pressure-regression-');p=Path(workspace.name);(p/'PressureRegression.java').write_text(header+probe+method+footer)
java='java'
subprocess.run(['javac','PressureRegression.java'],cwd=p,check=True)
subprocess.run([java,'PressureRegression'],cwd=p,check=True)

workspace.cleanup()
