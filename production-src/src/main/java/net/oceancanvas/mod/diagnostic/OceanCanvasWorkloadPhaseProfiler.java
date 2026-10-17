package net.oceancanvas.mod.diagnostic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OC-F215 workload phase profiler.
 *
 * <p>Ocean Canvas already measured whole-tick work, but that could only say that a tick was
 * expensive, not which Ocean Canvas phase owned the cost. This profiler is deliberately tiny:
 * call sites record already-known phase spans in nanoseconds, the profiler keeps bounded EMA/
 * max/total counters, and readers get immutable snapshots. It does not schedule work, inspect
 * chunks, or change controller decisions.</p>
 */
public final class OceanCanvasWorkloadPhaseProfiler {
    private static final int MAX_PHASES=32;
    private static final Map<String,Mutable> PHASES=new LinkedHashMap<>();
    private OceanCanvasWorkloadPhaseProfiler(){}

    public record PhaseSnapshot(String phase,long samples,double emaMs,double maxMs,double totalMs){ }

    public static synchronized void record(String phase,long nanos){
        if(phase==null||phase.isBlank()||nanos<0)return;
        String key=phase.trim();
        Mutable m=PHASES.get(key);
        if(m==null){
            if(PHASES.size()>=MAX_PHASES)return;
            m=new Mutable();PHASES.put(key,m);
        }
        double ms=nanos/1_000_000.0D;
        m.samples++;
        m.totalMs+=ms;
        m.maxMs=Math.max(m.maxMs,ms);
        m.emaMs=m.samples==1?ms:(m.emaMs*0.85D+ms*0.15D);
    }

    public static synchronized List<PhaseSnapshot> snapshot(){
        ArrayList<PhaseSnapshot> out=new ArrayList<>();
        for(var e:PHASES.entrySet()){
            Mutable m=e.getValue();
            out.add(new PhaseSnapshot(e.getKey(),m.samples,m.emaMs,m.maxMs,m.totalMs));
        }
        out.sort(Comparator.comparingDouble(PhaseSnapshot::emaMs).reversed());
        return List.copyOf(out);
    }

    public static synchronized PhaseSnapshot phase(String phase){
        Mutable m=PHASES.get(phase);
        return m==null?new PhaseSnapshot(phase==null?"":phase,0,0,0,0)
                :new PhaseSnapshot(phase,m.samples,m.emaMs,m.maxMs,m.totalMs);
    }

    public static synchronized void reset(){PHASES.clear();}

    private static final class Mutable { long samples; double emaMs,maxMs,totalMs; }
}
