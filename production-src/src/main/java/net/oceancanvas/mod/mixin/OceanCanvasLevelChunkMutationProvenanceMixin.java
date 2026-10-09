package net.oceancanvas.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.oceancanvas.mod.diagnostic.OceanCanvasPostPhysicalMutationDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Read-only provenance observer for direct LevelChunk mutation paths that bypass
 * Level#setBlock. It never changes the requested state, flags, or return value.
 */
@Mixin(LevelChunk.class)
public abstract class OceanCanvasLevelChunkMutationProvenanceMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void oceancanvas$recordDirectChunkMutation(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel serverLevel) {
            BlockState previous = cir.getReturnValue();
            boolean changed = previous != null && !previous.equals(state);
            OceanCanvasPostPhysicalMutationDiagnostics.recordDirectChunkSetBlockState(
                    serverLevel, pos, state, changed);
        }
    }
}
