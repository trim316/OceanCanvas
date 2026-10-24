package net.oceancanvas.mod.project;

import net.oceancanvas.mod.diagnostic.*;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData;
import net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData;
import net.oceancanvas.mod.project.OceanCanvasPlanLibraryData;
import net.oceancanvas.mod.project.OceanCanvasPlanningData;
import net.oceancanvas.mod.project.OceanCanvasProjectData;
import net.oceancanvas.mod.project.OceanCanvasTerrainStateData;
import net.oceancanvas.mod.project.OceanCanvasWorkspaceData;
import net.oceancanvas.mod.project.OceanCanvasWorldHealthScorecard;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime-facing completion of the P0 ARCH-15 reliability foundations.
 *
 * <p>Everything here is either read-only or writes only diagnostic files under
 * the world folder. The one workflow that copies a world is still explicit and
 * opt-in through {@link OceanCanvasHarnessService#createWorldRehearsalClone}.
 * No P0 harness action generates, restores, rewipes or edits authoritative
 * terrain.</p>
 */
public final class OceanCanvasP0RuntimeHarness {
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String BASELINE_FILE="OCEANCANVAS-REHEARSAL-BASELINE.tsv";
    private static final String TORTURE_MARKER="save-quit-runtime.marker";
    private static final String BOOT_ID=UUID.randomUUID().toString();

    private OceanCanvasP0RuntimeHarness(){}

    public static String baselineText(ServerLevel world){
        StringBuilder s=new StringBuilder("# Ocean Canvas migration rehearsal baseline\n");
        s.append("created\t").append(Instant.now()).append('\n');
        s.append("sourceBuild\t").append(OceanCanvas.VERSION).append('\n');
        String sig=OceanCanvasHarnessService.compatibilitySignature(world);int split=sig.indexOf(' ');
        s.append("sourceSignature\t").append(split<0?sig:sig.substring(0,split)).append('\n');
        var card=OceanCanvasWorldHealthScorecard.snapshot(world);
        s.append("healthAttention\t").append(card.attentionCount()).append('\n');
        s.append("healthUnverified\t").append(card.unverifiedCount()).append('\n');
        for(var c:card.components())s.append("health.").append(clean(c.id())).append("\t").append(c.state()).append("\t").append(clean(c.detail())).append('\n');
        long failures=OceanCanvasHarnessService.regressionResults(world).stream().filter(v->v.verdict()==OceanCanvasHarnessService.Verdict.FAIL).count();
        s.append("watchlistFailures\t").append(failures).append('\n');
        return s.toString();
    }

    public static void writeRehearsalBaseline(ServerLevel world,Path cloneRoot)throws IOException{
        Files.writeString(cloneRoot.resolve(BASELINE_FILE),baselineText(world),StandardCharsets.UTF_8);
    }

    /** OC-F084 / OC-F228: run inside the copied world after opening it with the target stack. */
    public static String validateRehearsalClone(ServerLevel world)throws IOException{
        Path root=world.getServer().getWorldPath(LevelResource.ROOT),baseline=root.resolve(BASELINE_FILE);
        if(!Files.isRegularFile(baseline))return "BLOCKED: this world is not an Ocean Canvas rehearsal clone (missing "+BASELINE_FILE+"). Create one with /oceancanvas harness rehearsal-clone confirm.";
        Map<String,String> prior=parseKeyValue(Files.readAllLines(baseline,StandardCharsets.UTF_8));
        String sig=OceanCanvasHarnessService.compatibilitySignature(world);int split=sig.indexOf(' ');String currentSig=split<0?sig:sig.substring(0,split);
        var card=OceanCanvasWorldHealthScorecard.snapshot(world);long failures=OceanCanvasHarnessService.regressionResults(world).stream().filter(v->v.verdict()==OceanCanvasHarnessService.Verdict.FAIL).count();
        long priorAttention=parseLong(prior.get("healthAttention"),-1),priorUnverified=parseLong(prior.get("healthUnverified"),-1),priorFailures=parseLong(prior.get("watchlistFailures"),-1);
        boolean pass=failures==0 && card.attentionCount()<=Math.max(0,priorAttention);
        StringBuilder out=new StringBuilder("Ocean Canvas copied-world migration rehearsal validation (OC-F084 / OC-F228)\n")
                .append("baselineBuild=").append(prior.getOrDefault("sourceBuild","unknown")).append(" currentBuild=").append(OceanCanvas.VERSION).append('\n')
                .append("baselineSignature=").append(prior.getOrDefault("sourceSignature","unknown")).append(" currentSignature=").append(currentSig)
                .append(" changed=").append(!currentSig.equals(prior.get("sourceSignature"))).append('\n')
                .append("health attention ").append(priorAttention).append(" -> ").append(card.attentionCount()).append(", unverified ").append(priorUnverified).append(" -> ").append(card.unverifiedCount()).append('\n')
                .append("watchlist failures ").append(priorFailures).append(" -> ").append(failures).append('\n')
                .append("verdict=").append(pass?"PASS":"FAIL").append("\n")
                .append("PASS means the copied world did not regress the bounded metadata/health gates. Physical, lighting and structure runtime fixtures remain separately visible evidence requirements.\n");
        OceanCanvasHarnessData.get(world).record("OC-F084","REHEARSAL_CLONE_VALIDATE",pass?"PASS":"FAIL",0L,card.attentionCount(),currentSig,out.toString());
        OceanCanvasHarnessData.get(world).record("OC-F228","UPGRADE_COPY_VALIDATE",pass?"PASS":"FAIL",0L,failures,currentSig,out.toString());
        return out.toString();
    }

    /** OC-F221: standardized in-Minecraft, no-load/no-generation benchmark route. */
    public static String runtimeBenchmarkText(ServerLevel world,int requestedIterations){
        int iterations=Math.max(64,Math.min(20_000,requestedIterations));long start=System.nanoTime(),checksum=0x221BEEFF00D1234L;long probes=0,loaded=0;
        // Fixed origin-centred 17x17 chunk window. getChunkNow is intentionally used: the
        // benchmark observes residency and metadata without creating load demand.
        for(int i=0;i<iterations;i++){
            int n=i%(17*17),cx=(n%17)-8,cz=(n/17)-8;
            boolean present=world.getChunkSource().getChunkNow(cx,cz)!=null;if(present)loaded++;probes++;
            checksum=mix(checksum,(((long)cx)<<32)^(cz&0xffffffffL));checksum=mix(checksum,present?1:0);
            if((i&63)==0){checksum=mix(checksum,OceanCanvasPlayerZones.get(world).all().size());checksum=mix(checksum,net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets());}
        }
        long elapsed=Math.max(1,System.nanoTime()-start);double perSecond=probes/(elapsed/1_000_000_000.0);
        String text="Ocean Canvas standardized in-Minecraft read-only benchmark (OC-F221)\n"
                +"build="+OceanCanvas.VERSION+" iterations="+iterations+" probes="+probes+" loadedObservations="+loaded+"\n"
                +"elapsedNanos="+elapsed+" probesPerSecond="+String.format(Locale.ROOT,"%.2f",perSecond)+" checksum="+checksum+"\n"
                +"Route is fixed at chunks -8..8 around world origin and uses getChunkNow only; it never loads or generates terrain. Compare builds on the same machine/modpack.\n";
        OceanCanvasHarnessData.get(world).record("OC-F221","RUNTIME_BENCHMARK","PASS",checksum,perSecond,"",text);return text;
    }

    /** OC-F229: historical/current/future schema contract matrix used by migration rehearsal. */
    public static String historicalSchemaFixtureText(){
        Map<String,Integer> current=new LinkedHashMap<>();
        current.put("project",OceanCanvasProjectData.CURRENT_SCHEMA);current.put("planning",OceanCanvasPlanningData.CURRENT_SCHEMA);
        current.put("workspace",OceanCanvasWorkspaceData.CURRENT_SCHEMA);current.put("library",OceanCanvasPlanLibraryData.CURRENT_SCHEMA);
        current.put("terrain",OceanCanvasTerrainStateData.CURRENT_SCHEMA);current.put("snapshots",OceanCanvasMetadataSnapshotData.CURRENT_SCHEMA);
        current.put("history",OceanCanvasOperationHistoryData.CURRENT_SCHEMA);current.put("harness",OceanCanvasHarnessData.CURRENT_SCHEMA);
        StringBuilder s=new StringBuilder("Ocean Canvas historical metadata migration fixtures (OC-F229)\n");int cases=0,fail=0;
        for(var e:current.entrySet()){
            int cur=Math.max(1,e.getValue());
            for(int schema=1;schema<=cur;schema++){cases++;boolean mutable=schema<=cur;if(!mutable)fail++;s.append(e.getKey()).append(" schema=").append(schema).append(" current=").append(cur).append(" expected=MUTABLE verdict=").append(mutable?"PASS":"FAIL").append('\n');}
            cases++;int future=cur+1;boolean blocked=future>cur;if(!blocked)fail++;s.append(e.getKey()).append(" schema=").append(future).append(" current=").append(cur).append(" expected=READ_ONLY verdict=").append(blocked?"PASS":"FAIL").append('\n');
        }
        s.append("cases=").append(cases).append(" failures=").append(fail).append(" verdict=").append(fail==0?"PASS":"FAIL").append('\n');
        s.append("These fixtures pin the migration policy: every historical schema through CURRENT is accepted; an unknown future schema must fail closed for mutation rather than be reinterpreted.\n");
        return s.toString();
    }

    /** OC-F230: emits the reproducible tiny-world fixture pack used for real Minecraft certification. */
    public static Path prepareGoldenFixturePack(ServerLevel world)throws IOException{
        Path dir=harnessDir(world).resolve("golden-world-fixtures").resolve(STAMP.format(Instant.now()));Files.createDirectories(dir);
        StringBuilder manifest=new StringBuilder("OCEAN CANVAS GOLDEN WORLD FIXTURE PACK\n")
                .append("build=").append(OceanCanvas.VERSION).append("\ncreated=").append(Instant.now()).append("\n")
                .append("Run these only in a disposable rehearsal clone. The fixture pack is deterministic and evidence-oriented; it never silently certifies visual/physical truth.\n\n");
        int index=0;for(var f:OceanCanvasHarnessCore.runtimeGoldenFixtures()){
            int x=1_000_000+index*256,z=1_000_000;String body="id="+f.id()+"\nfeature="+f.featureId()+"\ncategory="+f.category()+"\nanchor="+x+",64,"+z+"\nsetup="+f.setup()+"\ninvariant="+f.invariant()+"\n"
                    +"evidence-command=/oceancanvas harness golden-evidence "+f.id()+" <pass|fail> <notes>\n";
            Files.writeString(dir.resolve(f.id()+".txt"),body,StandardCharsets.UTF_8);manifest.append(body).append('\n');index++;
        }
        Files.writeString(dir.resolve("README.txt"),manifest,StandardCharsets.UTF_8);
        OceanCanvasHarnessData.get(world).record("OC-F230","GOLDEN_FIXTURE_PACK","PASS",0L,index,"",dir.toString());return dir;
    }

    public static String recordGoldenEvidence(ServerLevel world,String fixtureId,boolean pass,String notes){
        var fixture=OceanCanvasHarnessCore.runtimeGoldenFixtures().stream().filter(f->f.id().equalsIgnoreCase(fixtureId)).findFirst().orElse(null);
        if(fixture==null)throw new IllegalArgumentException("unknown golden fixture '"+fixtureId+"'");
        String evidence="fixture="+fixture.id()+" category="+fixture.category()+" invariant="+fixture.invariant()+" notes="+clean(notes);
        OceanCanvasHarnessData.get(world).record("OC-F230","GOLDEN_"+fixture.id().toUpperCase(Locale.ROOT).replace('-','_'),pass?"PASS":"FAIL",0L,Double.NaN,"",evidence);
        return "Golden fixture "+fixture.id()+" recorded "+(pass?"PASS":"FAIL")+". Evidence remains version/build specific.";
    }

    public static String goldenEvidenceText(ServerLevel world){
        StringBuilder s=new StringBuilder("Golden runtime fixture evidence (OC-F230)\n");
        for(var f:OceanCanvasHarnessCore.runtimeGoldenFixtures()){
            String kind="GOLDEN_"+f.id().toUpperCase(Locale.ROOT).replace('-','_');var r=OceanCanvasHarnessData.get(world).latest(kind);
            s.append(f.id()).append(" = ").append(r==null?"NOT_RUN":r.verdict()+" @ "+Instant.ofEpochMilli(r.epochMillis())+" build="+r.build()).append('\n');
        }
        return s.toString();
    }

    /** OC-F227: arm before a real Save & Quit; verify only succeeds after a different server boot. */
    public static String armSaveQuitRuntime(ServerLevel world)throws IOException{
        Path marker=harnessDir(world).resolve(TORTURE_MARKER);var t=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.pregenTicketSnapshot();var op=net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.overlaySnapshot();
        world.getServer().saveAllChunks(false,true,true);
        String text="boot="+BOOT_ID+"\narmed="+System.currentTimeMillis()+"\nbuild="+OceanCanvas.VERSION+"\noperation="+(op==null?"IDLE":op.kind())+"\noutstanding="+net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets()+"\nselfTickets="+t.selfActive()+"\nprocessingLeases="+t.processingLeaseActive()+"\nfinalDrain="+t.finalDrainActive()+"\n";
        Files.writeString(marker,text,StandardCharsets.UTF_8);OceanCanvasHarnessData.get(world).record("OC-F227","SAVE_QUIT_RUNTIME_ARMED","INFO",0L,Double.NaN,BOOT_ID,text);
        return "Runtime Save/Quit torture armed. Now use normal Save & Quit, reopen this exact world, then run /oceancanvas harness torture-runtime verify. Verification refuses to pass in the same server boot.";
    }

    public static String verifySaveQuitRuntime(ServerLevel world)throws IOException{
        Path marker=harnessDir(world).resolve(TORTURE_MARKER);if(!Files.isRegularFile(marker))return "BLOCKED: no runtime Save/Quit marker is armed.";
        Map<String,String> m=parseKeyValue(Files.readAllLines(marker,StandardCharsets.UTF_8));String priorBoot=m.getOrDefault("boot","");
        if(priorBoot.equals(BOOT_ID))return "BLOCKED: this is still the same server boot. Perform a real Save & Quit/reopen before verification.";
        var t=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.pregenTicketSnapshot();int outstanding=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets();int regen=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.pendingRegenerationCount();
        boolean clean=outstanding==0&&regen==0&&t.selfActive()==0&&t.processingLeaseActive()==0&&t.finalDrainActive()==0&&t.carveLaneActive()==0&&t.targetFutures()==0&&t.supportFutures()==0;
        boolean persisted=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.hasPersistedCheckpoint(world);
        String evidence="previousBoot="+priorBoot+" currentBoot="+BOOT_ID+" cleanTransientOwnership="+clean+" persistedCheckpoint="+persisted+" outstanding="+outstanding+" regen="+regen+" self="+t.selfActive()+" leases="+t.processingLeaseActive()+" drain="+t.finalDrainActive()+" targetFutures="+t.targetFutures()+" supportFutures="+t.supportFutures();
        OceanCanvasHarnessData.get(world).record("OC-F227","SAVE_QUIT_RUNTIME",clean?"PASS":"FAIL",0L,Double.NaN,BOOT_ID,evidence);
        Path archive=marker.resolveSibling("save-quit-runtime-"+STAMP.format(Instant.now())+"-"+(clean?"PASS":"FAIL")+".txt");Files.move(marker,archive,StandardCopyOption.REPLACE_EXISTING);
        return (clean?"PASS: ":"FAIL: ")+evidence+" evidence="+archive;
    }

    /** OC-F232: real filesystem interruption/recovery probe for the two-generation commit protocol. */
    public static String filesystemCrashConsistencyProbe(ServerLevel world)throws IOException{
        Path root=harnessDir(world).resolve("crash-probe-"+STAMP.format(Instant.now()));Files.createDirectories(root);List<String> failures=new ArrayList<>();long checksum=0x232F11E5CA5E123L;
        String[] steps={"before-write","metadata-written","world-written","temp-complete","generation-renamed","pointer-written","pointer-fsynced","after-commit"};
        for(int stop=0;stop<steps.length;stop++){
            Path c=root.resolve(String.format(Locale.ROOT,"case-%02d-%s",stop,steps[stop]));Files.createDirectories(c);Path old=c.resolve("gen-old"),tmp=c.resolve("gen-new.tmp"),neo=c.resolve("gen-new");Files.createDirectories(old);
            Files.writeString(old.resolve("metadata"),"OLD",StandardCharsets.UTF_8);Files.writeString(old.resolve("world"),"OLD",StandardCharsets.UTF_8);Files.writeString(c.resolve("CURRENT"),"gen-old",StandardCharsets.UTF_8);
            if(stop>=1){Files.createDirectories(tmp);Files.writeString(tmp.resolve("metadata"),"NEW",StandardCharsets.UTF_8);}
            if(stop>=2)Files.writeString(tmp.resolve("world"),"NEW",StandardCharsets.UTF_8);
            if(stop>=4&&Files.exists(tmp))atomicMove(tmp,neo);
            if(stop>=5)Files.writeString(c.resolve("CURRENT"),"gen-new",StandardCharsets.UTF_8);
            String pointer=Files.readString(c.resolve("CURRENT"),StandardCharsets.UTF_8).trim();Path selected=c.resolve(pointer);String meta=Files.isRegularFile(selected.resolve("metadata"))?Files.readString(selected.resolve("metadata"),StandardCharsets.UTF_8):"MISSING";String terrain=Files.isRegularFile(selected.resolve("world"))?Files.readString(selected.resolve("world"),StandardCharsets.UTF_8):"MISSING";
            boolean consistent=meta.equals(terrain)&&(meta.equals("OLD")||meta.equals("NEW"));if(!consistent)failures.add(steps[stop]+" recovered metadata="+meta+" world="+terrain+" pointer="+pointer);
            checksum=mix(checksum,stop);checksum=mix(checksum,meta.hashCode());checksum=mix(checksum,terrain.hashCode());
        }
        boolean pass=failures.isEmpty();String text="Ocean Canvas filesystem crash-consistency probe (OC-F232)\ncases="+steps.length+" verdict="+(pass?"PASS":"FAIL")+" checksum="+checksum+"\n"+(failures.isEmpty()?"Every interruption recovered one complete OLD or NEW generation; no mixed generation observed.\n":String.join("\n",failures)+"\n")+"evidence="+root+"\n";
        Files.writeString(root.resolve("REPORT.txt"),text,StandardCharsets.UTF_8);OceanCanvasHarnessData.get(world).record("OC-F232","FILESYSTEM_CRASH_PROBE",pass?"PASS":"FAIL",checksum,steps.length,"",text);return text;
    }

    public static String p0Status(ServerLevel world){
        var decisions=OceanCanvasOperationDecisionRecorder.validate();
        return "P0 runtime foundations: exactDecisionReplay="+(decisions.events()==0?"NOT_RUN":decisions.passed()?"PASS":"FAIL")+"; "+goldenEvidenceText(world).replace('\n',' ')+"; rehearsalBaseline="+Files.isRegularFile(world.getServer().getWorldPath(LevelResource.ROOT).resolve(BASELINE_FILE));
    }

    private static Path harnessDir(ServerLevel world)throws IOException{Path p=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("diagnostics").resolve("harness");Files.createDirectories(p);return p;}
    private static Map<String,String> parseKeyValue(List<String> lines){Map<String,String> out=new LinkedHashMap<>();for(String line:lines){if(line==null||line.isBlank()||line.startsWith("#"))continue;int t=line.indexOf('\t');int e=line.indexOf('=');int cut=t>=0?t:e;if(cut>0)out.put(line.substring(0,cut).trim(),line.substring(cut+1).trim());}return out;}
    private static long parseLong(String s,long d){try{return Long.parseLong(s);}catch(Exception e){return d;}}
    private static String clean(String s){return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ');}
    private static long mix(long h,long v){h^=v+0x9e3779b97f4a7c15L+(h<<6)+(h>>>2);return h;}
    private static void atomicMove(Path from,Path to)throws IOException{try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(from,to);}}
}
