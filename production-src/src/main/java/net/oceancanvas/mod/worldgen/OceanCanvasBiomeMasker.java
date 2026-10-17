package net.oceancanvas.mod.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;

/**
 * Applies Ocean Canvas biome-palette rules after vanilla terrain generation.
 *
 * <p>The current 26.2 baseline enables the global ocean mask by default (and
 * migrates older experimental false values) so the excavated water column uses
 * the configured ocean biome while underground biome data remains untouched.
 * Region biome rules are a separate, deliberate override and may paint full
 * columns, including regions outside the global canvas.</p>
 *
 * <p>This is intentionally a post-generation palette edit rather than a fixed
 * world biome source. Changing the generator's biome source previously interfered
 * with vanilla spawn search; editing already-generated chunk palettes does not
 * alter that generation-time decision path.</p>
 *
 * <p>v253.46 makes biome edits first-class terrain changes: the write helpers
 * report whether a quart cell actually changed, the flattener persists biome-only
 * edits, and the normal authoritative chunk publication path republishes them to
 * connected clients. The post-job visual gate also rechecks loaded target palettes
 * before completion. This matters because biome tint is cached separately from
 * block and light state, so a mathematically correct light field can still look
 * like a localized discolored water island when the palette/cache is stale.</p>
 *
 * <p>Biome data is stored at quart (4-block) resolution. The global mask touches
 * the excavated floor-to-water range plus one lookup-guard quart on BOTH visible
 * boundaries: below the authored floor and above the water surface. Vanilla's
 * fuzzy biome lookup can select a neighboring quart on either side; leaving the
 * lower neighbor vanilla can tint the visible seabed into broad pale islands even
 * when the block and skylight fields are mathematically correct; region rules intentionally paint the whole loaded
 * column. Writes use the mutable {@link PalettedContainer} backing
 * each {@link LevelChunkSection}; out-of-range vertical quart cells are ignored.</p>
 */
public final class OceanCanvasBiomeMasker {

	// Biome data is stored at 4-block ("quart") resolution in vanilla -
	// iterate whole cells, not every block, both because that's the
	// actual storage granularity and because it's 64x cheaper per chunk.
	private static final int QUART_SIZE = 4;

	/**
	 * One quart-cell of biome guard above the visible water surface.
	 *
	 * <p>Minecraft's biome lookup is not a direct "read the quart containing this
	 * block" operation. The fuzzy biome sampler can choose from the eight
	 * neighboring quart cells after its coordinate offset. At sea level that set
	 * includes the quart immediately ABOVE the water. v253.46 masked through the
	 * water quart itself but left that upper neighbor vanilla. In a place whose
	 * original upper biome had a different water color (the remaining runtime
	 * example reported {@code minecraft:lukewarm_ocean}), the fuzzy lookup could
	 * intermittently select that cell and render isolated cyan water islands even
	 * though lighting, the water quart, and client tint-cache invalidation were all
	 * correct. One guard quart closes the complete lookup neighborhood without
	 * repainting the underground biome column.
	 */
	private static final int SURFACE_LOOKUP_GUARD_QUARTS = 1;

	/**
	 * v253.72.4: one quart below the authored floor for the same fuzzy-biome
	 * reason as the surface guard. The seabed itself sits at a quart boundary
	 * often enough that the sampler can legally choose the vanilla quart below it;
	 * Sodium/Iris then blend that different water/foliage tint across many blocks
	 * and it looks like a lighting blob. This deliberately repaints only one
	 * four-block lookup guard, not the underground biome column.
	 */
	private static final int FLOOR_LOOKUP_GUARD_QUARTS = 1;

