package net.oceancanvas.mod.worldgen;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.AquaticPlacements;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.diagnostic.OceanCanvasPostPhysicalMutationDiagnostics;

import java.util.List;

/**
 * Restores the vanilla ocean vegetation pass after Ocean Canvas has finished
 * excavating a chunk.
 *
 * <p>Vanilla originally decorates the terrain before Ocean Canvas replaces the
 * surface with the authored seabed/water column, so the original vegetation is
 * necessarily removed with the original surface. Re-running an entire biome
 * decoration stage here would be unsafe because it would also duplicate ores,
 * disks, structures and unrelated vegetation. Instead this class invokes only
 * the exact vanilla aquatic {@link PlacedFeature}s belonging to the configured
 * ocean biome. Their own Count/Noise/Rarity/InSquare/Heightmap modifiers remain
 * authoritative, so Ocean Canvas does not invent independent kelp or seagrass
 * percentages.</p>
 *
 * <p>This runs at the flattener's authoritative commit boundary. A first pass
 * that actually places vegetation invalidates the just-completed lighting proof
 * and deliberately withholds durable commit. The deterministic retry sees the
 * already-authored vegetation, performs no further block mutation, and only then
 * may expose the durable commit. This keeps vegetation inside the same strict
 * physical/light proof contract instead of certifying blocks that changed after
 * the proof.</p>
 */
public final class OceanCanvasOceanVegetation {
    private OceanCanvasOceanVegetation() { }
    private static int diagnosticCalls;

    private static final long SALT_SEAGRASS_PRIMARY = 0x5EA6_0001L;
    private static final long SALT_KELP             = 0x4B45_4C50L;

    // Minecraft 26.2 ocean biome data uses primary seagrass and kelp only.

