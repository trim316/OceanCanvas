"""Execute actual pressure parking; owned residency must survive generic churn."""
from pathlib import Path
import subprocess
import tempfile

source=(Path(__file__).resolve().parents[2]/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
start=source.index('private static int parkExcessGenericLightOnlyForPressure(')
end=source.index('\n\t/**',start)
method=source[start:end]
header='''import java.util.*;
public class ParkingResidencyRegression {
 static final int LIGHT_GENERIC_PRESSURE_SCAN_LIMIT_PER_TICK=4096,LIGHT_PERSISTENT_SKY_BACKOFF_JITTER_TICKS=7,LIGHT_GLOBAL_BACKGROUND_PARK_TICKS=20;
 static class ServerLevel {long getGameTime(){return 100;}}
 static class OceanCanvasPrimitiveLongIntMap extends HashMap<Long,Integer> {static final int ABSENT=-1;public Integer remove(Object p){Integer v=super.remove(p);return v==null?ABSENT:v;}}
 static class OceanCanvasPrimitiveLongLongMap extends HashMap<Long,Long> {static final long ABSENT=Long.MIN_VALUE;public Long putIfAbsent(Long p,Long v){Long old=super.putIfAbsent(p,v);return old==null?ABSENT:old;}}
 static class OceanCanvasPrimitiveLongQueue {static final long EMPTY=Long.MIN_VALUE;ArrayDeque<Long> q=new ArrayDeque<>();long poll(){return q.isEmpty()?EMPTY:q.removeFirst();}void offer(long p){q.addLast(p);}}
 static class Residency {Set<Long> owned=new HashSet<>();boolean contains(long p){return owned.contains(p);}}
 static class Ledger {Map<Long,Long> parks=new HashMap<>();void offerPressurePark(long p,long due){parks.put(p,due);}}
 static class Counter {int n;void incrementAndGet(){n++;}}
 static class OceanCanvasLightFinalizerSession {
  OceanCanvasPrimitiveLongIntMap pendingTicks=new OceanCanvasPrimitiveLongIntMap();
  OceanCanvasPrimitiveLongQueue pendingWorkOrder=new OceanCanvasPrimitiveLongQueue();
  Set<Long> pendingWorkMembership=new HashSet<>(),visibleLightPriority=new HashSet<>(),allowPhysicalRepair=new HashSet<>();
  Residency relightResidencyLedger=new Residency();Ledger retryLedger=new Ledger();
 }
 static class Recovery {OceanCanvasPrimitiveLongLongMap pressureParkUntilTick=new OceanCanvasPrimitiveLongLongMap();}
 static class Pregen {Set<Long> PREGEN_TARGET_CHUNKS=new HashSet<>(),PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED=new HashSet<>(),PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED=new HashSet<>();}
 static class Telemetry {Counter LIGHT_DIAG_FOREVER_WORLD_PRESSURE_PARKS=new Counter();}
 static OceanCanvasLightFinalizerSession session;static Recovery recovery;static Pregen pregen;static Telemetry telemetry;static List<Long> released;
 static OceanCanvasLightFinalizerSession lightFinalizerSession(){return session;}static Recovery lightRecoverySession(){return recovery;}static Pregen pregenSession(){return pregen;}static Telemetry lightTelemetrySession(){return telemetry;}
 static void releaseLightRelightResidencyTicket(ServerLevel w,long p){released.add(p);session.relightResidencyLedger.owned.remove(p);}
 static void setup(){session=new OceanCanvasLightFinalizerSession();recovery=new Recovery();pregen=new Pregen();telemetry=new Telemetry();released=new ArrayList<>();}
 static void pending(long p){session.pendingTicks.put(p,0);session.pendingWorkMembership.add(p);session.pendingWorkOrder.offer(p);}
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
'''
footer='''
 public static void main(String[] args){
  setup();pending(1);pending(2);pending(3);session.relightResidencyLedger.owned.add(1L);
  check(parkExcessGenericLightOnlyForPressure(new ServerLevel(),1,2)==2,"ticketless cohort did not drain");
  check(session.pendingTicks.containsKey(1L)&&session.relightResidencyLedger.contains(1L),"pressure parking discarded owned proof residency");
  check(released.equals(List.of(2L,3L)),"resident proof parked before ticketless debt");
  check(recovery.pressureParkUntilTick.size()==2&&session.retryLedger.parks.size()==2,"parked debt lost its wake identity");
  setup();for(long p=1;p<=48;p++){pending(p);session.relightResidencyLedger.owned.add(p);}
  check(parkExcessGenericLightOnlyForPressure(new ServerLevel(),0,48)==0,"all-resident cohort must remain bounded by its existing ticket cap");
  check(session.pendingTicks.size()==48&&released.isEmpty(),"owned debt lost while nothing else was parkable");
  setup();pending(1);pending(2);pending(3);check(parkExcessGenericLightOnlyForPressure(new ServerLevel(),0,1)==1&&session.pendingTicks.size()==2,"parking batch budget widened");
  System.out.println("PASS: owned lighting residency survives pressure parking; ticketless debt and wake identity remain bounded");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='oc-parking-residency-') as temp:
    root=Path(temp)
    (root/'ParkingResidencyRegression.java').write_text(header+method+footer,encoding='utf-8')
    subprocess.run(['javac','ParkingResidencyRegression.java'],cwd=root,check=True)
    subprocess.run(['java','ParkingResidencyRegression'],cwd=root,check=True)
