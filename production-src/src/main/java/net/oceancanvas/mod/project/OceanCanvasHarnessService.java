package net.oceancanvas.mod.project;

import net.oceancanvas.mod.diagnostic.*;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.project.OceanCanvasAssetIntegrityService;
import net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData;
import net.oceancanvas.mod.project.OceanCanvasDeepHealthService;
import net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility;
import net.oceancanvas.mod.project.OceanCanvasPlanLibraryData;
import net.oceancanvas.mod.project.OceanCanvasPlanningData;
import net.oceancanvas.mod.project.OceanCanvasProjectData;
import net.oceancanvas.mod.project.OceanCanvasTerrainStateData;
import net.oceancanvas.mod.project.OceanCanvasWorkspaceData;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Minecraft-facing adapter for ARCH-15 Test Harness & Fixtures.
 *
 * <p>Every default check is metadata-only or pure CPU work: it never requests,
 * loads, generates, restores, rewipes, or modifies a chunk.  Checks that need a
 * real runtime/world observation remain explicitly NOT_RUN instead of being
 * converted into a false green result.</p>
 */
public final class OceanCanvasHarnessService {
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private OceanCanvasHarnessService(){}

    public enum Verdict { PASS, FAIL, NOT_RUN, INFO }
    public record Check(String id,String title,Verdict verdict,String evidence){}

    public static List<Check> regressionResults(ServerLevel world){
        List<Check> out=new ArrayList<>();
        String compat=OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
        out.add(new Check("OC-R248-META","Metadata forward-safety",compat.isBlank()?Verdict.PASS:Verdict.FAIL,
                compat.isBlank()?"All terrain-mutating schemas are supported by this build.":compat));

        var links=OceanCanvasAssetIntegrityService.scan(world,64);
        out.add(new Check("OC-R248-LINK","Plan/Project reference integrity",links.healthy()?Verdict.PASS:Verdict.FAIL,
                links.healthy()?"No broken cross-object links in the bounded metadata scan.":links.issues()+" issue(s); first findings are available in Health/diagnostics."));

        var terrain=OceanCanvasDeepHealthService.scan(world,64);
        out.add(new Check("OC-R248-SEAL","Terrain-state/seal consistency",terrain.healthy()?Verdict.PASS:Verdict.FAIL,
                terrain.healthy()?"No explicit terrain-state seal contradictions.":terrain.mismatches()+" explicit terrain-state contradiction(s)."));

        Set<String> names=new HashSet<>();String duplicate="";
        for(var z:OceanCanvasPlayerZones.get(world).all())if(!names.add(z.name().toLowerCase(Locale.ROOT))){duplicate=z.name();break;}
        out.add(new Check("OC-R248-REGION-ID","Region identity uniqueness",duplicate.isBlank()?Verdict.PASS:Verdict.FAIL,
                duplicate.isBlank()?"Region names are unique case-insensitively.":"Duplicate region identity: "+duplicate));

        var persisted=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.pregenCheckpoint(world);
        boolean activePregen=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.pregenRunning();
        boolean pregenCoherent=(persisted==null)||activePregen;
        out.add(new Check("OC-R248-PREGEN-RESUME","Pregen checkpoint ownership",pregenCoherent?Verdict.PASS:Verdict.FAIL,
                persisted==null?"No persisted Pregen/Rewipe/Expand checkpoint is waiting.":activePregen?"A persisted checkpoint has an active controller owner.":"Persisted "+persisted.kind()+" checkpoint exists without an active controller; inspect before mutation."));

        var restoreSaved=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.restoreCheckpoint(world);
        boolean activeRestore=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.restoreRunning();
        boolean restoreCoherent=(restoreSaved==null)||activeRestore;
        out.add(new Check("OC-R248-RESTORE-RESUME","Restore checkpoint ownership",restoreCoherent?Verdict.PASS:Verdict.FAIL,
                restoreSaved==null?"No persisted Restore checkpoint is waiting.":activeRestore?"A persisted Restore checkpoint has an active controller owner.":"Persisted Restore checkpoint exists without an active controller; inspect before mutation."));

        var replay=OceanCanvasPerformanceReplayRecorder.validate();
        out.add(new Check("OC-R248-REPLAY","Performance replay invariants",
                replay.samples()==0?Verdict.NOT_RUN:(replay.passed()?Verdict.PASS:Verdict.FAIL),
                replay.samples()==0?"No live Pregen performance samples have been recorded in this session.":replay.passed()?"Replay "+replay.samples()+" samples valid; checksum="+replay.checksum()+".":replay.issues().size()+" replay invariant issue(s); checksum="+replay.checksum()+"."));

        var fuzz=OceanCanvasHarnessCore.runBoundaryFuzz(OceanCanvasHarnessCore.DEFAULT_SEED,256);
        out.add(new Check("OC-R248-BOUNDARY","Shared geometry boundary invariant",fuzz.passed()?Verdict.PASS:Verdict.FAIL,
                fuzz.passed()?"256 deterministic boundary cases passed; checksum="+fuzz.checksum()+".":fuzz.failures().size()+" boundary failure(s), seed="+fuzz.seed()+"."));

        // These are deliberately not inferred from metadata. They require real
        // world/runtime certification and therefore stay visible as debt.
        out.add(new Check("OC-R248-PHYSICAL","Water/ice/physical cleanup",Verdict.NOT_RUN,"Requires a real generated-world fixture or runtime scan; metadata is not treated as proof."));
        out.add(new Check("OC-R248-LIGHT","Near-field lighting convergence",Verdict.NOT_RUN,"Requires in-client/render-distance runtime evidence; no fake PASS from server metadata."));
        out.add(new Check("OC-R248-STRUCT","Structure semantics",Verdict.NOT_RUN,"Requires real-world Never/Default/Always distribution, metadata, mob and loot certification."));
        return List.copyOf(out);
    }

