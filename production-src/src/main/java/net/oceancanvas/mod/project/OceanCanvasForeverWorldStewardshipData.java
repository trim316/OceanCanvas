package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * v235 Forever World stewardship state.
 *
 * Canonical, bounded metadata only: identity/lineage, generation heritage, future-generation
 * reserves, version frontiers, upgrade rehearsals and external portability dependencies.
 * Enforcement lives in {@link OceanCanvasForeverWorldStewardship}; this class deliberately does
 * not touch chunks so the metadata remains recoverable and safe to load even when an operation is
 * unavailable.
 */
public final class OceanCanvasForeverWorldStewardshipData extends SavedData {
    private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"forever_world_stewardship");
    private static final int MAX_HERITAGE=16384,MAX_RESERVES=512,MAX_FRONTIERS=512,MAX_REHEARSALS=256,MAX_LINEAGE=2048,MAX_ASSETS=4096;

    public record WorldIdentity(String worldUuid,String role,String parentWorldUuid,long createdAt,long updatedAt){
        public WorldIdentity{worldUuid=uuid(worldUuid);role=normalizeRole(role);parentWorldUuid=optionalUuid(parentWorldUuid);}
    }
    public record LineageEvent(String id,String eventType,String worldUuid,String relatedWorldUuid,String detail,long happenedAt){
        public LineageEvent{id=normalizeId(id,"lineage");eventType=token(eventType,"NOTE");worldUuid=uuid(worldUuid);relatedWorldUuid=optionalUuid(relatedWorldUuid);detail=limit(detail,768);}
    }
    public record Heritage(String id,int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,String heritageType,String generationVersion,String revisionRef,String source,long recordedAt){
        public Heritage{id=normalizeId(id,"heritage");int a=Math.min(minChunkX,maxChunkX),b=Math.max(minChunkX,maxChunkX),c=Math.min(minChunkZ,maxChunkZ),d=Math.max(minChunkZ,maxChunkZ);minChunkX=a;maxChunkX=b;minChunkZ=c;maxChunkZ=d;heritageType=token(heritageType,"UNKNOWN");generationVersion=limit(generationVersion,64);revisionRef=limit(revisionRef,128);source=limit(source,256);}
        public boolean contains(int cx,int cz){return cx>=minChunkX&&cx<=maxChunkX&&cz>=minChunkZ&&cz<=maxChunkZ;}
    }
    public record Reserve(String id,String name,int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,String expectedVersion,boolean enabled,String rationale,long createdAt,long updatedAt){
        public Reserve{id=normalizeId(id,"reserve");name=normalizeName(name,"Future Generation Reserve");int a=Math.min(minChunkX,maxChunkX),b=Math.max(minChunkX,maxChunkX),c=Math.min(minChunkZ,maxChunkZ),d=Math.max(minChunkZ,maxChunkZ);minChunkX=a;maxChunkX=b;minChunkZ=c;maxChunkZ=d;expectedVersion=limit(expectedVersion,64);rationale=limit(rationale,512);}
        public boolean intersects(int minX,int maxX,int minZ,int maxZ){return enabled&&minChunkX<=maxX&&maxChunkX>=minX&&minChunkZ<=maxZ&&maxChunkZ>=minZ;}
        public boolean contains(int cx,int cz){return enabled&&cx>=minChunkX&&cx<=maxChunkX&&cz>=minChunkZ&&cz<=maxChunkZ;}
    }
    public record Frontier(String id,String name,int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,String targetVersion,String status,int gatewayX,int gatewayZ,String notes,long createdAt,long updatedAt){
        public Frontier{id=normalizeId(id,"frontier");name=normalizeName(name,"Version Frontier");int a=Math.min(minChunkX,maxChunkX),b=Math.max(minChunkX,maxChunkX),c=Math.min(minChunkZ,maxChunkZ),d=Math.max(minChunkZ,maxChunkZ);minChunkX=a;maxChunkX=b;minChunkZ=c;maxChunkZ=d;targetVersion=limit(targetVersion,64);status=enumToken(status,Set.of("PLANNED","OPEN","COMPLETE","PAUSED","ARCHIVED"),"PLANNED");notes=limit(notes,512);}
    }
    public record UpgradeRehearsal(String id,String fromVersion,String toVersion,String status,String checkpointRef,String compatibilityReport,String validationNotes,long createdAt,long validatedAt,long committedAt){
        public UpgradeRehearsal{id=normalizeId(id,"rehearsal");fromVersion=limit(fromVersion,64);toVersion=limit(toVersion,64);status=enumToken(status,Set.of("PLANNED","CHECKPOINTED","MIGRATED","BLOCKED","VALIDATED","COMMITTED","ABANDONED"),"PLANNED");checkpointRef=limit(checkpointRef,256);compatibilityReport=limit(compatibilityReport,4096);validationNotes=limit(validationNotes,2048);}
        public boolean validFor(String version){return (status.equals("VALIDATED")||status.equals("COMMITTED"))&&toVersion.equalsIgnoreCase(version);}
    }
    public record ExternalAsset(String id,String label,String path,String fingerprint,boolean required,boolean portable,String notes,long updatedAt){
        public ExternalAsset{id=normalizeId(id,"asset");label=normalizeName(label,"External Asset");path=limit(path,1024);fingerprint=limit(fingerprint,128);notes=limit(notes,512);}
    }

    private final List<String> identityPacked,lineagePacked,heritagePacked,reservesPacked,frontiersPacked,rehearsalsPacked,assetsPacked;
    private static final Codec<OceanCanvasForeverWorldStewardshipData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.listOf().optionalFieldOf("identity",List.of()).forGetter(d->d.identityPacked),
            Codec.STRING.listOf().optionalFieldOf("lineage",List.of()).forGetter(d->d.lineagePacked),
            Codec.STRING.listOf().optionalFieldOf("heritage",List.of()).forGetter(d->d.heritagePacked),
            Codec.STRING.listOf().optionalFieldOf("reserves",List.of()).forGetter(d->d.reservesPacked),
            Codec.STRING.listOf().optionalFieldOf("frontiers",List.of()).forGetter(d->d.frontiersPacked),
            Codec.STRING.listOf().optionalFieldOf("rehearsals",List.of()).forGetter(d->d.rehearsalsPacked),
            Codec.STRING.listOf().optionalFieldOf("assets",List.of()).forGetter(d->d.assetsPacked)
    ).apply(i,OceanCanvasForeverWorldStewardshipData::new));
    public static final SavedDataType<OceanCanvasForeverWorldStewardshipData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasForeverWorldStewardshipData::new,CODEC,null);
    public OceanCanvasForeverWorldStewardshipData(){this(List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());}
    private OceanCanvasForeverWorldStewardshipData(List<String>a,List<String>b,List<String>c,List<String>d,List<String>e,List<String>f,List<String>g){identityPacked=new ArrayList<>(tail(a,1));lineagePacked=new ArrayList<>(tail(b,MAX_LINEAGE));heritagePacked=new ArrayList<>(tail(c,MAX_HERITAGE));reservesPacked=new ArrayList<>(tail(d,MAX_RESERVES));frontiersPacked=new ArrayList<>(tail(e,MAX_FRONTIERS));rehearsalsPacked=new ArrayList<>(tail(f,MAX_REHEARSALS));assetsPacked=new ArrayList<>(tail(g,MAX_ASSETS));}
    public static OceanCanvasForeverWorldStewardshipData get(ServerLevel world){var d=world.getDataStorage().computeIfAbsent(TYPE);d.ensureIdentity();return d;}

    public WorldIdentity identity(){ensureIdentity();return decodeIdentity(identityPacked.get(0));}
    private void ensureIdentity(){if(!identityPacked.isEmpty()&&decodeIdentity(identityPacked.get(0))!=null)return;long n=System.currentTimeMillis();var w=new WorldIdentity(UUID.randomUUID().toString(),"PRIMARY","",n,n);identityPacked.clear();identityPacked.add(encIdentity(w));lineagePacked.add(encLineage(new LineageEvent(newId("lineage"),"WORLD_CREATED",w.worldUuid(),"","Ocean Canvas Forever World identity established",n)));setDirty();}
    public WorldIdentity setRole(String role,String parentWorldUuid){var old=identity();var n=new WorldIdentity(old.worldUuid(),role,parentWorldUuid,old.createdAt(),System.currentTimeMillis());identityPacked.set(0,encIdentity(n));addLineage("ROLE_CHANGED",n.parentWorldUuid(),"Role set to "+n.role());setDirty();return n;}
    public void addLineage(String type,String related,String detail){var w=identity();lineagePacked.add(encLineage(new LineageEvent(newId("lineage"),type,w.worldUuid(),related,detail,System.currentTimeMillis())));trim(lineagePacked,MAX_LINEAGE);setDirty();}
    public List<LineageEvent> lineage(){return decodeAll(lineagePacked,OceanCanvasForeverWorldStewardshipData::decodeLineage);}

    public Heritage recordHeritage(int minX,int maxX,int minZ,int maxZ,String type,String version,String revision,String source){var h=new Heritage(newId("heritage"),minX,minZ,maxX,maxZ,type,version,revision,source,System.currentTimeMillis());heritagePacked.add(encHeritage(h));trim(heritagePacked,MAX_HERITAGE);setDirty();return h;}
    public List<Heritage> heritage(){return decodeAll(heritagePacked,OceanCanvasForeverWorldStewardshipData::decodeHeritage);}
    public Heritage latestHeritageAt(int cx,int cz){Heritage best=null;for(var h:heritage())if(h.contains(cx,cz)&&(best==null||h.recordedAt()>best.recordedAt()))best=h;return best;}

    public Reserve addReserve(String name,int minX,int maxX,int minZ,int maxZ,String expectedVersion,String rationale){long n=System.currentTimeMillis();var r=new Reserve(newId("reserve"),name,minX,minZ,maxX,maxZ,expectedVersion,true,rationale,n,n);reservesPacked.add(encReserve(r));trim(reservesPacked,MAX_RESERVES);setDirty();return r;}
    public List<Reserve> reserves(){return decodeAll(reservesPacked,OceanCanvasForeverWorldStewardshipData::decodeReserve);}
    public boolean removeReserve(String id){boolean changed=remove(reservesPacked,id,OceanCanvasForeverWorldStewardshipData::decodeReserve,Reserve::id);if(changed)setDirty();return changed;}
    public Reserve reserveIntersecting(int minX,int maxX,int minZ,int maxZ){for(var r:reserves())if(r.intersects(minX,maxX,minZ,maxZ))return r;return null;}

    public Frontier addFrontier(String name,int minX,int maxX,int minZ,int maxZ,String targetVersion,int gatewayX,int gatewayZ,String notes){long n=System.currentTimeMillis();var f=new Frontier(newId("frontier"),name,minX,minZ,maxX,maxZ,targetVersion,"PLANNED",gatewayX,gatewayZ,notes,n,n);frontiersPacked.add(encFrontier(f));trim(frontiersPacked,MAX_FRONTIERS);setDirty();return f;}
    public List<Frontier> frontiers(){return decodeAll(frontiersPacked,OceanCanvasForeverWorldStewardshipData::decodeFrontier);}
    public Frontier setFrontierStatus(String id,String status){String next=token(status,"PLANNED");if(!Set.of("PLANNED","OPEN","COMPLETE","PAUSED","ARCHIVED").contains(next))throw new IllegalArgumentException("frontier status must be PLANNED, OPEN, COMPLETE, PAUSED, or ARCHIVED");for(int i=0;i<frontiersPacked.size();i++){var f=decodeFrontier(frontiersPacked.get(i));if(f!=null&&f.id().equalsIgnoreCase(id)){var n=new Frontier(f.id(),f.name(),f.minChunkX(),f.minChunkZ(),f.maxChunkX(),f.maxChunkZ(),f.targetVersion(),next,f.gatewayX(),f.gatewayZ(),f.notes(),f.createdAt(),System.currentTimeMillis());frontiersPacked.set(i,encFrontier(n));setDirty();return n;}}throw new IllegalArgumentException("unknown frontier");}

    public UpgradeRehearsal startRehearsal(String from,String to,String checkpoint,String report){long n=System.currentTimeMillis();var r=new UpgradeRehearsal(newId("rehearsal"),from,to,checkpoint==null||checkpoint.isBlank()?"PLANNED":"CHECKPOINTED",checkpoint,report,"",n,0,0);rehearsalsPacked.add(encRehearsal(r));trim(rehearsalsPacked,MAX_REHEARSALS);setDirty();return r;}
    public List<UpgradeRehearsal> rehearsals(){return decodeAll(rehearsalsPacked,OceanCanvasForeverWorldStewardshipData::decodeRehearsal);}
    public UpgradeRehearsal markMigrated(String id,String notes){return updateRehearsal(id,r->{if(r.checkpointRef().isBlank())throw new IllegalArgumentException("rehearsal needs a checkpoint before migration");if(!(r.status().equals("CHECKPOINTED")||r.status().equals("BLOCKED")))throw new IllegalArgumentException("rehearsal must be CHECKPOINTED (or BLOCKED after a failed validation) before recording migration");return new UpgradeRehearsal(r.id(),r.fromVersion(),r.toVersion(),"MIGRATED",r.checkpointRef(),r.compatibilityReport(),notes,r.createdAt(),0,0);});}
    public UpgradeRehearsal validateRehearsal(String id,boolean pass,String notes){return updateRehearsal(id,r->{if(!r.status().equals("MIGRATED"))throw new IllegalArgumentException("rehearsal must be MIGRATED before validation");return new UpgradeRehearsal(r.id(),r.fromVersion(),r.toVersion(),pass?"VALIDATED":"BLOCKED",r.checkpointRef(),r.compatibilityReport(),notes,r.createdAt(),pass?System.currentTimeMillis():0,0);});}
    public UpgradeRehearsal commitRehearsal(String id){return updateRehearsal(id,r->{if(!r.status().equals("VALIDATED"))throw new IllegalArgumentException("rehearsal must be VALIDATED before commit");return new UpgradeRehearsal(r.id(),r.fromVersion(),r.toVersion(),"COMMITTED",r.checkpointRef(),r.compatibilityReport(),r.validationNotes(),r.createdAt(),r.validatedAt(),System.currentTimeMillis());});}
    private UpgradeRehearsal updateRehearsal(String id,java.util.function.Function<UpgradeRehearsal,UpgradeRehearsal> fn){for(int i=0;i<rehearsalsPacked.size();i++){var r=decodeRehearsal(rehearsalsPacked.get(i));if(r!=null&&r.id().equalsIgnoreCase(id)){var n=fn.apply(r);rehearsalsPacked.set(i,encRehearsal(n));setDirty();return n;}}throw new IllegalArgumentException("unknown rehearsal");}
    public boolean hasValidatedRehearsalFor(String version){for(var r:rehearsals())if(r.validFor(version))return true;return false;}

    public ExternalAsset putAsset(String id,String label,String path,String fingerprint,boolean required,boolean portable,String notes){var a=new ExternalAsset(id==null||id.isBlank()?newId("asset"):id,label,path,fingerprint,required,portable,notes,System.currentTimeMillis());for(int i=0;i<assetsPacked.size();i++){var old=decodeAsset(assetsPacked.get(i));if(old!=null&&old.id().equals(a.id())){assetsPacked.set(i,encAsset(a));setDirty();return a;}}assetsPacked.add(encAsset(a));trim(assetsPacked,MAX_ASSETS);setDirty();return a;}
    public List<ExternalAsset> assets(){return decodeAll(assetsPacked,OceanCanvasForeverWorldStewardshipData::decodeAsset);}

    private static <T> List<T> decodeAll(List<String> in,java.util.function.Function<String,T> f){var out=new ArrayList<T>();for(String s:in){T v=f.apply(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    private static <T> boolean remove(List<String> in,String id,java.util.function.Function<String,T> decoder,java.util.function.Function<T,String> idFn){for(int i=0;i<in.size();i++){T v=decoder.apply(in.get(i));if(v!=null&&idFn.apply(v).equalsIgnoreCase(id)){in.remove(i);return true;}}return false;}
    private static List<String> tail(List<String> in,int max){return in.size()<=max?in:new ArrayList<>(in.subList(in.size()-max,in.size()));}private static void trim(List<?> l,int max){while(l.size()>max)l.remove(0);}private static String newId(String p){return p+"_"+UUID.randomUUID().toString().substring(0,8);}private static String normalizeId(String v,String p){v=limit(v,96).trim().toLowerCase(Locale.ROOT);return v.isBlank()?newId(p):v;}private static String normalizeName(String v,String d){v=limit(v,128).trim();return v.isBlank()?d:v;}private static String token(String v,String d){v=limit(v,64).trim().toUpperCase(Locale.ROOT).replace(' ','_');return v.isBlank()?d:v;}private static String enumToken(String v,Set<String>a,String d){v=token(v,d);return a.contains(v)?v:d;}private static String normalizeRole(String v){String r=token(v,"PRIMARY");if(!Set.of("PRIMARY","STAGING","BACKUP","EXPERIMENT","ARCHIVE").contains(r))throw new IllegalArgumentException("world role must be PRIMARY, STAGING, BACKUP, EXPERIMENT, or ARCHIVE");return r;}private static String uuid(String v){try{return UUID.fromString(v).toString();}catch(Exception e){throw new IllegalArgumentException("world UUID is invalid");}}private static String optionalUuid(String v){if(v==null||v.isBlank())return "";try{return UUID.fromString(v).toString();}catch(Exception e){throw new IllegalArgumentException("parent world UUID must be a UUID");}}private static String limit(String v,int n){if(v==null)return "";return v.length()>n?v.substring(0,n):v;}
    private static String b64(String v){return Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes(StandardCharsets.UTF_8));}private static String un64(String v){try{return new String(Base64.getUrlDecoder().decode(v),StandardCharsets.UTF_8);}catch(Exception e){return "";}}private static String join(Object...v){var s=new StringBuilder();for(int i=0;i<v.length;i++){if(i>0)s.append('|');s.append(v[i] instanceof String?b64((String)v[i]):v[i]);}return s.toString();}private static String[] split(String s,int n){String[]p=s.split("\\|",-1);return p.length==n?p:null;}
    private static String encIdentity(WorldIdentity v){return join(v.worldUuid(),v.role(),v.parentWorldUuid(),v.createdAt(),v.updatedAt());}private static WorldIdentity decodeIdentity(String s){try{var p=split(s,5);return p==null?null:new WorldIdentity(un64(p[0]),un64(p[1]),un64(p[2]),Long.parseLong(p[3]),Long.parseLong(p[4]));}catch(Exception e){return null;}}
    private static String encLineage(LineageEvent v){return join(v.id(),v.eventType(),v.worldUuid(),v.relatedWorldUuid(),v.detail(),v.happenedAt());}private static LineageEvent decodeLineage(String s){try{var p=split(s,6);return p==null?null:new LineageEvent(un64(p[0]),un64(p[1]),un64(p[2]),un64(p[3]),un64(p[4]),Long.parseLong(p[5]));}catch(Exception e){return null;}}
    private static String encHeritage(Heritage v){return join(v.id(),v.minChunkX(),v.minChunkZ(),v.maxChunkX(),v.maxChunkZ(),v.heritageType(),v.generationVersion(),v.revisionRef(),v.source(),v.recordedAt());}private static Heritage decodeHeritage(String s){try{var p=split(s,10);return p==null?null:new Heritage(un64(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]),un64(p[5]),un64(p[6]),un64(p[7]),un64(p[8]),Long.parseLong(p[9]));}catch(Exception e){return null;}}
    private static String encReserve(Reserve v){return join(v.id(),v.name(),v.minChunkX(),v.minChunkZ(),v.maxChunkX(),v.maxChunkZ(),v.expectedVersion(),v.enabled(),v.rationale(),v.createdAt(),v.updatedAt());}private static Reserve decodeReserve(String s){try{var p=split(s,11);return p==null?null:new Reserve(un64(p[0]),un64(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]),un64(p[6]),Boolean.parseBoolean(p[7]),un64(p[8]),Long.parseLong(p[9]),Long.parseLong(p[10]));}catch(Exception e){return null;}}
    private static String encFrontier(Frontier v){return join(v.id(),v.name(),v.minChunkX(),v.minChunkZ(),v.maxChunkX(),v.maxChunkZ(),v.targetVersion(),v.status(),v.gatewayX(),v.gatewayZ(),v.notes(),v.createdAt(),v.updatedAt());}private static Frontier decodeFrontier(String s){try{var p=split(s,13);return p==null?null:new Frontier(un64(p[0]),un64(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]),un64(p[6]),un64(p[7]),Integer.parseInt(p[8]),Integer.parseInt(p[9]),un64(p[10]),Long.parseLong(p[11]),Long.parseLong(p[12]));}catch(Exception e){return null;}}
    private static String encRehearsal(UpgradeRehearsal v){return join(v.id(),v.fromVersion(),v.toVersion(),v.status(),v.checkpointRef(),v.compatibilityReport(),v.validationNotes(),v.createdAt(),v.validatedAt(),v.committedAt());}private static UpgradeRehearsal decodeRehearsal(String s){try{var p=split(s,10);return p==null?null:new UpgradeRehearsal(un64(p[0]),un64(p[1]),un64(p[2]),un64(p[3]),un64(p[4]),un64(p[5]),un64(p[6]),Long.parseLong(p[7]),Long.parseLong(p[8]),Long.parseLong(p[9]));}catch(Exception e){return null;}}
    private static String encAsset(ExternalAsset v){return join(v.id(),v.label(),v.path(),v.fingerprint(),v.required(),v.portable(),v.notes(),v.updatedAt());}private static ExternalAsset decodeAsset(String s){try{var p=split(s,8);return p==null?null:new ExternalAsset(un64(p[0]),un64(p[1]),un64(p[2]),un64(p[3]),Boolean.parseBoolean(p[4]),Boolean.parseBoolean(p[5]),un64(p[6]),Long.parseLong(p[7]));}catch(Exception e){return null;}}
}
