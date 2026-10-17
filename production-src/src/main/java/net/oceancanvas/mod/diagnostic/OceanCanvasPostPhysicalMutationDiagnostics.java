package net.oceancanvas.mod.diagnostic;

import java.util.concurrent.atomic.AtomicLong;
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
 */
public final class OceanCanvasPostPhysicalMutationDiagnostics {
    private OceanCanvasPostPhysicalMutationDiagnostics() { }

    private static final ThreadLocal<Integer> AUTHORIZED_AQUATIC_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    /*
     * StackWalker plus one WARN per vanilla fluid update became a measurable
     * large-area hot path. Correctness does not depend on that diagnostic: every
     * qualifying write is still detected and invalidates/re-arms strict lighting
     * proof in prepareLevelSetBlockMutation(). Preserve dense initial provenance,
     * then bounded periodic samples so a changed caller remains observable without
     * turning a fluid-settle burst into hundreds of thousands of stack walks and
     * formatted WARN lines.
     */
    private static final AtomicLong PROVENANCE_MUTATIONS = new AtomicLong();
    private static final long DENSE_PROVENANCE_SAMPLES = 16L;
    private static final long PROVENANCE_SAMPLE_MASK = 0xFFL;

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

    static boolean shouldInvalidateProof(boolean canvasTerrain, boolean physicalVerified,
            boolean lightingVerified, boolean changed) {
        return shouldRecord(canvasTerrain, physicalVerified, lightingVerified, changed);
    }

    static boolean shouldSampleProvenance(long ordinal) {
        return ordinal <= DENSE_PROVENANCE_SAMPLES || (ordinal & PROVENANCE_SAMPLE_MASK) == 0L;
    }

    public static void prepareLevelSetBlockMutation(ServerLevel world, BlockPos pos, BlockState newState) {
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
        OceanCanvasSurfaceFlattener.prepareForAquaticDecorationMutation(world, chunkPos);
    }

    public static void recordLevelSetBlock(ServerLevel world, BlockPos pos, BlockState newState,
            boolean changed) {
        if (isAuthorizedAquaticMutation()) return;
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        boolean canvasTerrain = OceanCanvasTerrainStateData.get(world).get(chunkPos)
                == OceanCanvasTerrainStateData.TerrainState.CANVAS;
        OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
        boolean physicalVerified = protectedData.isChunkProcessedPhysicallyVerified(chunkPos);
        boolean lightingVerified = protectedData.isChunkLightingVerified(chunkPos);
        if (!shouldRecord(canvasTerrain, physicalVerified, lightingVerified, changed)) return;

        long ordinal = PROVENANCE_MUTATIONS.incrementAndGet();
        if (!shouldSampleProvenance(ordinal)) return;
        String caller = StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("net.oceancanvas.mod.diagnostic."))
                .filter(frame -> !frame.getClassName().startsWith("net.oceancanvas.mod.mixin."))
                .filter(frame -> !frame.getClassName().equals("net.minecraft.world.level.Level"))
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .orElse("unknown"));
        OceanCanvas.LOGGER.warn(
                "(Ocean Canvas) POST-PHYSICAL-BLOCK-MUTATION build={} ordinal={} chunk={},{} pos={} newState={} caller={} classification=LEVEL_SETBLOCK_AFTER_PHYSICAL_BEFORE_LIGHT_CERT action=preserve-provenance-for-strict-fingerprint-failure",
                OceanCanvas.VERSION, ordinal, chunkX, chunkZ, pos, newState, caller);
    }
}