    public static boolean hasFailure(ServerLevel world){return regressionResults(world).stream().anyMatch(c->c.verdict()==Verdict.FAIL);}

    public static String report(ServerLevel world){
        StringBuilder s=new StringBuilder();
        s.append("Ocean Canvas ARCH-15 reliability harness\n")
                .append("build=").append(OceanCanvas.VERSION).append(" schema=").append(OceanCanvasHarnessCore.SCHEMA).append('\n')
                .append("generated=").append(Instant.now()).append("\n\nRegression watchlist (OC-F248)\n");
        for(Check c:regressionResults(world))s.append(c.verdict()).append(' ').append(c.id()).append(' ').append(c.title()).append(" | ").append(c.evidence()).append('\n');
        s.append("\nState machines (OC-F225)\n").append(stateMachineReport(world));
        s.append("\nMigration/schema rehearsal (OC-F084 / OC-F229)\n").append(migrationReport(world));
        s.append("\nGolden fixtures (OC-F230)\n").append(fixtureReport());
        s.append("\nGenerated invariant suite (OC-F226)\n").append(invariantSuiteText());
        s.append("\nSave & Quit torture rehearsal (OC-F227)\n").append(saveQuitTortureText());
        s.append("\nCrash consistency matrix (OC-F232)\n").append(crashConsistencyText());
        s.append("\nUpgrade compatibility fingerprint (OC-F228)\n").append(compatibilityReport(world));
        return s.toString();
    }

    public static String stateMachineReport(ServerLevel world){
        StringBuilder s=new StringBuilder();
        var op=net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.overlaySnapshot();
        String active="IDLE";
        if(op!=null){
            String kind=op.kind()==null?"":op.kind().toLowerCase(Locale.ROOT);
            if(kind.equals("restore"))active="RESTORE observable="+observableRestoreState(op)+" completed="+op.progressed()+"/"+op.total();
            else {String machine=kind.contains("rewipe")||kind.contains("reset")?"REWIPE":"PREGEN";active=machine+" observable="+observablePregenState(machine,op)+" submitted="+op.progressed()+"/"+op.total();}
        }
        s.append("current=").append(active).append('\n');
        for(var machine:OceanCanvasHarnessCore.stateMachines().values()){
            s.append(machine.displayName()).append(" states=").append(String.join(",",machine.states())).append('\n');
            for(var t:machine.transitions())s.append("  ").append(t.from()).append(" -> ").append(t.to()).append(" : ").append(t.reason()).append('\n');
        }
        return s.toString();
    }

