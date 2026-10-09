package net.oceancanvas.mod.diagnostic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.project.OceanCanvasTerrainStateData;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

/**
 * Read-only provenance for mutations after physical Canvas certification and
 * before the strict lighting certificate is published.
 */
public final class OceanCanvasPostPhysicalMutationDiagnostics {
    private OceanCanvasPostPhysicalMutationDiagnostics() { }

    static boolean shouldRecord(boolean canvasTerrain, boolean physicalVerified,
            boolean lightingVerified, boolean changed) {
        return changed && canvasTerrain && physicalVerified && !lightingVerified;
    }

    public static void recordLevelSetBlock(ServerLevel world, BlockPos pos, BlockState newState,
            boolean changed) {
        record(world, pos, newState, changed, "LEVEL_SETBLOCK_AFTER_PHYSICAL_BEFORE_LIGHT_CERT");
    }

    /**
     * R1-140 closes the diagnostic blind spot exposed by the R1-137 disposable
     * proof: direct LevelChunk#setBlockState writes do not necessarily traverse
     * Level#setBlock, so they need their own read-only observer.
     */
    public static void recordDirectChunkSetBlockState(ServerLevel world, BlockPos pos,
            BlockState newState, boolean changed) {
        record(world, pos, newState, changed,
                "LEVELCHUNK_SETBLOCKSTATE_AFTER_PHYSICAL_BEFORE_LIGHT_CERT");
    }

    private static void record(ServerLevel world, BlockPos pos, BlockState newState,
            boolean changed, String classification) {
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        boolean canvasTerrain = OceanCanvasTerrainStateData.get(world).get(chunkPos)
                == OceanCanvasTerrainStateData.TerrainState.CANVAS;
        OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
        boolean physicalVerified = protectedData.isChunkProcessedPhysicallyVerified(chunkPos);
        boolean lightingVerified = protectedData.isChunkLightingVerified(chunkPos);
        if (!shouldRecord(canvasTerrain, physicalVerified, lightingVerified, changed)) return;

        String caller = StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("net.oceancanvas.mod.diagnostic."))
                .filter(frame -> !frame.getClassName().startsWith("net.oceancanvas.mod.mixin."))
                .filter(frame -> !frame.getClassName().equals("net.minecraft.world.level.Level"))
                .filter(frame -> !frame.getClassName().equals("net.minecraft.world.level.chunk.LevelChunk"))
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .orElse("unknown"));
        OceanCanvas.LOGGER.warn(
                "(Ocean Canvas) POST-PHYSICAL-BLOCK-MUTATION build={} chunk={},{} pos={} newState={} caller={} classification={} action=preserve-provenance-for-strict-fingerprint-failure",
                OceanCanvas.VERSION, chunkX, chunkZ, pos, newState, caller, classification);
    }
}
