package net.oceancanvas.mod.project;

import net.oceancanvas.mod.diagnostic.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.performance.OceanCanvasIoPressureGovernor;
import net.oceancanvas.mod.performance.OceanCanvasPregenMetrics;
import net.oceancanvas.mod.project.OceanCanvasProjectData;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P2 performance/operation evidence engine: OC-F203, F050, F118 and F222.
 *
 * <p>Samples existing read-only telemetry once per second. It owns no tickets, performs no chunk
 * loads and cannot mark work complete. The rolling black box is session-local and bounded to 15
 * minutes; budget assertions and decay/baseline comparisons are diagnostic evidence only.</p>
 */
public final class OceanCanvasPerformanceEngine {
    private static final int MAX_SAMPLES=900;
    private static final long SAMPLE_NS=1_000_000_000L;
    private static final ArrayDeque<BlackBoxSample> BLACK_BOX=new ArrayDeque<>();
    private static long sequence,lastSampleNanos;
    private static String lastState="IDLE";
    private static int consecutiveBudgetViolations;
    private static long lastBudgetLogNanos;
    private static String lastBudgetFailureSignature="";
    // v253.72.9: the 12-hour lighting stall produced the same no-progress WARN
    // every ten seconds. The 1 Hz black box already preserves every sample, so
    // repeated log evidence is capped at once per minute unless the set of failing
    // budgets changes. This keeps diagnostics useful without making file I/O part
    // of the failure.
    private static final long BUDGET_REPEAT_LOG_NS=60_000_000_000L;
    // v253.125.26: never let rapidly changing failure signatures force a WARN every second
    // while the server is already stalled. The black box still records every sample.
    private static final long BUDGET_MIN_LOG_GAP_NS=10_000_000_000L;

    private OceanCanvasPerformanceEngine(){}

    public record BlackBoxSample(long sequence,long epochMillis,String kind,String phase,String reason,
                                 long handled,long total,long settled,int outstanding,int queued,
                                 double chunksPerSecond,double tickWorkMs,double tickIntervalMs,double heapFraction,
                                 String ioState,long ioOldestLoadMs,double ioBacklogFraction,String budgetState){ }
    public record BudgetAssertion(String id,String label,String state,double observed,double limit,String unit,String detail){ }
    public record BaselineComparison(String state,double throughputRatio,double cadenceRatio,double outstandingRatio,String detail){ }
    public record DecayStatus(String state,double earlyCps,double recentCps,double ratio,int samples,String detail){ }
    public record Summary(List<BudgetAssertion> budgets,BaselineComparison baseline,DecayStatus decay,
                          OceanCanvasIoPressureGovernor.Decision io,List<OceanCanvasWorkloadPhaseProfiler.PhaseSnapshot> phases,
                          List<OceanCanvasHotRegionMap.HotCell> hotRegions,int blackBoxSamples){ }