	private static final java.util.concurrent.atomic.AtomicLong SURFACE_GUARD_REPAIRED_CELLS =
			new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong FLOOR_GUARD_REPAIRED_CELLS =
			new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Biome id to registry holder, including negative entries for ids that
	 * do not resolve - see {@link #resolveBiome}. A plain HashMap rather
	 * than anything concurrent: every write happens on the server thread
	 * from the chunk-carve path, the same single-threaded assumption the
	 * rest of this package already makes.
	 */
	private static final java.util.Map<String, Holder<Biome>> RESOLVED_BIOMES = new java.util.HashMap<>();

	private OceanCanvasBiomeMasker() {
	}

	/**
	 * Paints the biome any REGION rule assigns to this chunk.
	 *
	 * <p><b>Separate from the global mask below, and called from a
	 * different place, because the two have genuinely different scopes.</b>
	 * The global mask only ever recolours the canvas it belongs to. A
	 * region rule is a deliberate instruction about one specific area the
	 * player drew, and there is nothing odd about drawing that area outside
	 * the canvas - marking out the biome of a bay just past the edge is a
	 * perfectly reasonable thing to want. This method is therefore invoked
	 * before the flattener's canvas-relevance early-out, which would
	 * otherwise have made every such rule silently do nothing at all.</p>
	 *
	 * <p><b>Paints the full column, not the carved band.</b> The global
	 * mask deliberately only touches the excavated range, because its whole
	 * job is recolouring water the canvas itself created. A region rule
	 * means "this area IS this biome", and biome is a column-wide property
	 * in vanilla - painting only from the ocean floor to sea level would
	 * leave anything above water reading as its old biome, which is
	 * visible, wrong, and would look like a bug rather than a limit.</p>
	 *
	 * <p>Cheap when unused: one {@code hasAnyBiomeOverride} check, which is
	 * false for essentially every world that has not asked for this.</p>
	 */
	public static boolean applyRegionBiomes(ServerLevel world, LevelChunk chunk) {
		OceanCanvasPlayerZones zones = OceanCanvasPlayerZones.get(world);
		if (!zones.hasAnyBiomeOverride()) {
			return false;
		}

		boolean changed = false;
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();

		for (int lx = 0; lx < 16; lx += QUART_SIZE) {
			for (int lz = 0; lz < 16; lz += QUART_SIZE) {
				int x = minX + lx;
				int z = minZ + lz;

				// Looked up at sea level, the one height a horizontal,
				// top-down rule can meaningfully be said to be "at". Every
				// region drawn on the map spans the full build range, so
				// this always hits; a region built from pos1/pos2 covering
				// only a deep slice will not repaint, which is correct -
				// biome is a whole-column property and pretending otherwise
				// would be a lie about what actually happened.
				String biomeId = zones.biomeOverrideAt(x, OceanCanvasConfig.WATER_SURFACE_Y, z);
				if (biomeId == null) {
					continue;
				}
				Holder<Biome> biome = resolveBiome(world, biomeId);
				if (biome == null) {
					continue; // reported once by resolveBiome, then cached as unresolvable
				}

				int quartX = x >> 2;
				int quartZ = z >> 2;
				int minQuartY = world.getMinY() >> 2;
				int maxQuartY = world.getMaxY() >> 2;
				for (int quartY = minQuartY; quartY <= maxQuartY; quartY++) {
					// setBiomeAt already ignores a quart outside this
					// chunk's loaded sections, so this range can be a plain
					// dimension-derived quart range so custom world heights stay correct.
					changed |= setBiomeAt(chunk, quartX, quartY, quartZ, biome);
				}
			}
		}
		return changed;
	}

	/**
	 * The original world-wide uniform-ocean mask, unchanged in behaviour:
	 * gated on {@code biomeMaskEnabled}, applied only inside the canvas,
	 * and only across the excavated range. Region rules are applied
	 * separately by {@link #applyRegionBiomes} and take precedence, since
	 * they are a deliberate local decision and this is a blanket default.
	 */
	public static boolean maskChunkIfEnabled(ServerLevel world, LevelChunk chunk, int baseFloorY, int floorVariation,
			int waterTop) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.biomeMaskEnabled()) {
			return false;
		}

		Holder<Biome> biome = resolveBiome(world, config.biomeMaskBiome());
		if (biome == null) {
			return false; // already logged once in resolveBiome - avoid spamming every chunk
		}

