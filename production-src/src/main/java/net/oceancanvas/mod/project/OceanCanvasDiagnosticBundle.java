package net.oceancanvas.mod.project;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bounded support bundle. It intentionally exports Ocean Canvas metadata and filtered diagnostics,
 * never region/chunk terrain files and never unrelated logs/configuration.
 */
public final class OceanCanvasDiagnosticBundle {
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final int MAX_LOG_LINES=1200;
    private OceanCanvasDiagnosticBundle(){}

    public record Result(Path file,String supportCode,long bytes){}

    public static Result create(ServerLevel world) throws IOException {
        Path dir=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("diagnostics");
        Files.createDirectories(dir);
        String stamp=STAMP.format(Instant.now());
        String code="OC-D-"+stamp;
        Path out=dir.resolve("oceancanvas-diagnostics-"+stamp+".zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(out),StandardCharsets.UTF_8)){
            text(zip,"summary.txt",summary(world,code));
            text(zip,"health.txt",health(world));
            text(zip,"workspace.txt",workspace(world));
            text(zip,"release-readiness.txt",releaseReadiness(world));
            text(zip,"reliability-harness.txt",net.oceancanvas.mod.project.OceanCanvasHarnessService.report(world));
            text(zip,"stewardship-report.md",OceanCanvasStewardshipReportService.markdown(world,"diagnostic bundle"));
            text(zip,"support-explainer.txt",OceanCanvasP1W3Service.supportExplainer(world));
            text(zip,"performance-replay.tsv",net.oceancanvas.mod.diagnostic.OceanCanvasPerformanceReplayRecorder.tsv());
            text(zip,"operation-black-box.tsv",net.oceancanvas.mod.project.OceanCanvasPerformanceEngine.blackBoxTsv());
            text(zip,"performance-engine.txt",performanceEngine(world));
            text(zip,"contained-incidents.tsv",net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.text());
            text(zip,"relevant-latest-log.txt",relevantLog());
            Path config=FabricLoader.getInstance().getConfigDir().resolve("oceancanvas.properties");
            if(Files.isRegularFile(config))boundedFile(zip,"oceancanvas.properties",config,256*1024);
            text(zip,"README.txt","Ocean Canvas diagnostic bundle\nSupport code: "+code+"\n\nStart with support-explainer.txt for a concise human-readable interpretation. This bundle intentionally excludes Minecraft region/chunk terrain, player data, unrelated mod configs, and unrelated log lines.\n");
        }
        return new Result(out,code,Files.size(out));
    }

