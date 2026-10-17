package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded evidence/state ledger for the P1-W3 observability tranche.
 *
 * <p>This deliberately stores evidence, classifications and workflow checkpoints only. It never
 * stores terrain or chunk NBT, and none of its health records can mutate the world. Destructive
 * recipe steps still have to pass the existing server-authored preview/token/lifecycle path.</p>
 */
public final class OceanCanvasP1W3Data extends SavedData {
    public static final int CURRENT_SCHEMA=1;
    private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"p1w3_evidence");
    private static final int MAX_CHUNK_EVIDENCE=500_000;
    private static final int MAX_STRUCTURES=8192;
    private static final int MAX_RECIPES=32;
    private static final int MAX_INCIDENTS=256;
    private static final int MAX_DEVIATIONS=1024;
    private static final int MAX_MILESTONES=512;
    private static final int MAX_SNAPSHOTS=24;
    private static final int MAX_RECAPS=64;

    public record ChunkEvidence(long chunkKey,boolean vanillaBaseline,long vanillaFingerprint,String biomeId,
                                String biomeProvenance,String biomeDetail,String generationVersion,
                                String observedBuild,long updatedAt){
        public ChunkEvidence{biomeId=safe(biomeId);biomeProvenance=upper(biomeProvenance,"INFERRED");biomeDetail=safe(biomeDetail);
            generationVersion=safe(generationVersion);observedBuild=safe(observedBuild);}
    }
    public record StructureEvidence(String id,String kind,String provenance,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,String detail,long updatedAt){
        public StructureEvidence{id=normalizeId(id,"structure");kind=upper(kind,"structure");provenance=upper(provenance,"INFERRED");detail=safe(detail);}
        public boolean contains(int x,int z){return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}
    }
    public record MaintenanceRecipe(String id,String name,List<String> steps,long updatedAt){
        public MaintenanceRecipe{id=normalizeId(id,"recipe");name=normalizeName(name,"Maintenance recipe");steps=steps==null?List.of():steps.stream().map(OceanCanvasP1W3Data::safe).filter(v->!v.isBlank()).limit(24).toList();}
    }
    public record RecipeRun(String recipeId,int stepIndex,String state,String detail,long updatedAt){
        public RecipeRun{recipeId=normalizeId(recipeId,"recipe");stepIndex=Math.max(0,stepIndex);state=upper(state,"IDLE");detail=safe(detail);}
    }
    public record HealthIncident(String id,long createdAt,long updatedAt,String severity,String kind,String subject,String detail,String confidence,String state){
        public HealthIncident{id=normalizeId(id,"incident");severity=upper(severity,"INFO");kind=upper(kind,"GENERAL");subject=safe(subject);detail=safe(detail);confidence=upper(confidence,"INFERRED");state=upper(state,"OPEN");}
        public boolean open(){return "OPEN".equals(state);}
    }
    public record Deviation(String id,String projectId,String objectId,String classification,String note,long updatedAt){
        public Deviation{id=normalizeId(id,"deviation");projectId=normalizeId(projectId,"project");objectId=normalizeId(objectId,"object");classification=upper(classification,"DEFECT");note=safe(note);}
    }
    public record MilestoneState(String id,String projectId,String milestoneId,long epochMillis,int regions,int processed,
                                 int planObjects,int implemented,int healthIssues,int tasksComplete,String fingerprint,String summary){
        public MilestoneState{id=normalizeId(id,"milestone_state");projectId=normalizeId(projectId,"project");milestoneId=normalizeId(milestoneId,"milestone");fingerprint=safe(fingerprint);summary=safe(summary);}
    }
    /** Bounded chunk-state scan used by the before/after split viewer. packedChunks is key=state,key=state... */
    public record WorldSnapshot(String id,String label,String scope,long epochMillis,String packedChunks,boolean authoritative,boolean truncated,String detail){
        public WorldSnapshot{id=normalizeId(id,"snapshot");label=normalizeName(label,"World snapshot");scope=safe(scope);packedChunks=safe(packedChunks);detail=safe(detail);}
    }
    public record SessionRecap(String id,long startedAt,long endedAt,int tasksCompleted,int regionsChanged,int markersResolved,int operations,String summary){
        public SessionRecap{id=normalizeId(id,"recap");summary=safe(summary);}
    }

    private static final Codec<OceanCanvasP1W3Data> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema),
            Codec.STRING.listOf().optionalFieldOf("chunks",List.of()).forGetter(d->d.encodeChunks()),
            Codec.STRING.listOf().optionalFieldOf("structures",List.of()).forGetter(d->d.encodeStructures()),
            Codec.STRING.listOf().optionalFieldOf("recipes",List.of()).forGetter(d->d.encodeRecipes()),
            Codec.STRING.optionalFieldOf("recipeRun","").forGetter(d->d.recipeRun==null?"":encodeRun(d.recipeRun)),
            Codec.STRING.listOf().optionalFieldOf("incidents",List.of()).forGetter(d->d.encodeIncidents()),
            Codec.STRING.listOf().optionalFieldOf("deviations",List.of()).forGetter(d->d.encodeDeviations()),
            Codec.STRING.listOf().optionalFieldOf("milestoneStates",List.of()).forGetter(d->d.encodeMilestones()),
            Codec.STRING.listOf().optionalFieldOf("snapshots",List.of()).forGetter(d->d.encodeSnapshots()),
            Codec.STRING.listOf().optionalFieldOf("recaps",List.of()).forGetter(d->d.encodeRecaps()),
            Codec.STRING.optionalFieldOf("worldgenBaselineSignature","").forGetter(d->d.worldgenBaselineSignature),
            Codec.STRING.optionalFieldOf("worldgenBaselineDetail","").forGetter(d->d.worldgenBaselineDetail),
            Codec.LONG.optionalFieldOf("worldgenBaselineAt",0L).forGetter(d->d.worldgenBaselineAt)
    ).apply(i,OceanCanvasP1W3Data::new));
    public static final SavedDataType<OceanCanvasP1W3Data> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasP1W3Data::new,CODEC,null);

    private int schema;
    private final LinkedHashMap<Long,ChunkEvidence> chunks=new LinkedHashMap<>();
    private final LinkedHashMap<String,StructureEvidence> structures=new LinkedHashMap<>();
    private final LinkedHashMap<String,MaintenanceRecipe> recipes=new LinkedHashMap<>();
    private RecipeRun recipeRun;
    private final ArrayList<HealthIncident> incidents=new ArrayList<>();
    private final LinkedHashMap<String,Deviation> deviations=new LinkedHashMap<>();
    private final ArrayList<MilestoneState> milestoneStates=new ArrayList<>();
    private final ArrayList<WorldSnapshot> snapshots=new ArrayList<>();
    private final ArrayList<SessionRecap> recaps=new ArrayList<>();
    private String worldgenBaselineSignature="",worldgenBaselineDetail="";private long worldgenBaselineAt=0L;

    public OceanCanvasP1W3Data(){this(CURRENT_SCHEMA,List.of(),List.of(),List.of(),"",List.of(),List.of(),List.of(),List.of(),List.of(),"","",0L);}
    private OceanCanvasP1W3Data(int schema,List<String> chunks,List<String> structures,List<String> recipes,String run,List<String> incidents,
                                List<String> deviations,List<String> milestones,List<String> snapshots,List<String> recaps,
                                String worldgenBaselineSignature,String worldgenBaselineDetail,long worldgenBaselineAt){
        this.schema=Math.max(1,schema);loadMap(chunks,MAX_CHUNK_EVIDENCE,this.chunks,OceanCanvasP1W3Data::decodeChunk,ChunkEvidence::chunkKey);
        loadMap(structures,MAX_STRUCTURES,this.structures,OceanCanvasP1W3Data::decodeStructure,StructureEvidence::id);
        loadMap(recipes,MAX_RECIPES,this.recipes,OceanCanvasP1W3Data::decodeRecipe,MaintenanceRecipe::id);this.recipeRun=decodeRun(run);
        loadList(incidents,MAX_INCIDENTS,this.incidents,OceanCanvasP1W3Data::decodeIncident);loadMap(deviations,MAX_DEVIATIONS,this.deviations,OceanCanvasP1W3Data::decodeDeviation,Deviation::id);
        loadList(milestones,MAX_MILESTONES,this.milestoneStates,OceanCanvasP1W3Data::decodeMilestone);loadList(snapshots,MAX_SNAPSHOTS,this.snapshots,OceanCanvasP1W3Data::decodeSnapshot);
        loadList(recaps,MAX_RECAPS,this.recaps,OceanCanvasP1W3Data::decodeRecap);this.worldgenBaselineSignature=safe(worldgenBaselineSignature);this.worldgenBaselineDetail=safe(worldgenBaselineDetail);this.worldgenBaselineAt=worldgenBaselineAt;
    }
    public static OceanCanvasP1W3Data get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}
    public int schema(){return schema;}

    public ChunkEvidence chunk(long key){return chunks.get(key);} public List<ChunkEvidence> chunks(){return List.copyOf(chunks.values());}
    public int chunkEvidenceCount(){return chunks.size();}
    public void putChunk(ChunkEvidence e){chunks.remove(e.chunkKey());chunks.put(e.chunkKey(),e);trimMap(chunks,MAX_CHUNK_EVIDENCE);setDirty();}

    /** Detailed v253.125.30 migration accounting so a future world can explain why
     * any legacy per-chunk evidence remains resident instead of silently keeping a
     * 500k-entry object ledger. */
    public record BulkEvidenceCompactionResult(int before,int removedInferredCanvas,int removedCurrentGlobalMask,
                                               int retainedVanillaBaseline,int retainedNonCanvas,
                                               int retainedDistinctEvidence,int retained){
        public int removed(){return removedInferredCanvas+removedCurrentGlobalMask;}
    }

    /**
     * v253.125.30 migration: compact both historical default-INFERRED Canvas rows and
     * the older per-chunk OC_ASSIGNED rows created by the uniform world biome mask.
     * The latter are redundant only when the CURRENT global mask is enabled and the
     * stored biome id/detail still exactly describe that same configuration. Restored
     * baselines, non-Canvas terrain, region overrides and any other provenance remain.
     * Iterates the backing map directly so a large legacy ledger is not copied first.
     */
    public BulkEvidenceCompactionResult compactRedundantDefaultCanvasEvidence(
            OceanCanvasTerrainStateData terrainState, boolean globalMaskEnabled, String globalMaskBiome){
        int before=chunks.size();
        if(terrainState==null||chunks.isEmpty())return new BulkEvidenceCompactionResult(before,0,0,0,0,before,before);
        String currentMask=safe(globalMaskBiome);
        int removedInferred=0,removedGlobal=0,baseline=0,nonCanvas=0,distinct=0;
        var it=chunks.entrySet().iterator();
        while(it.hasNext()){
            var e=it.next().getValue();
            if(e==null){distinct++;continue;}
            if(e.vanillaBaseline()){baseline++;continue;}
            if(terrainState.get(e.chunkKey())!=OceanCanvasTerrainStateData.TerrainState.CANVAS){nonCanvas++;continue;}
            String detail=e.biomeDetail();
            boolean inferredDefault=e.biomeId().isBlank()
                    &&"INFERRED".equalsIgnoreCase(e.biomeProvenance())
                    &&(detail.isBlank()||detail.startsWith("Canvas terrain written without an explicit biome override"));
            boolean currentGlobalMask=globalMaskEnabled&&!currentMask.isBlank()
                    &&currentMask.equals(e.biomeId())
                    &&"OC_ASSIGNED".equalsIgnoreCase(e.biomeProvenance())
                    &&detail.startsWith("World biome mask applied by Ocean Canvas");
            if(inferredDefault){it.remove();removedInferred++;continue;}
            if(currentGlobalMask){it.remove();removedGlobal++;continue;}
            distinct++;
        }
        int removed=removedInferred+removedGlobal;
        if(removed>0)setDirty();
        return new BulkEvidenceCompactionResult(before,removedInferred,removedGlobal,baseline,nonCanvas,distinct,chunks.size());
    }

    /** Compatibility overload retained for older tooling; only blank inferred rows are
     * eligible without current biome-mask configuration. */
    public int compactRedundantDefaultCanvasEvidence(OceanCanvasTerrainStateData terrainState){
        return compactRedundantDefaultCanvasEvidence(terrainState,false,"").removed();
    }
    public StructureEvidence structure(String id){return structures.get(normalizeId(id,"structure"));} public List<StructureEvidence> structures(){return List.copyOf(structures.values());}
    public void putStructure(StructureEvidence e){structures.remove(e.id());structures.put(e.id(),e);trimMap(structures,MAX_STRUCTURES);setDirty();}
    public List<MaintenanceRecipe> recipes(){return List.copyOf(recipes.values());} public MaintenanceRecipe recipe(String id){return recipes.get(normalizeId(id,"recipe"));}
    public void putRecipe(MaintenanceRecipe r){recipes.remove(r.id());recipes.put(r.id(),r);trimMap(recipes,MAX_RECIPES);setDirty();}
    public boolean removeRecipe(String id){boolean c=recipes.remove(normalizeId(id,"recipe"))!=null;if(c)setDirty();return c;}
    public RecipeRun recipeRun(){return recipeRun;} public void setRecipeRun(RecipeRun r){recipeRun=r;setDirty();}
    public List<HealthIncident> incidents(){var out=new ArrayList<>(incidents);java.util.Collections.reverse(out);return List.copyOf(out);} public void putIncident(HealthIncident e){
        for(int i=0;i<incidents.size();i++)if(incidents.get(i).id().equals(e.id())){incidents.set(i,e);setDirty();return;}incidents.add(e);trimList(incidents,MAX_INCIDENTS);setDirty();}
    public HealthIncident findOpenIncident(String kind,String subject){for(int i=incidents.size()-1;i>=0;i--){var e=incidents.get(i);if(e.open()&&e.kind().equalsIgnoreCase(kind)&&e.subject().equalsIgnoreCase(subject))return e;}return null;}
    public boolean setIncidentState(String id,String state){for(int i=0;i<incidents.size();i++){var e=incidents.get(i);if(e.id().equals(id)){incidents.set(i,new HealthIncident(e.id(),e.createdAt(),System.currentTimeMillis(),e.severity(),e.kind(),e.subject(),e.detail(),e.confidence(),state));setDirty();return true;}}return false;}
    public List<Deviation> deviations(){return List.copyOf(deviations.values());} public Deviation deviationFor(String projectId,String objectId){for(var d:deviations.values())if(d.projectId().equals(normalizeId(projectId,"project"))&&d.objectId().equals(normalizeId(objectId,"object")))return d;return null;}
    public void putDeviation(Deviation d){deviations.remove(d.id());deviations.put(d.id(),d);trimMap(deviations,MAX_DEVIATIONS);setDirty();}
    public List<MilestoneState> milestoneStates(String projectId){String p=normalizeId(projectId,"project");return milestoneStates.stream().filter(v->v.projectId().equals(p)).toList();}
    public void putMilestoneState(MilestoneState s){milestoneStates.add(s);trimList(milestoneStates,MAX_MILESTONES);setDirty();}
    public List<WorldSnapshot> snapshots(){var out=new ArrayList<>(snapshots);java.util.Collections.reverse(out);return List.copyOf(out);} public WorldSnapshot snapshot(String id){for(var s:snapshots)if(s.id().equals(id))return s;return null;}
    public void putSnapshot(WorldSnapshot s){snapshots.add(s);trimList(snapshots,MAX_SNAPSHOTS);setDirty();}
    public List<SessionRecap> recaps(){var out=new ArrayList<>(recaps);java.util.Collections.reverse(out);return List.copyOf(out);} public void putRecap(SessionRecap r){recaps.add(r);trimList(recaps,MAX_RECAPS);setDirty();}
    public String worldgenBaselineSignature(){return worldgenBaselineSignature;}public String worldgenBaselineDetail(){return worldgenBaselineDetail;}public long worldgenBaselineAt(){return worldgenBaselineAt;}
    public void setWorldgenBaseline(String signature,String detail){worldgenBaselineSignature=safe(signature);worldgenBaselineDetail=safe(detail);worldgenBaselineAt=System.currentTimeMillis();setDirty();}

    public String newId(String prefix){return normalizeId(prefix+"_"+UUID.randomUUID().toString().substring(0,8),prefix);}

    private List<String> encodeChunks(){return chunks.values().stream().map(OceanCanvasP1W3Data::encodeChunk).toList();}
    private List<String> encodeStructures(){return structures.values().stream().map(OceanCanvasP1W3Data::encodeStructure).toList();}
    private List<String> encodeRecipes(){return recipes.values().stream().map(OceanCanvasP1W3Data::encodeRecipe).toList();}
    private List<String> encodeIncidents(){return incidents.stream().map(OceanCanvasP1W3Data::encodeIncident).toList();}
    private List<String> encodeDeviations(){return deviations.values().stream().map(OceanCanvasP1W3Data::encodeDeviation).toList();}
    private List<String> encodeMilestones(){return milestoneStates.stream().map(OceanCanvasP1W3Data::encodeMilestone).toList();}
    private List<String> encodeSnapshots(){return snapshots.stream().map(OceanCanvasP1W3Data::encodeSnapshot).toList();}
    private List<String> encodeRecaps(){return recaps.stream().map(OceanCanvasP1W3Data::encodeRecap).toList();}

    private static String encodeChunk(ChunkEvidence v){return enc(Long.toUnsignedString(v.chunkKey()),v.vanillaBaseline(),Long.toUnsignedString(v.vanillaFingerprint()),v.biomeId(),v.biomeProvenance(),v.biomeDetail(),v.generationVersion(),v.observedBuild(),v.updatedAt());}
    private static ChunkEvidence decodeChunk(String s){try{var p=dec(s,9);return new ChunkEvidence(Long.parseUnsignedLong(p[0]),Boolean.parseBoolean(p[1]),Long.parseUnsignedLong(p[2]),p[3],p[4],p[5],p[6],p[7],Long.parseLong(p[8]));}catch(Exception e){return null;}}
    private static String encodeStructure(StructureEvidence v){return enc(v.id(),v.kind(),v.provenance(),v.minX(),v.minY(),v.minZ(),v.maxX(),v.maxY(),v.maxZ(),v.detail(),v.updatedAt());}
    private static StructureEvidence decodeStructure(String s){try{var p=dec(s,11);return new StructureEvidence(p[0],p[1],p[2],Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7]),Integer.parseInt(p[8]),p[9],Long.parseLong(p[10]));}catch(Exception e){return null;}}
    private static String encodeRecipe(MaintenanceRecipe v){return enc(v.id(),v.name(),String.join("\u001f",v.steps()),v.updatedAt());}
    private static MaintenanceRecipe decodeRecipe(String s){try{var p=dec(s,4);return new MaintenanceRecipe(p[0],p[1],p[2].isBlank()?List.of():List.of(p[2].split("\u001f",-1)),Long.parseLong(p[3]));}catch(Exception e){return null;}}
    private static String encodeRun(RecipeRun v){return enc(v.recipeId(),v.stepIndex(),v.state(),v.detail(),v.updatedAt());}
    private static RecipeRun decodeRun(String s){try{if(s==null||s.isBlank())return null;var p=dec(s,5);return new RecipeRun(p[0],Integer.parseInt(p[1]),p[2],p[3],Long.parseLong(p[4]));}catch(Exception e){return null;}}
    private static String encodeIncident(HealthIncident v){return enc(v.id(),v.createdAt(),v.updatedAt(),v.severity(),v.kind(),v.subject(),v.detail(),v.confidence(),v.state());}
    private static HealthIncident decodeIncident(String s){try{var p=dec(s,9);return new HealthIncident(p[0],Long.parseLong(p[1]),Long.parseLong(p[2]),p[3],p[4],p[5],p[6],p[7],p[8]);}catch(Exception e){return null;}}
    private static String encodeDeviation(Deviation v){return enc(v.id(),v.projectId(),v.objectId(),v.classification(),v.note(),v.updatedAt());}
    private static Deviation decodeDeviation(String s){try{var p=dec(s,6);return new Deviation(p[0],p[1],p[2],p[3],p[4],Long.parseLong(p[5]));}catch(Exception e){return null;}}
    private static String encodeMilestone(MilestoneState v){return enc(v.id(),v.projectId(),v.milestoneId(),v.epochMillis(),v.regions(),v.processed(),v.planObjects(),v.implemented(),v.healthIssues(),v.tasksComplete(),v.fingerprint(),v.summary());}
    private static MilestoneState decodeMilestone(String s){try{var p=dec(s,12);return new MilestoneState(p[0],p[1],p[2],Long.parseLong(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7]),Integer.parseInt(p[8]),Integer.parseInt(p[9]),p[10],p[11]);}catch(Exception e){return null;}}
    private static String encodeSnapshot(WorldSnapshot v){return enc(v.id(),v.label(),v.scope(),v.epochMillis(),v.packedChunks(),v.authoritative(),v.truncated(),v.detail());}
    private static WorldSnapshot decodeSnapshot(String s){try{var p=dec(s,8);return new WorldSnapshot(p[0],p[1],p[2],Long.parseLong(p[3]),p[4],Boolean.parseBoolean(p[5]),Boolean.parseBoolean(p[6]),p[7]);}catch(Exception e){return null;}}
    private static String encodeRecap(SessionRecap v){return enc(v.id(),v.startedAt(),v.endedAt(),v.tasksCompleted(),v.regionsChanged(),v.markersResolved(),v.operations(),v.summary());}
    private static SessionRecap decodeRecap(String s){try{var p=dec(s,8);return new SessionRecap(p[0],Long.parseLong(p[1]),Long.parseLong(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]),p[7]);}catch(Exception e){return null;}}

    private interface Decode<T>{T apply(String s);} private interface Key<T,K>{K apply(T t);}
    private static <T> void loadList(List<String> src,int max,List<T> out,Decode<T> d){if(src==null)return;int start=Math.max(0,src.size()-max);for(int i=start;i<src.size();i++){T v=d.apply(src.get(i));if(v!=null)out.add(v);}}
    private static <T,K> void loadMap(List<String> src,int max,Map<K,T> out,Decode<T>d,Key<T,K>k){if(src==null)return;int start=Math.max(0,src.size()-max);for(int i=start;i<src.size();i++){T v=d.apply(src.get(i));if(v!=null)out.put(k.apply(v),v);}}
    private static <K,V> void trimMap(LinkedHashMap<K,V> m,int max){while(m.size()>max)m.remove(m.keySet().iterator().next());}
    private static <T> void trimList(List<T> l,int max){while(l.size()>max)l.remove(0);}
    private static String enc(Object... values){StringBuilder s=new StringBuilder();for(int i=0;i<values.length;i++){if(i>0)s.append('\t');String v=String.valueOf(values[i]);s.append(Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes(StandardCharsets.UTF_8)));}return s.toString();}
    private static String[] dec(String s,int n){String[] raw=s.split("\\t",-1);if(raw.length!=n)throw new IllegalArgumentException("field count");String[] out=new String[n];for(int i=0;i<n;i++)out[i]=new String(Base64.getUrlDecoder().decode(raw[i]),StandardCharsets.UTF_8);return out;}
    private static String safe(String s){return s==null?"":s.trim();} private static String upper(String s,String fallback){String v=safe(s);return (v.isBlank()?fallback:v).toUpperCase(Locale.ROOT);}
    private static String normalizeName(String s,String fallback){String v=safe(s);return v.isBlank()?fallback:v.substring(0,Math.min(128,v.length()));}
    private static String normalizeId(String s,String fallback){String v=safe(s).toLowerCase(Locale.ROOT).replace(' ','_');if(v.isBlank())v=fallback;return v.substring(0,Math.min(96,v.length()));}
}