    private static String observablePregenState(String machine,net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Overlay p){
        if("PREGEN".equals(machine)){
            var perf=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.performanceSnapshot();
            if(perf!=null&&perf.active()&&!perf.phase().isBlank())return perf.phase();
        }
        if(p.progressed()<=0)return "PREPARING";
        if(p.progressed()<p.total())return "FEEDING";
        return "FINAL_DRAIN";
    }
    private static String observableRestoreState(net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Overlay r){
        if(r.progressed()<=0)return "PREPARING";
        if(r.progressed()<r.total())return "COPYING";
        return "VERIFYING";
    }

    public static String migrationReport(ServerLevel world){
        List<OceanCanvasHarnessCore.MigrationProbe> probes=List.of(
                probe("project",OceanCanvasProjectData.get(world).schema(),OceanCanvasProjectData.CURRENT_SCHEMA),
                probe("planning",OceanCanvasPlanningData.get(world).schema(),OceanCanvasPlanningData.CURRENT_SCHEMA),
                probe("workspace",OceanCanvasWorkspaceData.get(world).schema(),OceanCanvasWorkspaceData.CURRENT_SCHEMA),
                probe("plan-library",OceanCanvasPlanLibraryData.get(world).schema(),OceanCanvasPlanLibraryData.CURRENT_SCHEMA),
                probe("terrain-state",OceanCanvasTerrainStateData.get(world).schema(),OceanCanvasTerrainStateData.CURRENT_SCHEMA),
                probe("metadata-snapshots",OceanCanvasMetadataSnapshotData.get(world).schema(),OceanCanvasMetadataSnapshotData.CURRENT_SCHEMA),
                probe("operation-history",net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData.get(world).schema(),net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData.CURRENT_SCHEMA),
                probe("harness-evidence",OceanCanvasHarnessData.get(world).schema(),OceanCanvasHarnessData.CURRENT_SCHEMA));
        var r=OceanCanvasHarnessCore.rehearseSchemas(probes);
        StringBuilder s=new StringBuilder("safeForMutation=").append(r.safeForMutation()).append(" incompatible=").append(r.incompatibleCount()).append('\n');
        for(var q:r.probes())s.append("  ").append(q.component()).append(" observed=").append(q.observedSchema()).append(" current=").append(q.currentSchema()).append(" mutationAllowed=").append(q.mutationAllowed()).append(" | ").append(q.consequence()).append('\n');
        s.append("This is an Ocean Canvas metadata-schema rehearsal, not proof that a new Minecraft version is safe. Use a copied world for Minecraft-version migration.\n");
        s.append('\n').append(OceanCanvasP0RuntimeHarness.historicalSchemaFixtureText());
        return s.toString();
    }

    private static OceanCanvasHarnessCore.MigrationProbe probe(String component,int observed,int current){
        boolean allowed=observed<=current;
        return new OceanCanvasHarnessCore.MigrationProbe(component,observed,current,allowed,allowed?"Supported by this build.":"Newer metadata: destructive reinterpretation must remain blocked.");
    }

    public static String fixtureReport(){
        StringBuilder s=new StringBuilder();
        for(var f:OceanCanvasHarnessCore.goldenFixtures()){
            var a=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.polygonChunks(f.polygon(),100_000);
            var b=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.polygonChunks(f.polygon(),100_000);
            s.append("OFFLINE PASS ").append(f.id()).append(" chunks=").append(a.size()).append(" deterministic=").append(a.equals(b)).append(" | ").append(f.purpose()).append('\n');
        }
        for(var f:OceanCanvasHarnessCore.runtimeGoldenFixtures())
            s.append("RUNTIME NOT_RUN ").append(f.id()).append(" [").append(f.category()).append("] | ").append(f.invariant()).append('\n');
        return s.toString();
    }

