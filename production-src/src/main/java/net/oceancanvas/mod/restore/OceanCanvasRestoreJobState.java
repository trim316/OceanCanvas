package net.oceancanvas.mod.restore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.List;
import java.util.UUID;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Persistent checkpoint for Restore to Vanilla.
 *
 * <p>v253.78 stores the immutable restore scope as horizontal chunk runs and progress
 * as a compact bitset-word list. Older checkpoints containing a reordered boxed
 * {@code chunks} list remain readable and are migrated in memory on resume. A crash
 * during prune/generation leaves the current bit clear, so resume safely retries that
 * chunk instead of trusting a half-finished transaction.</p>
 */
public final class OceanCanvasRestoreJobState extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "restore_job_state");

    public record ChunkRun(int z,int minX,int maxX) {
        public ChunkRun { if(maxX<minX){int t=minX;minX=maxX;maxX=t;} }
        public long size(){return (long)maxX-(long)minX+1L;}
    }

    private static final Codec<ChunkRun> RUN_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("z").forGetter(ChunkRun::z),
            Codec.INT.fieldOf("minX").forGetter(ChunkRun::minX),
            Codec.INT.fieldOf("maxX").forGetter(ChunkRun::maxX)
    ).apply(instance, ChunkRun::new));

    private static final Codec<Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("regionName").forGetter(Snapshot::regionName),
            // Legacy v253.77-and-earlier fields. New checkpoints intentionally emit an
            // empty chunks list, but retaining the field keeps old worlds decodable.
            Codec.LONG.listOf().optionalFieldOf("chunks", List.of()).forGetter(Snapshot::chunks),
            Codec.INT.optionalFieldOf("nextIndex", 0).forGetter(Snapshot::nextIndex),
            Codec.STRING.listOf().optionalFieldOf("requestedBy", List.of()).forGetter(Snapshot::requestedByList),
            RUN_CODEC.listOf().optionalFieldOf("runs", List.of()).forGetter(Snapshot::runs),
            Codec.LONG.listOf().optionalFieldOf("completedWords", List.of()).forGetter(Snapshot::completedWords),
            Codec.INT.optionalFieldOf("cursor", 0).forGetter(Snapshot::cursor)
    ).apply(instance, Snapshot::new));

    private static final Codec<OceanCanvasRestoreJobState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            SNAPSHOT_CODEC.listOf().fieldOf("job")
                    .forGetter(data -> data.snapshot == null ? List.of() : List.of(data.snapshot))
    ).apply(instance, OceanCanvasRestoreJobState::new));

    public static final SavedDataType<OceanCanvasRestoreJobState> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasRestoreJobState::new, CODEC, null);

    private Snapshot snapshot;

    public OceanCanvasRestoreJobState() { this(List.of()); }
    private OceanCanvasRestoreJobState(List<Snapshot> jobs) { snapshot = jobs.isEmpty() ? null : jobs.get(0); }

    public static OceanCanvasRestoreJobState get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    public Snapshot get() { return snapshot; }

    public void save(Snapshot value) {
        snapshot = value;
        setDirty();
    }

    public void clear() {
        if (snapshot != null) {
            snapshot = null;
            setDirty();
        }
    }

    public record Snapshot(String regionName, List<Long> chunks, int nextIndex, List<String> requestedByList,
                           List<ChunkRun> runs, List<Long> completedWords, int cursor) {
        public Snapshot {
            regionName = regionName == null ? "" : regionName;
            chunks = primitiveLongCopy(chunks);
            requestedByList = List.copyOf(requestedByList == null ? List.of() : requestedByList);
            runs = List.copyOf(runs == null ? List.of() : runs);
            completedWords = primitiveLongCopy(completedWords);
            nextIndex = Math.max(0, Math.min(nextIndex, chunks.size()));
            cursor = Math.max(0, cursor);
        }

        private static List<Long> primitiveLongCopy(List<Long> values) {
            if (values == null || values.isEmpty()) return List.of();
            LongArrayList out = new LongArrayList(values.size());
            if (values instanceof LongList primitive) {
                for (int i = 0; i < primitive.size(); i++) out.add(primitive.getLong(i));
            } else {
                for (Long value : values) if (value != null) out.add(value.longValue());
            }
            return out;
        }

        /** Source compatibility for old call sites/tests while new code uses compact fields. */
        public Snapshot(String regionName,List<Long> chunks,int nextIndex,List<String> requestedByList){
            this(regionName,chunks,nextIndex,requestedByList,List.of(),List.of(),nextIndex);
        }

        public UUID requestedByUuid() {
            if (requestedByList.isEmpty()) return null;
            try { return UUID.fromString(requestedByList.get(0)); }
            catch (IllegalArgumentException ignored) { return null; }
        }
    }
}
