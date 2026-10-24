package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Safe Design -> Commit -> Reality boundary for externally-authored terrain.
 *
 * Ocean Canvas deliberately does not pretend it can invoke Gaea/WorldPainter in-process. Instead it
 * creates a deterministic, policy-checked deployment contract, records the Commit separately from
 * physical deployment, and requires explicit post-deployment verification before Reality is trusted.
 */
public final class OceanCanvasDeploymentService {
    private OceanCanvasDeploymentService() {}

    public record Gate(String code, boolean pass, String detail) {}
    public record Preflight(String assetId, String assetName, String fingerprint, int minChunkX, int minChunkZ,
                            int maxChunkX, int maxChunkZ, List<Gate> gates) {
        public boolean ready(){return gates.stream().allMatch(Gate::pass);}
        public String summary(){long bad=gates.stream().filter(g->!g.pass()).count();return ready()?"READY":("BLOCKED ("+bad+" gate(s))");}
    }

    public static Preflight preflight(ServerLevel world,String assetId){
        var lib=OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(assetId);
        if(asset==null)return new Preflight(assetId,"", "",0,0,0,0,List.of(new Gate("OC-D001",false,"Unknown Terrain Asset.")));
        var gates=new ArrayList<Gate>();
        var gaea=asset.revision(asset.approvedGaeaRevision());
        var wp=asset.revision(asset.approvedWorldPainterRevision());
        gates.add(new Gate("OC-D010",gaea!=null,"Approved Gaea revision "+(gaea==null?"is missing.":gaea.id()+" exists.")));
        gates.add(new Gate("OC-D011",wp!=null,"Approved WorldPainter revision "+(wp==null?"is missing.":wp.id()+" exists.")));
        gates.add(new Gate("OC-D012",!asset.placementData().isBlank(),asset.placementData().isBlank()?"Placement/coordinate transform is missing.":"Placement transform is recorded."));
        boolean bounds=asset.minX()<=asset.maxX()&&asset.minZ()<=asset.maxZ();
        gates.add(new Gate("OC-D013",bounds,bounds?"Terrain bounds are valid.":"Terrain bounds are inverted."));
        if(gaea!=null&&wp!=null){
            boolean sameBounds=gaea.minX()==wp.minX()&&gaea.minZ()==wp.minZ()&&gaea.maxX()==wp.maxX()&&gaea.maxZ()==wp.maxZ();
            gates.add(new Gate("OC-D014",sameBounds,sameBounds?"Approved Gaea/WorldPainter revisions agree on world bounds.":"Approved Gaea/WorldPainter revisions disagree on bounds."));
            boolean sea=wp.seaLevel()==asset.seaLevel();gates.add(new Gate("OC-D015",sea,sea?"WorldPainter sea level matches Terrain Asset.":"WorldPainter sea level differs from Terrain Asset."));
            boolean north="NORTH_UP".equalsIgnoreCase(wp.orientation());gates.add(new Gate("OC-D016",north,north?"WorldPainter orientation is NORTH_UP.":"WorldPainter orientation must be explicitly reconciled before deployment: "+wp.orientation()));
        }
        int minCx=Math.floorDiv(asset.minX(),16),maxCx=Math.floorDiv(asset.maxX(),16),minCz=Math.floorDiv(asset.minZ(),16),maxCz=Math.floorDiv(asset.maxZ(),16);
        String stewardship=OceanCanvasForeverWorldStewardship.blockRect(world,"DEPLOYMENT",minCx,maxCx,minCz,maxCz,"","","");
        gates.add(new Gate("OC-D020",stewardship.isBlank(),stewardship.isBlank()?"Forever World policy/reserve gate allows deployment.":stewardship));
        var fw=OceanCanvasForeverWorldData.get(world);long protectedCount=fw.protectedBuilds().stream().filter(b->b.chunkX()>=minCx&&b.chunkX()<=maxCx&&b.chunkZ()>=minCz&&b.chunkZ()<=maxCz).count();
        gates.add(new Gate("OC-D021",protectedCount==0,protectedCount==0?"No protected-build chunks intersect deployment.":protectedCount+" protected-build chunk(s) intersect deployment."));
        boolean busy=!safeIdle();gates.add(new Gate("OC-D022",!busy,busy?"A terrain mutation/recovery job is active.":"Terrain mutation systems are idle."));
        var assets=OceanCanvasAssetIntegrityService.scan(world,64);gates.add(new Gate("OC-D023",assets.healthy(),assets.healthy()?"Plan/Project/Terrain Asset links are healthy.":assets.issues()+" broken asset/model link(s) exist."));
        var workflow=OceanCanvasWorkflowGateService.evaluate(world,"DEPLOYMENT");gates.add(new Gate("OC-D024",workflow.pass(),workflow.pass()?"Shared workflow safety gates pass.":workflow.summary()));
        String fingerprint=fingerprint(asset,gaea,wp);
        return new Preflight(asset.id(),asset.name(),fingerprint,minCx,minCz,maxCx,maxCz,List.copyOf(gates));
    }

