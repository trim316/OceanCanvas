package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureIntegrityScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * One conservative release-gate view across the core 1.0 subsystems.
 *
 * <p>Source readiness and runtime certification are intentionally different. A gate can become
 * PASS only from independently inspectable current state (for example a verified current terrain
 * deployment) or an explicit runtime certification event recorded against this Forever World.
 * Missing evidence is RUNTIME_REQUIRED, never silently promoted to PASS.</p>
 */
public final class OceanCanvasReleaseReadiness {
    private OceanCanvasReleaseReadiness(){}
    public enum State { PASS, BLOCKED, RUNTIME_REQUIRED }
    public record Gate(String id,String name,State state,String detail){}
    public record Report(List<Gate> gates){
        public boolean sourceReady(){return gates.stream().noneMatch(g->g.state()==State.BLOCKED);}
        public boolean releaseReady(){return gates.stream().allMatch(g->g.state()==State.PASS);}
        public long blocked(){return gates.stream().filter(g->g.state()==State.BLOCKED).count();}
        public long runtimeRequired(){return gates.stream().filter(g->g.state()==State.RUNTIME_REQUIRED).count();}
    }


    /** Append-only operator certification. It does not bypass a currently detected BLOCKED state. */
    public static String certify(ServerLevel world,String gateId,String actor,String evidence){
        String gate=OceanCanvasReleaseEvidencePolicy.normalizeGate(gateId);
        if(gate.equals("OC-R004")||gate.equals("OC-R005"))
            return gate+" is certified automatically by a VERIFIED current Terrain Asset deployment; use /oceancanvas deploy verify instead.";
        if(gate.equals("OC-R010"))return "OC-R010 packaging is derived automatically after OC-R001..OC-R009 all PASS.";
        String ev=evidence==null?"":evidence.trim();
        if(ev.length()<12)return "Certification rejected: provide concrete runtime evidence (at least 12 characters).";
        var identity=OceanCanvasForeverWorldStewardshipData.get(world).identity();
        OceanCanvasForeverWorldData.get(world).addWorldEvent("WORLD",identity.worldUuid(),"VERIFIED",
                "Release gate certified: "+gate,ev,actor,OceanCanvasReleaseEvidencePolicy.sourceRef(gate,net.oceancanvas.mod.OceanCanvas.VERSION),System.currentTimeMillis());
        return gate+" runtime evidence recorded for build "+net.oceancanvas.mod.OceanCanvas.VERSION+". /oceancanvas release audit will still block it if a current invariant fails.";
    }

    /** Append a revocation marker; history remains intact and auditable. */
    public static String revoke(ServerLevel world,String gateId,String actor,String reason){
        String gate=OceanCanvasReleaseEvidencePolicy.normalizeGate(gateId);
        if(gate.equals("OC-R004")||gate.equals("OC-R005")||gate.equals("OC-R010"))return gate+" is derived and cannot be manually revoked.";
        String why=reason==null?"":reason.trim();if(why.isBlank())why="Runtime evidence invalidated; rerun the gate.";
        var identity=OceanCanvasForeverWorldStewardshipData.get(world).identity();
        OceanCanvasForeverWorldData.get(world).addWorldEvent("WORLD",identity.worldUuid(),"NOTE",
                "Release gate evidence revoked: "+gate,"REVOKED: "+why,actor,OceanCanvasReleaseEvidencePolicy.sourceRef(gate,net.oceancanvas.mod.OceanCanvas.VERSION),System.currentTimeMillis());
        return gate+" runtime certification revoked for build "+net.oceancanvas.mod.OceanCanvas.VERSION+".";
    }

    private static String certifiedEvidence(ServerLevel world,String gate){
        var identity=OceanCanvasForeverWorldStewardshipData.get(world).identity();
        return OceanCanvasForeverWorldData.get(world).worldEventsFor("WORLD",identity.worldUuid()).stream()
                .filter(e->OceanCanvasReleaseEvidencePolicy.matchesCurrentBuild(e.sourceRef(),gate,net.oceancanvas.mod.OceanCanvas.VERSION)).findFirst()
                .filter(e->e.eventType().equals("VERIFIED")&&!e.detail().startsWith("REVOKED:"))
                .map(OceanCanvasForeverWorldData.WorldEvent::detail).orElse("");
    }

    private static boolean currentVerifiedDeployment(ServerLevel world){
        var events=OceanCanvasForeverWorldData.get(world);
        for(var asset:OceanCanvasPlanLibraryData.get(world).terrainAssets()){
            var p=OceanCanvasDeploymentService.preflight(world,asset.id());
            if(p.fingerprint().isBlank())continue;
            boolean verified=events.worldEventsFor("TERRAIN_ASSET",asset.id()).stream()
                    .anyMatch(e->e.eventType().equals("VERIFIED")&&e.sourceRef().equals("deployment:"+p.fingerprint()));
            if(verified)return true;
        }
        return false;
    }

    private static State runtimeState(ServerLevel world,String gate,boolean blocked){
        if(blocked)return State.BLOCKED;
        return certifiedEvidence(world,gate).isBlank()?State.RUNTIME_REQUIRED:State.PASS;
    }
    private static String evidenceSuffix(ServerLevel world,String gate){
        String ev=certifiedEvidence(world,gate);return ev.isBlank()?"":" Runtime evidence: "+ev;
    }

