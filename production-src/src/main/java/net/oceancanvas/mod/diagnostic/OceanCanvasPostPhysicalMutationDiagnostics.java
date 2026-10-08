package net.oceancanvas.mod.diagnostic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.project.OceanCanvasTerrainStateData;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

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

    static boolean shouldRecord(boolean canvasTerrain, boolean physicalVerified,
            boolean lightingVerified, boolean changed) {
        return changed && canvasTerrain && physicalVerified && !lightingVerified;
    }

    public static void recordLevelSetBlock(ServerLevel world, BlockPos pos, BlockState newState,
            boolean changed) {
        ChunkPos chunkPos = new ChunkPos(pos);
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
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .orElse("unknown"));
        OceanCanvas.LOGGER.warn(
                "(Ocean Canvas) POST-PHYSICAL-BLOCK-MUTATION build={} chunk={},{} pos={} newState={} caller={} classification=LEVEL_SETBLOCK_AFTER_PHYSICAL_BEFORE_LIGHT_CERT action=preserve-provenance-for-strict-fingerprint-failure",
                OceanCanvas.VERSION, chunkPos.x, chunkPos.z, pos, newState, caller);
    }
}
