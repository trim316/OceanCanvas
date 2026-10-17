package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistent, explicit terrain-state ledger. This is intentionally separate from
 * processedChunks: "Ocean Canvas has finished touching this chunk" and "what
 * terrain is intentionally supposed to be here" are different questions.
 */
public final class OceanCanvasTerrainStateData extends SavedData {
    public static final int CURRENT_SCHEMA = 3;
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "terrain_state");

    public enum TerrainState {
        UNKNOWN((byte) 0),
        VANILLA((byte) 1),
        CANVAS((byte) 2),
        CUSTOM_OR_MODIFIED((byte) 3);

        private final byte code;
        TerrainState(byte code) { this.code = code; }
        public byte code() { return code; }

        public static TerrainState fromCode(byte code) {
            for (TerrainState state : values()) if (state.code == code) return state;
            return UNKNOWN;
        }

        public static TerrainState parse(String raw) {
            if (raw == null) return UNKNOWN;
            try { return valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return UNKNOWN; }
        }
    }

    public record Entry(long chunkKey, String state) {
        public Entry { state = TerrainState.parse(state).name(); }
        public TerrainState parsedState() { return TerrainState.parse(state); }
    }

    private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("chunkKey").forGetter(Entry::chunkKey),
            Codec.STRING.fieldOf("state").forGetter(Entry::state)
    ).apply(instance, Entry::new));

    /** Compact row run used for persistence. One 20k-wide Canvas row is roughly
     * 1,250 chunks, so a uniform 20k x 20k ledger serializes as ~1,250 runs
     * instead of ~1.56 million per-chunk Entry objects. */
    public record StateRun(int z, int minX, int maxX, String state) {
        public StateRun { state = TerrainState.parse(state).name(); }
        public TerrainState parsedState() { return TerrainState.parse(state); }
    }

    private static final Codec<StateRun> STATE_RUN_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("z").forGetter(StateRun::z),
            Codec.INT.fieldOf("minX").forGetter(StateRun::minX),
            Codec.INT.fieldOf("maxX").forGetter(StateRun::maxX),
            Codec.STRING.fieldOf("state").forGetter(StateRun::state)
    ).apply(instance, StateRun::new));

    private static long chunkRowSortKey(long packed) {
        int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
        // Signed long ordering already sorts the signed Z in the upper 32 bits.
        // Bias X into unsigned order inside the lower 32 bits so negative X sorts
        // before positive X without boxing/comparator allocations.
        return ((long) z << 32) | (((long) (x ^ Integer.MIN_VALUE)) & 0xffffffffL);
    }

    private static int sortKeyX(long key) { return ((int) key) ^ Integer.MIN_VALUE; }
    private static int sortKeyZ(long key) { return (int) (key >> 32); }

    /** Authored boundary policy only; never a physical or lighting certificate. */
    public record BoundaryColumn(long columnKey, int directions, int surfaceY) {}
    private static final Codec<BoundaryColumn> BOUNDARY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("columnKey").forGetter(BoundaryColumn::columnKey),
            Codec.INT.fieldOf("directions").forGetter(BoundaryColumn::directions),
            Codec.INT.fieldOf("surfaceY").forGetter(BoundaryColumn::surfaceY)
    ).apply(instance, BoundaryColumn::new));

    private static final Codec<OceanCanvasTerrainStateData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema", CURRENT_SCHEMA).forGetter(data -> data.schema),
            // Legacy decode-only field. v253.73.7 stops emitting the million-object
            // list but still accepts every existing v253.73.6 and older save.
            ENTRY_CODEC.listOf().optionalFieldOf("chunks", List.of()).forGetter(data -> List.of()),
            STATE_RUN_CODEC.listOf().optionalFieldOf("runs", List.of()).forGetter(OceanCanvasTerrainStateData::runsForCodec),
            BOUNDARY_CODEC.listOf().optionalFieldOf("boundaryColumns", List.of()).forGetter(data -> List.copyOf(data.boundaryColumns.values()))
    ).apply(instance, OceanCanvasTerrainStateData::new));

    public static final SavedDataType<OceanCanvasTerrainStateData> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasTerrainStateData::new, CODEC, null);

    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BoundaryColumn> boundaryColumns = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet boundaryChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int schema;
    private final Long2ByteOpenHashMap states = new Long2ByteOpenHashMap();
    // v253.78: persistence runs are maintained incrementally; dirty saves no longer
    // copy/sort every explicit chunk in a mature 20k canvas.
    private final net.oceancanvas.mod.util.OceanCanvasByteRowRunIndex stateRuns =
            new net.oceancanvas.mod.util.OceanCanvasByteRowRunIndex();
    // v253.77: maintained O(1) metadata summary. Snapshots/health must never
    // materialize and sort a million-entry terrain ledger on the server thread.
    private final long[] stateCounts = new long[TerrainState.values().length];
    private long membershipXor;
    private long membershipSum;
    private long membershipRevision;

    public OceanCanvasTerrainStateData() {
        this(CURRENT_SCHEMA, List.of(), List.of(), List.of());
    }

    private OceanCanvasTerrainStateData(int schema, List<Entry> entries, List<StateRun> runs, List<BoundaryColumn> boundaries) {
        this.schema = schema <= CURRENT_SCHEMA ? CURRENT_SCHEMA : schema;
        states.defaultReturnValue(TerrainState.UNKNOWN.code());
        for (Entry entry : entries) {
            TerrainState state = entry.parsedState();
            if (state != TerrainState.UNKNOWN) {
                states.put(entry.chunkKey(), state.code());
                stateRuns.set(ChunkPos.getX(entry.chunkKey()), ChunkPos.getZ(entry.chunkKey()), state.code());
            }
        }
        for (StateRun run : runs) {
            TerrainState state = run.parsedState();
            long width = (long) run.maxX() - (long) run.minX();
            if (state == TerrainState.UNKNOWN || width < 0L || width > 1_000_000L) continue;
            stateRuns.setRange(run.minX(), run.maxX(), run.z(), state.code());
            for (int x = run.minX();; x++) {
                states.put(ChunkPos.pack(x, run.z()), state.code());
                if (x == run.maxX()) break;
            }
        }
        for (BoundaryColumn b : boundaries) {
            if (b.directions() > 0 && (b.directions() & ~15) == 0 && b.surfaceY() >= -64 && b.surfaceY() < 320) { boundaryColumns.put(b.columnKey(), b); boundaryChunks.add(ChunkPos.pack(ChunkPos.getX(b.columnKey())>>4,ChunkPos.getZ(b.columnKey())>>4)); }
        }
        rebuildSummary();
    }

    public static OceanCanvasTerrainStateData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    public int schema() { return schema; }
    /** Unknown future terrain-state schemas must never drive older destructive semantics. */
    public boolean schemaSupportedForMutation() { return schema <= CURRENT_SCHEMA; }

    public TerrainState get(ChunkPos pos) {
        return get(ChunkPos.pack(pos.x(), pos.z()));
    }

    public TerrainState get(long chunkKey) {
        return TerrainState.fromCode(states.get(chunkKey));
    }

    public void set(ChunkPos pos, TerrainState state) {
        set(ChunkPos.pack(pos.x(), pos.z()), state);
    }

    public void set(long chunkKey, TerrainState state) {
        TerrainState normalized = state == null ? TerrainState.UNKNOWN : state;
        if (normalized != TerrainState.CANVAS) clearBoundaryColumns(chunkKey);
        TerrainState oldState = TerrainState.fromCode(states.get(chunkKey));
        if(oldState == normalized) return;
        if(oldState != TerrainState.UNKNOWN) removeFromSummary(chunkKey, oldState);
        if (normalized == TerrainState.UNKNOWN) {
            states.remove(chunkKey);
        } else {
            states.put(chunkKey, normalized.code());
            addToSummary(chunkKey, normalized);
        }
        stateRuns.set(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), normalized.code());
        membershipRevision++;
        setDirty();
    }

    public void recordBoundaryColumn(int x, int z, int directions, int surfaceY) {
        if (!schemaSupportedForMutation() || directions <= 0 || (directions & ~15) != 0) return;
        long key = ChunkPos.pack(x, z);
        boundaryChunks.add(ChunkPos.pack(x>>4,z>>4));
        BoundaryColumn next = new BoundaryColumn(key, directions, surfaceY);
        if (!next.equals(boundaryColumns.put(key, next))) setDirty();
    }

    /** Direction bits: source west/east/north/south of the selected column. */
    public boolean blocksBoundaryInflow(int x, int y, int z, int sourceX, int sourceZ) {
        if (!schemaSupportedForMutation()) return false;
        BoundaryColumn b = boundaryColumns.get(ChunkPos.pack(x, z));
        if (b == null || y <= b.surfaceY()) return false;
        int dx = sourceX - x, dz = sourceZ - z;
        int bit = dx == -1 && dz == 0 ? 1 : dx == 1 && dz == 0 ? 2
                : dx == 0 && dz == -1 ? 4 : dx == 0 && dz == 1 ? 8 : 0;
        return (b.directions() & bit) != 0;
    }

    public void clearBoundaryColumns(long chunkKey) {
        if (!boundaryChunks.remove(chunkKey)) return;
        int minX = ChunkPos.getX(chunkKey) * 16, minZ = ChunkPos.getZ(chunkKey) * 16;
        boolean changed = false;
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++)
            changed |= boundaryColumns.remove(ChunkPos.pack(x, z)) != null;
        if (changed) setDirty();
    }

    public long membershipRevision() { return membershipRevision; }

    public void clear(ChunkPos pos) { set(pos, TerrainState.UNKNOWN); }

    /** Constant-time count used by large-region previews; does not allocate an entries snapshot. */
    public int explicitStateCount() { return states.size(); }

    /** Primitive, allocation-free explicit-state traversal for diagnostics. */
    @FunctionalInterface
    public interface ExplicitStateConsumer { void accept(long chunkKey, TerrainState state); }

    public void forEachExplicitState(ExplicitStateConsumer consumer) {
        if (consumer == null) return;
        for (var iterator = states.long2ByteEntrySet().fastIterator(); iterator.hasNext();) {
            var entry = iterator.next();
            TerrainState state = TerrainState.fromCode(entry.getByteValue());
            if (state != TerrainState.UNKNOWN) consumer.accept(entry.getLongKey(), state);
        }
    }

    /**
     * Bounded primitive traversal. Returns the number of explicit states visited.
     * This preserves the same backing-map iteration semantics as entries(), but
     * avoids allocating a world-sized Entry list when a diagnostic needs only a
     * handful of loaded chunks.
     */
    public int forEachExplicitState(int limit, ExplicitStateConsumer consumer) {
        if (consumer == null || limit <= 0) return 0;
        int visited = 0;
        for (var iterator = states.long2ByteEntrySet().fastIterator(); iterator.hasNext() && visited < limit;) {
            var entry = iterator.next();
            TerrainState state = TerrainState.fromCode(entry.getByteValue());
            if (state == TerrainState.UNKNOWN) continue;
            consumer.accept(entry.getLongKey(), state);
            visited++;
        }
        return visited;
    }

    /** O(1) count used by metadata snapshots and periodic status publication. */
    public long stateCount(TerrainState state) {
        return state == null ? 0L : stateCounts[state.ordinal()];
    }

    /** Stable order-independent membership fingerprint maintained on every ledger mutation. */
    public String membershipFingerprint() {
        return String.format(java.util.Locale.ROOT, "%016x%016x", membershipXor, membershipSum);
    }

    private void rebuildSummary() {
        java.util.Arrays.fill(stateCounts, 0L);
        membershipXor = 0L;
        membershipSum = 0L;
        for (var iterator = states.long2ByteEntrySet().fastIterator(); iterator.hasNext();) {
            var entry = iterator.next();
            TerrainState state = TerrainState.fromCode(entry.getByteValue());
            if(state != TerrainState.UNKNOWN) addToSummary(entry.getLongKey(), state);
        }
    }

    private void addToSummary(long key, TerrainState state) {
        stateCounts[state.ordinal()]++;
        long h = summaryHash(key, state);
        membershipXor ^= h;
        membershipSum += Long.rotateLeft(h, 23);
    }

    private void removeFromSummary(long key, TerrainState state) {
        if(stateCounts[state.ordinal()] > 0L) stateCounts[state.ordinal()]--;
        long h = summaryHash(key, state);
        membershipXor ^= h;
        membershipSum -= Long.rotateLeft(h, 23);
    }

    private static long summaryHash(long key, TerrainState state) {
        long z = key ^ (0x9E3779B97F4A7C15L * (long)(state.code() + 1));
        z ^= z >>> 30; z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27; z *= 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Defensive snapshot for explicit diagnostics only; periodic telemetry must use O(1) summaries above. */
    public List<Entry> entries() { return List.copyOf(entriesForCodec()); }

    private List<Entry> entriesForCodec() {
        List<Entry> out = new ArrayList<>(states.size());
        for (var iterator = states.long2ByteEntrySet().fastIterator(); iterator.hasNext();) {
            var entry = iterator.next();
            TerrainState state = TerrainState.fromCode(entry.getByteValue());
            if (state != TerrainState.UNKNOWN) out.add(new Entry(entry.getLongKey(), state.name()));
        }
        return out;
    }

    private List<StateRun> runsForCodec() {
        if (stateRuns.isEmpty()) return List.of();
        List<StateRun> out = new ArrayList<>();
        // v253.125.37: serialize directly from the maintained run index. The old
        // snapshot() path first allocated an intermediate Run record for every row
        // segment, then allocated the actual StateRun records immediately after.
        stateRuns.forEachRun((z, minX, maxX, value) -> {
            TerrainState state = TerrainState.fromCode(value);
            if (state != TerrainState.UNKNOWN) out.add(new StateRun(z, minX, maxX, state.name()));
        });
        return out;
    }
}
