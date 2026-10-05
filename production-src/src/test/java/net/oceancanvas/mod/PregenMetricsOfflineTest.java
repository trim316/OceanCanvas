package net.oceancanvas.mod;

import net.oceancanvas.mod.performance.OceanCanvasPregenMetrics;
import net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.RateWindow;
import net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.Snapshot;
import net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.TickTiming;

/** Fake-clock regression suite: no Minecraft, sleeps, world writes or timing-dependent assertions. */
public final class PregenMetricsOfflineTest {
    private static int checks;
    private static long s(long seconds){return seconds*1_000_000_000L;}
    public static void main(String[] args){runAll();}
    public static void runAll(){
        checks=0;
        RateWindow steady=new RateWindow(0);
        equal("WARMING_UP",steady.estimate(0,100,false,false).quality(),"fresh window");
        for(int i=1;i<=10;i++)steady.observe(s(i),i*10);
        close(10,steady.mean(),"wall-time completion rate");
        equal(true,steady.hasSufficientSamples(s(10)),"ten seconds of usable samples");
        equal(10L,steady.estimate(s(10),100,false,false).lowSeconds(),"steady ETA lower");
        equal(10L,steady.estimate(s(10),100,false,false).highSeconds(),"steady ETA upper");
        equal("STEADY_SAMPLE",steady.estimate(s(10),100,false,false).quality(),"steady label");
        equal("FINAL_DRAIN",steady.estimate(s(10),3,true,false).quality(),"drain never advertised complete");
        equal(-1L,steady.estimate(s(10),3,true,false).highSeconds(),"no unreliable drain countdown");
        equal("THROTTLED",steady.estimate(s(10),100,false,true).quality(),"pause suppresses ETA");
        equal("FINALIZING",steady.estimate(s(10),0,false,false).quality(),"zero remaining is not completion authority");

        RateWindow lag=new RateWindow(0);
        for(int i=1;i<=10;i++)lag.observe(s(i*2),i*20);
        close(10,lag.mean(),"20 completions in two seconds is ten per second, not twenty");
        RateWindow weighted=new RateWindow(0);
        weighted.observe(s(1),10);weighted.observe(s(3),20);
        close(20.0/3.0,weighted.mean(),"variable sample duration is weighted");
        RateWindow variable=new RateWindow(0);
        for(int i=1;i<=20;i++)variable.observe(s(i),(i/2)*20);
        close(10,variable.mean(),"zero-rate intervals included");
        equal("VARIABLE_SAMPLE",variable.estimate(s(20),100,false,false).quality(),"variable label");
        equal(5L,variable.estimate(s(20),100,false,false).lowSeconds(),"fast-side estimate");
        equal(-1L,variable.estimate(s(20),100,false,false).highSeconds(),"zero slow-side rate is unbounded, not infinity encoded");

        for(int i=11;i<=41;i++)steady.observe(s(i),100);
        equal(31L,steady.noProgressSeconds(s(41)),"stall age");
        equal("NO_RECENT_COMPLETIONS",steady.estimate(s(41),100,false,false).quality(),"old throughput does not promise stalled ETA");
        steady.observe(s(42),110);
        equal(0L,steady.noProgressSeconds(s(42)),"new completion clears stall age");
        steady.observe(s(60),200);
        equal(0,steady.sampleCount(),"long pause resets samples");
        equal(false,steady.hasSufficientSamples(s(60)),"pause rewarms");
        steady.observe(s(61),190);
        equal(0,steady.sampleCount(),"counter regression rebaselines safely");
        steady.observe(s(59),195);
        equal(0,steady.sampleCount(),"clock regression rebaselines safely");
        RateWindow ring=new RateWindow(0);
        for(int i=1;i<=200;i++)ring.observe(s(i),i*10);
        equal(60,ring.sampleCount(),"sample memory bounded");close(10,ring.mean(),"ring wrap rate");
        RateWindow tiny=new RateWindow(0);tiny.observe(500_000_000,10);
        equal(0,tiny.sampleCount(),"subsecond samples accumulate");tiny.observe(s(1),20);
        close(20,tiny.mean(),"subsecond completions retained");

        equal(10L,OceanCanvasPregenMetrics.completedNewWork(100,0,80,10),"exclude skips and outstanding");
        equal(0L,OceanCanvasPregenMetrics.completedNewWork(100,100,0,0),"resumed cursor not fresh work");
        equal(10L,OceanCanvasPregenMetrics.completedNewWork(120,100,5,5),"resumed new work");
        equal(0L,OceanCanvasPregenMetrics.completedNewWork(100,0,100,0),"skip-only job cannot teach throughput");
        equal(0L,OceanCanvasPregenMetrics.completedNewWork(1,0,0,50),"outstanding clamp");
        equal(80L,OceanCanvasPregenMetrics.settledEstimate(100,200,20),"handled not equal settled");
        equal(200L,OceanCanvasPregenMetrics.settledEstimate(300,200,0),"settled cap");
        equal(0L,OceanCanvasPregenMetrics.settledEstimate(10,100,20),"settled nonnegative");
        var cold=new OceanCanvasPregenMetrics.Calibration(20,50,100,0,9,"BALANCED");
        equal(8,OceanCanvasPregenMetrics.conservativeStartRate(cold,"BALANCED",8,64),"unproven calibration ignored");
        var first=OceanCanvasPregenMetrics.mergeCalibration(null,"BALANCED",40,55,120,10);
        equal(true,first.usableFor("BALANCED"),"qualified calibration usable");
        equal(2,OceanCanvasPregenMetrics.conservativeStartRate(first,"BALANCED",8,64),"slow learning lowers start rate");
        equal(8,OceanCanvasPregenMetrics.conservativeStartRate(first,"OVERNIGHT",8,64),"profile mismatch ignored");
        equal(1,OceanCanvasPregenMetrics.conservativeStartRate(first,"BALANCED",1,64),"never raises configured rate");
        var merged=OceanCanvasPregenMetrics.mergeCalibration(first,"BALANCED",80,65,240,60);
        equal(2,merged.successfulRuns(),"successful runs counted");
        close(74.28571428571429,merged.chunksPerSecond(),"bounded weighted calibration merge");
        equal(first,OceanCanvasPregenMetrics.mergeCalibration(first,"BALANCED",Double.NaN,50,100,10),"bad sample ignored");
        equal(first,OceanCanvasPregenMetrics.mergeCalibration(first,"BALANCED",50,50,100,9),"short sample ignored");

        TickTiming tick=new TickTiming();
        close(-1,tick.workMs(),"timing initially unknown");tick.end(s(1));close(-1,tick.workMs(),"orphan end ignored");
        tick.begin(0);tick.end(5_000_000);close(5,tick.workMs(),"work excludes idle sleep");
        tick.begin(50_000_000);tick.end(55_000_000);
        close(50,tick.intervalMs(),"cadence includes sleep");close(5,tick.workMs(),"work remains five milliseconds");
        tick.begin(s(20));equal(-1.0,tick.intervalMs(),"long start gap invalidates cadence sample");

        Snapshot sample=new Snapshot("region-pregen","QUIET","FEEDING","Profile headroom",100,200,20,80,4,8,20,12,25,3,5,50,0.5,2048,4096,10,20,0,12,20,"STEADY_SAMPLE");
        equal(sample,Snapshot.decode(sample.encode()),"wire round trip");
        equal(Snapshot.idle(),Snapshot.decode("garbage"),"malformed packet idle");
        equal(Snapshot.idle(),Snapshot.decode(null),"null packet idle");
        equal(Snapshot.idle(),Snapshot.decode(sample.encode().replace("0.5","NaN")),"nonfinite telemetry rejected");
        equal(Snapshot.idle(),Snapshot.decode(Snapshot.idle().encode()),"idle round trip");
        equal("unknown",OceanCanvasPregenMetrics.duration(-1),"unknown ETA");
        equal("59s",OceanCanvasPregenMetrics.duration(59),"seconds format");
        equal("1m 0s",OceanCanvasPregenMetrics.duration(60),"minutes format");
        equal("1h 1m",OceanCanvasPregenMetrics.duration(3660),"hours format");
        equal("1d 1h",OceanCanvasPregenMetrics.duration(90000),"days format");
        check(sample.encode().length()<1024,"telemetry packet small");
        System.out.println("Pregen metrics offline checks passed: "+checks);
    }
    private static void close(double expected,double actual,String message){check(Math.abs(expected-actual)<0.000001,message+": "+actual);}
    private static void equal(Object expected,Object actual,String message){check(expected.equals(actual),message+" expected="+expected+" actual="+actual);}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
