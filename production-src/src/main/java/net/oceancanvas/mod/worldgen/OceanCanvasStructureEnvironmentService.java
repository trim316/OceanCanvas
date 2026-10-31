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
            // Re-setting an identical state only notifies neighbors; it does not recompute
            // this block's own fence/stair connections after the surrounding carve.
            world.setBlock(pos, neighborValidatedState(state, world, pos), Block.UPDATE_ALL);
        }
    }

    static BlockState neighborValidatedState(BlockState state,
            net.minecraft.world.level.LevelAccessor world, BlockPos pos) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.StairBlock)) return state;
        return Block.updateFromNeighbourShapes(state, world, pos);
    }

    static BoundingBox changedContextIntersection(BoundingBox structure, ChunkPos changed) {
        int minX = Math.max(structure.minX(), changed.getMinBlockX() - 1);
        int maxX = Math.min(structure.maxX(), changed.getMaxBlockX() + 1);
        int minZ = Math.max(structure.minZ(), changed.getMinBlockZ() - 1);
        int maxZ = Math.min(structure.maxZ(), changed.getMaxBlockZ() + 1);
        return minX > maxX || minZ > maxZ ? null
                : new BoundingBox(minX, structure.minY(), minZ, maxX, structure.maxY(), maxZ);
    }

    static boolean contextMutationPermitted(boolean ownsWholeFootprint, boolean playerProtected, boolean customTerrain) {
        return ownsWholeFootprint && !playerProtected && !customTerrain;
    }

    /**
     * A later raw carve may remove the support a previously revalidated fence connected to.
     * Repair only neighbor-derived geometry in discovered shipwreck pieces adjacent to THIS
     * completed carve. This runs after canonical repair, not on every light-finalizer visit.
     * Future chunks do not block admission: their own completed carve repeats this boundary.
     */
    public static boolean finishChangedChunkContext(ServerLevel world, ChunkPos changed,
            Iterable<BoundingBox> managedShipwreckBounds, LightRearm lightRearm) {
        var zones = OceanCanvasPlayerZones.get(world);
        var terrain = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world);
        java.util.Set<BoundingBox> unique = new java.util.HashSet<>();
        managedShipwreckBounds.forEach(unique::add);
        for (BoundingBox structure : unique) {
            if (!OceanCanvasActiveTerrainOperationBridge.coversStructureFootprint(structure)) continue;
            BoundingBox part = changedContextIntersection(structure, changed);
            if (part == null) continue;
            boolean mutated = false;
            for (BlockPos pos : BlockPos.betweenClosed(part.minX(), part.minY(), part.minZ(),
                    part.maxX(), part.maxY(), part.maxZ())) {
                if (OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
                var cp = ChunkPos.containing(pos);
                if (!contextMutationPermitted(true, zones.isProtected(pos.getX(), pos.getY(), pos.getZ()),
                        terrain.get(cp) == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED)) continue;
                // Existing carve admission guarantees loaded neighbors; nevertheless never
                // turn this repair into a synchronous chunk load when a neighbor unloads.
                var chunk = world.getChunkSource().getChunkNow(cp.x(), cp.z());
                if (chunk == null) return false;
                BlockState state = chunk.getBlockState(pos);
                if (!(state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)
                        && !(state.getBlock() instanceof net.minecraft.world.level.block.StairBlock)) continue;
                for (var direction : net.minecraft.core.Direction.values()) {
                    var neighbor = pos.relative(direction);
                    if (world.getChunkSource().getChunkNow(neighbor.getX() >> 4, neighbor.getZ() >> 4) == null) return false;
                }
                BlockState updated = neighborValidatedState(state, world, pos);
                if (updated == state || updated.getBlock() != state.getBlock()) continue;
                OceanCanvasUndoRecorderBridge.record(world, pos, state);
                if (!world.setBlock(pos, updated, Block.UPDATE_ALL)) return false;
                if (!mutated) {
                    mutated = true;
                    // Rearm immediately even if shutdown/unload preempts a later cell.
                    lightRearm.rearm(world, structure);
                }
            }
        }
        return true;
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