    public static boolean decorateCommittedChunk(ServerLevel world, ChunkPos pos) {
        if (world == null || pos == null || !Level.OVERWORLD.equals(world.dimension())) return true;

        OceanCanvasConfig config = OceanCanvasConfig.get();
        if (!config.biomeMaskEnabled()) return true;

        LevelChunk chunk = world.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) OCEAN-VEGETATION-DEFER chunk={},{} reason=target-not-resident", pos.x(), pos.z());
            return false;
        }

        int centerX = pos.getMinBlockX() + 8;
        int centerZ = pos.getMinBlockZ() + 8;
        if (!config.isInsideCanvas(centerX, centerZ)) return true;

        boolean biomeChanged = OceanCanvasBiomeMasker.applyRegionBiomes(world, chunk);
        biomeChanged |= OceanCanvasBiomeMasker.maskChunkIfEnabled(
                world, chunk, config.oceanFloorY(), config.oceanFloorVariation(), OceanCanvasConfig.WATER_SURFACE_Y);
        if (biomeChanged) {
            chunk.markUnsaved();
            var packet = ClientboundChunksBiomesPacket.forChunks(List.of(chunk));
            for (var player : PlayerLookup.tracking(world, pos)) player.connection.send(packet);
        }

        BlockPos origin = new BlockPos(pos.getMinBlockX(), world.getMinY(), pos.getMinBlockZ());
        String targetBiome = config.biomeMaskBiome();

        var before = Boolean.getBoolean("oceancanvas.vegetationMutationDiagnostic") && diagnosticCalls++ < 64
                ? diagnosticSnapshot(world, pos) : null;
        boolean[] blockMutation = new boolean[1];
        WorldGenLevel placementWorld = mutationAwarePlacementWorld(world, pos, blockMutation);
        try {
            if ("minecraft:deep_ocean".equals(targetBiome)) {
                place(world, placementWorld, origin, pos, AquaticPlacements.SEAGRASS_DEEP, SALT_SEAGRASS_PRIMARY);
                place(world, placementWorld, origin, pos, AquaticPlacements.KELP_COLD, SALT_KELP);
            } else if ("minecraft:ocean".equals(targetBiome)) {
                place(world, placementWorld, origin, pos, AquaticPlacements.SEAGRASS_NORMAL, SALT_SEAGRASS_PRIMARY);
                place(world, placementWorld, origin, pos, AquaticPlacements.KELP_COLD, SALT_KELP);
            } else {
                return true;
            }
            chunk.markUnsaved();
            if (before != null) {
                int emitted = 0;
                for (var entry : before.entrySet()) {
                    var at = entry.getKey();
                    var resident = world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                    if (resident == null) continue;
                    var after = resident.getBlockState(at);
                    if (!after.equals(entry.getValue()) && emitted++ < 8)
                        OceanCanvas.LOGGER.info("(Ocean Canvas) VEGETATION-MUTATION-TRACE sourceChunk={},{} targetChunk={},{} pos={} before={} after={}", pos.x(), pos.z(), at.getX() >> 4, at.getZ() >> 4, at, entry.getValue(), after);
                }
            }
            if (blockMutation[0]) {
                OceanCanvas.LOGGER.debug("(Ocean Canvas) OCEAN-VEGETATION-DEFER chunk={},{} reason=placement-invalidated-light-proof action=retry-after-authoritative-reproof",
                        pos.x(), pos.z());
            }
            return commitAllowedAfterDecoration(blockMutation[0]);
        } catch (RuntimeException ex) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) OCEAN-VEGETATION-FAILED chunk={},{} biome={} action=withhold-authoritative-commit-and-retry",
                    pos.x(), pos.z(), targetBiome, ex);
            return false;
        }
    }

    static boolean commitAllowedAfterDecoration(boolean blockMutation) {
        return !blockMutation;
    }

    /**
     * Preserve every vanilla placement decision/write, but register every real
     * aquatic block mutation before it changes the current lighting proof epoch.
     * This includes the owner chunk as well as adjacent chunks: the owner was
     * already lighting-certified immediately before this decoration callback, so
     * an owner write is just as capable of invalidating that proof as a cross-chunk
     * seagrass displacement.
     */
    private static WorldGenLevel mutationAwarePlacementWorld(ServerLevel world, ChunkPos owner, boolean[] blockMutation) {
        var notified = new java.util.HashSet<Long>();
        return (WorldGenLevel) java.lang.reflect.Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class },
                (proxy, method, arguments) -> {
                    boolean aquaticSetBlock = "setBlock".equals(method.getName());
                    if (aquaticSetBlock && arguments != null
                            && arguments.length >= 2 && arguments[0] instanceof BlockPos at
                            && arguments[1] instanceof net.minecraft.world.level.block.state.BlockState state
                            && !world.getBlockState(at).equals(state)) {
                        blockMutation[0] = true;
                        ChunkPos target = ChunkPos.containing(at);
                        if (notified.add(ChunkPos.pack(target.x(), target.z()))) {
                            OceanCanvasSurfaceFlattener.prepareForAquaticDecorationMutation(world, target);
                        }
                    }
                    if (aquaticSetBlock) OceanCanvasPostPhysicalMutationDiagnostics.beginAuthorizedAquaticMutation();
                    try {
                        return method.invoke(world, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    } finally {
                        if (aquaticSetBlock) OceanCanvasPostPhysicalMutationDiagnostics.endAuthorizedAquaticMutation();
                    }
                });
    }

    private static void place(ServerLevel world, WorldGenLevel placementWorld, BlockPos origin, ChunkPos chunkPos,
                              ResourceKey<PlacedFeature> key, long salt) {
        Holder.Reference<PlacedFeature> holder = world.registryAccess()
                .lookupOrThrow(Registries.PLACED_FEATURE)
                .getOrThrow(key);
        long seed = mix64(world.getSeed() ^ ChunkPos.pack(chunkPos.x(), chunkPos.z()) ^ salt);
        RandomSource random = RandomSource.create(seed);
        holder.value().placeWithBiomeCheck(placementWorld, world.getChunkSource().getGenerator(), random, origin);
    }

    private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> diagnosticSnapshot(ServerLevel world, ChunkPos source) {
        var result = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var config = OceanCanvasConfig.get();
        int floor = config.oceanFloorY(), variation = config.oceanFloorVariation();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var resident = world.getChunkSource().getChunkNow(source.x() + dx, source.z() + dz);
            if (resident == null) continue;
            for (int lx = 0; lx < 16; lx += 2) for (int lz = 0; lz < 16; lz += 2) {
                int x = resident.getPos().getMinBlockX() + lx, z = resident.getPos().getMinBlockZ() + lz;
                for (int y = floor - variation; y <= floor + variation + 2; y++) {
                    var at = new BlockPos(x, y, z);
                    result.put(at, resident.getBlockState(at));
                }
            }
        }
        return result;
    }

    private static long mix64(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
