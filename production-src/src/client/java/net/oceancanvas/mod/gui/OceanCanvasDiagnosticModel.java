package net.oceancanvas.mod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.oceancanvas.mod.network.OceanCanvasZoneClientCache;
import net.oceancanvas.mod.performance.OceanCanvasPregenMetrics;

/**
 * The diagnostic reasoning layer: invariants, stall risk, failure signatures, pause taxonomy and
 * abort safety (OC-F043, F044, F112, F113, F207, F212).
 *
 * <p><b>Everything here is derived from telemetry the server already broadcasts.</b> Not one line
 * changes the Pregen controller, adds a payload, or asks the server for anything new. That is a
 * deliberate constraint, not a shortcut: the operation path is the code this project spent
 * fifty-odd rounds stabilising, and the standing rule is that it does not change without new
 * runtime evidence. A diagnostic layer that had to modify the thing it diagnoses would be the
 * wrong trade.</p>
 *
 * <p>The cost of that constraint is stated rather than hidden. Several invariants worth having —
 * no orphan tickets, no work outside the selection, cleanup completeness — are simply not
 * observable from {@link OceanCanvasPregenMetrics.Snapshot}, and this class reports them as
 * {@code NOT_OBSERVABLE} instead of inventing a verdict. A dashboard that shows green for something
 * it cannot actually see is worse than one that admits the gap.</p>
 *
 * <p>State is kept across frames (a short rolling history) because trend invariants and the decay
 * detector need it. It is sampled at most once a second and bounded to a few minutes.</p>
 */
public final class OceanCanvasDiagnosticModel {

    private OceanCanvasDiagnosticModel(){}

    // ----------------------------------------------------------------- rolling sample history
    /** One sampled instant. Kept small; this is a diagnostic aid, not a telemetry store. */
    public record Sample(long atMillis,long handled,int outstanding,int finalDrainTickets,
                         double chunksPerSecond,double heapFraction,double tickWorkMs,
                         long noProgressSeconds,String phase,String reason) { }

    private static final List<Sample> HISTORY=new ArrayList<>();
    private static final long SAMPLE_INTERVAL_MS=1_000L;
    private static final long HISTORY_WINDOW_MS=300_000L;   // five minutes
    private static long lastSampleAt=0L;

    /** Called once per frame; samples at most once a second. Cheap enough for the render path. */
    public static void tick(){
        var p=OceanCanvasZoneClientCache.performance();
        if(p==null||!p.active()){ if(!HISTORY.isEmpty()&&HISTORY.get(HISTORY.size()-1).handled()>0) HISTORY.clear(); return; }
        long now=System.currentTimeMillis();
        if(now-lastSampleAt<SAMPLE_INTERVAL_MS) return;
        lastSampleAt=now;
        HISTORY.add(new Sample(now,p.handled(),p.outstanding(),p.finalDrainTickets(),
                p.chunksPerSecond(),p.heapFraction(),p.tickWorkMs(),p.noProgressSeconds(),p.phase(),p.reason()));
        while(!HISTORY.isEmpty()&&now-HISTORY.get(0).atMillis()>HISTORY_WINDOW_MS) HISTORY.remove(0);
    }

    public static List<Sample> history(){ return List.copyOf(HISTORY); }
    public static void reset(){ HISTORY.clear(); lastSampleAt=0L; }

    // ----------------------------------------------------------------- invariants (F043, F112)
    /**
     * Verdicts. {@code NOT_OBSERVABLE} is a first-class result, not a failure to implement — it
     * marks an invariant that genuinely cannot be checked from broadcast telemetry.
     */
    public static final String PASS="PASS", WARN="WARN", FAIL="FAIL", NOT_OBSERVABLE="NOT OBSERVABLE";

    /**
     * @param owner the subsystem that would be at fault, so a FAIL points somewhere
     * @param evidence the actual numbers behind the verdict, never a bare restatement
     */
    public record Invariant(String id,String statement,String verdict,String evidence,String owner) { }