    private static String summary(ServerLevel world,String code){
        OceanCanvasConfig c=OceanCanvasConfig.get();
        var project=OceanCanvasProjectData.get(world);var telemetry=OceanCanvasTerrainOperationPerformance.telemetrySnapshot();
        String minecraft=FabricLoader.getInstance().getModContainer("minecraft").map(v->v.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        String fabric=FabricLoader.getInstance().getModContainer("fabric-api").map(v->v.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        StringBuilder s=new StringBuilder();
        s.append("Ocean Canvas support code: ").append(code).append('\n');
        s.append("Ocean Canvas build: ").append(OceanCanvas.VERSION).append('\n');
        s.append("Forever World UUID: ").append(OceanCanvasForeverWorldStewardshipData.get(world).identity().worldUuid()).append('\n');
        s.append("Minecraft: ").append(minecraft).append("\nFabric API: ").append(fabric).append('\n');
        s.append("World dimension: ").append(world.dimension()).append('\n');
        s.append("Canvas: ").append(c.canvasSize()).append('x').append(c.canvasSize()).append(" centered ").append(c.centerX()).append(',').append(c.centerZ()).append('\n');
        s.append("Ocean floor: Y ").append(c.oceanFloorY()).append(" +/- ").append(c.oceanFloorVariation()).append(" transition ").append(c.oceanFloorTransitionThickness()).append('\n');
        s.append("Pregen enabled: ").append(c.pregenEnabled()).append(" configured chunks/tick: ").append(c.pregenChunksPerTick()).append(" profile: ").append(project.pregenProfile()).append('\n');
        s.append("Forever-world target: ").append(c.foreverWorldTargetHours()).append("h; full ").append(c.canvasSize()).append("x").append(c.canvasSize())
                .append(" requires ").append(String.format(Locale.ROOT,"%.2f",net.oceancanvas.mod.util.OceanCanvasScaleBudget.requiredChunksPerSecond(c.canvasSize(),c.foreverWorldTargetHours())))
                .append(" chunks/s before retry/recovery overhead.\n");
        s.append("Biome mask: ").append(c.biomeMaskEnabled()).append(" -> ").append(c.biomeMaskBiome()).append('\n');
        s.append("World policy: ").append(net.oceancanvas.mod.worldgen.OceanCanvasWorldPolicy.canonical()).append('\n');
        s.append("Shipwrecks: ").append(c.shipwrecksRule()).append(" expansion: ").append(c.expansionEnabled()).append(" taper: ").append(c.taperEnabled()).append('\n');
        s.append("Regions: ").append(OceanCanvasPlayerZones.get(world).all().size()).append(" current project region: ").append(project.currentProject()).append('\n');
        if(telemetry==null)s.append("Active operation: none\n");
        else s.append("Active operation: ").append(telemetry.kind()).append(" submitted=").append(telemetry.submittedChunks()).append('/').append(telemetry.totalChunks())
                .append(" adaptiveRate=").append(telemetry.adaptiveRatePerTick()).append(" outstanding=").append(telemetry.outstandingChunks())
                .append(" heap=").append(String.format(Locale.ROOT,"%.3f",telemetry.heapUseFraction())).append(" tickMsEma=").append(String.format(Locale.ROOT,"%.2f",telemetry.tickMsEma())).append('\n');
        var resourceBudget=OceanCanvasTerrainOperationPerformance.resourceBudgetSnapshot();
        s.append("Admission resource budget: state=").append(resourceBudget.state())
                .append(" limiter=").append(resourceBudget.limiter()).append(" cap=").append(resourceBudget.admissionCap()).append('/').append(resourceBudget.requestedRate())
                .append(" cpu=").append(String.format(Locale.ROOT,"%.2f",resourceBudget.cpuWorkMs())).append("ms")
                .append(" heap=").append(String.format(Locale.ROOT,"%.3f",resourceBudget.heapUseFraction()))
                .append(" tickets=").append(resourceBudget.transientTickets()).append('/').append(resourceBudget.ticketHardLimit())
                .append(" reason=").append(resourceBudget.reason()).append('\n');
        var b=project.benchmark();
        if(b!=null)s.append("Benchmark: cps=").append(String.format(Locale.ROOT,"%.2f",b.sustainableChunksPerSecond())).append(" healthyTickMs=").append(String.format(Locale.ROOT,"%.2f",b.healthyTickMs())).append(" preferredOutstanding=").append(b.preferredOutstanding()).append(" samples=").append(b.samples()).append('\n');
        return s.toString();
    }

    private static String health(ServerLevel world){
        var deep=OceanCanvasDeepHealthService.scan(world,128);var links=OceanCanvasAssetIntegrityService.scan(world,128);
        StringBuilder s=new StringBuilder("Metadata Health\n");
        s.append("explicit=").append(deep.explicitStates()).append(" healthy=").append(deep.healthyExplicit()).append(" mismatches=").append(deep.mismatches()).append(" legacyUnverified=").append(deep.legacyUnverified()).append(" truncated=").append(deep.truncated()).append('\n');
        for(var f:deep.findings())s.append("OC-H-META chunk ").append(f.chunkX()).append(',').append(f.chunkZ()).append(" classification=").append(f.classification()).append(" state=").append(f.terrainState()).append(" processed=").append(f.processed()).append(" repairable=").append(f.repairable()).append(" detail=").append(f.detail()).append('\n');
        s.append("\nPlan/Project integrity\nobjects=").append(links.planningObjects()).append(" terrainAssets=").append(links.terrainAssets()).append(" projects=").append(links.projects()).append(" tasks=").append(links.tasks()).append(" issues=").append(links.issues()).append('\n');
        for(var f:links.findings())s.append("OC-H-LINK ").append(f.kind()).append(' ').append(f.id()).append(": ").append(f.detail()).append('\n');
        return s.toString();
    }

    private static String performanceEngine(ServerLevel world){
        var r=net.oceancanvas.mod.project.OceanCanvasPerformanceEngine.summary(world);
        StringBuilder s=new StringBuilder("P2 Performance Engine\n");
        s.append("io=").append(r.io().state()).append(" oldestLoadMs=").append(r.io().oldestLoadMs()).append(" backlogFraction=").append(String.format(Locale.ROOT,"%.3f",r.io().backlogFraction())).append('\n');
        s.append("baseline=").append(r.baseline().state()).append(" throughputRatio=").append(String.format(Locale.ROOT,"%.3f",r.baseline().throughputRatio())).append(" cadenceRatio=").append(String.format(Locale.ROOT,"%.3f",r.baseline().cadenceRatio())).append(" | ").append(r.baseline().detail()).append('\n');
        s.append("decay=").append(r.decay().state()).append(" earlyCps=").append(String.format(Locale.ROOT,"%.2f",r.decay().earlyCps())).append(" recentCps=").append(String.format(Locale.ROOT,"%.2f",r.decay().recentCps())).append(" ratio=").append(String.format(Locale.ROOT,"%.3f",r.decay().ratio())).append('\n');
        s.append("blackBoxSamples=").append(r.blackBoxSamples()).append('\n');
        for(var b:r.budgets())s.append("BUDGET ").append(b.id()).append(' ').append(b.state()).append(" observed=").append(b.observed()).append(b.unit()).append(" limit=").append(b.limit()).append(b.unit()).append(" | ").append(b.detail()).append('\n');
        for(var p:r.phases())s.append("PHASE ").append(p.phase()).append(" samples=").append(p.samples()).append(" emaMs=").append(String.format(Locale.ROOT,"%.3f",p.emaMs())).append(" maxMs=").append(String.format(Locale.ROOT,"%.3f",p.maxMs())).append('\n');
        for(var h:r.hotRegions())s.append("HOT [").append(h.minChunkX()).append(',').append(h.minChunkZ()).append("]..[").append(h.maxChunkX()).append(',').append(h.maxChunkZ()).append("] active=").append(h.active()).append(" deferred=").append(h.deferred()).append(" avgLatencyMs=").append(String.format(Locale.ROOT,"%.1f",h.averageLatencyMs())).append(" maxLatencyMs=").append(h.maxLatencyMs()).append('\n');
        return s.toString();
    }

    private static String workspace(ServerLevel world){
        var work=OceanCanvasWorkspaceData.get(world);var plan=OceanCanvasPlanningData.get(world);var lib=OceanCanvasPlanLibraryData.get(world);
        StringBuilder s=new StringBuilder();
        s.append("Schemas: workspace=").append(work.schema()).append(" planning=").append(plan.schema()).append(" planLibrary=").append(lib.schema()).append('\n');
        s.append("Projects: ").append(work.projects().size()).append(" tasks: ").append(work.tasks().size()).append(" journal entries: ").append(work.journal().size()).append('\n');
        s.append("Active work project: ").append(work.activeWorkProject()).append('\n');
        for(var p:work.projects())s.append("PROJECT ").append(p.id()).append(" | ").append(p.name()).append(" | ").append(p.status()).append(" | region=").append(p.regionName()).append(" | plans=").append(p.planningObjectIds().size()).append(" | terrainAssets=").append(p.terrainAssetIds().size()).append('\n');
        for(var t:work.tasks())s.append("TASK ").append(t.id()).append(" | ").append(t.title()).append(" | ").append(t.status()).append(" | priority=").append(t.priority()).append(" | project=").append(t.projectId()).append(" | dependencies=").append(t.dependencies().size()).append(" | checklist=").append(t.checklist().size()).append('\n');
        return s.toString();
    }

    private static String releaseReadiness(ServerLevel world){
        var r=OceanCanvasReleaseReadiness.audit(world);
        StringBuilder s=new StringBuilder("Ocean Canvas 1.0 release readiness\n")
                .append("blocked=").append(r.blocked()).append(" runtimeRequired=").append(r.runtimeRequired()).append(" releaseReady=").append(r.releaseReady()).append('\n');
        for(var g:r.gates())s.append(g.id()).append(' ').append(g.name()).append(": ").append(g.state()).append(" | ").append(g.detail()).append('\n');
        return s.toString();
    }

    private static String relevantLog(){
        Path log=FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");if(!Files.isRegularFile(log))return "latest.log not found.\n";
        ArrayDeque<String> lines=new ArrayDeque<>();
        try(BufferedReader r=Files.newBufferedReader(log,StandardCharsets.UTF_8)){String line;while((line=r.readLine())!=null){String l=line.toLowerCase(Locale.ROOT);if(l.contains("ocean canvas")||l.contains("oceancanvas")||l.contains("oc-h")||l.contains("oc-p")||l.contains("oc-r")||l.contains("exception")||l.contains("error")){lines.addLast(line);while(lines.size()>MAX_LOG_LINES)lines.removeFirst();}}}
        catch(IOException e){return "Could not read latest.log: "+e.getMessage()+"\n";}
        return lines.isEmpty()?"No relevant Ocean Canvas/error lines found in latest.log.\n":String.join("\n",lines)+"\n";
    }

    private static void text(ZipOutputStream zip,String name,String value) throws IOException {zip.putNextEntry(new ZipEntry(name));OutputStreamWriter w=new OutputStreamWriter(zip,StandardCharsets.UTF_8);w.write(value==null?"":value);w.flush();zip.closeEntry();}
    private static void boundedFile(ZipOutputStream zip,String name,Path file,long cap) throws IOException {zip.putNextEntry(new ZipEntry(name));try(var in=Files.newInputStream(file)){byte[] b=new byte[8192];long left=cap;for(int n;(n=in.read(b,0,(int)Math.min(b.length,left)))>0&&left>0;){zip.write(b,0,n);left-=n;}}zip.closeEntry();}
}
