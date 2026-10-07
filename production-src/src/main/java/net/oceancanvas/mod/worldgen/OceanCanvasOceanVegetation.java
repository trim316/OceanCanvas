package net.oceancanvas.mod.worldgen;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.AquaticPlacements;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;

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
 * <p>This runs only at the flattener's authoritative commit boundary: after
 * physical-profile and lighting proof. That ordering is intentional. The strict
 * physical audit defines the freshly excavated water column before decoration;
 * placing kelp/seagrass earlier would make correct vanilla vegetation look like
 * an audit failure and get removed again.</p>
 */
public final class OceanCanvasOceanVegetation {
    private OceanCanvasOceanVegetation() { }

    private static final long SALT_SEAGRASS_PRIMARY = 0x5EA6_0001L;
    private static final long SALT_SEAGRASS_SIMPLE  = 0x5EA6_0002L;
    private static final long SALT_KELP             = 0x4B45_4C50L;

    /*
     * Minecraft 26.2 still registers minecraft:seagrass_simple, but the
     * convenience constant was removed from AquaticPlacements. Keep the registry
     * id authoritative instead of replacing it with a custom feature/rate.
     */
    private static final ResourceKey<PlacedFeature> SEAGRASS_SIMPLE = ResourceKey.create(
            Registries.PLACED_FEATURE,
            Identifier.fromNamespaceAndPath("minecraft", "seagrass_simple"));

    /**
     * Ensures the current committed Canvas chunk has its vanilla ocean biome
     * palette and then runs only that biome's normal aquatic vegetation placed
     * features. Returns false only when the chunk is unexpectedly unavailable or
     * feature placement throws; callers should withhold the durable job commit in
     * that case so recovery can retry rather than silently accepting a barren
     * chunk.
     */
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

        // Repair/apply the same post-generation palette used by the flattener
        // before asking BiomeFilter to evaluate the vanilla aquatic features.
        boolean biomeChanged = OceanCanvasBiomeMasker.applyRegionBiomes(world, chunk);
        biomeChanged |= OceanCanvasBiomeMasker.maskChunkIfEnabled(
                world, chunk, config.oceanFloorY(), config.oceanFloorVariation(), OceanCanvasConfig.WATER_SURFACE_Y);
        if (biomeChanged) {
            chunk.markUnsaved();
            var packet = ClientboundChunksBiomesPacket.forChunks(List.of(chunk));
            for (var player : PlayerLookup.tracking(world, pos)) player.connection.send(packet);
        }

        // Vanilla PlacedFeatures are chunk-origin based. Using the dimension
        // minimum Y matches ChunkGenerator.applyBiomeDecoration's section origin;
        // the aquatic heightmap modifiers choose the actual seabed position.
        BlockPos origin = new BlockPos(pos.getMinBlockX(), world.getMinY(), pos.getMinBlockZ());
        String targetBiome = config.biomeMaskBiome();

        try {
            if ("minecraft:deep_ocean".equals(targetBiome)) {
                place(world, origin, pos, AquaticPlacements.SEAGRASS_DEEP, SALT_SEAGRASS_PRIMARY);
                place(world, origin, pos, SEAGRASS_SIMPLE, SALT_SEAGRASS_SIMPLE);
                place(world, origin, pos, AquaticPlacements.KELP_COLD, SALT_KELP);
            } else if ("minecraft:ocean".equals(targetBiome)) {
                // Exact normal-ocean vegetation set from vanilla's biome data:
                // seagrass_normal + seagrass_simple + kelp_cold.
                place(world, origin, pos, AquaticPlacements.SEAGRASS_NORMAL, SALT_SEAGRASS_PRIMARY);
                place(world, origin, pos, SEAGRASS_SIMPLE, SALT_SEAGRASS_SIMPLE);
                place(world, origin, pos, AquaticPlacements.KELP_COLD, SALT_KELP);
            } else {
                // Do not guess a vegetation recipe for custom/datapack biome ids.
                // The biome conversion still applies; unsupported vegetation
                // recipes are deliberately a no-op rather than corrupting them.
                return true;
            }
            chunk.markUnsaved();
            return true;
        } catch (RuntimeException ex) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) OCEAN-VEGETATION-FAILED chunk={},{} biome={} action=withhold-authoritative-commit-and-retry",
                    pos.x(), pos.z(), targetBiome, ex);
            return false;
        }
    }

    private static void place(ServerLevel world, BlockPos origin, ChunkPos chunkPos,
                              ResourceKey<PlacedFeature> key, long salt) {
        Holder.Reference<PlacedFeature> holder = world.registryAccess()
                .lookupOrThrow(Registries.PLACED_FEATURE)
                .getOrThrow(key);

        // Stable per-world/per-chunk/per-feature seed. The PlacedFeature itself
        // still owns vanilla CountPlacement / NoiseBasedCountPlacement / rarity,
        // in-square distribution, heightmap selection and biome filtering. A
        // deterministic seed also makes a recovery retry target the same sites
        // instead of increasing density with each retry.
        long seed = mix64(world.getSeed() ^ ChunkPos.pack(chunkPos.x(), chunkPos.z()) ^ salt);
        RandomSource random = RandomSource.create(seed);
        holder.value().placeWithBiomeCheck(world, world.getChunkSource().getGenerator(), random, origin);
    }

    private static long mix64(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