    public static List<Invariant> invariants(){
        var out=new ArrayList<Invariant>();
        var p=OceanCanvasZoneClientCache.performance();
        boolean stale=OceanCanvasZoneClientCache.performanceStale();
        if(p==null||!p.active()){
            out.add(new Invariant("job.idle","No operation is running","—","Nothing to check while idle.","—"));
            return out;
        }

        out.add(check("scope.bound","Handled chunks never exceed the operation's total scope",
                p.total()<=0||p.handled()<=p.total(),
                p.handled()+" handled of "+p.total()+" total","PregenManager cursor"));

        long minHandled=Long.MAX_VALUE; boolean regressed=false;
        for(var s:HISTORY){ if(s.handled()<minHandled) minHandled=s.handled(); }
        for(int i=1;i<HISTORY.size();i++) if(HISTORY.get(i).handled()<HISTORY.get(i-1).handled()) regressed=true;
        out.add(check("progress.monotonic","Progress only ever moves forward",!regressed,
                regressed?"Handled count decreased between samples — this should be impossible"
                        :"Monotonic across "+HISTORY.size()+" samples","PregenManager job accounting"));

        boolean throttledHasReason=!"THROTTLED".equals(p.phase())||!p.reason().isBlank();
        out.add(check("pause.named","Every throttled tick names a machine-readable reason",throttledHasReason,
                "THROTTLED".equals(p.phase())?"Reason: "+p.reason():"Phase is "+p.phase(),
                "PregenManager admission gate"));

        boolean feedingProgresses=!"FEEDING".equals(p.phase())||p.noProgressSeconds()<60;
        out.add(new Invariant("progress.feeding","A feeding controller retires work within a minute",
                feedingProgresses?PASS:FAIL,
                "Phase "+p.phase()+", no progress for "+p.noProgressSeconds()+"s, throughput "
                        +String.format(Locale.US,"%.2f",p.chunksPerSecond())+"/s",
                "PregenManager admission / chunk load path"));

        boolean heapOk=p.heapMaxMiB()<=0||p.heapFraction()<0.82D||p.reason().contains("Heap");
        out.add(new Invariant("heap.bound","Heap stays under the 82% pause fraction, or the pause is named",
                heapOk?PASS:WARN,
                p.heapMaxMiB()<=0?"Heap not reported":Math.round(p.heapFraction()*100)+"% of "+p.heapMaxMiB()+" MiB",
                "JVM heap / OceanCanvasPregenMetrics"));

        boolean drainTicketsScoped=p.finalDrainTickets()==0||"FINAL_DRAIN".equals(p.phase());
        out.add(new Invariant("tickets.drain-scoped","Final-drain tickets exist only during the final drain",
                drainTicketsScoped?PASS:WARN,
                p.finalDrainTickets()+" drain tickets while phase is "+p.phase(),
                "PregenManager ticket lifecycle"));

        boolean growth=queueGrowingWithoutProgress();
        out.add(new Invariant("queue.bounded","Outstanding work does not grow while nothing retires",
                growth?FAIL:PASS,
                growth?"Outstanding rose across the sample window with zero completions"
                        :"Outstanding "+p.outstanding()+", queued "+p.queued(),
                "PregenManager admission gate"));

        out.add(new Invariant("telemetry.fresh","Telemetry is arriving while an operation runs",
                stale?WARN:PASS,
                stale?"No performance packet for over 5 seconds":"Current",
                "Networking / server tick"));

        // Stated, not silently omitted.
        out.add(new Invariant("tickets.no-orphans","No Ocean Canvas chunk ticket outlives its job",
                NOT_OBSERVABLE,"Ticket pools are counted in telemetry but not enumerated per ticket. "
                +"Proving this needs a server-side inspector (OC-F208).","PregenManager ticket lifecycle"));
        out.add(new Invariant("scope.no-outside-writes","No block is written outside the selection",
                NOT_OBSERVABLE,"Requires a boundary canary ring around the operation (OC-F119).",
                "OceanCanvasSurfaceFlattener"));
        out.add(new Invariant("cleanup.complete","Cancel or shutdown leaves no leases, tickets or worker state",
                NOT_OBSERVABLE,"Requires a post-operation cleanup proof report (OC-F115).",
                "PregenManager shutdown path"));
        return out;
    }

