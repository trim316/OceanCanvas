package net.oceancanvas.mod.diagnostic;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OC-F223 exact Pregen ownership decision recorder.
 *
 * <p>The old performance replay sampled aggregate controller state once per
 * second. That is useful for throughput diagnosis but cannot reproduce a rare
 * admission/retirement ordering bug. This recorder is called at the two real
 * ownership boundaries in {@code OceanCanvasSurfaceFlattener}: the instant a
 * target first enters PREGEN_TARGET_CHUNKS and the instant authoritative
 * physical audit retires it. The monotonic sequence therefore preserves the
 * exact order Ocean Canvas itself observed, including multiple changes in one
 * server tick.</p>
 *
 * <p>The recorder is diagnostic only: no chunk access, tickets, futures or
 * world mutation. A bounded in-memory ring prevents long Pregen runs from
 * growing without limit.</p>
 */
public final class OceanCanvasOperationDecisionRecorder {
    private static final int MAX_EVENTS=50_000;
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final ArrayDeque<Event> EVENTS=new ArrayDeque<>();
    private static long sequence;

    public record Event(long sequence,long nanoTime,String action,long chunk,String source,String kind,String phase) {
        public int chunkX(){return ChunkPos.getX(chunk);} public int chunkZ(){return ChunkPos.getZ(chunk);}
    }
    public record Validation(int events,int admissions,int retirements,int liveTargets,List<String> issues,long checksum){
        public Validation{issues=List.copyOf(issues);} public boolean passed(){return events>0&&issues.isEmpty();}
    }

    private OceanCanvasOperationDecisionRecorder(){}

    public static void register(){ServerLifecycleEvents.SERVER_STOPPED.register(server->clear());}

    public static void recordAdmission(long packed,String source){record("ADMIT",packed,source);}
    public static void recordRetirement(long packed,String source){record("RETIRE",packed,source);}
    public static void recordDeferral(long packed,String source){record("DEFER",packed,source);}
    public static void recordCancellation(long packed,String source){record("CANCEL",packed,source);}

    private static synchronized void record(String action,long packed,String source){
        String kind="",phase="";
        try{
            // v253.125.31: ownership-decision replay needs action/chunk/source, not a
            // full performance snapshot. The .30 diagnostic caught this observability
            // hook doing expensive status enumeration on the server thread.
            var overlay=net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.overlaySnapshot();if(overlay!=null)kind=overlay.kind();
        }catch(Throwable ignored){}
        EVENTS.addLast(new Event(++sequence,System.nanoTime(),action,packed,source==null?"":source,kind==null?"":kind,phase==null?"":phase));
        while(EVENTS.size()>MAX_EVENTS)EVENTS.removeFirst();
    }

    public static synchronized List<Event> snapshot(){return List.copyOf(EVENTS);}
    public static synchronized void clear(){EVENTS.clear();sequence=0L;}

    /** Deterministically replays the exact ownership decisions and detects duplicate admission/retirement. */
    public static Validation validate(){return validate(snapshot());}
    public static Validation validate(List<Event> input){
        List<Event> events=input==null?List.of():List.copyOf(input);
        var raw=new ArrayList<OceanCanvasHarnessCore.OwnershipDecision>(events.size());
        for(Event e:events)raw.add(new OceanCanvasHarnessCore.OwnershipDecision(e.sequence(),e.action(),e.chunk()));
        var replay=OceanCanvasHarnessCore.replayOwnershipDecisions(raw);
        List<String> issues=new ArrayList<>();for(var i:replay.issues())issues.add(i.id()+": "+i.detail());
        return new Validation(replay.events(),replay.admissions(),replay.retirements(),replay.liveTargets(),issues,replay.checksum());
    }

    public static String tsv(){
        StringBuilder s=new StringBuilder("# Ocean Canvas exact operation decision replay\n# build=").append(OceanCanvas.VERSION).append(" events=").append(snapshot().size()).append('\n');
        s.append("sequence\tnanoTime\taction\tchunkX\tchunkZ\tpacked\tsource\tkind\tphase\n");
        for(Event e:snapshot())s.append(e.sequence()).append('\t').append(e.nanoTime()).append('\t').append(e.action()).append('\t')
                .append(e.chunkX()).append('\t').append(e.chunkZ()).append('\t').append(e.chunk()).append('\t').append(safe(e.source())).append('\t')
                .append(safe(e.kind())).append('\t').append(safe(e.phase())).append('\n');
        return s.toString();
    }

    public static Path export(ServerLevel world)throws IOException{
        Path dir=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("diagnostics").resolve("replays");Files.createDirectories(dir);
        Path out=dir.resolve("operation-decisions-"+STAMP.format(Instant.now())+".tsv");Files.writeString(out,tsv(),StandardCharsets.UTF_8);return out;
    }

    private static String safe(String s){return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ');}
    private static long mix(long h,long v){h^=v+0x9e3779b97f4a7c15L+(h<<6)+(h>>>2);return h;}
}
