package net.oceancanvas.mod.project;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.project.OceanCanvasHarnessService;
import net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared P1-W3 evidence/diagnostic services.  Everything in this class is read-only with respect
 * to terrain.  It may persist observations, approvals, baselines and reports, but it never writes
 * blocks or bypasses the operation lifecycle.
 */
public final class OceanCanvasP1W3Service {
    private OceanCanvasP1W3Service() {}

    public record DatapackStatus(String currentSignature,String baselineSignature,boolean baselineRecorded,boolean changed,String detail){}
    public record LegacyGroup(String generationVersion,int chunks,String confidence){}
    public record DriftResult(int regions,int checkedRingChunks,int suspiciousChunks,List<String> findings){}
    public record TerraformEstimate(String objectId,int sampledColumns,long addBlocks,long removeBlocks,int unknownColumns,int targetY,String confidence,String detail){}
    public record VerificationResult(String projectId,int objectsChecked,int deviations,int unverified,List<String> findings){}
    public record MilestoneCapture(String id,String summary,String fingerprint){}
    public record PoiIntegrityResult(int chunksChecked,int structureReferences,int poiRecords,int suspicious,int unloaded,List<String> findings){}

    private static volatile long sessionStartedAt;
    private static volatile int sessionStartTasksComplete,sessionStartJournal,sessionStartOperations,sessionStartMarkers;

