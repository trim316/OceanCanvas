package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/** OC-F090 bounded, human-readable world stewardship report. */
public final class OceanCanvasStewardshipReportService {
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final int MAX_ARCHIVES=32;
    private OceanCanvasStewardshipReportService() {}
    public record Result(Path file,long bytes,String summary) {}

    public static Result write(ServerLevel world,String reason) throws IOException {
        Path dir=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("stewardship");Files.createDirectories(dir);
        String stamp=STAMP.format(Instant.now());String body=markdown(world,reason);
        Path archive=dir.resolve("stewardship-"+stamp+".md");Files.writeString(archive,body,StandardCharsets.UTF_8);
        Path latest=dir.resolve("stewardship-latest.md");Files.writeString(latest,body,StandardCharsets.UTF_8);
        prune(dir);
        return new Result(archive,Files.size(archive),"Stewardship report written: "+archive.getFileName());
    }

    public static String markdown(ServerLevel world,String reason){
        var score=OceanCanvasWorldHealthScorecard.snapshot(world);var zones=OceanCanvasPlayerZones.get(world).all();var project=OceanCanvasProjectData.get(world);
        var work=OceanCanvasWorkspaceData.get(world);var history=OceanCanvasOperationHistoryData.get(world).recent();var compat=net.oceancanvas.mod.project.OceanCanvasHarnessService.compatibilityMatrix(world);
        var canary=OceanCanvasBoundaryCanaryData.get(world).latestResult();var gates=OceanCanvasWorkflowGateService.evaluate(world,"STEWARDSHIP_REPORT");
        StringBuilder s=new StringBuilder();
        s.append("# Ocean Canvas World Stewardship Report\n\n");
        s.append("Generated: ").append(Instant.now()).append("  \nBuild: ").append(OceanCanvas.VERSION).append("  \nReason: ").append(safe(reason)).append("\n\n");
        s.append("## Stewardship health\n\n");
        for(var c:score.components())s.append("- **").append(c.label()).append(" — ").append(c.state()).append("**: ").append(c.detail()).append('\n');
        s.append("\n## Regions and maintenance\n\n");
        s.append("Defined regions: ").append(zones.size()).append("; protected: ").append(zones.stream().filter(OceanCanvasPlayerZones.Zone::protectedNow).count()).append(".\n\n");
        for(var z:zones.stream().sorted(Comparator.comparing(v->v.name().toLowerCase(Locale.ROOT))).toList()){
            var meta=project.regionMeta(z.name());s.append("- ").append(z.name()).append(" — ").append(z.protectedNow()?"PROTECTED":"DRAFT")
                    .append(meta==null?"":"; stage="+meta.stage()).append("; chunks=").append(z.exactChunks().size()).append('\n');
        }
        s.append("\nProjects: ").append(work.projects().size()).append("; tasks: ").append(work.tasks().size()).append(".\n");
        s.append("Workflow gate: ").append(gates.pass()?"PASS":"BLOCKED").append(" — ").append(gates.summary()).append("\n");
        if(canary!=null)s.append("Boundary canary: ").append(canary.state()).append(" — ").append(canary.detail()).append("\n");
        var ownership=OceanCanvasOperationOwnership.snapshot(world);if(ownership.active())s.append("Active operation owner: ").append(ownership.kind()).append(" — ").append(ownership.requesterDisplay()).append(" — ").append(ownership.authority()).append("\n");
        var tickets=OceanCanvasTicketOwnershipInspector.snapshot(world,32);s.append("Ocean Canvas ticket/lease ownership: ").append(tickets.summary()).append("\n");
        for(var t:tickets.entries())s.append("  - ").append(t.pool()).append(" @ ").append(t.chunkX()).append(',').append(t.chunkZ()).append(" age=").append(t.ageMillis()).append("ms; ").append(t.purpose()).append("; releases when ").append(t.releaseCondition()).append('\n');

        s.append("\n## Upgrade / compatibility evidence\n\n");
        if(compat.isEmpty())s.append("- No compatibility matrix evidence recorded for this environment.\n");
        else for(var r:compat)s.append("- ").append(r.capability()).append(" — **").append(r.status()).append("**: ").append(r.evidence()).append('\n');

        s.append("\n## Recent destructive / lifecycle operations\n\n");
        if(history.isEmpty())s.append("- No operation history recorded.\n");
        else for(int i=0;i<Math.min(30,history.size());i++){var h=history.get(i);s.append("- ").append(Instant.ofEpochMilli(h.epochMillis())).append(" — ").append(h.kind()).append(' ').append(h.phase())
                .append(" by ").append(h.requester()).append(": ").append(h.detail());if(h.hasScope())s.append(" [").append(h.scopeType()).append(' ').append(h.scopeId()).append(']');s.append('\n');}
        s.append("\n---\nThis report is metadata/diagnostic evidence. It does not claim physical terrain correctness unless a physical scanner produced that evidence.\n");
        return s.toString();
    }

    private static void prune(Path dir){
        try(var stream=Files.list(dir)){var files=stream.filter(p->p.getFileName().toString().startsWith("stewardship-")&&p.getFileName().toString().endsWith(".md")&&!p.getFileName().toString().equals("stewardship-latest.md")).sorted(Comparator.comparingLong(OceanCanvasStewardshipReportService::mtime).reversed()).toList();for(int i=MAX_ARCHIVES;i<files.size();i++)Files.deleteIfExists(files.get(i));}
        catch(IOException ignored){}
    }
    private static long mtime(Path p){try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0L;}}
    private static String safe(String s){return s==null?"":s;}
}
