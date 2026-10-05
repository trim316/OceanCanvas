package net.oceancanvas.mod.operation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Small persisted visibility history for Ocean Canvas operations.
 *
 * <p>This is not a terrain backup and not undo state. It is durable context for
 * Recovery/Health/Timeline UX: what operation was requested, by whom, and when.
 * The list is deliberately bounded so multi-year worlds do not grow an
 * unbounded SavedData blob.</p>
 */
public final class OceanCanvasOperationHistoryData extends SavedData {
    public static final int CURRENT_SCHEMA = 4;
    private static final int MAX_ENTRIES = 200;
    private static final Identifier DATA_ID =
            Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "operation_history");

    public record Entry(long epochMillis, String kind, String phase, String requester, String detail,
                        String scopeType, String scopeId, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, String scopeDescriptor) {
        public Entry {
            kind = kind == null ? "" : kind;
            phase = phase == null || phase.isBlank() ? "STARTED" : phase;
            requester = requester == null ? "" : requester;
            detail = detail == null ? "" : detail;
            scopeType = scopeType == null ? "" : scopeType;
            scopeId = scopeId == null ? "" : scopeId;
            scopeDescriptor = scopeDescriptor == null ? "" : scopeDescriptor;
        }
        public boolean hasScope(){return !scopeType.isBlank() && minChunkX<=maxChunkX && minChunkZ<=maxChunkZ;}
    }

    private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("epochMillis").forGetter(Entry::epochMillis),
            Codec.STRING.fieldOf("kind").forGetter(Entry::kind),
            Codec.STRING.optionalFieldOf("phase", "STARTED").forGetter(Entry::phase),
            Codec.STRING.fieldOf("requester").forGetter(Entry::requester),
            Codec.STRING.fieldOf("detail").forGetter(Entry::detail),
            Codec.STRING.optionalFieldOf("scopeType","").forGetter(Entry::scopeType),
            Codec.STRING.optionalFieldOf("scopeId","").forGetter(Entry::scopeId),
            Codec.INT.optionalFieldOf("minChunkX",1).forGetter(Entry::minChunkX),
            Codec.INT.optionalFieldOf("minChunkZ",1).forGetter(Entry::minChunkZ),
            Codec.INT.optionalFieldOf("maxChunkX",0).forGetter(Entry::maxChunkX),
            Codec.INT.optionalFieldOf("maxChunkZ",0).forGetter(Entry::maxChunkZ),
            Codec.STRING.optionalFieldOf("scopeDescriptor","").forGetter(Entry::scopeDescriptor)
    ).apply(instance, Entry::new));

    private static final Codec<OceanCanvasOperationHistoryData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.optionalFieldOf("schema", CURRENT_SCHEMA).forGetter(data -> data.schema),
                    ENTRY_CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(data -> data.entries)
            ).apply(instance, OceanCanvasOperationHistoryData::new));

    public static final SavedDataType<OceanCanvasOperationHistoryData> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasOperationHistoryData::new, CODEC, null);

    private int schema;
    private final List<Entry> entries;

    public OceanCanvasOperationHistoryData() {
        this(CURRENT_SCHEMA, List.of());
    }

    private OceanCanvasOperationHistoryData(int schema, List<Entry> entries) {
        this.schema = Math.max(1, schema);
        this.entries = new ArrayList<>();
        if (entries != null) {
            int start = Math.max(0, entries.size() - MAX_ENTRIES);
            this.entries.addAll(entries.subList(start, entries.size()));
        }
    }

    public static OceanCanvasOperationHistoryData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    public void record(String kind, String requester, String detail) {
        record(kind, "STARTED", requester, detail);
    }

    public void record(String kind, String phase, String requester, String detail) {
        record(kind,phase,requester,detail,"","",1,1,0,0,"");
    }

    public void record(String kind,String phase,String requester,String detail,String scopeType,String scopeId,
                       int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ){
        record(kind,phase,requester,detail,scopeType,scopeId,minChunkX,minChunkZ,maxChunkX,maxChunkZ,"");
    }

    public void record(String kind,String phase,String requester,String detail,String scopeType,String scopeId,
                       int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,String scopeDescriptor){
        entries.add(new Entry(System.currentTimeMillis(),kind,phase,requester,detail,scopeType,scopeId,minChunkX,minChunkZ,maxChunkX,maxChunkZ,scopeDescriptor));
        while(entries.size()>MAX_ENTRIES)entries.remove(0);
        setDirty();
    }

    /** Most-recent-first immutable snapshot. */
    public List<Entry> recent() {
        List<Entry> out = new ArrayList<>(entries);
        java.util.Collections.reverse(out);
        return List.copyOf(out);
    }

    public int schema() { return schema; }
}
