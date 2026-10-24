package net.oceancanvas.mod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator;

/**
 * Post-placement structure environment repair.
 *
 * <p>Structure placement can leave WATERLOGGED and neighbor-shape-derived state reflecting the
 * old placement environment/order. This service owns that bounded repair and the persisted
 * revalidation queue. It does not own structure placement or lighting invalidation.</p>
 */
public final class OceanCanvasStructureEnvironmentService {
    private OceanCanvasStructureEnvironmentService() {}

    @FunctionalInterface
    public interface LightRearm {
        void rearm(ServerLevel world, BoundingBox bounds);
    }

    /**
     * Recompute waterlogging from actual adjacent water, then re-submit non-container blocks to
     * vanilla neighbor-shape updates. Containers are deliberately never touched.
     */
    public static void revalidatePlacedEnvironment(ServerLevel world, BlockPos min, BlockPos max) {
        var waterlogged = BlockStateProperties.WATERLOGGED;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = world.getBlockState(pos);
            if (!state.hasProperty(waterlogged)) continue;
            boolean touchingWater = false;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                if (world.getFluidState(pos.relative(dir)).is(net.minecraft.tags.FluidTags.WATER)) {
                    touchingWater = true;
                    break;
                }
            }
            if (state.getValue(waterlogged) != touchingWater) {
                world.setBlock(pos, state.setValue(waterlogged, touchingWater), Block.UPDATE_ALL);
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = world.getBlockState(pos);
            if (world.getBlockEntity(pos) instanceof Container) continue;
            world.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }

    /** Repair structures relocated during the current flatten pass, then re-arm their light ring. */
    public static boolean finishRelocatedThisPass(ServerLevel world,
            Iterable<BoundingBox> relocatedBounds, LightRearm lightRearm) {
        for (BoundingBox bounds : relocatedBounds) {
            if (OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
            revalidatePlacedEnvironment(world,
                    new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                    new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            lightRearm.rearm(world, bounds);
        }
        return true;
    }

    /**
     * Consume persisted structure revalidation work only from the chunk that owns the bounds'
     * minimum corner, preserving the old exactly-once distribution across the flatten queue.
     */
    public static boolean drainPersistedForChunk(ServerLevel world, ChunkPos chunkPos, LightRearm lightRearm) {
        OceanCanvasProtectedData data = OceanCanvasProtectedData.get(world);
        for (java.util.Map.Entry<BlockPos, BoundingBox> entry : data.getPendingRevalidations().entrySet()) {
            if (OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
            BoundingBox bounds = entry.getValue();
            ChunkPos boundsChunk = ChunkPos.containing(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()));
            if (boundsChunk.x() != chunkPos.x() || boundsChunk.z() != chunkPos.z()) continue;
            revalidatePlacedEnvironment(world,
                    new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                    new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            lightRearm.rearm(world, bounds);
            data.clearPendingRevalidation(entry.getKey());
        }
        return true;
    }
}
