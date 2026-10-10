"""Execute the actual ordinary-pressure decision across thresholds and transitions."""
from pathlib import Path
import subprocess
import tempfile
source=(Path(__file__).resolve().parents[2]/"production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
a=source.index("private static boolean runtimePressureHoldsLightFinalizer(")
b=source.index("/**\n\t * v253.125.30",a)
method=source[a:b].replace("net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry", "TickTelemetry").replace("net.oceancanvas.mod.OceanCanvas.VERSION", '"fixture"')
header=r"""
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public class LightPressureEscapeRegression {
 static class ServerLevel {long tick;long getGameTime(){return tick;}}
 static class TickTelemetry {static double work,interval;static double workMs(){return work;}static double intervalMs(){return interval;}}
 static class Ledger {int activeCount(){return 7;}}
 static class Session {
  AtomicLong runtimePressureHoldUntilTick=new AtomicLong(Long.MIN_VALUE),runtimePressureLastLogTick=new AtomicLong(Long.MIN_VALUE),runtimePressureNextEscapeTick=new AtomicLong(Long.MIN_VALUE),runtimePressureConservativeUntilTick=new AtomicLong(Long.MIN_VALUE),runtimePressureHolds=new AtomicLong();
  Map<Long,Integer> pendingTicks=new HashMap<>();Ledger relightResidencyLedger=new Ledger();
 }
 static class Log {void info(String text,Object...values){}}
 static class OceanCanvas {static Log LOGGER=new Log();}
 static Session session;static double heap;
 static Session lightFinalizerSession(){return session;}static double currentHeapUseFraction(ServerLevel w){return heap;}
 static void setup(double h,double work,double interval){session=new Session();session.pendingTicks.put(1L,2);heap=h;TickTelemetry.work=work;TickTelemetry.interval=interval;}
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 static boolean held(ServerLevel w,long tick){w.tick=tick;boolean r=runtimePressureHoldsLightFinalizer(w);check(session.pendingTicks.equals(Map.of(1L,2))&&session.relightResidencyLedger.activeCount()==7,"decision dropped proof debt/ticket");return r;}
"""
footer=r"""
 public static void main(String[] args){
 ServerLevel w=new ServerLevel();setup(.79,30,50);check(!held(w,0),"healthy path held");check(!runtimePressureHoldsLightFinalizer(null),"null world held");
 for(double h:new double[]{.80,.82,.849999}){
  setup(h,39,55);int escapes=0;for(int t=0;t<=20;t++){boolean hold=held(w,t);check(hold==(t==0||t%5!=0),"mild heap cadence differs at "+t);if(!hold)escapes++;}check(escapes==4,"mild ordinary escapes missing");
 }
 double[][] conservative={{.85,30,50},{.90,30,50},{.82,70,50},{.82,100,50},{.82,250,50},{.82,30,100},{.82,30,150},{.82,30,500}};
 for(double[] metrics:conservative){setup(metrics[0],metrics[1],metrics[2]);for(int t=0;t<=200;t++)check(held(w,t)==(t==0||t%100!=0),"conservative cadence differs at "+Arrays.toString(metrics)+" tick "+t);}
 setup(.82,30,50);for(int t=0;t<4;t++)check(held(w,t),"early fast escape");heap=.90;check(held(w,4),"critical transition escaped");check(session.runtimePressureNextEscapeTick.get()==104,"critical transition retained fast timer");
 heap=.82;for(int t=5;t<104;t++)check(held(w,t),"critical cooldown shortened at "+t);check(!held(w,104),"critical cooldown recovery failed");
 setup(.85,30,50);check(held(w,0),"hard pressure not held");heap=.82;for(int t=1;t<40;t++)check(held(w,t),"hard cooldown shortened");check(held(w,40),"hard recovery immediate burst");check(session.runtimePressureNextEscapeTick.get()==45,"mild recovery not accelerated after hard cooldown");check(!held(w,45),"mild recovery missing");
 setup(.82,30,50);for(int t=0;t<4;t++)held(w,t);TickTelemetry.work=70;check(held(w,4),"tick transition escaped");check(session.runtimePressureNextEscapeTick.get()==104,"tick pressure retained fast timer");TickTelemetry.work=30;for(int t=5;t<14;t++)check(held(w,t),"tick cooldown shortened");held(w,14);check(session.runtimePressureNextEscapeTick.get()==19,"tick recovery deadline wrong");check(!held(w,19),"tick recovery missing");
 setup(.85,30,50);for(int t=0;t<90;t++)check(held(w,t),"hard periodic escape came early");heap=.90;check(held(w,90),"critical escalation burst");check(session.runtimePressureNextEscapeTick.get()==100,"existing conservative cadence reset unexpectedly");heap=.82;for(int t=91;t<100;t++)check(held(w,t),"critical escalation accelerated");check(!held(w,100),"original conservative periodic liveness changed");for(int t=101;t<190;t++)check(held(w,t),"critical history enabled fast slice");held(w,190);check(session.runtimePressureNextEscapeTick.get()==195,"critical history recovery deadline wrong");check(!held(w,195),"critical history recovery stalled");
 setup(.90,30,50);held(w,0);heap=.2;for(int t=1;t<100;t++)check(held(w,t),"clear metrics dropped critical cooldown");check(!held(w,100),"expired hold retained");check(session.runtimePressureConservativeUntilTick.get()==Long.MIN_VALUE&&session.runtimePressureNextEscapeTick.get()==Long.MIN_VALUE,"reset retained stale pressure history");
 System.out.println("PASS: mild heap5tick escape; hard/critical/tick100tick cadence, transition cooldowns and unchanged proof debt/tickets");
 }
}
"""
with tempfile.TemporaryDirectory(prefix="oc-light-pressure-") as tmp:
 folder=Path(tmp);(folder/"LightPressureEscapeRegression.java").write_text(header+method+footer)
 subprocess.run(["javac","LightPressureEscapeRegression.java"],cwd=folder,check=True)
 subprocess.run(["java","LightPressureEscapeRegression"],cwd=folder,check=True)