    private static Invariant check(String id,String statement,boolean ok,String evidence,String owner){
        return new Invariant(id,statement,ok?PASS:FAIL,evidence,owner);
    }

    private static boolean queueGrowingWithoutProgress(){
        if(HISTORY.size()<20) return false;
        var first=HISTORY.get(0); var last=HISTORY.get(HISTORY.size()-1);
        return last.handled()==first.handled() && last.outstanding()>first.outstanding();
    }

    // ----------------------------------------------------------------- stall risk (F113)
    /**
     * A weighted heuristic, deliberately labelled as one. It combines signals the controller
     * already publishes; it does not predict anything the controller does not already know, and a
     * high score is a prompt to look, not a diagnosis.
     */
    public record StallRisk(int score,String band,List<String> contributors) { }

    public static StallRisk stallRisk(){
        var p=OceanCanvasZoneClientCache.performance();
        var contributors=new ArrayList<String>();
        if(p==null||!p.active()) return new StallRisk(0,"IDLE",List.of());
        int score=0;

        long np=p.noProgressSeconds();
        if(np>0){
            int pts=(int)Math.min(40,np*4/3);
            if(pts>0){ score+=pts; contributors.add("No progress for "+np+"s (+"+pts+")"); }
        }
        if(p.heapMaxMiB()>0&&p.heapFraction()>0.70D){
            int pts=(int)Math.min(20,Math.round((p.heapFraction()-0.70D)*167));
            if(pts>0){ score+=pts; contributors.add("Heap at "+Math.round(p.heapFraction()*100)+"% (+"+pts+")"); }
        }
        if(p.tickIntervalMs()>0&&p.tickWorkMs()>p.tickIntervalMs()*0.5D){
            int pts=(int)Math.min(15,Math.round((p.tickWorkMs()/Math.max(1D,p.tickIntervalMs())-0.5D)*30));
            if(pts>0){ score+=pts; contributors.add("Tick work "+String.format(Locale.US,"%.1f",p.tickWorkMs())
                    +"ms of "+String.format(Locale.US,"%.1f",p.tickIntervalMs())+"ms (+"+pts+")"); }
        }
        double best=0; for(var s:HISTORY) best=Math.max(best,s.chunksPerSecond());
        if(best>0.5D&&p.chunksPerSecond()<best*0.5D){
            int pts=(int)Math.min(15,Math.round((1-p.chunksPerSecond()/best)*15));
            if(pts>0){ score+=pts; contributors.add("Throughput "+String.format(Locale.US,"%.2f",p.chunksPerSecond())
                    +"/s against a session best of "+String.format(Locale.US,"%.2f",best)+" (+"+pts+")"); }
        }
        if(p.outstanding()>0&&p.chunksPerSecond()<=0.01D){
            score+=10; contributors.add(p.outstanding()+" outstanding with no measurable throughput (+10)");
        }
        score=Math.min(100,score);
        String band=score>=60?"HIGH":score>=30?"ELEVATED":"LOW";
        if(contributors.isEmpty()) contributors.add("No stall signals present.");
        return new StallRisk(score,band,List.copyOf(contributors));
    }

    // ----------------------------------------------------------------- signatures (F044)
    /**
     * Named failure shapes this project has actually seen, so a run can be recognised rather than
     * re-diagnosed from scratch. {@code firstEvidence} is what to capture before anything else —
     * the point of a signature library is to shorten the path from symptom to the right log.
     */
    public record Signature(String id,String name,String meaning,String subsystem,String firstEvidence) { }