		OceanCanvasPlayerZones zones = OceanCanvasPlayerZones.get(world);
		boolean anyRegionRule = zones.hasAnyBiomeOverride();
		boolean changed = false;

		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();

		for (int lx = 0; lx < 16; lx += QUART_SIZE) {
			for (int lz = 0; lz < 16; lz += QUART_SIZE) {
				int x = minX + lx;
				int z = minZ + lz;
				// Biomes are stored in 4x4 quart cells. Require the entire cell to be
				// inside the exact operation mask so a radius edge can never recolor
				// blocks outside the user's selection. The narrow partial cell remains
				// unchanged rather than violating block-boundary safety.
				if (!OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(
						chunk.getPos().x(), chunk.getPos().z(), x, z)
						|| !OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(
						chunk.getPos().x(), chunk.getPos().z(), x + QUART_SIZE - 1, z + QUART_SIZE - 1)) continue;
				if (!config.isInsideCanvas(x, z)) {
					continue;
				}
				// A region rule already painted this cell - do not undo it.
				if (anyRegionRule
						&& zones.biomeOverrideAt(x, OceanCanvasConfig.WATER_SURFACE_Y, z) != null) {
					continue;
				}

				// Same deterministic per-column formula the carve loop
				// itself uses (OceanCanvasSurfaceFlattener#floorOffset is
				// package-visible for exactly this kind of reuse) -
				// approximated at this cell's anchor corner rather than
				// per-block, since the mask is purely cosmetic and the
				// underlying noise changes gradually (48-block grid) -
				// worst case the biome boundary is off by a few blocks
				// right at a cell edge, never visible as a hard seam.
				int floorY = baseFloorY + OceanCanvasSurfaceFlattener.floorOffset(x, z, floorVariation);

				int quartX = x >> 2;
				int quartZ = z >> 2;
				int floorQuartY = floorY >> 2;
				int quartYStart = floorQuartY - FLOOR_LOOKUP_GUARD_QUARTS;
				int waterQuartY = waterTop >> 2;
				int quartYEnd = waterQuartY + SURFACE_LOOKUP_GUARD_QUARTS;
				for (int quartY = quartYStart; quartY <= quartYEnd; quartY++) {
					boolean cellChanged = setBiomeAt(chunk, quartX, quartY, quartZ, biome);
					changed |= cellChanged;
					if (cellChanged && quartY > waterQuartY) {
						SURFACE_GUARD_REPAIRED_CELLS.incrementAndGet();
					}
					if (cellChanged && quartY < floorQuartY) {
						FLOOR_GUARD_REPAIRED_CELLS.incrementAndGet();
					}
				}
			}
		}
		return changed;
	}

	/**
	 * Writes one biome quart-cell, given GLOBAL quart coordinates (block
	 * position right-shifted by 2) - see the class doc's "least-confirmed
	 * API surface" note for why this indirection exists instead of a direct
	 * {@code chunk.setBiome(...)} call. {@code quartX}/{@code quartZ} are
	 * always numerically aligned to this chunk's own quart-local range by
	 * every call site in {@link #maskChunkIfEnabled} (each column's global
	 * quart coordinate, masked down to 0-3 below), so only {@code quartY}
	 * needs an actual section lookup - a chunk spans every loaded section
	 * vertically, unlike a fixed 0-3 horizontal range.
	 */
	private static boolean setBiomeAt(LevelChunk chunk, int quartX, int quartY, int quartZ, Holder<Biome> biome) {
		int blockY = quartY << 2;
		int sectionIndex = chunk.getSectionIndex(blockY);
		if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
			return false; // outside this chunk's loaded section range - nothing to write
		}
		LevelChunkSection section = chunk.getSection(sectionIndex);
		int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
		int localQuartX = quartX & 3;
		int localQuartY = quartY - sectionY * 4;
		int localQuartZ = quartZ & 3;

		// See the class doc's note on why this cast is safe despite
		// getBiomes()'s narrower declared PalettedContainerRO return type.
		@SuppressWarnings("unchecked")
		PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
		Holder<Biome> previous = biomes.get(localQuartX, localQuartY, localQuartZ);
		if (previous.equals(biome)) {
			return false;
		}
		biomes.set(localQuartX, localQuartY, localQuartZ, biome);
		return true;
	}

	/**
	 * Resolves a biome id to its registry holder, caching both successes
	 * and failures.
	 *
	 * <p><b>A map rather than the single slot this used to be.</b> There
	 * was exactly one biome id in the world when this was written - the
	 * global mask's. Region rules mean a single chunk can now legitimately
	 * involve several different ids, and a one-entry cache would have
	 * thrashed between them, re-resolving on nearly every quart-cell.</p>
	 *
	 * <p><b>Failures are cached too</b>, as a null value under a present
	 * key. Without that, a mistyped id in one region would re-resolve and
	 * re-log once per cell per chunk, forever - thousands of identical
	 * warnings a second, which is how a small configuration mistake turns
	 * into an unusable log. Cached this way it is reported once and then
	 * quietly skipped.</p>
	 */
	private static Holder<Biome> resolveBiome(ServerLevel world, String biomeId) {
		if (RESOLVED_BIOMES.containsKey(biomeId)) {
			return RESOLVED_BIOMES.get(biomeId);
		}

		int colon = biomeId.indexOf(':');
		if (colon < 0) {
			OceanCanvas.LOGGER.warn(
					"(Ocean Canvas) biome id '{}' isn't a valid namespaced id (expected e.g. "
							+ "'minecraft:ocean') - ignoring it until it's corrected.",
					biomeId);
			RESOLVED_BIOMES.put(biomeId, null);
			return null;
		}
		Identifier id = Identifier.fromNamespaceAndPath(biomeId.substring(0, colon), biomeId.substring(colon + 1));
		net.minecraft.resources.ResourceKey<Biome> key = net.minecraft.resources.ResourceKey.create(Registries.BIOME, id);

		// NOT registryOrThrow(...) - this exact codebase already confirmed
		// during the shipwreck-structure-type work that
		// RegistryAccess#registryOrThrow doesn't exist under that name on
		// this build (see OceanCanvasSurfaceFlattener's "only shipwrecks"
		// doc note). lookupOrThrow(ResourceKey) -> HolderLookup#get(ResourceKey)
		// is the modern Holder-based registry access path introduced
		// alongside the same Holder/Optional-centric API direction already
		// confirmed elsewhere in this project (see OceanCanvasProtectedData's
		// SavedData rewrite) - better-grounded than a fresh guess, but
		// still the single line to check first if this doesn't compile.
		java.util.Optional<Holder.Reference<Biome>> resolved =
				world.registryAccess().lookupOrThrow(Registries.BIOME).get(key);
		if (resolved.isEmpty()) {
			OceanCanvas.LOGGER.warn(
					"(Ocean Canvas) biome id '{}' isn't a known biome - ignoring it until it's corrected. "
							+ "(A datapack that was present when this was set may have been removed.)",
					biomeId);
			RESOLVED_BIOMES.put(biomeId, null);
			return null;
		}

		RESOLVED_BIOMES.put(biomeId, resolved.get());
		return resolved.get();
	}

	/**
	 * Drops the resolution cache. Called when a world unloads, because the
	 * holders in it belong to that world's registries - keeping them across
	 * a world change would hand out biome references from a registry that
	 * is no longer the live one.
	 */
	/** Number of above-water guard quart cells actually corrected this session. */
	public static long surfaceGuardRepairedCells() {
		return SURFACE_GUARD_REPAIRED_CELLS.get();
	}

	/** v253.72.4: number of below-floor fuzzy-lookup guard quart cells corrected. */
	public static long floorGuardRepairedCells() {
		return FLOOR_GUARD_REPAIRED_CELLS.get();
	}

	public static void clearCache() {
		RESOLVED_BIOMES.clear();
		SURFACE_GUARD_REPAIRED_CELLS.set(0L);
		FLOOR_GUARD_REPAIRED_CELLS.set(0L);
	}
}
