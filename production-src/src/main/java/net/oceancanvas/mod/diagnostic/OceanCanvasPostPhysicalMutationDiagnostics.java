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

    /** Only calibrated native sculk-sensor phase/power changes below all authored profile cells.
     * Physical band, block replacement, fluid and native light changes still restart every proof.
     * The separate RETURN provenance diagnostic remains active for these real vanilla writes. */
    static boolean irrelevantUndergroundPropertyChange(int y, int transitionBottom,
            BlockState oldState, BlockState newState) {
        return y < transitionBottom && oldState.is(net.minecraft.world.level.block.Blocks.SCULK_SENSOR)
                && oldState.getBlock() == newState.getBlock()
                && oldState.getFluidState().equals(newState.getFluidState())
                && identicalNativeLightInputs(oldState, newState);
    }

    static boolean identicalNativeLightInputs(BlockState a, BlockState b) {
        if (a.getLightDampening() != b.getLightDampening() || a.getLightEmission() != b.getLightEmission()
                || a.propagatesSkylightDown() != b.propagatesSkylightDown()
                || a.useShapeForLightOcclusion() != b.useShapeForLightOcclusion()) return false;
        if (!sameShape(a.getOcclusionShape(), b.getOcclusionShape())) return false;
        for (var direction : net.minecraft.core.Direction.values())
            if (!sameShape(a.getFaceOcclusionShape(direction), b.getFaceOcclusionShape(direction))) return false;
        return true;
    }
    private static boolean sameShape(net.minecraft.world.phys.shapes.VoxelShape a,
            net.minecraft.world.phys.shapes.VoxelShape b) {
        return a == b || !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(a, b,
                net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME);
    }

    public static void prepareLevelSetBlockMutation(ServerLevel world, BlockPos pos, BlockState newState) {
        if (isAuthorizedAquaticMutation()) return;
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        BlockState oldState = world.getBlockState(pos);
        boolean changed = !oldState.equals(newState);
        boolean canvasTerrain = OceanCanvasTerrainStateData.get(world).get(chunkPos)
                == OceanCanvasTerrainStateData.TerrainState.CANVAS;
        OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
        boolean physicalVerified = protectedData.isChunkProcessedPhysicallyVerified(chunkPos);
        boolean lightingVerified = protectedData.isChunkLightingVerified(chunkPos);
        if (changed && canvasTerrain && physicalVerified && !lightingVerified) {
            var config = net.oceancanvas.mod.config.OceanCanvasConfig.get();
            int floor = config.oceanFloorY() + net.oceancanvas.mod.worldgen.OceanCanvasFloorProfile.floorOffset(
                    pos.getX(), pos.getZ(), config.oceanFloorVariation());
            if (irrelevantUndergroundPropertyChange(pos.getY(), floor - config.oceanFloorTransitionThickness(), oldState, newState)) return;
        }
        if (shouldInvalidateProof(canvasTerrain, physicalVerified, lightingVerified, changed)) {
            OceanCanvasSurfaceFlattener.prepareForAquaticDecorationMutation(world, chunkPos);
        } else if (changed && canvasTerrain && physicalVerified && lightingVerified) {
            // Keep the ordinary diagnostic boundary unchanged. Only selected,
            // active, unprotected above-surface fluid inflow reopens a final cert.
            OceanCanvasSurfaceFlattener.prepareForTrackedAboveSurfaceFluidMutation(world, pos, newState);
        }
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