    public static Report audit(ServerLevel world){
        var out=new ArrayList<Gate>();
        int structureIssues=0,incomplete=0;
        for(var zone:OceanCanvasPlayerZones.get(world).all()){
            var r=OceanCanvasStructureIntegrityScanner.scanRegion(world,zone);structureIssues+=r.issues();incomplete+=r.incompleteKinds();
        }
        State s1=runtimeState(world,"OC-R001",structureIssues>0||incomplete>0);
        out.add(new Gate("OC-R001","Structures",s1,
                structureIssues>0?structureIssues+" integrity issue(s) detected.":incomplete>0?"Structure evidence scan is incomplete for "+incomplete+" kind(s); load/scan the required coverage before certification.":
                        (s1==State.PASS?"Structure runtime matrix certified.":"Source verifier is clean for available evidence; /locate, save/reload, loot, guardians, determinism and large-area statistics still require runtime proof.")+evidenceSuffix(world,"OC-R001")));

        var deep=OceanCanvasDeepHealthService.scan(world,32);
        State s2=runtimeState(world,"OC-R002",deep.mismatches()>0);
        out.add(new Gate("OC-R002","Physical Canvas",s2,
                deep.mismatches()>0?deep.mismatches()+" metadata/seal contradiction(s).":(s2==State.PASS?"Physical Canvas runtime matrix certified.":"Block/fluid/entity/floor correctness requires completed representative Physical Health scans.")+evidenceSuffix(world,"OC-R002")));

        boolean jobs=net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning();
        State s3=runtimeState(world,"OC-R003",jobs);
        out.add(new Gate("OC-R003","Pregen/Rewipe/Restore",s3,
                jobs?"A terrain job is currently active.":(s3==State.PASS?"Long-duration operation lifecycle matrix certified.":"Scheduler source baseline is preserved; cancel/resume/save/restart/tail-drain remains a runtime gate.")+evidenceSuffix(world,"OC-R003")));

        var assets=OceanCanvasPlanLibraryData.get(world).terrainAssets();
        boolean hasDeployable=assets.stream().anyMatch(a->OceanCanvasDeploymentService.preflight(world,a.id()).ready());
        boolean verifiedDeployment=currentVerifiedDeployment(world);
        State s4=verifiedDeployment?State.PASS:(assets.isEmpty()||hasDeployable?State.RUNTIME_REQUIRED:State.BLOCKED);
        out.add(new Gate("OC-R004","Design -> Commit -> Reality",s4,
                verifiedDeployment?"A current manifest has COMMIT -> DEPLOYED -> VERIFIED evidence.":assets.isEmpty()?"Deployment bridge is source-complete; create/import a Terrain Asset to exercise it.":hasDeployable?"At least one Terrain Asset passes preflight; physical deploy + verify still required.":"Terrain Assets exist, but none pass deployment preflight."));

        boolean pipeline=assets.stream().anyMatch(a->a.revision(a.approvedGaeaRevision())!=null&&a.revision(a.approvedWorldPainterRevision())!=null&&!a.placementData().isBlank());
        State s5=verifiedDeployment?State.PASS:(pipeline?State.RUNTIME_REQUIRED:State.BLOCKED);
        out.add(new Gate("OC-R005","Gaea -> WorldPainter -> Minecraft",s5,
                verifiedDeployment?"A current approved Gaea/WorldPainter manifest is physically deployed and verified.":pipeline?"Approved Gaea and WorldPainter lineage exists; one real external round trip/deployment must be verified.":"No Terrain Asset currently has complete approved Gaea + WorldPainter + placement lineage."));

        var assetHealth=OceanCanvasAssetIntegrityService.scan(world,64);
        State s6=runtimeState(world,"OC-R006",!assetHealth.healthy());
        out.add(new Gate("OC-R006","Physical Health",s6,
                !assetHealth.healthy()?assetHealth.issues()+" broken world-model link(s).":(s6==State.PASS?"Physical Health scan/repair evidence certified.":"Health services are source-complete; representative physical scan/repair verification remains runtime work.")+evidenceSuffix(world,"OC-R006")));

        var forever=OceanCanvasForeverWorldStewardship.health(world);
        State s7=runtimeState(world,"OC-R007",forever.state().equals("INCOMPLETE"));
        out.add(new Gate("OC-R007","Forever World",s7,
                forever.state().equals("INCOMPLETE")?"Forever World state=INCOMPLETE; resolve stewardship findings before certification.":(s7==State.PASS?"Forever World lifecycle matrix certified.":"Forever World state="+forever.state()+"; copy/role/reserve/rehearsal/recovery behavior still requires real save lifecycle exercises.")+evidenceSuffix(world,"OC-R007")));

        State s8=runtimeState(world,"OC-R008",false);
        out.add(new Gate("OC-R008","UX real-client QA",s8,(s8==State.PASS?"Real-client UX matrix certified.":"Minecraft rendering, focus, scaling and interaction require a client playthrough.")+evidenceSuffix(world,"OC-R008")));
        State s9=runtimeState(world,"OC-R009",false);
        out.add(new Gate("OC-R009","Release hardening",s9,(s9==State.PASS?"Failure/recovery compatibility matrix certified.":"Crash/interruption/malformed-metadata/mod-compatibility recovery matrix has not been runtime-certified.")+evidenceSuffix(world,"OC-R009")));

        boolean priorPass=out.stream().allMatch(g->g.state()==State.PASS);
        out.add(new Gate("OC-R010","1.0 packaging",priorPass?State.PASS:out.stream().anyMatch(g->g.state()==State.BLOCKED)?State.BLOCKED:State.RUNTIME_REQUIRED,
                priorPass?"Source package, migration, recovery, limitations and release checklist are complete; all prior gates PASS.":"Source-side packaging is complete, but 1.0 cannot be finalized until OC-R001..OC-R009 all PASS."));
        return new Report(List.copyOf(out));
    }
}
