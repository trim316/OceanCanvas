package net.oceancanvas.mod.performance;

import java.util.Arrays;
import java.util.Locale;

/** Pure bounded measurement/model code; never changes a job's rate or completion state. */
public class OceanCanvasPregenMetrics {
    public static final int CALIBRATION_VERSION=1;
    public static final int MIN_CALIBRATION_NEW_CHUNKS=256;
    protected OceanCanvasPregenMetrics() {}

    public record Estimate(double chunksPerSecond,long lowSeconds,long highSeconds,String quality) {}

    /** A conservative, profile-specific summary from successful new work only. */
    public record Calibration(double chunksPerSecond,double cadenceMs,int preferredOutstanding,
                              int successfulRuns,int samples,String profile) {
        public boolean usableFor(String selectedProfile){
            return successfulRuns>0&&samples>=10&&Double.isFinite(chunksPerSecond)&&chunksPerSecond>0
                    &&Double.isFinite(cadenceMs)&&cadenceMs>0&&profile!=null&&profile.equals(selectedProfile);
        }
    }

    /** Merge only same-profile, successful samples; the bounded weight avoids a very old run dominating forever. */
    public static Calibration mergeCalibration(Calibration prior,String profile,double chunksPerSecond,double cadenceMs,
                                               int preferredOutstanding,int samples){
        if(profile==null||profile.isBlank()||!Double.isFinite(chunksPerSecond)||chunksPerSecond<=0
                ||!Double.isFinite(cadenceMs)||cadenceMs<=0||samples<10)return prior;
        int safeOutstanding=Math.max(1,Math.min(512,preferredOutstanding));
        if(prior==null||!prior.usableFor(profile))return new Calibration(chunksPerSecond,cadenceMs,safeOutstanding,1,samples,profile);
        int oldWeight=Math.min(240,prior.samples()),newWeight=Math.min(60,samples),weight=oldWeight+newWeight;
        return new Calibration((prior.chunksPerSecond()*oldWeight+chunksPerSecond*newWeight)/weight,
                (prior.cadenceMs()*oldWeight+cadenceMs*newWeight)/weight,
                Math.max(1,Math.min(512,(prior.preferredOutstanding()*oldWeight+safeOutstanding*newWeight)/weight)),
                Math.min(100,prior.successfulRuns()+1),Math.min(300,prior.samples()+samples),profile);
    }

    /** Never raises user configuration: a learned sample can only start more cautiously. */
    public static int conservativeStartRate(Calibration calibration,String profile,int configuredRate,int profileCap){
        int configured=Math.max(1,Math.min(Math.max(1,profileCap),configuredRate));
        if(calibration==null||!calibration.usableFor(profile))return configured;
        int learned=(int)Math.ceil(calibration.chunksPerSecond()/20.0D);
        return Math.max(1,Math.min(configured,Math.min(Math.max(1,profileCap),learned)));
    }

    /** Last 60 wall-time samples. Skipped/already-sealed work must be excluded by the caller. */
    public static final class RateWindow {
        private final double[] rates=new double[60],seconds=new double[60];
        private int size,cursor;
        private long started,anchor,lastProgress,lastCompleted;
        private long anchorCompleted;