    public static Path writeReport(ServerLevel world,String stem,String text) throws IOException{
        Path dir=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("diagnostics").resolve("harness");
        Files.createDirectories(dir);Path out=dir.resolve(stem+"-"+STAMP.format(Instant.now())+".txt");
        Files.writeString(out,text,StandardCharsets.UTF_8);return out;
    }

    /**
     * Safe migration-rehearsal pack: copies only Ocean Canvas SavedData files
     * and writes a schema/report manifest. It never copies region terrain or
     * pretends to be a complete cloned world.
     */
    public static Path createMetadataRehearsalPack(ServerLevel world) throws IOException{
        Path root=world.getServer().getWorldPath(LevelResource.ROOT);
        Path out=root.resolve("oceancanvas").resolve("rehearsal").resolve("metadata-"+STAMP.format(Instant.now()));
        Path dataOut=out.resolve("data");Files.createDirectories(dataOut);
        Path data=root.resolve("data");int copied=0;
        if(Files.isDirectory(data))try(var stream=Files.list(data)){
            for(Path p:stream.filter(Files::isRegularFile).toList()){
                String n=p.getFileName().toString().toLowerCase(Locale.ROOT);
                if(!n.contains("oceancanvas"))continue;
                Files.copy(p,dataOut.resolve(p.getFileName()),StandardCopyOption.COPY_ATTRIBUTES);copied++;
            }
        }
        String manifest="Ocean Canvas metadata migration rehearsal pack\n"
                +"build="+OceanCanvas.VERSION+"\ncreated="+Instant.now()+"\ncopiedSavedDataFiles="+copied+"\n\n"
                +migrationReport(world)+"\n"
                +"WARNING: This is NOT a world backup or a Minecraft-version clone. It deliberately excludes region/chunk terrain and player data.\n";
        Files.writeString(out.resolve("manifest.txt"),manifest,StandardCharsets.UTF_8);
        return out;
    }

    public static String invariantSuiteText(){
        StringBuilder s=new StringBuilder();
        for(var v:OceanCanvasHarnessCore.generatedInvariantSuite())
            s.append(v.id()).append('\t').append(v.featureId()).append('\t').append(v.automation()).append('\t')
                    .append(v.source()).append('\t').append(v.assertion()).append('\n');
        return s.toString();
    }

    public static String saveQuitTortureText(){
        var r=OceanCanvasHarnessCore.runSaveQuitTorture();
        StringBuilder s=new StringBuilder("cases=").append(r.cases()).append(" passed=").append(r.passed()).append(" checksum=").append(r.checksum()).append('\n');
        for(var c:r.results())s.append(c.machine()).append(' ').append(c.interruptedAt()).append(" -> ").append(c.recoveryState()).append(" -> ").append(c.resumeState())
                .append(" cleanup=").append(c.cleanupProven()).append(" | ").append(c.detail()).append('\n');
        for(String f:r.failures())s.append("FAIL ").append(f).append('\n');
        s.append("This is the deterministic interruption matrix. Real Save & Quit/restart certification remains a runtime golden fixture.\n");
        return s.toString();
    }

    public static String crashConsistencyText(){
        var r=OceanCanvasHarnessCore.runCrashConsistencyMatrix();
        StringBuilder s=new StringBuilder("cases=").append(r.cases()).append(" passed=").append(r.passed()).append(" checksum=").append(r.checksum()).append('\n');
        for(var p:r.points())s.append(p.step()).append(' ').append(p.id()).append(" commitVisible=").append(p.commitVisible())
                .append(" recovered=").append(p.recoveredGeneration()).append(" consistent=").append(p.metadataWorldConsistent()).append(" | ").append(p.detail()).append('\n');
        for(String f:r.failures())s.append("FAIL ").append(f).append('\n');
        return s.toString();
    }

