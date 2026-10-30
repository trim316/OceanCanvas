package net.oceancanvas.mod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Reconciles only an attachment that vanilla itself says cannot survive. */
final class OceanCanvasUnsupportedCrystalPolicy {
    private OceanCanvasUnsupportedCrystalPolicy() { }
    static boolean nativeUnsupportedWaterCrystal(BlockState state, LevelReader world, BlockPos pos) {
        return state.getBlock() instanceof AmethystClusterBlock && !state.hasBlockEntity()
                && state.getFluidState().getType() == Fluids.WATER && state.getFluidState().isSource()
                && !state.canSurvive(world,pos);
    }
    static boolean permits(boolean activePregen, boolean selectedColumn, boolean canvas,
            boolean physicalSeal, boolean inAuthoredWaterBand, boolean nativeUnsupported, boolean protectedCell) {
        return activePregen && selectedColumn && canvas && physicalSeal
                && inAuthoredWaterBand && nativeUnsupported && !protectedCell;
    }
}
