package net.oceancanvas.mod.pregen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.List;
import java.util.Objects;

/**
 * v253.61.11 companion checkpoint for the exact block mask of the single active
 * Pregen/rewipe job. Pregen's transport/indexing envelope is chunk-based, but a
 * block-radius request must never mutate the extra columns in its two edge chunks.
 * Keeping this separate from OceanCanvasJobState also preserves that record codec's
 * already-full 16-field arity and remains backward compatible with old worlds.
 */
public final class OceanCanvasJobScopeState extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "job_scope_state");

    public record Scope(String kind, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                        int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ,
                        long checkpointNextIndex, long checkpointSubmittedCount,
                        long committedNextIndex, long committedSubmittedCount, boolean cleanStop,
                        String scopeName, String scopeShape) {
        public Scope { scopeName = scopeName == null ? "" : scopeName; scopeShape = scopeShape == null ? "" : scopeShape; }
        public boolean sameOperationScope(OceanCanvasJobState.Snapshot snapshot) {
            return snapshot != null && kind.equals(snapshot.kind())
                    && minChunkX == snapshot.minChunkX() && maxChunkX == snapshot.maxChunkX()
                    && minChunkZ == snapshot.minChunkZ() && maxChunkZ == snapshot.maxChunkZ();
        }
        public boolean matches(OceanCanvasJobState.Snapshot snapshot) {
            return sameOperationScope(snapshot)
                    && checkpointNextIndex == snapshot.nextIndex()
                    && checkpointSubmittedCount == snapshot.submittedCount();
        }
    }

    private static final Codec<Scope> SCOPE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("kind").forGetter(Scope::kind),
            Codec.INT.fieldOf("minChunkX").forGetter(Scope::minChunkX),
            Codec.INT.fieldOf("maxChunkX").forGetter(Scope::maxChunkX),
            Codec.INT.fieldOf("minChunkZ").forGetter(Scope::minChunkZ),
            Codec.INT.fieldOf("maxChunkZ").forGetter(Scope::maxChunkZ),
            Codec.INT.fieldOf("minBlockX").forGetter(Scope::minBlockX),
            Codec.INT.fieldOf("maxBlockX").forGetter(Scope::maxBlockX),
            Codec.INT.fieldOf("minBlockZ").forGetter(Scope::minBlockZ),
            Codec.INT.fieldOf("maxBlockZ").forGetter(Scope::maxBlockZ),
            Codec.LONG.fieldOf("checkpointNextIndex").forGetter(Scope::checkpointNextIndex),
            Codec.LONG.fieldOf("checkpointSubmittedCount").forGetter(Scope::checkpointSubmittedCount),
            Codec.LONG.optionalFieldOf("committedNextIndex", 0L).forGetter(Scope::committedNextIndex),
            Codec.LONG.optionalFieldOf("committedSubmittedCount", 0L).forGetter(Scope::committedSubmittedCount),
            Codec.BOOL.optionalFieldOf("cleanStop", false).forGetter(Scope::cleanStop),
            Codec.STRING.optionalFieldOf("scopeName", "").forGetter(Scope::scopeName),
            Codec.STRING.optionalFieldOf("scopeShape", "").forGetter(Scope::scopeShape)
    ).apply(i, Scope::new));

    private static final Codec<OceanCanvasJobScopeState> CODEC = RecordCodecBuilder.create(i -> i.group(
            SCOPE_CODEC.listOf().optionalFieldOf("scope", List.of())
                    .forGetter(data -> data.scope == null ? List.of() : List.of(data.scope))
    ).apply(i, OceanCanvasJobScopeState::new));

    public static final SavedDataType<OceanCanvasJobScopeState> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasJobScopeState::new, CODEC, null);

    private Scope scope;

    public OceanCanvasJobScopeState() { this(List.of()); }
    private OceanCanvasJobScopeState(List<Scope> scopes) { this.scope = scopes.isEmpty() ? null : scopes.get(0); }

    public static OceanCanvasJobScopeState get(ServerLevel world) { return world.getDataStorage().computeIfAbsent(TYPE); }
    public Scope get() { return scope; }
    public void save(Scope value) { if (Objects.equals(scope, value)) return; scope = value; setDirty(); }
    public void clear() { if (scope != null) { scope = null; setDirty(); } }
}