    public static List<Signature> matchedSignatures(){
        var out=new ArrayList<Signature>();
        var p=OceanCanvasZoneClientCache.performance();
        if(p==null||!p.active()) return out;
        String reason=p.reason()==null?"":p.reason();

        if("FINAL_DRAIN".equals(p.phase())&&p.noProgressSeconds()>60&&p.outstanding()>0)
            out.add(new Signature("final-drain-hang","Final-drain hang",
                    "Every chunk was submitted, but owned chunks are not retiring. The job cannot complete and will not report completion.",
                    "PregenManager final drain / ticket release",
                    "Server log around the drain transition, plus Outstanding and Final-drain ticket counts over a minute."));

        int breaker=0, coldLoad=0, heap=0, cadence=0;
        for(var s:HISTORY){
            String r=s.reason()==null?"":s.reason();
            if(r.startsWith("Neighbor-stall circuit breaker")) breaker++;
            if(r.startsWith("Cold-load debt")) coldLoad++;
            if(r.equals("Heap pressure")) heap++;
            if(r.equals("Tick cadence limit")) cadence++;
        }
        int n=Math.max(1,HISTORY.size());
        if(breaker>=10&&engagementCycles("Neighbor-stall circuit breaker")>=3)
            out.add(new Signature("breaker-sawtooth","Neighbour-stall sawtooth",
                    "The circuit breaker is engaging, releasing and re-engaging repeatedly rather than clearing once. Throughput oscillates instead of recovering.",
                    "PregenManager breaker release condition",
                    "A two-minute log covering at least three engage/release cycles, with the queued count at each."));
        if(coldLoad*100/n>=40)
            out.add(new Signature("cold-load-bound","Cold-load bound run",
                    "Most of this run is spent waiting on chunk loads rather than on carving. Admission tuning will not help; the cost is below Ocean Canvas.",
                    "Chunk storage / world generation, not admission",
                    "Disk throughput during the run, and whether the world is on spinning or networked storage."));
        if(heap*100/n>=25)
            out.add(new Signature("heap-bound","Heap-bound run",
                    "Admission is repeatedly halted by the 82% heap pause. The run is memory-limited.",
                    "JVM heap allocation for the instance",
                    "Allocated heap for the instance, and whether other mods hold large caches."));
        if(cadence*100/n>=25)
            out.add(new Signature("cadence-bound","Server-cadence bound run",
                    "The server tick is running long independently of Ocean Canvas, so admission keeps yielding.",
                    "Server or other mods, not Ocean Canvas",
                    "A tick profile with Ocean Canvas idle, to establish the baseline cadence."));
        if("NO_RECENT_COMPLETIONS".equals(p.etaQuality())&&p.rate()>0&&reason.equals("Feeding within profile limits"))
            out.add(new Signature("silent-stall","Silent completion stall",
                    "The controller believes it is feeding normally and reports no hold, yet nothing has retired for 30 seconds. The gap is between admission and retirement.",
                    "Chunk future completion / retirement path",
                    "A log covering the last 30 seconds, and the Queue causality view once OC-F209 exists."));
        return out;
    }

    /** Counts how many times a reason engaged after being absent, i.e. distinct episodes. */
    private static int engagementCycles(String reasonPrefix){
        int cycles=0; boolean inside=false;
        for(var s:HISTORY){
            boolean now=s.reason()!=null&&s.reason().startsWith(reasonPrefix);
            if(now&&!inside) cycles++;
            inside=now;
        }
        return cycles;
    }

    // ----------------------------------------------------------------- pause taxonomy (F212)
    /**
     * The other half of v253.39's explanations: who owns the condition and what specifically ends
     * it. The reason strings are the same set the screen explains, and a regression test requires
     * both to cover every reason {@code PregenManager} can emit.
     */
    public record PauseEntry(String reason,String owner,String trigger,String resumeCondition) { }

