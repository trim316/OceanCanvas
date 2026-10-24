package net.oceancanvas.mod.diagnostic;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OC-F045 performance replay recorder.
 *
 * <p>Samples the already-existing read-only Pregen performance snapshot at most
 * once per second, retains at most ten minutes, and performs no extra chunk or
 * world access. The resulting TSV can be validated offline through
 * {@link OceanCanvasHarnessCore#validateReplay(List)}.</p>
 */
public final class OceanCanvasPerformanceReplayRecorder {
    private static final int MAX_SAMPLES=600;
    private static final long SAMPLE_NS=1_000_000_000L;
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final ArrayDeque<OceanCanvasHarnessCore.ReplaySample> SAMPLES=new ArrayDeque<>();
    private static long lastSampleNanos;
    private static long operationStartNanos;
    private static String lastKind="";

    private OceanCanvasPerformanceReplayRecorder(){}

    public static void register(){
        ServerTickEvents.END_SERVER_TICK.register(server->{
            long now=System.nanoTime();
            if(lastSampleNanos!=0&&now-lastSampleNanos<SAMPLE_NS)return;
            lastSampleNanos=now;
            var p=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.performanceSnapshot();
            if(p==null||!p.active()){
                lastKind=""; operationStartNanos=0L;
                return;
            }
            if(operationStartNanos==0L||!p.kind().equals(lastKind)){
                operationStartNanos=now; lastKind=p.kind();
            }
            add(new OceanCanvasHarnessCore.ReplaySample(
                    Math.max(0L,(now-operationStartNanos)/1_000_000L),p.kind(),p.phase(),p.handled(),p.total(),
                    p.outstanding(),p.queued(),p.finalDrainTickets(),p.heapFraction(),p.tickWorkMs(),p.tickIntervalMs(),
                    p.chunksPerSecond(),p.noProgressSeconds()));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{lastSampleNanos=0L;operationStartNanos=0L;lastKind="";});
    }

    private static synchronized void add(OceanCanvasHarnessCore.ReplaySample sample){
        SAMPLES.addLast(sample);while(SAMPLES.size()>MAX_SAMPLES)SAMPLES.removeFirst();
    }

    public static synchronized List<OceanCanvasHarnessCore.ReplaySample> snapshot(){return List.copyOf(SAMPLES);}
    public static synchronized void clear(){SAMPLES.clear();}

    public static OceanCanvasHarnessCore.ReplayValidation validate(){return OceanCanvasHarnessCore.validateReplay(snapshot());}

    public static String tsv(){
        StringBuilder s=new StringBuilder();
        s.append("# Ocean Canvas performance replay\n# schema=").append(OceanCanvasHarnessCore.SCHEMA)
                .append(" build=").append(OceanCanvas.VERSION).append(" samples=").append(snapshot().size()).append('\n');
        s.append("elapsedMillis\tkind\tphase\thandled\ttotal\toutstanding\tqueued\tfinalDrainTickets\theapFraction\ttickWorkMs\ttickIntervalMs\tchunksPerSecond\tnoProgressSeconds\n");
        for(var q:snapshot())s.append(q.elapsedMillis()).append('\t').append(safe(q.kind())).append('\t').append(safe(q.phase())).append('\t')
                .append(q.handled()).append('\t').append(q.total()).append('\t').append(q.outstanding()).append('\t').append(q.queued()).append('\t')
                .append(q.finalDrainTickets()).append('\t').append(q.heapFraction()).append('\t').append(q.tickWorkMs()).append('\t')
                .append(q.tickIntervalMs()).append('\t').append(q.chunksPerSecond()).append('\t').append(q.noProgressSeconds()).append('\n');
        return s.toString();
    }

    public static Path export(ServerLevel world) throws IOException{
        Path dir=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("diagnostics").resolve("replays");
        Files.createDirectories(dir);
        Path out=dir.resolve("performance-replay-"+STAMP.format(Instant.now())+".tsv");
        Files.writeString(out,tsv(),StandardCharsets.UTF_8);
        return out;
    }

    private static String safe(String s){return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ');}
}
