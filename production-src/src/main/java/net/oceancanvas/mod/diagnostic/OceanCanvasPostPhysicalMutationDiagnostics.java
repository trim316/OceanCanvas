package net.oceancanvas.mod.diagnostic;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.project.OceanCanvasTerrainStateData;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;

/**
 * R1-139 diagnostic boundary for mutations that occur after physical Canvas
 * certification but before the strict lighting certificate is published.
 *
 * <p>The light finalizer already fails closed when its boundary fingerprint
 * changes, but a hash alone cannot identify the vanilla mechanism that wrote the
 * first changed cell. Level#setBlock is deliberately observed here because Ocean
 * Canvas' bulk terrain authoring uses direct LevelChunk writes; successful calls
 * reaching this observer are therefore high-value provenance for scheduled
 * vanilla/gameplay mutations such as fluid/falling-block behavior.</p>
 */
public final class OceanCanvasPostPhysicalMutationDiagnostics {
    private OceanCanvasPostPhysicalMutationDiagnostics() { }

    /*
     * Aquatic decoration is a special case: OceanCanvasOceanVegetation already
     * observes every real placement before delegating to Level#setBlock and
     * invalidates the exact target chunk once per placement batch. Repeating the
     * same state lookup/invalidation plus a StackWalker and WARN for every kelp
     * segment is redundant and became a dominant large-area hot path. Keep this
     * marker thread-local and nestable so unrelated Level#setBlock calls retain
     * the full fail-closed diagnostic path and exceptions cannot leak suppression
     * across server-thread work.
     */
    private static final ThreadLocal<Integer> AUTHORIZED_AQUATIC_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    /*
     * R1-145: a large water field can execute tens of thousands of FlowingFluid
     * writes while a chunk is in the physical-to-light gap. The strict lighting
     * invalidator already treats every block/fluid callback in one chunk and one
     * server tick as one physical epoch. Mirror that exact boundary here: one
     * pre-write invalidation and one provenance record per chunk per tick are
     * sufficient. This does not weaken the certificate or mutation fingerprint;
     * it only removes duplicate state lookups, StackWalker attribution and WARN
     * I/O after the first mutation in the same already-invalidated epoch.
     *
     * Thread-local state is intentional: Level#setBlock is observed on the server
     * thread, and a tick change clears the bounded set before it can be reused.
     */
    private static final ThreadLocal<TickChunkSet> PREPARED_CHUNKS =
            ThreadLocal.withInitial(TickChunkSet::new);
    private static final ThreadLocal<TickChunkSet> RECORDED_CHUNKS =
            ThreadLocal.withInitial(TickChunkSet::new);

    private static final class TickChunkSet {
        long tick = Long.MIN_VALUE;
        final Set<Long> chunks = new HashSet<>();

        boolean first(long currentTick, long packedChunk) {
            if (tick != currentTick) {
                tick = currentTick;
                chunks.clear();
            }
            return chunks.add(packedChunk);
        }
    }

    public static void beginAuthorizedAquaticMutation() {
        AUTHORIZED_AQUATIC_DEPTH.set(AUTHORIZED_AQUATIC_DEPTH.get() + 1);
    }

    public static void endAuthorizedAquaticMutation() {
        int depth = AUTHORIZED_AQUATIC_DEPTH.get();
        if (depth <= 1) AUTHORIZED_AQUATIC_DEPTH.remove();
        else AUTHORIZED_AQUATIC_DEPTH.set(depth - 1);
    }

    static boolean isAuthorizedAquaticMutation() {
        return AUTHORIZED_AQUATIC_DEPTH.get() > 0;
    }

    static boolean shouldRecord(boolean canvasTerrain, boolean physicalVerified,
            boolean lightingVerified, boolean changed) {
        return changed && canvasTerrain && physicalVerified && !lightingVerified;
    }

    /**
     * A real world-level mutation in the physical-to-light gap invalidates the
     * in-flight lighting epoch before Minecraft is allowed to perform the write.
     * This is deliberately the same fail-closed boundary used by provenance.
     */
    static boolean shouldInvalidateProof(boolean canvasTerrain, boolean physicalVerified,
            boolean lightingVerified, boolean changed) {
        return shouldRecord(canvasTerrain, physicalVerified, lightingVerified, changed);
    }

    public static void prepareLevelSetBlockMutation(ServerLevel world, BlockPos pos, BlockState newState) {
        // The aquatic placement proxy has already compared the old/new state and
        // invalidated this exact target chunk before entering Level#setBlock.
        if (isAuthorizedAquaticMutation()) return;
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        boolean changed = !world.getBlockState(pos).equals(newState);
        boolean canvasTerrain = OceanCanvasTerrainStateData.get(world).get(chunkPos)
                == OceanCanvasTerrainStateData.TerrainState.CANVAS;
        OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
        boolean physicalVerified = protectedData.isChunkProcessedPhysicallyVerified(chunkPos);
        boolean lightingVerified = protectedData.isChunkLightingVerified(chunkPos);
        if (!shouldInvalidateProof(canvasTerrain, physicalVerified, lightingVerified, changed)) return;
        if (!PREPARED_CHUNKS.get().first(world.getGameTime(), ChunkPos.pack(chunkX, chunkZ))) return;

        // Reuse the existing strict epoch restart. Despite its historical aquatic
        // name, this method only dirties/re-arms lighting proof state; it grants no
        // block-write or physical-repair authority.
        OceanCanvasSurfaceFlattener.prepareForAquaticDecorationMutation(world, chunkPos);
    }

    public static void recordLevelSetBlock(ServerLevel world, BlockPos pos, BlockState newState,
            boolean changed) {
        // Known aquatic writes already carry explicit owner/target provenance in
        // OceanCanvasOceanVegetation and were invalidated before the write. Do not
        // perform a StackWalker or emit one WARN per kelp/seagrass block here.
        if (isAuthorizedAquaticMutation()) return;
        // Construct from explicit block-to-chunk coordinates. This avoids relying on
        // a BlockPos convenience constructor whose mapped API shape differs across
        // the 26.x line while preserving floor semantics for negative coordinates.
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        boolean canvasTerrain = OceanCanvasTerrainStateData.get(world).get(chunkPos)
                == OceanCanvasTerrainStateData.TerrainState.CANVAS;
        OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
        boolean physicalVerified = protectedData.isChunkProcessedPhysicallyVerified(chunkPos);
        boolean lightingVerified = protectedData.isChunkLightingVerified(chunkPos);
        if (!shouldRecord(canvasTerrain, physicalVerified, lightingVerified, changed)) return;
        if (!RECORDED_CHUNKS.get().first(world.getGameTime(), ChunkPos.pack(chunkX, chunkZ))) return;

        String caller = StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("net.oceancanvas.mod.diagnostic."))
                .filter(frame -> !frame.getClassName().startsWith("net.oceancanvas.mod.mixin."))
                .filter(frame -> !frame.getClassName().equals("net.minecraft.world.level.Level"))
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .orElse("unknown"));
        OceanCanvas.LOGGER.warn(
                "(Ocean Canvas) POST-PHYSICAL-BLOCK-MUTATION build={} chunk={},{} pos={} newState={} caller={} classification=LEVEL_SETBLOCK_AFTER_PHYSICAL_BEFORE_LIGHT_CERT action=preserve-provenance-for-strict-fingerprint-failure",
                OceanCanvas.VERSION, chunkX, chunkZ, pos, newState, caller);
    }
}
