package net.oceancanvas.mod.gui;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Real top-down terrain colours for {@link OceanCanvasMapScreen}, sampled
 * from the client's own already-loaded chunks - the single change that
 * moves this screen from "a diagram of some rectangles" to "a map of your
 * actual world."
 *
 * <p><b>Uses vanilla's own {@link MapColor}, the exact same colour data a
 * real in-game map item paints with</b> - not a hand-picked palette
 * approximating what stone or sand "should" look like. That's deliberate
 * and it's most of why the result reads as native: a spruce forest, a
 * mesa, a deep ocean trench, and a player's own quartz build all come out
 * the same colours the player already recognises from a vanilla map on
 * their wall, including any colours added by future Minecraft versions or
 * by other mods' blocks, with no palette of ours to keep up to date.</p>
 *
 * <p><b>Vanilla's own two shading rules are reproduced, not invented:</b></p>
 * <ul>
 *   <li><b>North-slope relief.</b> A real map item shades each column
 *       against its northern neighbour, which is what gives vanilla maps
 *       their embossed, hand-drawn-contour look rather than reading as
 *       flat colour blobs. Same comparison here ({@code z - step}),
 *       expressed as a continuous multiplier rather than vanilla's four
 *       discrete brightness steps - at map-screen zoom levels a smooth
 *       ramp reads better than banding, and it costs nothing.</li>
 *   <li><b>Water depth.</b> Vanilla darkens water by how deep it is; on an
 *       ocean-canvas world that is not a detail, it is the whole picture -
 *       it's what makes the excavated canvas floor visibly, gradually
 *       deeper than the natural shelf outside it, and what makes the
 *       carved boundary legible at a glance without drawing a single
 *       outline.</li>
 * </ul>
 *
 * <p><b>Three real performance techniques, because a naive version of this
 * would hitch every frame.</b> These are the difference between a map that
 * feels like part of the game and one that stutters when you pan:</p>
 * <ol>
 *   <li><b>Zoom-adaptive sample step.</b> Samples are taken on a
 *       power-of-two world grid ({@link #stepForCellSize}) chosen so one
 *       sample covers roughly {@link OceanCanvasMapScreen#TERRAIN_CELL_PX}
 *       screen pixels. Zoomed out over a 20,000-block canvas that means
 *       one sample per chunk, not per block - the sample count stays
 *       roughly constant no matter how much world is on screen.</li>
 *   <li><b>Persistent cache, keyed per step.</b> A sampled column is
 *       remembered ({@link Long2IntOpenHashMap}, one map per step level),
 *       so panning re-uses everything already computed and only the newly
 *       exposed edge costs anything. Cleared wholesale on a slow timer
 *       (see {@link #tickAndMaybeExpire}) so the map still visibly fills
 *       in as the flattener converts land to canvas underneath you.</li>
 *   <li><b>Per-frame sample budget.</b> At most
 *       {@link #MAX_NEW_SAMPLES_PER_FRAME} previously-unseen columns are
 *       computed in any one frame; the rest stay {@link #UNKNOWN} and get
 *       picked up on following frames. Opening the screen on a fresh view
 *       therefore fills in over a few frames instead of dropping one long
 *       frame - which, as a bonus, reads as a deliberate "map developing"
 *       effect rather than as a stall.</li>
 * </ol>
 *
 * <p><b>Only ever reads chunks the client already has</b> - it cannot and
 * does not request or generate anything, so this can never do to the
 * client what an unbounded force-load once did to the server (see
 * {@code OceanCanvasSurfaceFlattener#neighborsReady}'s doc for that real
 * incident). Anything outside the client's render distance simply reads as
 * unmapped, which is honest: the client genuinely does not know what is
 * there, and the screen draws it as unexplored rather than inventing it.</p>
 *
 * <p><b>Risk profile, stated as plainly as everywhere else in this
 * project:</b> {@code BlockState#getMapColor(BlockGetter, BlockPos)} and
 * {@link MapColor}'s {@code col} field are long-standing vanilla API -
 * map items have worked this way for many years - and {@code
 * ChunkAccess#getHeight(Heightmap.Types, int, int)} plus {@code
 * LevelReader#hasChunk(int, int)} are both already confirmed working in
 * this project's own server-side flattener. {@code MapColor#col} is used
 * in preference to {@code calculateRGBColor(MapColor.Brightness)}
 * deliberately: one plain public field is a smaller symbol surface to be
 * wrong about than a method plus a nested enum, and doing the brightness
 * multiply here gives the continuous shading described above anyway. This
 * whole class is nonetheless isolated in one file on purpose - if it turns
 * out not to compile against this exact 26.2 build, deleting this file and
 * the single {@code drawTerrain} call in {@link OceanCanvasMapScreen}
 * restores a fully working schematic map with nothing else touched.</p>
 */
public final class OceanCanvasMapTerrain {

	// v253.78: map screens are ephemeral, but terrain-change packets are global. Keep
	// only weak references so the packet bridge can invalidate every currently-live
	// map without making a closed screen/session immortal.
	private static final java.util.concurrent.ConcurrentLinkedQueue<java.lang.ref.WeakReference<OceanCanvasMapTerrain>> ACTIVE =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** Returned for a column the client has no loaded chunk for. Fully transparent, so the screen's own base colour shows through. */
	public static final int UNKNOWN = 0;

	/**
	 * Cache marker for "asked, and the client genuinely has no chunk
	 * there." Distinct from {@link #UNKNOWN} (which also means "not
	 * sampled yet") so that a view containing large unloaded areas - the
	 * normal case the moment you zoom out past render distance - does not
	 * re-ask about the same empty columns every single frame and burn the
	 * whole sample budget discovering nothing. Never returned to callers;
	 * {@link #colorAt} translates it back to {@link #UNKNOWN}. The periodic
	 * whole-cache expiry is what lets a chunk that has since loaded get
	 * picked up.
	 */
	private static final int UNLOADED = -1;

	private static final int MAX_NEW_SAMPLES_PER_FRAME = 320;

	/**
	 * Largest world-space sampling step, and its base-2 logarithm. These
	 * two and the zoom range must be chosen together: if the step caps out
	 * before the zoom does, {@code step / blocksPerPixel} collapses, cells
	 * shrink to the screen's minimum, and the number of samples needed per
	 * frame grows without bound at exactly the moment the least detail is
	 * visible. A cap of {@value #MAX_STEP} blocks comfortably covers the
	 * furthest {@code OceanCanvasMapScreen} allows anyone to zoom out.
	 */
	private static final int MAX_STEP = 1_024;

	private static final int MAX_STEP_LEVEL = 10;

	/** Whole-cache expiry interval, in milliseconds - see the class doc's caching note. */
	private static final long CACHE_LIFETIME_MS = 30_000L; // retry horizon for unloaded samples only; known terrain is session-persistent

	/** Hard cap on remembered columns before the cache is dropped, so a long panning session can't grow it without bound. */
	private static final int CACHE_ENTRY_LIMIT = 250_000;

	/** How far below the surface to look for the floor under water, before giving up and treating it as very deep. */
	private static final int MAX_WATER_DEPTH_PROBE = 48;


	// One cache per power-of-two step level (1, 2, 4, 8, 16, 32), indexed
	// by log2(step). Separate maps rather than one map with the step
	// packed into the key: zooming changes step, and a per-step map means
	// zooming back to a previous level still has that level's samples
	// warm, instead of having thrown them away.
	private final Long2IntOpenHashMap[] cacheByStepLevel = new Long2IntOpenHashMap[MAX_STEP_LEVEL + 1];

	private long lastExpiryMs;
	private final java.util.Set<Long> authoritativeCanvasChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final java.util.concurrent.atomic.AtomicLong terrainRevision = new java.util.concurrent.atomic.AtomicLong();
	private int samplesThisFrame;
	private final OceanCanvasJourneyMapTiles journeyMapTiles = new OceanCanvasJourneyMapTiles();

	public OceanCanvasMapTerrain() {
		for (int i = 0; i < cacheByStepLevel.length; i++) {
			cacheByStepLevel[i] = new Long2IntOpenHashMap();
			cacheByStepLevel[i].defaultReturnValue(UNKNOWN);
		}
		ACTIVE.add(new java.lang.ref.WeakReference<>(this));
	}

	/** Invalidate all live map instances without retaining closed screens. */
	public static void invalidateActive(java.util.List<Long> chunks, String kind) {
		if (chunks == null || chunks.isEmpty()) return;
		for (var ref : ACTIVE) {
			OceanCanvasMapTerrain terrain = ref.get();
			if (terrain == null) ACTIVE.remove(ref);
			else terrain.invalidateChunks(chunks, kind);
		}
	}

	/**
	 * The world-space sampling step (a power of two, in blocks) that puts
	 * roughly one sample per {@code targetCellPx} screen pixels at the
	 * given zoom. Rounding UP to a power of two rather than using the
	 * exact ratio keeps sample positions stable across small zoom changes,
	 * so zooming doesn't continuously invalidate the cache.
	 */
	public static int stepForCellSize(double blocksPerPixel, int targetCellPx) {
		double wanted = blocksPerPixel * targetCellPx;
		int step = 1;
		while (step < wanted && step < MAX_STEP) {
			step <<= 1;
		}
		return step;
	}

	/**
	 * Call once per frame, before sampling. Resets the per-frame sample
	 * budget and drops the whole cache periodically so terrain that has
	 * changed underneath the player (the flattener converting land to
	 * canvas, a build going up) actually shows up rather than staying
	 * frozen at whatever it looked like when the screen was opened.
	 */
	public void tickAndMaybeExpire(long nowMs) {
		samplesThisFrame = 0;
		boolean tooBig = false;
		for (Long2IntOpenHashMap cache : cacheByStepLevel) {
			if (cache.size() > CACHE_ENTRY_LIMIT) {
				tooBig = true;
				break;
			}
		}
		if (tooBig) {
			lastExpiryMs = nowMs;
			for (Long2IntOpenHashMap cache : cacheByStepLevel) cache.clear();
		}
        // Known terrain is deliberately retained for the whole client session. This makes
        // exploration accumulate instead of collapsing back to a tiny live-chunk blip every
        // 30 seconds. TerrainChanged packets explicitly invalidate changed chunks below.
	}

	/**
	 * The shaded ARGB colour for one world column, or {@link #UNKNOWN} if
	 * the client has no chunk there or this frame's sample budget is spent.
	 * {@code blockX}/{@code blockZ} are expected to already be aligned to
	 * {@code step}.
	 */
	public int colorAt(net.minecraft.client.multiplayer.ClientLevel level, int blockX, int blockZ, int step) {
		int level2 = log2(step);
		Long2IntOpenHashMap cache = cacheByStepLevel[level2];
		long key = (((long) blockX) << 32) ^ (blockZ & 0xFFFFFFFFL);
        long centerChunk = net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(blockX + Math.max(0, step/2),16), Math.floorDiv(blockZ + Math.max(0, step/2),16));
        if (authoritativeCanvasChunks.contains(centerChunk)) return 0xFF1D4D73;

		int cached = cache.get(key);
		if (cached == UNLOADED) {
			return journeyMapTiles.colorAt(blockX, blockZ, step);
		}
		if (cached != UNKNOWN) {
			return cached;
		}
		if (samplesThisFrame >= MAX_NEW_SAMPLES_PER_FRAME) {
			return UNKNOWN; // budget spent - this column fills in on a later frame
		}

		// v250: sample the CENTER of the screen cell, not its north-west corner. At far
		// zoom a cell can span 512-1024 blocks; corner-only probing systematically lost
		// islands/coastlines and made known JourneyMap land read as empty ocean.
		int sampleX = blockX + Math.max(0, step / 2);
		int sampleZ = blockZ + Math.max(0, step / 2);
		int color;
		if (level != null && level.hasChunk(sampleX >> 4, sampleZ >> 4)) {
			color = sampleColumn(level, sampleX, sampleZ, step);
		} else {
			color = journeyMapTiles.colorAt(blockX, blockZ, step);
		}
		samplesThisFrame++;
		cache.put(key, color == UNKNOWN ? UNLOADED : color);
		return color;
	}

    public void invalidateChunks(java.util.List<Long> chunks, String kind) {
        if (chunks == null || chunks.isEmpty()) return;
        boolean changed = false;
        boolean canvas = "CANVAS_WRITE".equals(kind);
        boolean restore = "RESTORE_TO_VANILLA".equals(kind) || "CUSTOM_IMPORT".equals(kind);
        for (long packed : chunks) {
            if (canvas) changed |= authoritativeCanvasChunks.add(packed);
            else if (restore) changed |= authoritativeCanvasChunks.remove(packed);
            int cx=net.minecraft.world.level.ChunkPos.getX(packed), cz=net.minecraft.world.level.ChunkPos.getZ(packed);
            for (int level=0; level<cacheByStepLevel.length; level++) {
                int step=1<<level; int bx=Math.floorDiv(cx*16,step)*step, bz=Math.floorDiv(cz*16,step)*step;
                cacheByStepLevel[level].remove((((long)bx)<<32) ^ (bz & 0xffffffffL));
            }
        }
        if (changed || canvas || restore) terrainRevision.incrementAndGet();
    }

    boolean isAuthoritativeCanvas(double worldX, double worldZ) {
        int cx=Math.floorDiv((int)Math.floor(worldX),16), cz=Math.floorDiv((int)Math.floor(worldZ),16);
        return authoritativeCanvasChunks.contains(net.minecraft.world.level.ChunkPos.pack(cx,cz));
    }

    long rasterRevision() { return terrainRevision.get() * 1_000_003L + journeyMapTiles.revision(); }

    int journeyRasterColorAt(double worldX, double worldZ) { return journeyMapTiles.rasterColorAt(worldX, worldZ); }

    java.awt.image.BufferedImage journeyViewport(int width,int height,double viewX,double viewZ,double bpp) {
        return journeyMapTiles.renderViewport(width,height,viewX,viewZ,bpp);
    }

	/** Surface Y from an already-loaded client chunk, or Integer.MIN_VALUE when unknown. */
	public int surfaceYAt(net.minecraft.client.multiplayer.ClientLevel level, int blockX, int blockZ) {
		if (level == null || !level.hasChunk(blockX >> 4, blockZ >> 4)) return Integer.MIN_VALUE;
		return level.getChunk(blockX >> 4, blockZ >> 4)
				.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
	}

	private static int log2(int step) {
		int level = 0;
		int value = step;
		while (value > 1) {
			value >>= 1;
			level++;
		}
		return Math.min(level, MAX_STEP_LEVEL);
	}

	private int sampleColumn(net.minecraft.client.multiplayer.ClientLevel level, int blockX, int blockZ, int step) {
		if (!level.hasChunk(blockX >> 4, blockZ >> 4)) {
			return journeyMapTiles.colorAt(blockX, blockZ);
		}

		// ChunkAccess#getHeight and LevelReader#getHeight do NOT mean the
		// same thing, and the difference is exactly one block. The chunk
		// method returns the topmost OCCUPIED block (internally
		// getFirstAvailable() - 1); the level method returns the first
		// FREE Y above it, which is why it adds the 1 back. This is the
		// chunk method, so this value is already the block a player would
		// see looking straight down - subtracting one here would have
		// coloured every column by the block UNDERNEATH its surface:
		// grass as dirt, snow as stone, the top course of a build as
		// whatever it was laid on.
		int topY = level.getChunk(blockX >> 4, blockZ >> 4)
				.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
		int worldBottomY = level.getMinY();
		if (topY < worldBottomY) {
			return UNKNOWN;
		}

		BlockPos topPos = new BlockPos(blockX, topY, blockZ);
		BlockState topState = level.getBlockState(topPos);
		MapColor mapColor = topState.getMapColor(level, topPos);
		// col == 0 is vanilla's "no colour" entry (what air and a handful
		// of invisible blocks report). Checked by value rather than by
		// comparing against the MapColor.NONE constant - one fewer symbol
		// to be wrong about, identical result.
		if (mapColor == null || mapColor.col == 0) {
			return UNKNOWN;
		}

		double brightness;
		if (topState.getFluidState().isEmpty()) {
			// Solid ground: vanilla's north-neighbour relief shading. The
			// neighbour is sampled at the same step, so relief stays
			// readable at every zoom instead of vanishing when zoomed out.
			int northY = neighborSurfaceY(level, blockX, blockZ - step);
			brightness = reliefBrightness(topY, northY);
		} else {
			// Water: depth shading, exactly the property that makes the
			// excavated canvas floor read as visibly deeper than the
			// natural shelf around it. See the class doc.
			int floorY = topY;
			int probed = 0;
			while (floorY > worldBottomY && probed < MAX_WATER_DEPTH_PROBE) {
				BlockPos below = new BlockPos(blockX, floorY - 1, blockZ);
				if (level.getBlockState(below).getFluidState().isEmpty()) {
					break;
				}
				floorY--;
				probed++;
			}
			int depth = Math.max(0, topY - floorY);
			// 0 blocks deep -> full brightness, MAX probe deep -> quite
			// dark. A ramp on measured depth rather than on distance from
			// a sea-level constant, deliberately: this file must not read
			// OceanCanvasConfig at all (that is server state, and on a
			// dedicated server the client's copy is a different file), and
			// measured depth is the more honest input anyway.
			double t = Math.min(1.0, depth / (double) MAX_WATER_DEPTH_PROBE);
			brightness = 1.0 - 0.62 * t;
		}

		return shade(mapColor.col, brightness);
	}

	/**
	 * Surface height of a neighbouring column for relief shading, or the
	 * sentinel {@link Integer#MIN_VALUE} when that neighbour isn't loaded -
	 * treated as "same height" by {@link #reliefBrightness} so an unloaded
	 * neighbour produces flat, unshaded terrain rather than a hard fake
	 * ridge along the edge of the client's render distance.
	 */
	private static int neighborSurfaceY(net.minecraft.client.multiplayer.ClientLevel level, int blockX, int blockZ) {
		if (!level.hasChunk(blockX >> 4, blockZ >> 4)) {
			return Integer.MIN_VALUE;
		}
		// Same ChunkAccess (topmost occupied) convention as sampleColumn -
		// kept identical on purpose so the relief comparison is like for
		// like. A stray offset on only one side of that subtraction would
		// silently bias the shading across the whole map.
		return level.getChunk(blockX >> 4, blockZ >> 4)
				.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
	}

	private static double reliefBrightness(int hereY, int northY) {
		if (northY == Integer.MIN_VALUE) {
			return 1.0;
		}
		int delta = hereY - northY;
		if (delta == 0) {
			return 1.0;
		}
		// Continuous ramp rather than vanilla's four discrete steps - see
		// the class doc. Clamped so a cliff face doesn't blow out to pure
		// white or crush to pure black.
		double ramp = Math.max(-1.0, Math.min(1.0, delta / 8.0));
		return 1.0 + ramp * 0.28;
	}

	/** Multiplies an opaque RGB by a brightness factor, returning opaque ARGB. */
	private static int shade(int rgb, double brightness) {
		int r = clampChannel(((rgb >> 16) & 0xFF) * brightness);
		int g = clampChannel(((rgb >> 8) & 0xFF) * brightness);
		int b = clampChannel((rgb & 0xFF) * brightness);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static int clampChannel(double value) {
		return (int) Math.max(0, Math.min(255, Math.round(value)));
	}
}