    public static PauseEntry taxonomyFor(String rawReason){
        String r=rawReason==null?"":rawReason.trim();
        if(r.equals("Starting")) return e(r,"PregenManager","Job accepted, first tick not yet run","The first admission decision");
        if(r.equals("Feeding within profile limits")) return e(r,"PregenManager","No hold active","Not a pause");
        if(r.equals("Tick cadence limit")) return e(r,"Server tick loop","Tick interval exceeded the budget","Tick cadence returns within budget");
        if(r.equals("Heap pressure")) return e(r,"JVM heap","Heap use reached 82%","Heap falls below the pause fraction");
        if(r.equals("Hard queue limit")) return e(r,"PregenManager","Outstanding hit the absolute ceiling","Owned chunks retire below the cap");
        if(r.startsWith("Neighbor-stall circuit breaker")) return e(r,"PregenManager breaker","Too much of the queue waiting on non-ticking neighbours","Residual queue drains, or its stale share falls to about a third");
        if(r.startsWith("Cold-load debt hard hold")) return e(r,"Chunk storage","A single target loading for 10s or more","That load completes");
        if(r.startsWith("Cold-load debt cohort hold")) return e(r,"Chunk storage","Four or more loading, majority stale, oldest 5s or more","The cohort of loads completes");
        if(r.startsWith("Proactive health hold")) return e(r,"PregenManager health model","Trajectory predicts a stall","The health trajectory improves");
        if(r.startsWith("Preemptive no-ready hold")) return e(r,"Chunk ticking tier","Carve lane deep, nothing loading or ready, a fifth stale","Stale frontier chunks reach the ticking tier");
        if(r.startsWith("I/O pressure governor")) return e(r,"Chunk storage","Independent FULL-load backlog/age model reached elevated or stalled pressure","Pending FULL loads complete and storage pressure clears");
        if(r.startsWith("Resource budget")) return e(r,"Pregen resource budget","CPU tick work, heap use, transient tickets, or multiple machine resources reached an explicit admission budget","The named resource regains headroom; the budget layer never raises the controller rate");
        if(r.startsWith("Outstanding load backlog")) return e(r,"Chunk storage","Requested-but-unqueued passed max(8, a third of target)","Pending loads arrive and queue");
        if(r.startsWith("Adaptive queue-depth limit")) return e(r,"PregenManager","Outstanding reached the adaptive safe depth","Chunks retire below the target depth");
        if(r.startsWith("Startup canary")) return e(r,"PregenManager","Fewer than four proven retirements since start","Four chunks retire successfully");
        if(r.startsWith("Recovery ramp")) return e(r,"PregenManager","Recovering from a stall","Sustained retirement raises the paced rate");
        if(r.equals("Predictive stale-frontier throttle")) return e(r,"PregenManager","About a tenth of the carve lane stale","The stale share falls");
        if(r.equals("Wall-time retirement-paced admission")) return e(r,"PregenManager","Pacing mode, not a hold","Not a pause");
        if(r.equals("Above target queue depth; reduced feed")) return e(r,"PregenManager","Depth above target","Depth returns to target");
        if(r.startsWith("Lighting finalization backlog")
                || r.startsWith("Lighting finalization flow-control"))
            return e(r,"Lighting finalizer","Authored lighting reached the bounded high-water mark","Backlog drains below the low-water mark");
        if(r.startsWith("Lighting finalization emergency backlog"))
            return e(r,"Lighting finalizer","Lighting reached the emergency ceiling, so normal terrain admission is closed; bounded restart-recovery admission may continue only when it is required to break a recovery/light dependency","Backlog falls below the emergency ceiling and then the normal low-water mark");
        if(r.startsWith("Bounded metadata replay through already light-certified Canvas chunks")) return e(r,"PregenManager restart reconciliation","Restart scan is verifying persisted lighting certificates without loading certified chunks","Metadata scan reaches the prior cursor");
        if(r.startsWith("Admission ownership precondition failed")
                ||r.startsWith("Final-drain admission ownership precondition failed"))
            return e(r,"Chunk ownership","A chunk could not be taken under Ocean Canvas ownership","That chunk becomes ownable");
        if(r.equals("Diagnosing stalled outstanding chunks")) return e(r,"PregenManager diagnostics","Outstanding chunks making no progress","The diagnostic pass resolves to a specific hold");
        if(r.startsWith("Waiting for owned targets")) return e(r,"PregenManager final drain","All work submitted","Owned targets finish and release");
        if(r.startsWith("Crash recovery rescan drain:")) return e(r,"Restart recovery","Persisted recovery debt still lacks authoritative proof","Bounded rescan certifies or repairs those chunks");
        if(r.equals("Forever-world proactive continuity pulse: 1 NORMAL terrain target inside measured safe headroom")) return e(r,"Forever-world liveness controller","One normal target fits inside measured worst-case lighting headroom","That bounded pulse retires before another is considered");
        if(r.equals("Forever-world resume reserve: 1 NORMAL terrain target after 16 light publications/active retirements")) return e(r,"Forever-world liveness controller","Publication/retirement progress earned one debt-negative normal-terrain reserve","The reserved target retires before more credit is earned");
        if(r.startsWith("Lighting recovery safety cap reached")) return e(r,"Lighting recovery","Absolute pending-recovery cap reached","Recovery/finalization debt drains below the cap");
        if(r.startsWith("Lighting repair safety cap reached")) return e(r,"Lighting repair","Absolute pending-repair cap reached","Bounded repair/finalization debt drains below the cap");
        if(r.equals("Predictive light-headroom clamp before high-water overshoot")) return e(r,"Lighting headroom governor","Worst-case next-tick fan-out could cross high-water","Finalization creates safe measured headroom");
        if(r.startsWith("Recovery-heavy light pressure")) return e(r,"Restart recovery","Recovery lighting debt dominates the active window","Recovery debt drains toward low-water");
        if(r.startsWith("Restart recovery quarantine:")) return e(r,"Restart recovery quarantine","Chunks remain uncertified after restart","Authoritative proof retires quarantine debt");
        if(r.equals("Save & Quit / shutdown preemption")) return e(r,"Shutdown preemption","Normal admission closed for prompt resumable shutdown","Ownership and persistence handoff completes");
        return null;
    }
    private static PauseEntry e(String r,String owner,String trigger,String resume){ return new PauseEntry(r,owner,trigger,resume); }

