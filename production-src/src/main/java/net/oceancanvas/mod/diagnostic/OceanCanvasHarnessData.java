package net.oceancanvas.mod.diagnostic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bounded durable evidence for the ARCH-15 reliability harness.
 *
 * <p>Raw telemetry is intentionally not persisted here.  Each row is a compact
 * summary of an explicitly-run harness action so a later build can answer
 * "what did we last prove on this world/machine?" without growing the save
 * indefinitely.  Full reports remain ordinary files under
 * {@code oceancanvas/diagnostics/harness}.</p>
 */
public final class OceanCanvasHarnessData extends SavedData {
    public static final int CURRENT_SCHEMA=1;
    private static final int MAX_RUNS=128;
    private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"harness_evidence");

    public record Run(String id,long epochMillis,String featureId,String kind,String verdict,String build,
                      long checksum,double metric,String signature,String evidence) {
        public Run {
            id=safe(id);featureId=safe(featureId);kind=safe(kind).toUpperCase(Locale.ROOT);
            verdict=safe(verdict).toUpperCase(Locale.ROOT);build=safe(build);signature=safe(signature);evidence=safe(evidence);
        }
    }

    private static final Codec<Run> RUN_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(Run::id),
            Codec.LONG.fieldOf("epochMillis").forGetter(Run::epochMillis),
            Codec.STRING.fieldOf("featureId").forGetter(Run::featureId),
            Codec.STRING.fieldOf("kind").forGetter(Run::kind),
            Codec.STRING.fieldOf("verdict").forGetter(Run::verdict),
            Codec.STRING.fieldOf("build").forGetter(Run::build),
            Codec.LONG.optionalFieldOf("checksum",0L).forGetter(Run::checksum),
            Codec.DOUBLE.optionalFieldOf("metric",Double.NaN).forGetter(Run::metric),
            Codec.STRING.optionalFieldOf("signature","").forGetter(Run::signature),
            Codec.STRING.optionalFieldOf("evidence","").forGetter(Run::evidence)
    ).apply(i,Run::new));

    private static final Codec<OceanCanvasHarnessData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema),
            RUN_CODEC.listOf().optionalFieldOf("runs",List.of()).forGetter(d->d.runs)
    ).apply(i,OceanCanvasHarnessData::new));

    public static final SavedDataType<OceanCanvasHarnessData> TYPE=
            new SavedDataType<>(DATA_ID,OceanCanvasHarnessData::new,CODEC,null);

    private int schema;
    private final List<Run> runs;

    public OceanCanvasHarnessData(){this(CURRENT_SCHEMA,List.of());}
    private OceanCanvasHarnessData(int schema,List<Run> runs){
        this.schema=Math.max(1,schema);this.runs=new ArrayList<>();
        if(runs!=null){int start=Math.max(0,runs.size()-MAX_RUNS);this.runs.addAll(runs.subList(start,runs.size()));}
    }
    public static OceanCanvasHarnessData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}
    public int schema(){return schema;}

    public Run record(String featureId,String kind,String verdict,long checksum,double metric,String signature,String evidence){
        long now=System.currentTimeMillis();
        String id=Long.toUnsignedString(now,36)+"-"+Integer.toUnsignedString(runs.size(),36);
        Run run=new Run(id,now,featureId,kind,verdict,OceanCanvas.VERSION,checksum,metric,signature,evidence);
        runs.add(run);while(runs.size()>MAX_RUNS)runs.remove(0);setDirty();return run;
    }
    public List<Run> recent(){var out=new ArrayList<>(runs);java.util.Collections.reverse(out);return List.copyOf(out);}
    public Run latest(String kind){String k=safe(kind).toUpperCase(Locale.ROOT);for(int i=runs.size()-1;i>=0;i--){Run r=runs.get(i);if(r.kind().equals(k))return r;}return null;}
    public Run latestBefore(String kind,long epochExclusive){String k=safe(kind).toUpperCase(Locale.ROOT);for(int i=runs.size()-1;i>=0;i--){Run r=runs.get(i);if(r.epochMillis()<epochExclusive&&r.kind().equals(k))return r;}return null;}
    public void clear(){if(runs.isEmpty())return;runs.clear();setDirty();}
    private static String safe(String v){return v==null?"":v;}
}