    /** Commit records immutable intent/evidence; it does not mutate blocks. */
    public static String commit(ServerLevel world,String assetId,String actor){
        var p=preflight(world,assetId);if(!p.ready())return "Deployment Commit blocked: "+p.summary()+". Run /oceancanvas deploy preflight "+assetId+".";
        var data=OceanCanvasForeverWorldData.get(world);
        data.addWorldEvent("TERRAIN_ASSET",p.assetId(),"COMMITTED","Terrain deployment committed",
                "fingerprint="+p.fingerprint()+" chunks=["+p.minChunkX()+","+p.minChunkZ()+"]..["+p.maxChunkX()+","+p.maxChunkZ()+"]",actor,"deployment:"+p.fingerprint(),System.currentTimeMillis());
        OceanCanvasMetadataSnapshotData.get(world).capture(world,"deployment-commit:"+p.assetId(),"Pre-deployment recovery checkpoint for "+p.assetName());
        OceanCanvasForeverWorldStewardshipData.get(world).addLineage("DEPLOYMENT_COMMITTED","",p.assetId()+" "+p.fingerprint());
        return "Committed Terrain Asset '"+p.assetName()+"' for deployment. Fingerprint "+p.fingerprint()+". Physical Reality has NOT been changed yet.";
    }

    /** Called only after the external WorldPainter/import step has physically completed. */
    public static String acknowledgeDeployed(ServerLevel world,String assetId,String fingerprint,String actor){
        var p=preflight(world,assetId);if(!p.fingerprint().equalsIgnoreCase(fingerprint))return "Deployment acknowledgement rejected: manifest fingerprint does not match current approved revisions/placement.";
        boolean committed=OceanCanvasForeverWorldData.get(world).worldEventsFor("TERRAIN_ASSET",assetId).stream()
                .anyMatch(e->e.eventType().equals("COMMITTED")&&e.sourceRef().equals("deployment:"+p.fingerprint()));
        if(!committed)return "Deployment acknowledgement rejected: commit this exact manifest first.";
        var terrain=OceanCanvasTerrainStateData.get(world);
        for(int z=p.minChunkZ();z<=p.maxChunkZ();z++)for(int x=p.minChunkX();x<=p.maxChunkX();x++)terrain.set(ChunkPos.pack(x,z),OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED);
        OceanCanvasForeverWorldData.get(world).addWorldEvent("TERRAIN_ASSET",assetId,"DEPLOYED","Terrain physically deployed","Awaiting Physical Health verification.",actor,"deployment:"+p.fingerprint(),System.currentTimeMillis());
        OceanCanvasForeverWorldStewardship.recordCompleted(world,"DEPLOYMENT",p.minChunkX(),p.maxChunkX(),p.minChunkZ(),p.maxChunkZ(),p.fingerprint());
        return "Deployment recorded for '"+p.assetName()+"'. Reality is now UNVERIFIED until a Physical Health verification is recorded.";
    }

    public static String verify(ServerLevel world,String assetId,String fingerprint,String actor,String evidence){
        var p=preflight(world,assetId);if(!p.fingerprint().equalsIgnoreCase(fingerprint))return "Verification rejected: manifest fingerprint is stale.";
        boolean deployed=OceanCanvasForeverWorldData.get(world).worldEventsFor("TERRAIN_ASSET",assetId).stream().anyMatch(e->e.eventType().equals("DEPLOYED")&&e.sourceRef().equals("deployment:"+p.fingerprint()));
        if(!deployed)return "Verification rejected: this manifest has not been acknowledged as deployed.";
        if(evidence==null||evidence.trim().length()<4)return "Verification rejected: record concrete Physical Health/runtime evidence.";
        OceanCanvasForeverWorldData.get(world).addWorldEvent("TERRAIN_ASSET",assetId,"VERIFIED","Terrain Reality verified",evidence.trim(),actor,"deployment:"+p.fingerprint(),System.currentTimeMillis());
        OceanCanvasForeverWorldStewardshipData.get(world).addLineage("DEPLOYMENT_VERIFIED","",assetId+" "+p.fingerprint());
        return "Terrain Asset '"+p.assetName()+"' verified against Reality for manifest "+p.fingerprint()+".";
    }

    private static boolean safeIdle(){return !net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning();}
    private static String fingerprint(OceanCanvasPlanLibraryData.TerrainAsset a,OceanCanvasPlanLibraryData.AssetRevision g,OceanCanvasPlanLibraryData.AssetRevision w){
        String raw=a.id()+"|"+a.minX()+"|"+a.minZ()+"|"+a.maxX()+"|"+a.maxZ()+"|"+a.seaLevel()+"|"+a.placementData()+"|"+(g==null?"":g.id()+":"+g.fileHash())+"|"+(w==null?"":w.id()+":"+w.fileHash());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0,24);}catch(Exception e){throw new IllegalStateException(e);}
    }
}
