package net.oceancanvas.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draft: refuse only landing writes that violate an active, selected, unprotected
 * authored water column. Returning false retains vanilla's item-drop behavior.
 */
@Mixin(FallingBlockEntity.class)
public abstract class OceanCanvasFallingBlockPlacementMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean oceancanvas$preserveAuthoredWater(Level level, BlockPos pos, BlockState state, int flags) {
        if (OceanCanvasSurfaceFlattener.shouldRejectFallingBlockPlacement(level, pos, state)) return false;
        return level.setBlock(pos, state, flags);
    }
}
