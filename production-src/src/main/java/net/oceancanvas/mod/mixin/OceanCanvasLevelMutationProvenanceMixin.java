package net.oceancanvas.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.oceancanvas.mod.diagnostic.OceanCanvasPostPhysicalMutationDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Read-only provenance observer; never changes Level#setBlock's result or arguments. */
@Mixin(Level.class)
public abstract class OceanCanvasLevelMutationProvenanceMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN"))
    private void oceancanvas$recordPostPhysicalMutation(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            OceanCanvasPostPhysicalMutationDiagnostics.recordLevelSetBlock(
                    serverLevel, pos, state, Boolean.TRUE.equals(cir.getReturnValue()));
        }
    }
}