    public static String minecraftVersion(){
        return FabricLoader.getInstance().getModContainer("minecraft").map(v->v.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** Lightweight stable fingerprint: 25 columns x top/floor/mid block states, never chunk NBT. */
    public static long chunkFingerprint(ServerLevel world,LevelChunk chunk){
        if(chunk==null)return 0L;
        long h=0xcbf29ce484222325L;
        int minY=world.dimensionType().minY();
        int[] p={0,4,8,12,15};
        for(int lx:p)for(int lz:p){
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,lx,lz);
            h=fnv(h,top);
            int mid=Math.max(minY,Math.min(top,(minY+top)/2));
            for(int y:new int[]{Math.max(minY,top),mid,Math.max(minY,Math.min(top,OceanCanvasConfig.get().oceanFloorY()))}){
                h=fnv(h,chunk.getBlockState(new BlockPos(lx,y,lz)).toString().hashCode());
            }
        }
        return h;
    }

    private static final java.util.concurrent.atomic.AtomicLong DEFAULT_CANVAS_EVIDENCE_SKIPS=new java.util.concurrent.atomic.AtomicLong();

    /**
     * Record only materially distinct per-chunk evidence.
     *
     * <p>v253.125.29: a bulk 20k-square Pregen does not need a second 500k-entry
     * observability ledger that repeats the authoritative TerrainStateData CANVAS
     * state for every ordinary chunk. The .28 soak caught a server-thread stall
     * inside SavedDataStorage while this optional P1-W3 ledger was lazily loaded,
     * and the object-heavy map also amplified heap/autosave cost. Default Canvas
     * writes with no explicit biome provenance now stay represented by the compact
     * terrain-state ledger. Per-chunk P1-W3 evidence is retained when a region
     * override/global biome assignment exists, or when the caller says prior
     * evidence must be reconciled (for example a known VANILLA/CUSTOM state).
     * A global biome mask is configuration-level provenance and is intentionally
     * not duplicated into one object record per Canvas chunk.</p>
     */
    public static void recordCanvasChunk(ServerLevel world,LevelChunk chunk){
        recordCanvasChunk(world,chunk,false);
    }

    public static void recordCanvasChunk(ServerLevel world,LevelChunk chunk,boolean reconcilePriorEvidence){
        if(world==null||chunk==null)return;
        int wx=chunk.getPos().getMiddleBlockX(),wz=chunk.getPos().getMiddleBlockZ();
        String override=OceanCanvasPlayerZones.get(world).biomeOverrideAt(wx,OceanCanvasConfig.get().oceanFloorY(),wz);
        boolean hasOverride=override!=null&&!override.isBlank();
        boolean hasGlobalMask=OceanCanvasConfig.get().biomeMaskEnabled();

        if(!reconcilePriorEvidence&&!hasOverride){
            long skipped=DEFAULT_CANVAS_EVIDENCE_SKIPS.incrementAndGet();
            if(skipped<=4L||(skipped&8191L)==0L){
                OceanCanvas.LOGGER.info("(Ocean Canvas) P1W3-BULK-EVIDENCE-COMPACT build={} skippedDefaultCanvasRecords={} action=use-compact-terrain-state-ledger-instead-of-per-chunk-observability-object",
                        OceanCanvas.VERSION,skipped);
            }
            return;
        }

        long key=ChunkPos.pack(chunk.getPos().x(),chunk.getPos().z());
        var data=OceanCanvasP1W3Data.get(world);var old=data.chunk(key);
        String biomeId=override;
        String provenance="INFERRED";String detail;
        if(hasOverride){provenance="USER_ASSIGNED";detail="Region biome override authored by the user.";}
        else if(hasGlobalMask){biomeId=OceanCanvasConfig.get().biomeMaskBiome();provenance="OC_ASSIGNED";detail="World biome mask applied by Ocean Canvas.";}
        else {biomeId=old==null?"":old.biomeId();provenance=old==null?"INFERRED":old.biomeProvenance();detail="Canvas terrain written without an explicit biome override; biome origin is not asserted.";}
        String generation=old!=null&&!old.generationVersion().isBlank()?old.generationVersion():minecraftVersion();
        boolean baseline=old!=null&&old.vanillaBaseline();long fp=baseline?old.vanillaFingerprint():0L;
        data.putChunk(new OceanCanvasP1W3Data.ChunkEvidence(key,baseline,fp,biomeId,provenance,detail,generation,OceanCanvas.VERSION,System.currentTimeMillis()));
    }

    /** Called only after a Restore FULL-regeneration has become the authoritative live chunk. */
    public static void recordRestoredChunk(ServerLevel world,LevelChunk chunk){
        if(world==null||chunk==null)return;
        long key=ChunkPos.pack(chunk.getPos().x(),chunk.getPos().z());
        var data=OceanCanvasP1W3Data.get(world);var old=data.chunk(key);
        String biome=old==null?"":old.biomeId();
        data.putChunk(new OceanCanvasP1W3Data.ChunkEvidence(key,true,chunkFingerprint(world,chunk),biome,"RESTORED",
                "Vanilla FULL regeneration completed through Ocean Canvas Restore; fingerprint is authoritative for this restored state.",
                minecraftVersion(),OceanCanvas.VERSION,System.currentTimeMillis()));
    }

    public static String vanillaBaselineStatus(ServerLevel world,int limit){
        int baseline=0,loadedMatch=0,loadedMismatch=0,unloaded=0;List<String> issues=new ArrayList<>();
        for(var e:OceanCanvasP1W3Data.get(world).chunks()){
            if(!e.vanillaBaseline())continue;baseline++;
            LevelChunk c=world.getChunkSource().getChunkNow(ChunkPos.getX(e.chunkKey()),ChunkPos.getZ(e.chunkKey()));
            if(c==null){unloaded++;continue;}
            long fp=chunkFingerprint(world,c);if(fp==e.vanillaFingerprint())loadedMatch++;else{loadedMismatch++;if(issues.size()<Math.max(1,limit))issues.add("chunk "+ChunkPos.getX(e.chunkKey())+","+ChunkPos.getZ(e.chunkKey())+" fingerprint drift");}
        }
        return "baseline="+baseline+" loadedMatch="+loadedMatch+" loadedMismatch="+loadedMismatch+" unloaded="+unloaded+(issues.isEmpty()?"":" · "+String.join("; ",issues));
    }

    /** Datapack/worldgen awareness keyed to selected server data-pack IDs plus dependency/config signature. */
    public static DatapackStatus datapackStatus(ServerLevel world){
        List<String> ids=new ArrayList<>(world.getServer().getPackRepository().getSelectedIds());ids.sort(String::compareTo);
        String compat=OceanCanvasHarnessService.compatibilitySignature(world);
        String config=OceanCanvasConfig.get().biomeMaskEnabled()+"|"+OceanCanvasConfig.get().biomeMaskBiome()+"|"+
                OceanCanvasConfig.get().shipwrecksRule()+"|"+OceanCanvasConfig.get().naturalOceanRuinsRule()+"|"+
                OceanCanvasConfig.get().buriedTreasureRule()+"|"+OceanCanvasConfig.get().naturalOceanMonumentsRule()+"|"+OceanCanvasConfig.get().naturalRuinedPortalsRule();
        String detail="packs="+String.join(",",ids)+" | "+compat.substring(Math.max(0,compat.indexOf(' ')+1))+" | worldgenConfig="+config;
        String current=sha256(detail);var d=OceanCanvasP1W3Data.get(world);String prior=d.worldgenBaselineSignature();
        return new DatapackStatus(current,prior,!prior.isBlank(),!prior.isBlank()&&!prior.equals(current),detail);
    }
    public static DatapackStatus acceptDatapackBaseline(ServerLevel world){var s=datapackStatus(world);OceanCanvasP1W3Data.get(world).setWorldgenBaseline(s.currentSignature(),s.detail());return datapackStatus(world);}

    public static List<LegacyGroup> legacyGroups(ServerLevel world){
        Map<String,Integer> c=new LinkedHashMap<>();Map<String,String> conf=new HashMap<>();
        for(var e:OceanCanvasP1W3Data.get(world).chunks()){
            String v=e.generationVersion().isBlank()?"UNKNOWN":e.generationVersion();c.merge(v,1,Integer::sum);
            conf.put(v,e.generationVersion().isBlank()?"INFERRED":"AUTHORITATIVE");
        }
        return c.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).map(e->new LegacyGroup(e.getKey(),e.getValue(),conf.get(e.getKey()))).toList();
    }

