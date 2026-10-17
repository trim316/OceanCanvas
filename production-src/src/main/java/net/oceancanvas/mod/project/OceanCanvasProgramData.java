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
 * Durable metadata/evidence for roadmap/program phases P6-P10.
 *
 * <p>This store is deliberately non-terrain-authoritative.  It can describe construction,
 * atlas/history, collaboration and advanced-analysis artifacts, but it never edits blocks,
 * chunks, tickets, protected regions or operation controller state.  Any destructive follow-up
 * must continue through the existing preview/token/server-authoritative operation path.</p>
 */
public final class OceanCanvasProgramData extends SavedData {
    public static final int CURRENT_SCHEMA = 2;
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "program_p6_p9");
    private static final int MAX_ENTRIES = 1536;
    private static final int MAX_EVIDENCE = 768;

    public record Entry(String id,String phase,String featureId,String kind,String subjectId,String label,
                        int x,int z,String state,String payload,String author,long createdAt,long updatedAt) {
        public Entry {
            id=normalizeId(id,"entry"); phase=upper(phase,"P6"); featureId=upper(featureId,"OC-F000"); kind=upper(kind,"ARTIFACT");
            subjectId=clip(subjectId,"",128); label=clip(label,"Artifact",160); state=upper(state,"ACTIVE");
            payload=clip(payload,"",8192); author=clip(author,"system",96); createdAt=Math.max(0L,createdAt);updatedAt=Math.max(createdAt,updatedAt);
        }
    }
    public record Evidence(String id,String phase,String featureId,String subjectId,String state,int severity,
                           String summary,int x,int z,long observedAt) {
        public Evidence {
            id=normalizeId(id,"evidence");phase=upper(phase,"P6");featureId=upper(featureId,"OC-F000");subjectId=clip(subjectId,"",128);
            state=upper(state,"INFO");severity=Math.max(0,Math.min(100,severity));summary=clip(summary,"",4096);observedAt=Math.max(0L,observedAt);
        }
    }

    private static final Codec<OceanCanvasProgramData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema),
            Codec.STRING.listOf().optionalFieldOf("entries",List.of()).forGetter(d->d.entries.values().stream().map(OceanCanvasProgramData::encEntry).toList()),
            Codec.STRING.listOf().optionalFieldOf("evidence",List.of()).forGetter(d->d.evidence.stream().map(OceanCanvasProgramData::encEvidence).toList()),
            Codec.STRING.listOf().optionalFieldOf("governance",List.of()).forGetter(d->d.governance.entrySet().stream().map(e->en(e.getKey())+"\t"+en(e.getValue())).toList())
    ).apply(i,OceanCanvasProgramData::new));
    public static final SavedDataType<OceanCanvasProgramData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasProgramData::new,CODEC,null);

    private int schema;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private final ArrayList<Evidence> evidence=new ArrayList<>();
    /** Small durable governance state. Unlike the rolling evidence log this is not retention-trimmed. */
    private final LinkedHashMap<String,String> governance=new LinkedHashMap<>();

    public OceanCanvasProgramData(){this(CURRENT_SCHEMA,List.of(),List.of(),List.of());}
    private OceanCanvasProgramData(int schema,List<String> entries,List<String> evidence,List<String> governance){
        this.schema=Math.max(1,schema);
        if(entries!=null)for(String s:entries){Entry v=decEntry(s);if(v!=null)this.entries.put(v.id(),v);}
        trimMap(this.entries,MAX_ENTRIES);
        if(evidence!=null)for(String s:evidence){Evidence v=decEvidence(s);if(v!=null)this.evidence.add(v);}
        trim(this.evidence,MAX_EVIDENCE);
        if(governance!=null)for(String raw:governance){String[] f=raw.split("\t",2);if(f.length==2){String k=un(f[0]),v=un(f[1]);if(!k.isBlank()&&!v.isBlank())this.governance.put(k,v);}}
    }
    public static OceanCanvasProgramData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}
    public int schema(){return schema;}
    public String newId(String prefix){return normalizeId(prefix+"_"+UUID.randomUUID().toString().substring(0,8),prefix);}
    public List<Entry> entries(){return List.copyOf(entries.values());}
    public List<Entry> entries(String phase){String p=upper(phase,"");return entries.values().stream().filter(v->p.isBlank()||v.phase().equals(p)).toList();}
    public List<Entry> featureEntries(String featureId){String f=upper(featureId,"");return entries.values().stream().filter(v->v.featureId().equals(f)).toList();}
    public Entry entry(String id){return entries.get(OceanCanvasProgramData.normalizeId(id,""));}
    public Entry latest(String featureId){String f=upper(featureId,"");Entry hit=null;for(Entry e:entries.values())if(e.featureId().equals(f)&&(hit==null||e.updatedAt()>hit.updatedAt()))hit=e;return hit;}
    public void putEntry(Entry v){entries.put(v.id(),v);trimMap(entries,MAX_ENTRIES);setDirty();}
    public boolean removeEntry(String id){boolean changed=entries.remove(OceanCanvasProgramData.normalizeId(id,""))!=null;if(changed)setDirty();return changed;}
    public List<Evidence> evidence(){return reverse(evidence);}
    public List<Evidence> evidence(String phase){String p=upper(phase,"");return reverse(evidence).stream().filter(v->p.isBlank()||v.phase().equals(p)).toList();}
    public void addEvidence(Evidence v){evidence.add(v);trim(evidence,MAX_EVIDENCE);setDirty();}
    public void clearEvidenceFor(String featureId){String f=upper(featureId,"");if(evidence.removeIf(v->v.featureId().equals(f)))setDirty();}
    public String governanceState(String key){return governance.getOrDefault(normalizeId(key,""),"");}
    public void setGovernanceState(String key,String state){String k=normalizeId(key,"");String v=upper(state,"");if(k.isBlank()||v.isBlank())return;if(!v.equals(governance.put(k,v)))setDirty();}

    private static String encEntry(Entry v){return String.join("\t",en(v.id()),en(v.phase()),en(v.featureId()),en(v.kind()),en(v.subjectId()),en(v.label()),Integer.toString(v.x()),Integer.toString(v.z()),en(v.state()),en(v.payload()),en(v.author()),Long.toString(v.createdAt()),Long.toString(v.updatedAt()));}
    private static Entry decEntry(String s){try{String[] f=s.split("\\t",-1);if(f.length!=13)return null;return new Entry(un(f[0]),un(f[1]),un(f[2]),un(f[3]),un(f[4]),un(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),un(f[8]),un(f[9]),un(f[10]),Long.parseLong(f[11]),Long.parseLong(f[12]));}catch(RuntimeException ex){return null;}}
    private static String encEvidence(Evidence v){return String.join("\t",en(v.id()),en(v.phase()),en(v.featureId()),en(v.subjectId()),en(v.state()),Integer.toString(v.severity()),en(v.summary()),Integer.toString(v.x()),Integer.toString(v.z()),Long.toString(v.observedAt()));}
    private static Evidence decEvidence(String s){try{String[] f=s.split("\\t",-1);if(f.length!=10)return null;return new Evidence(un(f[0]),un(f[1]),un(f[2]),un(f[3]),un(f[4]),Integer.parseInt(f[5]),un(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),Long.parseLong(f[9]));}catch(RuntimeException ex){return null;}}
    private static String en(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    private static String un(String s){try{return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}catch(IllegalArgumentException ex){return "";}}
    private static String normalizeId(String v,String fallback){String s=v==null?"":v.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:-]","_");return s.isBlank()?fallback:s;}
    private static String upper(String v,String fallback){String s=v==null?"":v.trim();return (s.isBlank()?fallback:s).toUpperCase(Locale.ROOT);}
    private static String clip(String v,String fallback,int max){String s=v==null||v.isBlank()?fallback:v.trim();return s.substring(0,Math.min(max,s.length()));}
    private static <T> void trim(ArrayList<T> v,int max){while(v.size()>max)v.remove(0);}
    private static <K,V> void trimMap(LinkedHashMap<K,V> v,int max){while(v.size()>max){K k=v.keySet().iterator().next();v.remove(k);}}
    private static <T> List<T> reverse(List<T> in){ArrayList<T> out=new ArrayList<>(in);java.util.Collections.reverse(out);return List.copyOf(out);}
}
