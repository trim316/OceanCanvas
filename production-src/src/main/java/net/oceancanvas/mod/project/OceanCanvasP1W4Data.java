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
import java.util.UUID;

/**
 * Bounded persistence for the P1-W4 stewardship/collaboration tranche.
 *
 * <p>All records are metadata/evidence. Destructive retirement steps deliberately stop at the
 * existing preview/token operation boundary and this data file never mutates terrain.</p>
 */
public final class OceanCanvasP1W4Data extends SavedData {
    public static final int CURRENT_SCHEMA=1;
    private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"p1w4_stewardship");
    private static final int MAX_SCALE=256,MAX_RETIREMENTS=128,MAX_REVIEWS=256,MAX_SESSION_PLANS=32,
            MAX_STORAGE=96,MAX_CONTEXTS=16,MAX_MAINTENANCE=32,MAX_UPGRADES=24;

    public record WorldScaleSnapshot(String id,long epochMillis,long definedChunks,long reservedChunks,long protectedChunks,
                                     long canvasChunks,long restoredChunks,long modifiedChunks,long untouchedEstimate,String detail){
        public WorldScaleSnapshot{id=sanitizeId(id,"scale");detail=cleanText(detail);definedChunks=nn(definedChunks);reservedChunks=nn(reservedChunks);protectedChunks=nn(protectedChunks);canvasChunks=nn(canvasChunks);restoredChunks=nn(restoredChunks);modifiedChunks=nn(modifiedChunks);untouchedEstimate=nn(untouchedEstimate);}
    }
    public record RetirementRecord(String id,String subjectType,String subjectId,String mode,String state,String requestedBy,long createdAt,long updatedAt,String note){
        public RetirementRecord{id=sanitizeId(id,"retirement");subjectType=upper(subjectType,"PROJECT");subjectId=cleanText(subjectId);mode=upper(mode,"ARCHIVE_ONLY");state=upper(state,"PROPOSED");requestedBy=cleanText(requestedBy);note=cleanText(note);}
        public boolean terminal(){return state.equals("COMPLETE")||state.equals("CANCELLED");}
    }
    public record ReviewItem(String id,String subjectType,String subjectId,String label,String state,String author,String comment,long createdAt,long updatedAt){
        public ReviewItem{id=sanitizeId(id,"review");subjectType=upper(subjectType,"PROJECT");subjectId=cleanText(subjectId);label=clip(label,"Review",128);state=upper(state,"OPEN");author=clip(author,"unknown",64);comment=clip(comment,"",512);}
    }
    public record SessionPlan(String id,String label,int availableMinutes,String state,List<String> taskIds,int cursor,long createdAt,long updatedAt,String rationale){
        public SessionPlan{id=sanitizeId(id,"session_plan");label=clip(label,"Session plan",128);availableMinutes=Math.max(5,Math.min(720,availableMinutes));state=upper(state,"PLANNED");taskIds=taskIds==null?List.of():taskIds.stream().map(OceanCanvasP1W4Data::cleanText).filter(v->!v.isBlank()).distinct().limit(16).toList();cursor=Math.max(0,Math.min(taskIds.size(),cursor));rationale=clip(rationale,"",512);}
    }
    public record StorageSample(long epochMillis,long totalBytes,long oceanCanvasBytes,long regionBytes,long poiBytes,long entityBytes){
        public StorageSample{totalBytes=nn(totalBytes);oceanCanvasBytes=nn(oceanCanvasBytes);regionBytes=nn(regionBytes);poiBytes=nn(poiBytes);entityBytes=nn(entityBytes);}
    }
    public record RecentContext(String id,String type,String subjectId,String label,int x,int z,long updatedAt){
        public RecentContext{id=sanitizeId(id,"context");type=upper(type,"PLACE");subjectId=cleanText(subjectId);label=clip(label,"Context",128);}
    }
    public record MaintenanceItem(String id,String label,int cadenceDays,long lastCompletedAt,long nextDueAt,String state,String note){
        public MaintenanceItem{id=sanitizeId(id,"maintenance");label=clip(label,"Maintenance",128);cadenceDays=Math.max(1,Math.min(3650,cadenceDays));state=upper(state,"DUE");note=clip(note,"",512);}
    }
    public record UpgradeSnapshot(String id,String minecraftVersion,String compatibilitySignature,String datapackSignature,int biomeCount,int structureCount,int poiTypeCount,long observedAt,String detail){
        public UpgradeSnapshot{id=sanitizeId(id,"upgrade");minecraftVersion=cleanText(minecraftVersion);compatibilitySignature=cleanText(compatibilitySignature);datapackSignature=cleanText(datapackSignature);biomeCount=Math.max(0,biomeCount);structureCount=Math.max(0,structureCount);poiTypeCount=Math.max(0,poiTypeCount);detail=clip(detail,"",1024);}
    }

    private static final Codec<OceanCanvasP1W4Data> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema),
            Codec.STRING.listOf().optionalFieldOf("scale",List.of()).forGetter(d->d.scale.stream().map(OceanCanvasP1W4Data::encScale).toList()),
            Codec.STRING.listOf().optionalFieldOf("retirements",List.of()).forGetter(d->d.retirements.values().stream().map(OceanCanvasP1W4Data::encRetirement).toList()),
            Codec.STRING.listOf().optionalFieldOf("reviews",List.of()).forGetter(d->d.reviews.values().stream().map(OceanCanvasP1W4Data::encReview).toList()),
            Codec.STRING.listOf().optionalFieldOf("sessionPlans",List.of()).forGetter(d->d.sessionPlans.stream().map(OceanCanvasP1W4Data::encSession).toList()),
            Codec.STRING.listOf().optionalFieldOf("storage",List.of()).forGetter(d->d.storage.stream().map(OceanCanvasP1W4Data::encStorage).toList()),
            Codec.STRING.listOf().optionalFieldOf("contexts",List.of()).forGetter(d->d.contexts.stream().map(OceanCanvasP1W4Data::encContext).toList()),
            Codec.STRING.listOf().optionalFieldOf("maintenance",List.of()).forGetter(d->d.maintenance.values().stream().map(OceanCanvasP1W4Data::encMaintenance).toList()),
            Codec.STRING.listOf().optionalFieldOf("upgrades",List.of()).forGetter(d->d.upgrades.stream().map(OceanCanvasP1W4Data::encUpgrade).toList())
    ).apply(i,OceanCanvasP1W4Data::new));
    public static final SavedDataType<OceanCanvasP1W4Data> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasP1W4Data::new,CODEC,null);

    private int schema;
    private final ArrayList<WorldScaleSnapshot> scale=new ArrayList<>();
    private final LinkedHashMap<String,RetirementRecord> retirements=new LinkedHashMap<>();
    private final LinkedHashMap<String,ReviewItem> reviews=new LinkedHashMap<>();
    private final ArrayList<SessionPlan> sessionPlans=new ArrayList<>();
    private final ArrayList<StorageSample> storage=new ArrayList<>();
    private final ArrayList<RecentContext> contexts=new ArrayList<>();
    private final LinkedHashMap<String,MaintenanceItem> maintenance=new LinkedHashMap<>();
    private final ArrayList<UpgradeSnapshot> upgrades=new ArrayList<>();

    public OceanCanvasP1W4Data(){this(CURRENT_SCHEMA,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());installMaintenanceDefaults();}
    private OceanCanvasP1W4Data(int schema,List<String> scale,List<String> retirements,List<String> reviews,List<String> sessions,List<String> storage,List<String> contexts,List<String> maintenance,List<String> upgrades){
        this.schema=Math.max(1,schema);loadList(scale,MAX_SCALE,this.scale,OceanCanvasP1W4Data::decScale);loadMap(retirements,MAX_RETIREMENTS,this.retirements,OceanCanvasP1W4Data::decRetirement,RetirementRecord::id);loadMap(reviews,MAX_REVIEWS,this.reviews,OceanCanvasP1W4Data::decReview,ReviewItem::id);loadList(sessions,MAX_SESSION_PLANS,this.sessionPlans,OceanCanvasP1W4Data::decSession);loadList(storage,MAX_STORAGE,this.storage,OceanCanvasP1W4Data::decStorage);loadList(contexts,MAX_CONTEXTS,this.contexts,OceanCanvasP1W4Data::decContext);loadMap(maintenance,MAX_MAINTENANCE,this.maintenance,OceanCanvasP1W4Data::decMaintenance,MaintenanceItem::id);loadList(upgrades,MAX_UPGRADES,this.upgrades,OceanCanvasP1W4Data::decUpgrade);installMaintenanceDefaults();
    }
    public static OceanCanvasP1W4Data get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);} public int schema(){return schema;}
    public String newId(String prefix){return sanitizeId(prefix+"_"+UUID.randomUUID().toString().substring(0,8),prefix);}

    public List<WorldScaleSnapshot> scale(){return reverse(scale);} public void putScale(WorldScaleSnapshot v){scale.add(v);trim(scale,MAX_SCALE);setDirty();}
    public List<RetirementRecord> retirements(){return List.copyOf(retirements.values());} public RetirementRecord retirement(String id){return retirements.get(sanitizeId(id,"retirement"));} public void putRetirement(RetirementRecord v){retirements.put(v.id(),v);trimMap(retirements,MAX_RETIREMENTS);setDirty();}
    public List<ReviewItem> reviews(){return List.copyOf(reviews.values());} public ReviewItem review(String id){return reviews.get(sanitizeId(id,"review"));} public void putReview(ReviewItem v){reviews.put(v.id(),v);trimMap(reviews,MAX_REVIEWS);setDirty();}
    public List<SessionPlan> sessionPlans(){return reverse(sessionPlans);} public SessionPlan activeSessionPlan(){for(int i=sessionPlans.size()-1;i>=0;i--){var p=sessionPlans.get(i);if(!p.state().equals("COMPLETE")&&!p.state().equals("CANCELLED"))return p;}return null;} public void putSessionPlan(SessionPlan p){for(int i=0;i<sessionPlans.size();i++)if(sessionPlans.get(i).id().equals(p.id())){sessionPlans.set(i,p);setDirty();return;}sessionPlans.add(p);trim(sessionPlans,MAX_SESSION_PLANS);setDirty();}
    public List<StorageSample> storage(){return reverse(storage);} public void putStorage(StorageSample v){storage.add(v);trim(storage,MAX_STORAGE);setDirty();}
    public List<RecentContext> contexts(){return reverse(contexts);} public void touchContext(String type,String subjectId,String label,int x,int z){String t=upper(type,"PLACE"),s=cleanText(subjectId);contexts.removeIf(v->v.type().equals(t)&&v.subjectId().equalsIgnoreCase(s));contexts.add(new RecentContext(newId("context"),t,s,label,x,z,System.currentTimeMillis()));trim(contexts,MAX_CONTEXTS);setDirty();}
    public List<MaintenanceItem> maintenance(){return List.copyOf(maintenance.values());} public MaintenanceItem maintenance(String id){return maintenance.get(sanitizeId(id,"maintenance"));} public void putMaintenance(MaintenanceItem v){maintenance.put(v.id(),v);trimMap(maintenance,MAX_MAINTENANCE);setDirty();}
    public List<UpgradeSnapshot> upgrades(){return reverse(upgrades);} public UpgradeSnapshot latestUpgrade(){return upgrades.isEmpty()?null:upgrades.get(upgrades.size()-1);} public void putUpgrade(UpgradeSnapshot v){upgrades.add(v);trim(upgrades,MAX_UPGRADES);setDirty();}

    private void installMaintenanceDefaults(){long now=System.currentTimeMillis();putDefault(new MaintenanceItem("health_scan","Review Ocean Canvas health inbox",30,0L,now,"DUE","Read-only health and provenance review."));putDefault(new MaintenanceItem("diagnostic_bundle","Capture support/diagnostic bundle",30,0L,now,"DUE","Retain bounded evidence before major changes."));putDefault(new MaintenanceItem("boundary_audit","Run selection boundary drift audit",30,0L,now,"DUE","Check for modifications immediately outside managed Regions."));putDefault(new MaintenanceItem("upgrade_rehearsal","Review upgrade compatibility evidence",90,0L,now,"DUE","Especially important after changing Minecraft, Fabric, datapacks or worldgen dependencies."));}
    private void putDefault(MaintenanceItem i){maintenance.putIfAbsent(i.id(),i);}

    private static String encScale(WorldScaleSnapshot v){return enc(v.id(),v.epochMillis(),v.definedChunks(),v.reservedChunks(),v.protectedChunks(),v.canvasChunks(),v.restoredChunks(),v.modifiedChunks(),v.untouchedEstimate(),v.detail());}
    private static WorldScaleSnapshot decScale(String s){try{var p=dec(s,10);return new WorldScaleSnapshot(p[0],L(p[1]),L(p[2]),L(p[3]),L(p[4]),L(p[5]),L(p[6]),L(p[7]),L(p[8]),p[9]);}catch(Exception e){return null;}}
    private static String encRetirement(RetirementRecord v){return enc(v.id(),v.subjectType(),v.subjectId(),v.mode(),v.state(),v.requestedBy(),v.createdAt(),v.updatedAt(),v.note());}
    private static RetirementRecord decRetirement(String s){try{var p=dec(s,9);return new RetirementRecord(p[0],p[1],p[2],p[3],p[4],p[5],L(p[6]),L(p[7]),p[8]);}catch(Exception e){return null;}}
    private static String encReview(ReviewItem v){return enc(v.id(),v.subjectType(),v.subjectId(),v.label(),v.state(),v.author(),v.comment(),v.createdAt(),v.updatedAt());}
    private static ReviewItem decReview(String s){try{var p=dec(s,9);return new ReviewItem(p[0],p[1],p[2],p[3],p[4],p[5],p[6],L(p[7]),L(p[8]));}catch(Exception e){return null;}}
    private static String encSession(SessionPlan v){return enc(v.id(),v.label(),v.availableMinutes(),v.state(),String.join("\u001f",v.taskIds()),v.cursor(),v.createdAt(),v.updatedAt(),v.rationale());}
    private static SessionPlan decSession(String s){try{var p=dec(s,9);return new SessionPlan(p[0],p[1],I(p[2]),p[3],p[4].isBlank()?List.of():List.of(p[4].split("\u001f",-1)),I(p[5]),L(p[6]),L(p[7]),p[8]);}catch(Exception e){return null;}}
    private static String encStorage(StorageSample v){return enc(v.epochMillis(),v.totalBytes(),v.oceanCanvasBytes(),v.regionBytes(),v.poiBytes(),v.entityBytes());}
    private static StorageSample decStorage(String s){try{var p=dec(s,6);return new StorageSample(L(p[0]),L(p[1]),L(p[2]),L(p[3]),L(p[4]),L(p[5]));}catch(Exception e){return null;}}
    private static String encContext(RecentContext v){return enc(v.id(),v.type(),v.subjectId(),v.label(),v.x(),v.z(),v.updatedAt());}
    private static RecentContext decContext(String s){try{var p=dec(s,7);return new RecentContext(p[0],p[1],p[2],p[3],I(p[4]),I(p[5]),L(p[6]));}catch(Exception e){return null;}}
    private static String encMaintenance(MaintenanceItem v){return enc(v.id(),v.label(),v.cadenceDays(),v.lastCompletedAt(),v.nextDueAt(),v.state(),v.note());}
    private static MaintenanceItem decMaintenance(String s){try{var p=dec(s,7);return new MaintenanceItem(p[0],p[1],I(p[2]),L(p[3]),L(p[4]),p[5],p[6]);}catch(Exception e){return null;}}
    private static String encUpgrade(UpgradeSnapshot v){return enc(v.id(),v.minecraftVersion(),v.compatibilitySignature(),v.datapackSignature(),v.biomeCount(),v.structureCount(),v.poiTypeCount(),v.observedAt(),v.detail());}
    private static UpgradeSnapshot decUpgrade(String s){try{var p=dec(s,9);return new UpgradeSnapshot(p[0],p[1],p[2],p[3],I(p[4]),I(p[5]),I(p[6]),L(p[7]),p[8]);}catch(Exception e){return null;}}

    private interface Decode<T>{T get(String s);} private interface Key<T>{String get(T t);}
    private static <T> void loadList(List<String> src,int max,List<T> dst,Decode<T> d){if(src==null)return;int start=Math.max(0,src.size()-max);for(int i=start;i<src.size();i++){T v=d.get(src.get(i));if(v!=null)dst.add(v);}}
    private static <T> void loadMap(List<String> src,int max,LinkedHashMap<String,T> dst,Decode<T>d,Key<T>k){if(src==null)return;int start=Math.max(0,src.size()-max);for(int i=start;i<src.size();i++){T v=d.get(src.get(i));if(v!=null)dst.put(k.get(v),v);}}
    private static <T> List<T> reverse(List<T> src){var out=new ArrayList<>(src);java.util.Collections.reverse(out);return List.copyOf(out);} private static <T> void trim(List<T> l,int max){while(l.size()>max)l.remove(0);} private static <T> void trimMap(LinkedHashMap<String,T> m,int max){while(m.size()>max)m.remove(m.keySet().iterator().next());}
    private static String enc(Object... vals){StringBuilder s=new StringBuilder();for(int i=0;i<vals.length;i++){if(i>0)s.append('\t');s.append(Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(vals[i]).getBytes(StandardCharsets.UTF_8)));}return s.toString();}
    private static String[] dec(String s,int n){String[] r=s.split("\\t",-1);if(r.length!=n)throw new IllegalArgumentException("field count");String[] o=new String[n];for(int i=0;i<n;i++)o[i]=new String(Base64.getUrlDecoder().decode(r[i]),StandardCharsets.UTF_8);return o;}
    private static long L(String s){return Long.parseLong(s);} private static int I(String s){return Integer.parseInt(s);} private static long nn(long v){return Math.max(0L,v);} private static String cleanText(String s){return s==null?"":s.trim();} private static String upper(String s,String f){String v=cleanText(s);return (v.isBlank()?f:v).toUpperCase(Locale.ROOT);} private static String clip(String s,String f,int max){String v=cleanText(s);if(v.isBlank())v=f;return v.substring(0,Math.min(max,v.length()));} private static String sanitizeId(String s,String f){String v=cleanText(s).toLowerCase(Locale.ROOT).replace(' ','_');if(v.isBlank())v=f;return v.substring(0,Math.min(96,v.length()));}
}
