package net.oceancanvas.mod.server;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared canonical terrain contract for existing flattening and experimental
 * direct generation. Generation adapters must reuse the same target semantics
 * rather than silently inventing a second ocean format.
 *
 * This class is pure: no world mutation, chunk loads, lighting or saves.
 */
final class OceanTerrainTarget {
    private OceanTerrainTarget() {}

    static BlockState blockAt(int y, int floorY, int waterSurfaceY) {
        if (floorY > waterSurfaceY)
            throw new IllegalArgumentException("floor above water surface");
        if (y < floorY) return Blocks.STONE.defaultBlockState();
        if (y <= waterSurfaceY) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    static boolean matchesAuthored(BlockState actual, int y, int floorY, int waterSurfaceY) {
        BlockState target = blockAt(y, floorY, waterSurfaceY);
        if (target.is(Blocks.WATER))
            return actual.is(Blocks.WATER) && actual.getFluidState().isSource();
        return actual.is(target.getBlock());
    }

    static boolean matchesSettled(BlockState actual, int y, int floorY, int waterSurfaceY) {
        if (y == waterSurfaceY)
            return (actual.is(Blocks.WATER) && actual.getFluidState().isSource())
                    || actual.is(Blocks.ICE);
        if (y == waterSurfaceY + 1)
            return actual.isAir() || actual.is(Blocks.SNOW);
        return matchesAuthored(actual, y, floorY, waterSurfaceY);
    }
}