    // ----------------------------------------------------------------- abort safety (F207)
    /**
     * What cancelling right now would leave behind. Cancellation itself is unchanged; this only
     * reports, from broadcast state, whether the operation is sitting at a clean point.
     */
    public record AbortSafety(String verdict,String detail,List<String> remaining) { }

    public static AbortSafety abortSafety(){
        var p=OceanCanvasZoneClientCache.performance();
        if(p==null||!p.active())
            return new AbortSafety("NO OPERATION","Nothing is running.",List.of());
        var remaining=new ArrayList<String>();
        if(p.outstanding()>0) remaining.add(p.outstanding()+" chunks owned but not yet retired");
        if(p.finalDrainTickets()>0) remaining.add(p.finalDrainTickets()+" final-drain tickets held");
        if(p.queued()>0) remaining.add(p.queued()+" chunks queued for carving");
        long left=p.total()>0?Math.max(0,p.total()-p.handled()):0;
        if(remaining.isEmpty())
            return new AbortSafety("CLEAN CHECKPOINT",
                    "No chunks are owned and no tickets are held. Cancelling now leaves nothing to unwind."
                    +(left>0?" "+left+" chunks of the region would remain uncarved.":""),List.of());
        return new AbortSafety("CLEANUP PENDING",
                "Cancelling now leaves work Ocean Canvas must unwind before the operation is fully closed."
                +(left>0?" "+left+" chunks of the region would remain uncarved.":""),
                List.copyOf(remaining));
    }
}
