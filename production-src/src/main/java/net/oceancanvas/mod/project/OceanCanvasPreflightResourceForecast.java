package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.performance.OceanCanvasPregenMetrics;

import java.util.Locale;

/**
 * OC-F206 server-authored resource forecast for an operation preview.
 *
 * <p>This is deliberately a range, not a promise. Duration uses the exact machine calibration
 * when one is available for the current Pregen profile; otherwise the result says UNVERIFIED and
 * falls back to a conservative configured-rate envelope. Disk and heap are likewise bounded
 * planning estimates. They are never used to bypass admission/backpressure.</p>
 */
public final class OceanCanvasPreflightResourceForecast {
    private OceanCanvasPreflightResourceForecast() {}

    public record Forecast(long chunks,long expectedChunkWrites,double diskLowMiB,double diskHighMiB,
                           long durationLowSeconds,long durationHighSeconds,long heapUsedMiB,long heapMaxMiB,
                           long projectedPeakMiB,String calibration,String risk,String detail) {
        public Forecast {
            calibration=safe(calibration);risk=safe(risk);detail=safe(detail);
        }
        public String compact(){
            String duration=durationLowSeconds<0?"duration unverified":OceanCanvasPregenMetrics.duration(durationLowSeconds)+"–"+OceanCanvasPregenMetrics.duration(durationHighSeconds);
            return String.format(Locale.ROOT,"%d chunk write(s) · disk %.0f–%.0f MiB · %s · projected heap %d/%d MiB · %s risk · %s",
                    expectedChunkWrites,diskLowMiB,diskHighMiB,duration,projectedPeakMiB,heapMaxMiB,risk,calibration);
        }
    }

    public static Forecast estimate(ServerLevel world,String kind,long chunks){
        long safeChunks=Math.max(0L,chunks);
        var project=OceanCanvasProjectData.get(world);
        var benchmark=project.benchmark();
        String profile=project.pregenProfile().name();
        boolean calibrated=benchmark!=null && benchmark.calibrationVersion()==OceanCanvasPregenMetrics.CALIBRATION_VERSION
                && benchmark.successfulRuns()>0 && benchmark.samples()>=10 && benchmark.sustainableChunksPerSecond()>0.01D
                && profile.equalsIgnoreCase(benchmark.observedProfile());
        double cps;
        String calibration;
        if(calibrated){
            cps=Math.max(0.01D,benchmark.sustainableChunksPerSecond());
            calibration="CALIBRATED "+String.format(Locale.ROOT,"%.1f chunks/s (%d samples)",cps,benchmark.samples());
        }else{
            int configured=Math.max(1,OceanCanvasConfig.get().pregenChunksPerTick());
            // Configured submissions/tick are not completion throughput. Treat one quarter of the
            // theoretical rate as the centre of a deliberately broad unverified envelope.
            cps=Math.max(0.25D,configured*5.0D);
            calibration="UNVERIFIED · no current machine calibration for "+profile;
        }
        long low=safeChunks==0?0L:(long)Math.ceil(safeChunks/(calibrated?cps*1.15D:cps));
        long high=safeChunks==0?0L:(long)Math.ceil(safeChunks/(calibrated?Math.max(0.01D,cps*0.55D):Math.max(0.01D,cps*0.20D)));

        // Region files vary wildly with structures/entities/palettes. Keep the range intentionally
        // broad and label it planning-only; expectedChunkWrites is the authoritative useful count.
        double diskLow=safeChunks*0.05D;
        double diskHigh=safeChunks*1.50D;
        Runtime rt=Runtime.getRuntime();
        long used=Math.max(0L,(rt.totalMemory()-rt.freeMemory())/(1024L*1024L));
        long max=Math.max(1L,rt.maxMemory()/(1024L*1024L));
        int preferred=calibrated?Math.max(1,benchmark.preferredOutstanding()):Math.max(8,OceanCanvasConfig.get().pregenChunksPerTick()*4);
        // ~2 MiB per outstanding target is a safety-planning allowance, not a measured allocation.
        long projected=Math.min(Long.MAX_VALUE/2,used+preferred*2L);
        double heapFraction=projected/(double)max;
        String risk;
        if(heapFraction>=0.85D || safeChunks>=500_000L || !calibrated) risk="HIGH";
        else if(heapFraction>=0.70D || safeChunks>=100_000L) risk="MEDIUM";
        else risk="LOW";
        String detail="Forecast only; scheduler still yields to MSPT, heap, I/O and cleanup. kind="+safe(kind)+", preferredOutstanding="+preferred;
        return new Forecast(safeChunks,safeChunks,diskLow,diskHigh,low,Math.max(low,high),used,max,projected,calibration,risk,detail);
    }

    private static String safe(String s){return s==null?"":s;}
}
