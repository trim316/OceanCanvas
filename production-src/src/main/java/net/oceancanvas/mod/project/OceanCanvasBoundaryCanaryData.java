package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.List;

/** Persistent OC-F119 boundary-canary baseline/result evidence. */
public final class OceanCanvasBoundaryCanaryData extends SavedData {
    public static final int CURRENT_SCHEMA=1;
    private static final int MAX_RESULTS=64;
    private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"boundary_canary");

    public record Sample(long chunkKey,long fingerprint) {}
    public record Baseline(String kind,String scopeId,long epochMillis,int ringCandidates,int sampled,int skippedUnloaded,List<Sample> samples){
        public Baseline{kind=safe(kind);scopeId=safe(scopeId);samples=samples==null?List.of():List.copyOf(samples);}
    }
    public record Result(long epochMillis,String kind,String scopeId,String state,int checked,int changed,int skippedUnloaded,String detail){
        public Result{kind=safe(kind);scopeId=safe(scopeId);state=safe(state);detail=safe(detail);}
    }

    private static final Codec<Sample> SAMPLE_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.LONG.fieldOf("chunkKey").forGetter(Sample::chunkKey),
            Codec.LONG.fieldOf("fingerprint").forGetter(Sample::fingerprint)
    ).apply(i,Sample::new));
    private static final Codec<Baseline> BASELINE_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("kind").forGetter(Baseline::kind),
            Codec.STRING.optionalFieldOf("scopeId","").forGetter(Baseline::scopeId),
            Codec.LONG.fieldOf("epochMillis").forGetter(Baseline::epochMillis),
            Codec.INT.fieldOf("ringCandidates").forGetter(Baseline::ringCandidates),
            Codec.INT.fieldOf("sampled").forGetter(Baseline::sampled),
            Codec.INT.fieldOf("skippedUnloaded").forGetter(Baseline::skippedUnloaded),
            SAMPLE_CODEC.listOf().optionalFieldOf("samples",List.of()).forGetter(Baseline::samples)
    ).apply(i,Baseline::new));
    private static final Codec<Result> RESULT_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.LONG.fieldOf("epochMillis").forGetter(Result::epochMillis),
            Codec.STRING.fieldOf("kind").forGetter(Result::kind),
            Codec.STRING.optionalFieldOf("scopeId","").forGetter(Result::scopeId),
            Codec.STRING.fieldOf("state").forGetter(Result::state),
            Codec.INT.fieldOf("checked").forGetter(Result::checked),
            Codec.INT.fieldOf("changed").forGetter(Result::changed),
            Codec.INT.fieldOf("skippedUnloaded").forGetter(Result::skippedUnloaded),
            Codec.STRING.fieldOf("detail").forGetter(Result::detail)
    ).apply(i,Result::new));
    private static final Codec<OceanCanvasBoundaryCanaryData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema),
            BASELINE_CODEC.listOf().optionalFieldOf("active",List.of()).forGetter(d->d.active==null?List.of():List.of(d.active)),
            RESULT_CODEC.listOf().optionalFieldOf("results",List.of()).forGetter(d->d.results)
    ).apply(i,OceanCanvasBoundaryCanaryData::new));
    public static final SavedDataType<OceanCanvasBoundaryCanaryData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasBoundaryCanaryData::new,CODEC,null);

    private int schema;
    private Baseline active;
    private final List<Result> results;
    public OceanCanvasBoundaryCanaryData(){this(CURRENT_SCHEMA,List.of(),List.of());}
    private OceanCanvasBoundaryCanaryData(int schema,List<Baseline> active,List<Result> results){
        this.schema=Math.max(1,schema);this.active=active==null||active.isEmpty()?null:active.get(active.size()-1);this.results=new ArrayList<>();
        if(results!=null){int start=Math.max(0,results.size()-MAX_RESULTS);this.results.addAll(results.subList(start,results.size()));}
    }
    public static OceanCanvasBoundaryCanaryData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}
    public Baseline active(){return active;}
    public void setActive(Baseline baseline){active=baseline;setDirty();}
    public void clearActive(){active=null;setDirty();}
    public void record(Result result){active=null;results.add(result);while(results.size()>MAX_RESULTS)results.remove(0);setDirty();}
    public Result latestResult(){return results.isEmpty()?null:results.get(results.size()-1);}
    public String acknowledgeLatestFailure(String actor){
        Result last=latestResult();if(last==null||!"FAIL".equals(last.state()))return "No unacknowledged boundary-canary failure exists.";
        record(new Result(System.currentTimeMillis(),last.kind(),last.scopeId(),"ACKNOWLEDGED",last.checked(),last.changed(),last.skippedUnloaded(),"Acknowledged by "+safe(actor)+" after review; prior FAIL evidence remains in history."));
        return "Boundary-canary failure acknowledged after review. The original FAIL remains in retained evidence.";
    }
    public List<Result> recent(){var out=new ArrayList<>(results);java.util.Collections.reverse(out);return List.copyOf(out);}
    private static String safe(String s){return s==null?"":s;}
}