        public RateWindow(long now){started=anchor=lastProgress=now;}
        public void observe(long now,long completed) {
            completed=Math.max(0,completed);
            if(now<anchor || completed<lastCompleted){reset(now,completed);return;}
            if(completed>lastCompleted)lastProgress=now;
            lastCompleted=completed;
            double elapsed=(now-anchor)/1_000_000_000.0;
            if(elapsed<1.0)return;
            // A menu pause/debugger gap is not a sustainable-throughput sample.
            if(elapsed>10.0){reset(now,completed);return;}
            rates[cursor]=(completed-anchorCompleted)/elapsed;seconds[cursor]=elapsed;
            cursor=(cursor+1)%rates.length;size=Math.min(rates.length,size+1);
            anchor=now;anchorCompleted=completed;
        }
        /** v253.125.30: lighting publication is genuine job progress even when the
         * terrain-completion rate is momentarily flat. This only updates the liveness
         * clock; chunks/second remains terrain-only and cannot be inflated by relights. */
        public void noteProgress(long now){if(now>=lastProgress)lastProgress=now;}
        private void reset(long now,long completed){
            size=cursor=0;started=anchor=lastProgress=now;lastCompleted=anchorCompleted=completed;
        }
        public long noProgressSeconds(long now){return Math.max(0,(now-lastProgress)/1_000_000_000L);}
        public int sampleCount(){return size;}
        public double mean(){
            double work=0,time=0;for(int i=0;i<size;i++){work+=rates[i]*seconds[i];time+=seconds[i];}
            return time==0?0:work/time;
        }
        public boolean hasSufficientSamples(long now){return size>=10 && now-started>=10_000_000_000L && mean()>0;}
        public Estimate estimate(long now,long remaining,boolean draining,boolean paused){
            double mean=mean();
            if(draining)return new Estimate(mean,-1,-1,"FINAL_DRAIN");
            if(paused)return new Estimate(mean,-1,-1,"THROTTLED");
            if(remaining<=0)return new Estimate(mean,-1,-1,"FINALIZING");
            if(noProgressSeconds(now)>=30)return new Estimate(mean,-1,-1,"NO_RECENT_COMPLETIONS");
            if(!hasSufficientSamples(now))return new Estimate(mean,-1,-1,"WARMING_UP");
            double[] sorted=Arrays.copyOf(rates,size);Arrays.sort(sorted);
            double lowRate=sorted[(int)Math.floor((size-1)*0.10)],highRate=sorted[(int)Math.ceil((size-1)*0.90)];
            if(highRate<=0)return new Estimate(mean,-1,-1,"NO_RECENT_COMPLETIONS");
            long low=duration(remaining,highRate),high=lowRate>0?duration(remaining,lowRate):-1;
            return new Estimate(mean,low,high,lowRate>0&&highRate/lowRate<=2.0?"STEADY_SAMPLE":"VARIABLE_SAMPLE");
        }
        private static long duration(long remaining,double rate){
            double seconds=Math.ceil(remaining/rate);
            return Double.isFinite(seconds)&&seconds<Long.MAX_VALUE?(long)Math.max(1,seconds):Long.MAX_VALUE;
        }
    }

    /** Hook-span work time is distinct from start-to-start tick cadence (which includes sleep). */
    public static final class TickTiming {
        private long start,previousStart;
        private boolean started,previous;
        private double workMs=-1,intervalMs=-1;
        public void begin(long now){
            if(previous && now>previousStart && now-previousStart<=10_000_000_000L)
                intervalMs=ema(intervalMs,(now-previousStart)/1_000_000.0);
            else if(previous)intervalMs=-1;
            start=previousStart=now;started=previous=true;
        }
        public void end(long now){
            if(started&&now>=start)workMs=ema(workMs,(now-start)/1_000_000.0);
            started=false;
        }
        private static double ema(double old,double sample){return old<0?sample:old*0.8+sample*0.2;}
        public double workMs(){return workMs;}
        public double intervalMs(){return intervalMs;}
    }

    public static long completedNewWork(long handled,long resumedBase,long skipped,int outstanding){
        long issued=Math.max(0,handled-Math.max(0,resumedBase)-Math.max(0,skipped));
        return Math.max(0,issued-Math.max(0,outstanding));
    }
    public static long settledEstimate(long handled,long total,int outstanding){
        return Math.max(0,Math.min(total,handled-Math.max(0,outstanding)));
    }

