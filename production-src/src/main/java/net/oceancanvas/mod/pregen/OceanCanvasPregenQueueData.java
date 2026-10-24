package net.oceancanvas.mod.pregen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import java.util.List;

/** Independent metadata file; never rewrites region/planning/terrain schemas. */
public final class OceanCanvasPregenQueueData extends SavedData {
    private static final Codec<OceanCanvasPregenQueueModel.Entry> ENTRY=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(OceanCanvasPregenQueueModel.Entry::id),
            Codec.STRING.fieldOf("region").forGetter(OceanCanvasPregenQueueModel.Entry::region),
            Codec.STRING.fieldOf("owner").forGetter(OceanCanvasPregenQueueModel.Entry::owner),
            Codec.STRING.fieldOf("shape").forGetter(OceanCanvasPregenQueueModel.Entry::shape),
            Codec.STRING.fieldOf("canvas").forGetter(OceanCanvasPregenQueueModel.Entry::canvas),
            Codec.LONG.fieldOf("chunks").forGetter(OceanCanvasPregenQueueModel.Entry::chunks),
            Codec.STRING.optionalFieldOf("state","WAITING").forGetter(OceanCanvasPregenQueueModel.Entry::state),
            Codec.STRING.optionalFieldOf("detail","").forGetter(OceanCanvasPregenQueueModel.Entry::detail)
    ).apply(i,OceanCanvasPregenQueueModel.Entry::new));
    private static final Codec<OceanCanvasPregenQueueData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",1).forGetter(d->d.model.schema()),
            Codec.LONG.optionalFieldOf("revision",0L).forGetter(d->d.model.revision()),
            ENTRY.listOf().optionalFieldOf("entries",List.of()).forGetter(d->d.model.entries())
    ).apply(i,OceanCanvasPregenQueueData::new));
    public static final SavedDataType<OceanCanvasPregenQueueData> TYPE=new SavedDataType<>(
            Identifier.fromNamespaceAndPath("oceancanvas","pregen_queue"),OceanCanvasPregenQueueData::new,CODEC,null);
    private final OceanCanvasPregenQueueModel model;
    public OceanCanvasPregenQueueData(){this(1,0,List.of());}
    private OceanCanvasPregenQueueData(int schema,long revision,List<OceanCanvasPregenQueueModel.Entry> entries){
        model=new OceanCanvasPregenQueueModel(schema,revision,entries);
    }
    public static OceanCanvasPregenQueueData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}
    public OceanCanvasPregenQueueModel model(){return model;}
    public void changed(){setDirty();}
}