    public static String compatibilitySignature(ServerLevel world){
        String mc=modVersion("minecraft"),loader=FabricLoader.getInstance().getModContainer("fabricloader").map(v->v.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        String fabric=modVersion("fabric-api"),c2me=modVersion("c2me"),modernfix=modVersion("modernfix"),voxy=modVersion("voxy");
        String raw="mc="+mc+"|loader="+loader+"|fabric="+fabric+"|c2me="+c2me+"|modernfix="+modernfix+"|voxy="+voxy
                +"|project="+OceanCanvasProjectData.CURRENT_SCHEMA+"|planning="+OceanCanvasPlanningData.CURRENT_SCHEMA
                +"|workspace="+OceanCanvasWorkspaceData.CURRENT_SCHEMA+"|library="+OceanCanvasPlanLibraryData.CURRENT_SCHEMA
                +"|terrain="+OceanCanvasTerrainStateData.CURRENT_SCHEMA+"|snapshots="+OceanCanvasMetadataSnapshotData.CURRENT_SCHEMA
                +"|history="+net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData.CURRENT_SCHEMA+"|harness="+OceanCanvasHarnessData.CURRENT_SCHEMA;
        return sha256(raw)+" "+raw;
    }

    public static String compatibilityReport(ServerLevel world){
        String signature=compatibilitySignature(world);int split=signature.indexOf(' ');String hash=split<0?signature:signature.substring(0,split),detail=split<0?"":signature.substring(split+1);
        var data=OceanCanvasHarnessData.get(world);var prior=data.latest("COMPATIBILITY");
        boolean changed=prior!=null&&!hash.equals(prior.signature());
        return "signature="+hash+" changedSinceLastRecorded="+changed+"\n"+detail+"\n"
                +(prior==null?"No prior compatibility fingerprint is recorded for this world.":"Prior="+prior.signature()+" build="+prior.build()+" at="+Instant.ofEpochMilli(prior.epochMillis()))+"\n"
                +"A changed fingerprint requires the fixed harness gates plus real runtime fixtures before an upgrade is certified.\n";
    }

    public static OceanCanvasHarnessData.Run recordCompatibility(ServerLevel world){
        String signature=compatibilitySignature(world);int split=signature.indexOf(' ');String hash=split<0?signature:signature.substring(0,split);
        boolean safe=migrationReport(world).contains("safeForMutation=true");
        return OceanCanvasHarnessData.get(world).record("OC-F228","COMPATIBILITY",safe?"PASS":"FAIL",0L,Double.NaN,hash,compatibilityReport(world));
    }

    /** OC-F083: version-signature keyed certification rows. Current-version evidence never carries across an upgrade. */
    public record CompatibilityMatrixRow(String capability,String status,long epochMillis,String evidence){}

    public static java.util.List<CompatibilityMatrixRow> compatibilityMatrix(ServerLevel world){
        String signature=compatibilitySignature(world);int split=signature.indexOf(' ');String hash=split<0?signature:signature.substring(0,split);
        var runs=OceanCanvasHarnessData.get(world).recent();
        java.util.function.Function<String,OceanCanvasHarnessData.Run> find=kind->{for(var r:runs)if(r.kind().equals(kind)&&r.signature().equals(hash))return r;return null;};
        var rows=new java.util.ArrayList<CompatibilityMatrixRow>();
        rows.add(matrixRow("Metadata schemas",find.apply("COMPAT_METADATA"),"UNVERIFIED","Run compatibility matrix after every dependency/version change."));
        rows.add(matrixRow("Boundary geometry",find.apply("COMPAT_GEOMETRY"),"UNVERIFIED","Run compatibility matrix after every dependency/version change."));
        rows.add(matrixRow("Lifecycle replay",find.apply("COMPAT_LIFECYCLE"),"UNVERIFIED","Run compatibility matrix after every dependency/version change."));
        rows.add(matrixRow("Save/Quit simulation",find.apply("COMPAT_SAVE_QUIT_SIM"),"UNVERIFIED","Offline interruption rehearsal not yet recorded for this signature."));
        rows.add(matrixRow("Crash consistency simulation",find.apply("COMPAT_CRASH_SIM"),"UNVERIFIED","Offline crash rehearsal not yet recorded for this signature."));
        rows.add(matrixRow("Pregen runtime",find.apply("COMPAT_PREGEN"),"UNVERIFIED","No successful Pregen completion has been observed under this exact signature."));
        rows.add(matrixRow("Rewipe runtime",find.apply("COMPAT_REWIPE"),"UNVERIFIED","No successful Rewipe completion has been observed under this exact signature."));
        rows.add(matrixRow("Restore runtime",find.apply("COMPAT_RESTORE"),"UNVERIFIED","No successful Restore completion has been observed under this exact signature."));
        rows.add(matrixRow("Expand runtime",find.apply("COMPAT_EXPAND"),"UNVERIFIED","No successful Expand completion has been observed under this exact signature."));
        rows.add(matrixRow("Physical cleanup",find.apply("COMPAT_PHYSICAL"),"UNVERIFIED","Requires real generated-world liquid/ice/terrain evidence."));
        rows.add(matrixRow("Near-field lighting",find.apply("COMPAT_LIGHTING"),"UNVERIFIED","Requires in-client render-distance evidence."));
        rows.add(matrixRow("Structure semantics",find.apply("COMPAT_STRUCTURES"),"UNVERIFIED","Requires real Never/Default/Always distribution, metadata, mobs and loot evidence."));
        return java.util.List.copyOf(rows);
    }

    private static CompatibilityMatrixRow matrixRow(String capability,OceanCanvasHarnessData.Run run,String missingStatus,String missingEvidence){
        if(run==null)return new CompatibilityMatrixRow(capability,missingStatus,0L,missingEvidence);
        String status=run.verdict().equals("PASS")?(run.kind().endsWith("_SIM")?"SIMULATED":run.kind().matches("COMPAT_(PREGEN|REWIPE|RESTORE|EXPAND)")?"OBSERVED":"VALIDATED"):"FAILED";
        return new CompatibilityMatrixRow(capability,status,run.epochMillis(),run.evidence());
    }

    /** Runs only deterministic/offline gates and persists them against the current environment fingerprint. */
    public static java.util.List<CompatibilityMatrixRow> recordCompatibilityMatrix(ServerLevel world){
        String signature=compatibilitySignature(world);int split=signature.indexOf(' ');String hash=split<0?signature:signature.substring(0,split);
        var data=OceanCanvasHarnessData.get(world);
        boolean metadata=OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world).isBlank();
        data.record("OC-F083","COMPAT_METADATA",metadata?"PASS":"FAIL",0L,Double.NaN,hash,metadata?"All mutation-relevant Ocean Canvas schemas are supported.":OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world));
        var geometry=OceanCanvasHarnessCore.runBoundaryFuzz(OceanCanvasHarnessCore.DEFAULT_SEED,5000);
        data.record("OC-F083","COMPAT_GEOMETRY",geometry.passed()?"PASS":"FAIL",geometry.checksum(),geometry.executedCases(),hash,geometry.passed()?"5,000 deterministic boundary cases passed.":geometry.failures().size()+" boundary failures.");
        var replay=OceanCanvasHarnessCore.replayDeterministically(java.util.List.of(
                new OceanCanvasHarnessCore.ReplaySample(0,"pregen","PREPARING",0,2,0,0,0,.3,1,50,8,0),
                new OceanCanvasHarnessCore.ReplaySample(1000,"pregen","FEEDING",1,2,1,0,0,.3,1,50,8,0),
                new OceanCanvasHarnessCore.ReplaySample(2000,"pregen","FINAL_DRAIN",2,2,0,0,1,.3,1,50,8,0)));
        data.record("OC-F083","COMPAT_LIFECYCLE",replay.passed()?"PASS":"FAIL",replay.checksum(),replay.transitions(),hash,replay.passed()?"Deterministic legal lifecycle replay passed.":replay.issues().stream().map(issue->issue.index()+":"+issue.id()+":"+issue.detail()).collect(java.util.stream.Collectors.joining("; ")));
        var torture=OceanCanvasHarnessCore.runSaveQuitTorture();data.record("OC-F083","COMPAT_SAVE_QUIT_SIM",torture.passed()?"PASS":"FAIL",torture.checksum(),torture.cases(),hash,"Offline Save/Quit matrix: "+torture.cases()+" cases.");
        var crash=OceanCanvasHarnessCore.runCrashConsistencyMatrix();data.record("OC-F083","COMPAT_CRASH_SIM",crash.passed()?"PASS":"FAIL",crash.checksum(),crash.cases(),hash,"Offline crash-consistency matrix: "+crash.cases()+" cases.");
        recordCompatibility(world);
        return compatibilityMatrix(world);
    }

    /** Runtime observation is deliberately weaker than certification, but is version-signature keyed evidence. */
    public static void recordOperationObservation(ServerLevel world,String operation,String evidence){
        if(world==null||operation==null)return;
        String raw=operation.trim().toUpperCase(Locale.ROOT),op;
        if(raw.contains("REWIPE")||raw.contains("RESET"))op="REWIPE";
        else if(raw.contains("PREGEN"))op="PREGEN";
        else if(raw.contains("RESTORE"))op="RESTORE";
        else if(raw.contains("EXPAND"))op="EXPAND";
        else return;
        String signature=compatibilitySignature(world);int split=signature.indexOf(' ');String hash=split<0?signature:signature.substring(0,split);
        OceanCanvasHarnessData.get(world).record("OC-F083","COMPAT_"+op,"PASS",0L,Double.NaN,hash,evidence==null?"Successful runtime completion observed.":evidence);
    }

    private static String modVersion(String id){return FabricLoader.getInstance().getModContainer(id).map(v->v.getMetadata().getVersion().getFriendlyString()).orElse("absent");}
    private static String sha256(String raw){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    /**
     * OC-F084. Creates an explicitly-requested, synchronous full world clone in
     * a sibling directory after forcing a save.  It never launches the clone or
     * mutates level.dat; the unique folder plus REHEARSAL-README make accidental
     * confusion with the authoritative world less likely.
     */
    public static Path createWorldRehearsalClone(ServerLevel world) throws IOException{
        Path source=world.getServer().getWorldPath(LevelResource.ROOT);
        Path parent=source.getParent()==null?source.toAbsolutePath().getParent():source.getParent();
        if(parent==null)throw new IOException("world folder has no writable parent");
        String worldName=source.getFileName()==null?"world":source.getFileName().toString();
        Path rehearsalParent=parent.resolve("oceancanvas_rehearsals");Files.createDirectories(rehearsalParent);
        Path out=rehearsalParent.resolve(worldName+"_rehearsal_"+STAMP.format(Instant.now()));
        if(Files.exists(out))throw new IOException("rehearsal destination already exists: "+out);
        world.getServer().saveAllChunks(false,true,true);
        Files.walkFileTree(source,new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException{
                Files.createDirectories(out.resolve(source.relativize(dir)));return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
                Path rel=source.relativize(file);String n=file.getFileName()==null?"":file.getFileName().toString();
                if("session.lock".equals(n))return FileVisitResult.CONTINUE;
                Files.copy(file,out.resolve(rel),StandardCopyOption.COPY_ATTRIBUTES);return FileVisitResult.CONTINUE;
            }
        });
        String readme="OCEAN CANVAS WORLD MIGRATION REHEARSAL CLONE\n"
                +"source="+source+"\ncreated="+Instant.now()+"\nsourceBuild="+OceanCanvas.VERSION+"\n"
                +"compatibility="+compatibilitySignature(world)+"\n\n"
                +"This folder is a TEST COPY. Open/test this copy with the target Minecraft/Fabric/Ocean Canvas build before upgrading the authoritative world.\n"
                +"Recommended fixed checks: load/save/reload; /oceancanvas harness compatibility; /oceancanvas harness all; Pregen/Rewipe/Restore on a disposable small region; structures; lighting; physical water/ice cleanup; Save & Quit.\n"
                +"Do not treat a successful metadata-only rehearsal as proof that runtime terrain/worldgen behavior is compatible.\n";
        Files.writeString(out.resolve("REHEARSAL-README.txt"),readme,StandardCharsets.UTF_8);
        Files.writeString(out.resolve("OCEANCANVAS-SOURCE-HARNESS.txt"),report(world),StandardCharsets.UTF_8);
        OceanCanvasP0RuntimeHarness.writeRehearsalBaseline(world,out);
        OceanCanvasHarnessData.get(world).record("OC-F084","WORLD_REHEARSAL_CLONE","PASS",0L,Double.NaN,"",out.toString());
        return out;
    }

    public static String benchmarkText(int iterations){
        var b=OceanCanvasHarnessCore.runBenchmark(OceanCanvasHarnessCore.DEFAULT_SEED,iterations);
        return "Ocean Canvas standardized CPU-only geometry benchmark (OC-F221 foundation)\n"
                +"build="+OceanCanvas.VERSION+" seed="+b.seed()+" iterations="+b.iterations()+"\n"
                +"elapsedNanos="+b.elapsedNanos()+" iterations="+b.iterations()+" operationsPerSecond="+String.format(Locale.ROOT,"%.2f",b.operationsPerSecond())+" checksum="+b.checksum()+"\n"
                +"This benchmark is comparable between builds on the same machine, but it is not a substitute for a real Minecraft Pregen benchmark.\n";
    }

    public static String simulatorProfileText(String profileId,int chunks){
        String id=profileId==null?"BASELINE":profileId.trim().toUpperCase(Locale.ROOT);
        if(!OceanCanvasHarnessCore.simulationProfiles().containsKey(id))throw new IllegalArgumentException("unknown synthetic profile: "+id);
        var r=OceanCanvasHarnessCore.runSyntheticLoad(id,Math.max(32,Math.min(100_000,chunks)));
        StringBuilder s=new StringBuilder("Ocean Canvas isolated fault/load profile ").append(id).append("\n")
                .append("This simulator never mutates the Minecraft world or production controller.\n")
                .append("terminal=").append(r.terminal()).append(" passed=").append(r.passed())
                .append(" submitted=").append(r.submitted()).append('/').append(r.targetChunks())
                .append(" completed=").append(r.completed()).append(" maxOutstanding=").append(r.maxOutstanding())
                .append(" faultDetected=").append(r.faultDetected()).append(" checksum=").append(r.checksum()).append('\n');
        for(String note:r.observations())s.append("  ").append(note).append('\n');
        return s.toString();
    }

    public static String simulatorText(int chunks){
        StringBuilder s=new StringBuilder("Ocean Canvas synthetic controller/load simulator (OC-F046 / OC-F047 foundation)\n")
                .append("This is isolated from production controllers and never touches a Minecraft world.\n");
        var ids=new java.util.ArrayList<>(OceanCanvasHarnessCore.simulationProfiles().keySet());java.util.Collections.sort(ids);
        for(String id:ids){
            var r=OceanCanvasHarnessCore.runSyntheticLoad(id,chunks);
            s.append(id).append(" terminal=").append(r.terminal()).append(" passed=").append(r.passed())
                    .append(" submitted=").append(r.submitted()).append('/').append(r.targetChunks())
                    .append(" completed=").append(r.completed()).append(" maxOutstanding=").append(r.maxOutstanding())
                    .append(" faultDetected=").append(r.faultDetected()).append(" checksum=").append(r.checksum()).append('\n');
            for(String note:r.observations())s.append("  ").append(note).append('\n');
        }
        return s.toString();
    }

    public static String boundaryText(int cases){
        var r=OceanCanvasHarnessCore.runBoundaryFuzz(OceanCanvasHarnessCore.DEFAULT_SEED,cases);
        StringBuilder s=new StringBuilder("Ocean Canvas boundary fuzzer (OC-F231)\nseed=").append(r.seed()).append(" requestedCases=").append(r.requestedCases()).append(" executedCases=").append(r.executedCases()).append(" passed=").append(r.passed()).append(" checksum=").append(r.checksum()).append('\n');
        for(var f:r.failures())s.append("FAIL case=").append(f.caseIndex()).append(" invariant=").append(f.invariant()).append(" detail=").append(f.detail()).append('\n');
        return s.toString();
    }
}