    public record Snapshot(String kind,String profile,String phase,String reason,
                           long handled,long total,long skipped,long settled,
                           int rate,int rateCap,int outstanding,int queued,int sharedQueue,int finalDrainTickets,
                           double tickWorkMs,double tickIntervalMs,double heapFraction,long heapUsedMiB,long heapMaxMiB,double chunksPerSecond,
                           long elapsedSeconds,long noProgressSeconds,long etaLow,long etaHigh,String etaQuality) {
        public static Snapshot idle(){return new Snapshot("","","IDLE","No active Pregen job",0,0,0,0,0,0,0,0,0,0,-1,-1,0,0,0,0,0,0,-1,-1,"UNAVAILABLE");}
        public boolean active(){return !kind.isEmpty();}
        public String encode(){
            return String.join("\t","2",kind,profile,phase,reason,Long.toString(handled),Long.toString(total),Long.toString(skipped),Long.toString(settled),
                    Integer.toString(rate),Integer.toString(rateCap),Integer.toString(outstanding),Integer.toString(queued),Integer.toString(sharedQueue),Integer.toString(finalDrainTickets),
                    Double.toString(tickWorkMs),Double.toString(tickIntervalMs),Double.toString(heapFraction),Long.toString(heapUsedMiB),Long.toString(heapMaxMiB),Double.toString(chunksPerSecond),
                    Long.toString(elapsedSeconds),Long.toString(noProgressSeconds),Long.toString(etaLow),Long.toString(etaHigh),etaQuality);
        }
        public static Snapshot decode(String packed){
            try{
                String[] p=packed.split("\\t",-1);
                if(p.length==26&&p[0].equals("2")){
                    double work=Double.parseDouble(p[15]),interval=Double.parseDouble(p[16]),heap=Double.parseDouble(p[17]),rate=Double.parseDouble(p[20]);
                    if(!Double.isFinite(work)||!Double.isFinite(interval)||!Double.isFinite(heap)||!Double.isFinite(rate))return idle();
                    return new Snapshot(p[1],p[2],p[3],p[4],Long.parseLong(p[5]),Long.parseLong(p[6]),Long.parseLong(p[7]),Long.parseLong(p[8]),
                            Integer.parseInt(p[9]),Integer.parseInt(p[10]),Integer.parseInt(p[11]),Integer.parseInt(p[12]),Integer.parseInt(p[13]),Integer.parseInt(p[14]),
                            work,interval,heap,Long.parseLong(p[18]),Long.parseLong(p[19]),rate,
                            Long.parseLong(p[21]),Long.parseLong(p[22]),Long.parseLong(p[23]),Long.parseLong(p[24]),p[25]);
                }
                if(p.length==23&&p[0].equals("1")){
                    double work=Double.parseDouble(p[14]),interval=Double.parseDouble(p[15]),heap=Double.parseDouble(p[16]),rate=Double.parseDouble(p[17]);
                    if(!Double.isFinite(work)||!Double.isFinite(interval)||!Double.isFinite(heap)||!Double.isFinite(rate))return idle();
                    return new Snapshot(p[1],p[2],p[3],p[4],Long.parseLong(p[5]),Long.parseLong(p[6]),Long.parseLong(p[7]),Long.parseLong(p[8]),
                            Integer.parseInt(p[9]),Integer.parseInt(p[10]),Integer.parseInt(p[11]),Integer.parseInt(p[12]),Integer.parseInt(p[13]),0,
                            work,interval,heap,0,0,rate,Long.parseLong(p[18]),Long.parseLong(p[19]),Long.parseLong(p[20]),Long.parseLong(p[21]),p[22]);
                }
            }catch(RuntimeException ignored){}
            return idle();
        }
}
    public static String duration(long seconds){
        if(seconds<0)return "unknown";
        if(seconds<60)return seconds+"s";
        if(seconds<3600)return (seconds/60)+"m "+(seconds%60)+"s";
        if(seconds<86400)return (seconds/3600)+"h "+((seconds%3600)/60)+"m";
        return (seconds/86400)+"d "+((seconds%86400)/3600)+"h";
    }
    public static String decimal(double number){return number<0?"unavailable":String.format(Locale.ROOT,"%.1f",number);}
}