    /** Syncs durable OC-known structure provenance from the persisted relocation ledger. */
    public static void syncStructureEvidence(ServerLevel world){
        var data=OceanCanvasP1W3Data.get(world);int i=0;long now=System.currentTimeMillis();
        for(var s:OceanCanvasProtectedData.get(world).relocatedStructureSnapshots()){
            var b=s.bounds();String id="relocated_"+s.kind()+"_"+b.minX()+"_"+b.minZ()+"_"+(i++);String detail="Persisted relocated structure footprint.";
            var old=data.structure(id);
            // v253.125.38: health/upgrade reads call this sync frequently. Do not
            // rewrite identical durable evidence with a new timestamp and dirty the
            // SavedData on every read-only diagnostic pass.
            if(old!=null && old.kind().equalsIgnoreCase(s.kind()) && old.provenance().equals("OC_RELOCATED")
                    && old.minX()==b.minX() && old.minY()==b.minY() && old.minZ()==b.minZ()
                    && old.maxX()==b.maxX() && old.maxY()==b.maxY() && old.maxZ()==b.maxZ()
                    && old.detail().equals(detail)) continue;
            data.putStructure(new OceanCanvasP1W3Data.StructureEvidence(id,s.kind(),"OC_RELOCATED",b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),detail,now));
        }
    }

    public static DriftResult boundaryDriftAudit(ServerLevel world){
        var zones=OceanCanvasPlayerZones.get(world).all();var states=OceanCanvasTerrainStateData.get(world);Set<Long> allIntended=new HashSet<>();
        for(var z:zones)allIntended.addAll(z.exactChunks());
        int checked=0,suspicious=0;List<String> findings=new ArrayList<>();
        for(var z:zones){Set<Long> own=new HashSet<>(z.exactChunks());Set<Long> ring=new HashSet<>();for(long p:own){int x=ChunkPos.getX(p),zz=ChunkPos.getZ(p);for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){if(dx==0&&dz==0)continue;long q=ChunkPos.pack(x+dx,zz+dz);if(!own.contains(q))ring.add(q);}}
            for(long q:ring){checked++;if(states.get(q)!=OceanCanvasTerrainStateData.TerrainState.UNKNOWN&&!allIntended.contains(q)){suspicious++;if(findings.size()<32)findings.add(z.name()+": modified chunk outside boundary at "+ChunkPos.getX(q)+","+ChunkPos.getZ(q));}}
        }
        if(suspicious>0)incident(world,"HIGH","BOUNDARY_DRIFT","regions",suspicious+" explicit terrain-state chunk(s) lie in immediate outside rings: "+(findings.isEmpty()?"":findings.get(0)),"AUTHORITATIVE");
        return new DriftResult(zones.size(),checked,suspicious,List.copyOf(findings));
    }

    public static void refreshHealthInbox(ServerLevel world){
        var deep=OceanCanvasDeepHealthService.scan(world,64);if(deep.mismatches()>0)incident(world,"HIGH","METADATA","terrain",deep.mismatches()+" terrain metadata mismatch(es).","AUTHORITATIVE");
        var links=OceanCanvasAssetIntegrityService.scan(world,64);if(links.issues()>0)incident(world,"MEDIUM","WORKFLOW_LINK","workspace",links.issues()+" stale/broken workflow link(s).","AUTHORITATIVE");
        var pack=datapackStatus(world);if(pack.changed())incident(world,"HIGH","DATAPACK_CHANGE","worldgen", "Selected datapack/dependency/worldgen signature differs from accepted baseline.","AUTHORITATIVE");
        var drift=boundaryDriftAudit(world);if(drift.suspiciousChunks()>0)incident(world,"HIGH","BOUNDARY_DRIFT","regions",drift.suspiciousChunks()+" suspicious outside-boundary chunk(s).","AUTHORITATIVE");
        var canary=OceanCanvasBoundaryCanaryData.get(world).latestResult();if(canary!=null&&"FAIL".equals(canary.state()))incident(world,"CRITICAL","CANARY","operations",canary.detail(),"AUTHORITATIVE");
    }

    private static void incident(ServerLevel world,String severity,String kind,String subject,String detail,String confidence){
        var d=OceanCanvasP1W3Data.get(world);var old=d.findOpenIncident(kind,subject);long now=System.currentTimeMillis();String id=old==null?d.newId("incident"):old.id();long created=old==null?now:old.createdAt();
        d.putIncident(new OceanCanvasP1W3Data.HealthIncident(id,created,now,severity,kind,subject,detail,confidence,"OPEN"));
    }

    public static String supportExplainer(ServerLevel world){
        refreshHealthInbox(world);var d=OceanCanvasP1W3Data.get(world);var score=OceanCanvasWorldHealthScorecard.snapshot(world);var pack=datapackStatus(world);
        long open=d.incidents().stream().filter(OceanCanvasP1W3Data.HealthIncident::open).count();long critical=d.incidents().stream().filter(v->v.open()&&(v.severity().equals("CRITICAL")||v.severity().equals("HIGH"))).count();
        StringBuilder s=new StringBuilder("Ocean Canvas support bundle explainer\n\nWHAT THIS SAYS\n");
        s.append("Open health inbox: ").append(open).append(" (high/critical ").append(critical).append(")\n");
        s.append("Datapack/worldgen baseline: ").append(!pack.baselineRecorded()?"NOT RECORDED":pack.changed()?"CHANGED":"MATCH").append("\n");
        s.append("Vanilla baseline: ").append(vanillaBaselineStatus(world,4)).append("\n\nSTEWARDSHIP COMPONENTS\n");
        for(var c:score.components())s.append(c.label()).append(": ").append(c.state()).append(" — ").append(c.detail()).append('\n');
        s.append("\nOPEN INCIDENTS\n");int n=0;for(var i:d.incidents()){if(!i.open())continue;s.append(i.severity()).append(' ').append(i.kind()).append(" [").append(i.confidence()).append("]: ").append(i.detail()).append('\n');if(++n>=20)break;}
        s.append("\nInterpretation: AUTHORITATIVE means directly observed/persisted evidence; INFERRED means Ocean Canvas is deliberately not claiming physical truth. No item in this report auto-repairs terrain.\n");
        return s.toString();
    }

    /** Snapshot exact explicit terrain classifications for a bounded region; used by before/after split. */
    public static OceanCanvasP1W3Data.WorldSnapshot captureRegionSnapshot(ServerLevel world,String regionName,String label){
        var zone=OceanCanvasPlayerZones.get(world).zoneByName(regionName);if(zone==null)throw new IllegalArgumentException("unknown region");var states=OceanCanvasTerrainStateData.get(world);StringBuilder p=new StringBuilder();boolean truncated=false;int n=0;
        var keys=new ArrayList<>(zone.exactChunks());keys.sort(Long::compareUnsigned);for(long k:keys){if(n>=4096){truncated=true;break;}if(n++>0)p.append(',');p.append(Long.toUnsignedString(k)).append('=').append(states.get(k).name());}
        var d=OceanCanvasP1W3Data.get(world);var snap=new OceanCanvasP1W3Data.WorldSnapshot(d.newId("snapshot"),label,regionName,System.currentTimeMillis(),p.toString(),true,truncated,"Explicit terrain-state snapshot; no chunk NBT copied.");d.putSnapshot(snap);return snap;
    }

    public static MilestoneCapture captureMilestone(ServerLevel world,String projectId,String milestoneId){
        var work=OceanCanvasWorkspaceData.get(world);var project=work.project(projectId);if(project==null)throw new IllegalArgumentException("unknown project");
        boolean milestoneExists=project.milestoneRecords().stream().anyMatch(m->m.id().equals(milestoneId));if(!milestoneExists)throw new IllegalArgumentException("unknown milestone");
        int processed=OceanCanvasProtectedData.get(world).processedChunkCount(),regions=OceanCanvasPlayerZones.get(world).all().size();var plan=OceanCanvasPlanningData.get(world);int objects=0,impl=0;
        for(String id:project.planningObjectIds()){var o=plan.object(id);if(o!=null){objects++;if(o.implemented())impl++;}}
        long healthObserved=OceanCanvasDeepHealthService.scan(world,64).mismatches()+OceanCanvasAssetIntegrityService.scan(world,64).issues();
        int health=(int)Math.min(Integer.MAX_VALUE,Math.max(0L,healthObserved));int complete=(int)work.tasks().stream().filter(t->t.projectId().equals(project.id())&&t.terminal()).count();
        String material=project.id()+"|"+milestoneId+"|"+regions+"|"+processed+"|"+objects+"|"+impl+"|"+health+"|"+complete;String fp=sha256(material);var prior=OceanCanvasP1W3Data.get(world).milestoneStates(project.id());
        String diff=prior.isEmpty()?"Initial milestone state.":diffMilestone(prior.get(prior.size()-1),regions,processed,objects,impl,health,complete);var data=OceanCanvasP1W3Data.get(world);var state=new OceanCanvasP1W3Data.MilestoneState(data.newId("milestone_state"),project.id(),milestoneId,System.currentTimeMillis(),regions,processed,objects,impl,health,complete,fp,diff);data.putMilestoneState(state);return new MilestoneCapture(state.id(),diff,fp);
    }
    private static String diffMilestone(OceanCanvasP1W3Data.MilestoneState p,int r,int pc,int po,int i,int h,int t){return "Since previous: regions "+signed(r-p.regions())+", processed "+signed(pc-p.processed())+", plan objects "+signed(po-p.planObjects())+", implemented "+signed(i-p.implemented())+", health issues "+signed(h-p.healthIssues())+", completed tasks "+signed(t-p.tasksComplete())+".";}

    public static TerraformEstimate terraformEstimate(ServerLevel world,String objectId,int targetY){
        var o=OceanCanvasPlanningData.get(world).object(objectId);if(o==null)throw new IllegalArgumentException("unknown planning object");if(o.points().isEmpty())return new TerraformEstimate(objectId,0,0,0,0,targetY,"UNVERIFIED","Object has no geometry.");
        int sampled=0,unknown=0;long add=0,remove=0;Set<Long> visited=new HashSet<>();
        // Bounded representative sampling along object points and connecting segments every <=8 blocks.
        for(int a=0;a<o.points().size();a++){var p=o.points().get(a);var q=o.points().get(Math.min(a+1,o.points().size()-1));double dist=Math.hypot(q.x()-p.x(),q.z()-p.z());int steps=Math.max(1,Math.min(512,(int)Math.ceil(dist/8.0)));for(int j=0;j<=steps;j++){double f=j/(double)steps;int x=(int)Math.round(p.x()+(q.x()-p.x())*f),z=(int)Math.round(p.z()+(q.z()-p.z())*f);long key=(((long)x)<<32)^(z&0xffffffffL);if(!visited.add(key))continue;sampled++;if(sampled>4096){unknown++;continue;}int y=world.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);if(y<targetY)add+=targetY-y;else remove+=y-targetY;}}
        String confidence=unknown==0?"AUTHORITATIVE_SAMPLE":"INFERRED";return new TerraformEstimate(objectId,Math.min(sampled,4096),add,remove,unknown,targetY,confidence,"Column estimate sampled at <=8-block intervals; values are planning quantities, not an execution command.");
    }

    public static VerificationResult verifyProject(ServerLevel world,String projectId){
        var work=OceanCanvasWorkspaceData.get(world);var p=work.project(projectId);if(p==null)throw new IllegalArgumentException("unknown project");var plan=OceanCanvasPlanningData.get(world);var data=OceanCanvasP1W3Data.get(world);int checked=0,deviations=0,unverified=0;List<String> findings=new ArrayList<>();
        for(String id:p.planningObjectIds()){var o=plan.object(id);if(o==null||!o.implemented())continue;checked++;if(o.elevationProfile().isEmpty()||o.points().isEmpty()){unverified++;if(findings.size()<32)findings.add(o.name()+": no elevation profile; physical conformance UNVERIFIED");continue;}
            int mismatches=0,samples=0;for(int n=0;n<o.points().size()&&n<64;n++){var pt=o.points().get(n);double fraction=o.points().size()<=1?0:n/(double)(o.points().size()-1);double expected=elevationAt(o,fraction);int actual=world.getHeight(Heightmap.Types.WORLD_SURFACE,pt.x(),pt.z());samples++;if(Math.abs(actual-expected)>=4)mismatches++;}
            if(mismatches>0){var prior=data.deviationFor(projectId,o.id());String classification=prior==null?"DEFECT":prior.classification();if(!"ACCEPTED".equals(classification))deviations++;String detail=o.name()+": "+mismatches+"/"+samples+" samples differ >=4 blocks; classification "+classification;if(findings.size()<32)findings.add(detail);if(prior==null)data.putDeviation(new OceanCanvasP1W3Data.Deviation(data.newId("deviation"),projectId,o.id(),"DEFECT",detail,System.currentTimeMillis()));}
        }
        if(deviations>0)incident(world,"MEDIUM","BUILD_DEVIATION",projectId,deviations+" unresolved build deviation(s) in project.","AUTHORITATIVE");return new VerificationResult(projectId,checked,deviations,unverified,List.copyOf(findings));
    }
    private static double elevationAt(OceanCanvasPlanningData.PlanningObject o,double fraction){var e=o.elevationProfile();if(e.size()==1)return e.get(0).y();double pos=fraction*(e.size()-1);int a=Math.max(0,Math.min(e.size()-1,(int)Math.floor(pos))),b=Math.min(e.size()-1,a+1);double f=pos-a;return e.get(a).y()+(e.get(b).y()-e.get(a).y())*f;}

    public static boolean classifyDeviation(ServerLevel world,String projectId,String objectId,String classification,String note){String c=classification==null?"":classification.trim().toUpperCase(Locale.ROOT);if(!Set.of("ACCEPTED","TEMPORARY","DEFECT").contains(c))return false;var d=OceanCanvasP1W3Data.get(world);var old=d.deviationFor(projectId,objectId);String id=old==null?d.newId("deviation"):old.id();d.putDeviation(new OceanCanvasP1W3Data.Deviation(id,projectId,objectId,c,note,System.currentTimeMillis()));return true;}

    /**
     * Bounded read-only POI/structure coherence scan. Loaded modified chunks are checked against
     * vanilla's actual PoiManager records and PoiTypes state registry; unloaded chunks remain
     * explicitly UNVERIFIED and are never force-loaded for diagnostics.
     */
    public static PoiIntegrityResult poiIntegrity(ServerLevel world,int limit){
        syncStructureEvidence(world);int bounded=Math.max(1,limit);List<String> findings=new ArrayList<>();
        var poi=world.getPoiManager();final int[] counts=new int[5]; // chunks, refs, poiRecords, suspicious, unloaded
        int visited=OceanCanvasTerrainStateData.get(world).forEachExplicitState(bounded,(chunkKey,state)->{
            counts[0]++;int cx=ChunkPos.getX(chunkKey),cz=ChunkPos.getZ(chunkKey);LevelChunk c=world.getChunkSource().getChunkNow(cx,cz);if(c==null){counts[4]++;return;}
            int chunkProblems=0,before=counts[1];for(var entry:c.getAllReferences().entrySet())counts[1]+=entry.getValue().size();
            var records=poi.getInChunk(holder->true,new ChunkPos(cx,cz),PoiManager.Occupancy.ANY).limit(512).iterator();
            while(records.hasNext()){var record=records.next();counts[2]++;BlockPos pos=record.getPos();var blockState=world.getBlockState(pos);var expected=PoiTypes.forState(blockState);if(expected.isEmpty()||!expected.get().equals(record.getPoiType())){chunkProblems++;if(findings.size()<32)findings.add("POI mismatch at "+pos.getX()+","+pos.getY()+","+pos.getZ()+": stored type does not match current block state.");}}
            if(state==OceanCanvasTerrainStateData.TerrainState.CANVAS&&counts[1]>before){chunkProblems++;if(findings.size()<32)findings.add("chunk "+cx+","+cz+" is CANVAS but retains "+(counts[1]-before)+" structure reference(s).");}
            if(chunkProblems>0)counts[3]++;
        });
        if(counts[3]>0)incident(world,"MEDIUM","POI_INTEGRITY","modified_chunks",counts[3]+" loaded modified chunk(s) have POI/structure coherence findings.","AUTHORITATIVE");
        return new PoiIntegrityResult(visited,counts[1],counts[2],counts[3],counts[4],List.copyOf(findings));
    }

    public static void startSession(ServerLevel world){
        sessionStartedAt=System.currentTimeMillis();
        var w=OceanCanvasWorkspaceData.get(world);
        sessionStartTasksComplete=(int)w.tasks().stream().filter(OceanCanvasWorkspaceData.GeoTask::terminal).count();
        sessionStartJournal=w.journal().size();
        sessionStartOperations=OceanCanvasOperationHistoryData.get(world).recent().size();
        sessionStartMarkers=w.atlasFeatures().size();

        // v253.125.29: eagerly migrate the old object-heavy bulk evidence before a
        // resumed Pregen can first-touch it from flattenChunk. This deliberately
        // moves the one-time SavedData/NBT read to SERVER_STARTED instead of the
        // latency-sensitive terrain controller path that the .28 watchdog caught.
        long compactStarted=System.nanoTime();
        var p1=OceanCanvasP1W3Data.get(world);
        var config=OceanCanvasConfig.get();
        var compact=p1.compactRedundantDefaultCanvasEvidence(
                OceanCanvasTerrainStateData.get(world),config.biomeMaskEnabled(),config.biomeMaskBiome());
        if(compact.before()>0||compact.removed()>0){
            OceanCanvas.LOGGER.info("(Ocean Canvas) P1W3-BULK-EVIDENCE-MIGRATION build={} before={} removed={} removedInferredCanvas={} removedCurrentGlobalMask={} retained={} retainedVanillaBaseline={} retainedNonCanvas={} retainedDistinctEvidence={} currentMaskEnabled={} currentMaskBiome={} elapsedMs={} action=eager-startup-compaction-before-pregen-hot-path",
                    OceanCanvas.VERSION,compact.before(),compact.removed(),compact.removedInferredCanvas(),compact.removedCurrentGlobalMask(),
                    compact.retained(),compact.retainedVanillaBaseline(),compact.retainedNonCanvas(),compact.retainedDistinctEvidence(),
                    config.biomeMaskEnabled(),config.biomeMaskBiome(),(System.nanoTime()-compactStarted)/1_000_000L);
        }
    }
    public static OceanCanvasP1W3Data.SessionRecap finishSession(ServerLevel world){long end=System.currentTimeMillis();var w=OceanCanvasWorkspaceData.get(world);int tasks=Math.max(0,(int)w.tasks().stream().filter(OceanCanvasWorkspaceData.GeoTask::terminal).count()-sessionStartTasksComplete);int journal=Math.max(0,w.journal().size()-sessionStartJournal);int ops=Math.max(0,OceanCanvasOperationHistoryData.get(world).recent().size()-sessionStartOperations);int markers=Math.max(0,sessionStartMarkers-w.atlasFeatures().size());String summary="Session: "+tasks+" task(s) completed, "+journal+" journal/region change(s), "+markers+" marker(s) resolved, "+ops+" major operation history event(s).";var d=OceanCanvasP1W3Data.get(world);var r=new OceanCanvasP1W3Data.SessionRecap(d.newId("recap"),sessionStartedAt,end,tasks,journal,markers,ops,summary);d.putRecap(r);return r;}

    public static String confidenceSummary(ServerLevel world){var d=OceanCanvasP1W3Data.get(world);long a=d.incidents().stream().filter(v->v.confidence().equals("AUTHORITATIVE")).count(),i=d.incidents().stream().filter(v->v.confidence().equals("INFERRED")).count(),u=d.incidents().stream().filter(v->v.confidence().equals("USER_DEFINED")).count();return "authoritative="+a+" inferred="+i+" userDefined="+u;}

    private static long fnv(long h,long v){h^=v;return h*0x100000001b3L;}
    private static String signed(int n){return n>=0?"+"+n:Integer.toString(n);}
    private static String sha256(String raw){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&0xff));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
