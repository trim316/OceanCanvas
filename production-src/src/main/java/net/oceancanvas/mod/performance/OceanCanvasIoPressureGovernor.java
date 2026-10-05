package net.oceancanvas.mod.performance;

/**
 * OC-F217 I/O pressure governor.
 *
 * <p>CPU/tick pressure and storage pressure are different failure modes. This pure model consumes
 * only the queue facts the flattener already measures and returns an admission ceiling plus an
 * explicit I/O state. It can only hold or reduce admission; it can never increase the configured
 * rate, retire work, or mutate chunk state.</p>
 */
public class OceanCanvasIoPressureGovernor {
    protected OceanCanvasIoPressureGovernor(){}

    public enum State { CLEAR, ELEVATED, SATURATED, STALLED }
    public record Decision(State state,int admissionCap,double backlogFraction,long oldestLoadMs,String reason){
        public boolean constrained(){return state!=State.CLEAR;}
    }

    public static Decision evaluate(int requestedRate,int outstanding,int loadingNotQueued,int loadingStale,long oldestLoadingMs){
        int requested=Math.max(0,requestedRate);
        int live=Math.max(1,outstanding);
        int loading=Math.max(0,loadingNotQueued);
        int stale=Math.max(0,Math.min(loading,loadingStale));
        long age=Math.max(0L,oldestLoadingMs);
        double fraction=loading/(double)live;

        if(stale>0&&age>=10_000L)
            return new Decision(State.STALLED,0,fraction,age,"I/O stalled: at least one target has waited >=10s for FULL load");
        if(loading>=4&&stale>=Math.max(2,(loading+1)/2)&&age>=5_000L)
            return new Decision(State.SATURATED,0,fraction,age,"I/O saturated: majority of the loading cohort is stale");
        if(loading>=8||fraction>=0.33D||age>=2_500L){
            int cap=requested==0?0:Math.max(1,(int)Math.ceil(requested*0.50D));
            return new Decision(State.ELEVATED,cap,fraction,age,"I/O elevated: pending FULL-load backlog is accumulating");
        }
        return new Decision(State.CLEAR,requested,fraction,age,"I/O clear");
    }
}