    public static void register(){
        ServerTickEvents.END_SERVER_TICK.register(server->{
            long now=System.nanoTime();
            if(lastSampleNanos!=0L&&now-lastSampleNanos<SAMPLE_NS)return;
            lastSampleNanos=now;
            ServerLevel world=server.overworld();
            if(world!=null)sample(world,now);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->resetSession());
    }

    private static void sample(ServerLevel world,long now){
        var p=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.performanceSnapshot();
        if(p==null)p=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Performance.idle();
        var q=OceanCanvasTerrainRuntimeDiagnostics.queueSnapshot(world);
        var io=OceanCanvasIoPressureGovernor.evaluate(Math.max(0,p.rate()),Math.max(0,p.outstanding()),
                q.loadingNotQueued(),q.loadingStale(),q.oldestLoadingMs());
        var budgets=budgetAssertions(p,io);
        String budgetState=budgets.stream().anyMatch(b->"FAIL".equals(b.state()))?"FAIL":
                budgets.stream().anyMatch(b->"WARN".equals(b.state()))?"WARN":"PASS";
        String state=(p.active()?p.kind()+"/"+p.phase()+"/"+p.reason():"IDLE");
        if(!state.equals(lastState)){
            add(new BlackBoxSample(++sequence,System.currentTimeMillis(),p.kind(),p.phase(),"STATE: "+lastState+" -> "+state,
                    p.handled(),p.total(),p.settled(),p.outstanding(),p.queued(),p.chunksPerSecond(),p.tickWorkMs(),p.tickIntervalMs(),p.heapFraction(),
                    io.state().name(),io.oldestLoadMs(),io.backlogFraction(),budgetState));
            lastState=state;
        } else if(!p.active()) {
            // Keep the last operation's black-box window intact while the server is idle.
            // One IDLE transition sample is enough; endless idle samples would evict the evidence.
            return;
        }
        add(new BlackBoxSample(++sequence,System.currentTimeMillis(),p.kind(),p.phase(),p.reason(),p.handled(),p.total(),p.settled(),
                p.outstanding(),p.queued(),p.chunksPerSecond(),p.tickWorkMs(),p.tickIntervalMs(),p.heapFraction(),io.state().name(),
                io.oldestLoadMs(),io.backlogFraction(),budgetState));

        boolean failing="FAIL".equals(budgetState);
        int previousViolationCount=consecutiveBudgetViolations;
        consecutiveBudgetViolations=failing?consecutiveBudgetViolations+1:0;
        if(failing){
            String signature=budgetFailureSignature(budgets);
            boolean newlyConfirmed=previousViolationCount<3&&consecutiveBudgetViolations>=3;
            boolean failureSetChanged=!signature.equals(lastBudgetFailureSignature);
            boolean minGapElapsed=lastBudgetLogNanos==0L||now-lastBudgetLogNanos>=BUDGET_MIN_LOG_GAP_NS;
            boolean repeatElapsed=lastBudgetLogNanos==0L||now-lastBudgetLogNanos>=BUDGET_REPEAT_LOG_NS;
            if(consecutiveBudgetViolations>=3&&minGapElapsed&&(newlyConfirmed||failureSetChanged||repeatElapsed)){
                lastBudgetLogNanos=now;
                lastBudgetFailureSignature=signature;
                OceanCanvas.LOGGER.warn("(Ocean Canvas) P2 performance budget assertion: {}",compactBudget(budgets));
            }
        } else if(previousViolationCount>=3){
            OceanCanvas.LOGGER.info("(Ocean Canvas) P2 performance budget recovered after {} consecutive failing samples.",previousViolationCount);
            lastBudgetFailureSignature="";
            lastBudgetLogNanos=0L;
        }
    }

    public static synchronized Summary summary(ServerLevel world){
        var p=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.performanceSnapshot();
        if(p==null)p=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Performance.idle();
        var q=OceanCanvasTerrainRuntimeDiagnostics.queueSnapshot(world);
        var io=OceanCanvasIoPressureGovernor.evaluate(Math.max(0,p.rate()),Math.max(0,p.outstanding()),q.loadingNotQueued(),q.loadingStale(),q.oldestLoadingMs());
        return new Summary(budgetAssertions(p,io),compareToBaseline(world,p),decayStatus(),io,
                OceanCanvasWorkloadPhaseProfiler.snapshot(),OceanCanvasHotRegionMap.hottest(8),BLACK_BOX.size());
    }

    /** OC-F222: explicit, named budgets. These assert/report; they never abort a job. */
    public static List<BudgetAssertion> budgetAssertions(net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Performance p,OceanCanvasIoPressureGovernor.Decision io){
        ArrayList<BudgetAssertion> out=new ArrayList<>();
        out.add(assertMax("tick-work","Server tick work",p.tickWorkMs(),50.0D,"ms",65.0D));
        out.add(assertMax("tick-interval","Tick interval",p.tickIntervalMs(),60.0D,"ms",100.0D));
        out.add(assertMax("heap","Heap use",p.heapFraction()*100.0D,82.0D,"%",90.0D));
        out.add(assertMax("no-progress","No-progress window",p.noProgressSeconds(),30.0D,"s",60.0D));
        double ioScore=switch(io.state()){case CLEAR->0;case ELEVATED->1;case SATURATED->2;case STALLED->3;};
        out.add(new BudgetAssertion("io-pressure","Storage pressure",ioScore<=0?"PASS":ioScore==1?"WARN":"FAIL",ioScore,1.0D,"level",io.reason()));
        var controller=OceanCanvasWorkloadPhaseProfiler.phase("pregen.controller");
        var flattener=OceanCanvasWorkloadPhaseProfiler.phase("terrain.flattener");
        out.add(assertMax("oc-controller","Ocean Canvas controller EMA",controller.emaMs(),12.0D,"ms",25.0D));
        out.add(assertMax("oc-flattener","Ocean Canvas flattener EMA",flattener.emaMs(),35.0D,"ms",50.0D));
        return List.copyOf(out);
    }

    private static BudgetAssertion assertMax(String id,String label,double observed,double warn,String unit,double fail){
        if(!Double.isFinite(observed)||observed<0)return new BudgetAssertion(id,label,"UNAVAILABLE",observed,warn,unit,"No current sample");
        String state=observed>fail?"FAIL":observed>warn?"WARN":"PASS";
        return new BudgetAssertion(id,label,state,observed,warn,unit,"Budget <= "+fmt(warn)+unit+"; hard failure > "+fmt(fail)+unit);
    }

    /** OC-F050: compare current run against the persisted successful calibration for this profile. */
    public static BaselineComparison compareToBaseline(ServerLevel world,net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Performance p){
        var b=OceanCanvasProjectData.get(world).benchmark();
        if(b==null||b.calibrationVersion()!=OceanCanvasPregenMetrics.CALIBRATION_VERSION||b.samples()<10||b.sustainableChunksPerSecond()<=0)
            return new BaselineComparison("UNAVAILABLE",-1,-1,-1,"No compatible known-good successful-run baseline yet.");
        if(!p.active())
            return new BaselineComparison("NO_ACTIVE_RUN",-1,-1,-1,"Known-good baseline exists; start Pregen to compare a live run.");
        if(!b.observedProfile().equals(p.profile()))
            return new BaselineComparison("PROFILE_MISMATCH",-1,-1,-1,"Known-good baseline is for "+b.observedProfile()+", current profile is "+p.profile()+".");
        double throughput=p.chunksPerSecond()>0?p.chunksPerSecond()/b.sustainableChunksPerSecond():0.0D;
        double cadence=b.healthyTickMs()>0&&p.tickIntervalMs()>0?p.tickIntervalMs()/b.healthyTickMs():-1.0D;
        double outstanding=b.preferredOutstanding()>0?p.outstanding()/(double)b.preferredOutstanding():-1.0D;
        String state=throughput>=0.80D&&(cadence<0||cadence<=1.20D)?"MATCH":throughput>=0.60D?"DEGRADED":"REGRESSED";
        return new BaselineComparison(state,throughput,cadence,outstanding,
                "Current throughput is "+fmt(throughput*100.0D)+"% of known-good; tick cadence ratio="+fmt(cadence)+".");
    }

    /** OC-F118: compare stable early-session throughput with the latest minute. */
    public static synchronized DecayStatus decayStatus(){
        ArrayList<BlackBoxSample> active=new ArrayList<>();
        for(var s:BLACK_BOX)if(s.kind()!=null&&!s.kind().isBlank()&&s.chunksPerSecond()>0&&"PASS".equals(s.budgetState()))active.add(s);
        if(active.size()<120)return new DecayStatus("WARMING_UP",0,0,-1,active.size(),"Need at least two minutes of healthy samples.");
        int window=Math.min(60,active.size()/2);
        double early=meanCps(active,0,window),recent=meanCps(active,active.size()-window,active.size());
        if(early<=0)return new DecayStatus("UNAVAILABLE",early,recent,-1,active.size(),"Early stable throughput is unavailable.");
        double ratio=recent/early;
        String state=ratio<0.55D?"SEVERE_DECAY":ratio<0.75D?"DECAY":"STABLE";
        return new DecayStatus(state,early,recent,ratio,active.size(),"Recent healthy throughput is "+fmt(ratio*100.0D)+"% of the early-session healthy window.");
    }

    private static double meanCps(List<BlackBoxSample> samples,int from,int to){double sum=0;int n=0;for(int i=from;i<to;i++){double v=samples.get(i).chunksPerSecond();if(v>0&&Double.isFinite(v)){sum+=v;n++;}}return n==0?0:sum/n;}

    public static synchronized List<BlackBoxSample> blackBoxSnapshot(){return List.copyOf(BLACK_BOX);}
    public static synchronized String blackBoxTsv(){
        StringBuilder s=new StringBuilder("# Ocean Canvas P2 operation black box\n");
        s.append("sequence\tepochMillis\tkind\tphase\treason\thandled\ttotal\tsettled\toutstanding\tqueued\tchunksPerSecond\ttickWorkMs\ttickIntervalMs\theapFraction\tioState\tioOldestLoadMs\tioBacklogFraction\tbudgetState\n");
        for(var x:BLACK_BOX)s.append(x.sequence()).append('\t').append(x.epochMillis()).append('\t').append(clean(x.kind())).append('\t').append(clean(x.phase())).append('\t').append(clean(x.reason())).append('\t')
                .append(x.handled()).append('\t').append(x.total()).append('\t').append(x.settled()).append('\t').append(x.outstanding()).append('\t').append(x.queued()).append('\t')
                .append(x.chunksPerSecond()).append('\t').append(x.tickWorkMs()).append('\t').append(x.tickIntervalMs()).append('\t').append(x.heapFraction()).append('\t')
                .append(x.ioState()).append('\t').append(x.ioOldestLoadMs()).append('\t').append(x.ioBacklogFraction()).append('\t').append(x.budgetState()).append('\n');
        return s.toString();
    }

    public static synchronized void resetSession(){BLACK_BOX.clear();sequence=0;lastSampleNanos=0;lastState="IDLE";consecutiveBudgetViolations=0;lastBudgetLogNanos=0;lastBudgetFailureSignature="";OceanCanvasWorkloadPhaseProfiler.reset();OceanCanvasHotRegionMap.clear();}
    private static synchronized void add(BlackBoxSample s){BLACK_BOX.addLast(s);while(BLACK_BOX.size()>MAX_SAMPLES)BLACK_BOX.removeFirst();}
    private static String compactBudget(List<BudgetAssertion> xs){StringBuilder s=new StringBuilder();for(var x:xs)if("WARN".equals(x.state())||"FAIL".equals(x.state())){if(s.length()>0)s.append("; ");s.append(x.id()).append('=').append(x.state()).append('(').append(fmt(x.observed())).append(x.unit()).append(')');}return s.length()==0?"PASS":s.toString();}
    private static String budgetFailureSignature(List<BudgetAssertion> xs){StringBuilder s=new StringBuilder();for(var x:xs)if("FAIL".equals(x.state())){if(s.length()>0)s.append(',');s.append(x.id());}return s.toString();}
    private static String clean(String s){return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ');}
    private static String fmt(double d){return !Double.isFinite(d)||d<0?"n/a":String.format(Locale.ROOT,"%.2f",d);}
}
