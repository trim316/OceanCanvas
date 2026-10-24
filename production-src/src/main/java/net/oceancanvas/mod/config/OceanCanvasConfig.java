package net.oceancanvas.mod.config;

import net.fabricmc.loader.api.FabricLoader;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.StructureOverride;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * The region settings model for a canvas.
 *
 * <p>This is intentionally tiny for the M0 bootstrap: a fixed, single
 * rectangular canvas centered on a point, plus a flag for whether it is
 * allowed to grow later. Nothing here touches world generation yet -
 * {@link net.oceancanvas.mod.worldgen.OceanCanvasBiomeSource} and the
 * bundled noise settings datapack own that.</p>
 *
 * <p>Loaded from {@code config/oceancanvas.properties} in the run
 * directory. If the file doesn't exist yet, sensible defaults matching
 * the project spec (20,000 x 20,000, centered on the origin) are used
 * and then written out so the file is self-documenting.</p>
 *
 * <p><b>Now mutable at runtime, not just at first load.</b> Added to
 * support the in-game config/Mod Menu screen (see the
 * {@code net.oceancanvas.mod.integration} package) and, longer term, any
 * in-game command that wants to change a setting without a restart. Use
 * {@link #toBuilder()} to get a {@link Builder} pre-filled with the
 * current values, change what you need, and call {@link Builder#save()}
 * to persist it and atomically swap the live singleton. Everything that
 * previously called {@link #get()} keeps working exactly the same way -
 * this is purely additive.</p>
 */
public final class OceanCanvasConfig {

	private static final String FILE_NAME = "oceancanvas.properties";

	private static final int DEFAULT_CANVAS_SIZE = 20_000;
	private static final int DEFAULT_CENTER_X = 0;
	private static final int DEFAULT_CENTER_Z = 0;
	private static final boolean DEFAULT_EXPANSION_ENABLED = true;

	/**
	 * The single, fixed water-surface height (top water block) for the
	 * whole canvas - deliberately a hardcoded constant, not derived from
	 * {@code world.getSeaLevel()}. That dynamic value produced two
	 * different, contradictory bugs in a row (water one block too low,
	 * then a confusing mismatch against natural water elsewhere causing
	 * the player to drown unexpectedly) - two independent code paths
	 * (the flattener and the spawn-point Mixin) were each computing
	 * their own version of "sea level" from it and landing on different
	 * answers. Standard vanilla Minecraft sea level is y=62 (the water
	 * surface, i.e. water fills up to and including this block, with
	 * air starting at 63) - use this constant everywhere a "where's the
	 * ocean surface" answer is needed instead of re-deriving it.
	 */
	public static final int WATER_SURFACE_Y = 62;

	/**
	 * How deep the canvas's ocean floor should be excavated to, at
	 * minimum, wherever natural terrain is shallower than this. Real
	 * vanilla terrain that's already deeper than this (an actual ocean
	 * trench, a natural cave system) is left completely untouched -
	 * this only ever carves DOWN to reach the target, never removes
	 * anything already below it. Default matches the user's stated
	 * starting point (y=25) for a noticeably roomier canvas than
	 * standard vanilla ocean depth, while remaining fully configurable
	 * the same way canvas size is.
	 */
	private static final int DEFAULT_OCEAN_FLOOR_Y = 25;

	/**
	 * How much the excavated floor's height is allowed to vary up/down
	 * from {@link #oceanFloorY} (e.g. 5 means roughly -5 to +5), giving
	 * it subtle high/low points instead of looking like a flat
	 * swimming-pool bottom. Only affects columns that actually get
	 * excavated - real terrain already deeper is still never touched
	 * regardless of this value.
	 *
	 * <p>Briefly lowered to 2 after feedback that a flat/broad-wavelength
	 * version looked like "rolling hills" - that turned out to be about
	 * the shape of the variation (see
	 * {@code OceanCanvasSurfaceFlattener#floorOffset}'s doc comment for
	 * the full story), not the amplitude. Restored to the user's
	 * originally-stated "+/- 5 blocks" once the actual shape was fixed
	 * with proper value noise instead.</p>
	 */
	private static final int DEFAULT_OCEAN_FLOOR_VARIATION = 5;

	/**
	 * How thick the guaranteed-solid stone buffer directly beneath the
	 * excavated ocean floor is. This is the actual fix for the
	 * "cutting into caves and leaving weird artifacts" problem: rather
	 * than trying to detect and preserve cave shapes near the boundary,
	 * this guarantees a solid, sealed layer of stone for this many
	 * blocks below the floor - any cave void that would otherwise have
	 * been exposed right at the boundary gets filled in instead. Real
	 * caves/underground only ever start below this buffer, completely
	 * undisturbed.
	 */
	private static final int DEFAULT_OCEAN_FLOOR_TRANSITION_THICKNESS = 4;

	/**
	 * On by default as of the user's explicit request to actually start
	 * using pregeneration for real: "when I run an expansion, the
	 * default is to start running a chunk pregen for that exact size" -
	 * see {@code PregenManager#expand}, which already gated its
	 * proactive ring-flattening behind this exact flag from the start.
	 * Was off by default before this (drafted/untested-feature caution,
	 * matching every other feature in this project's early rounds) -
	 * flipped now that the underlying flattener itself has been
	 * confirmed working in real gameplay across many rounds of fixes.
	 * Still fully toggleable, same as before (in-game config screen or
	 * hand-editing oceancanvas.properties).
	 */
	private static final boolean DEFAULT_PREGEN_ENABLED = true;

	/**
	 * How many chunks {@code /oceancanvas pregen} is allowed to
	 * force-load and flatten per server tick while a pregen job is
	 * running. Deliberately conservative and configurable, not
	 * unbounded - the project already has a real, confirmed history of
	 * an unbounded chunk force-load cascade overloading the server (see
	 * {@code OceanCanvasSurfaceFlattener#neighborsReady}'s doc comment
	 * for the "~900 ticks behind" incident), and pregen is the one
	 * feature whose entire job is to force-load chunks nobody is
	 * standing near - the exact situation that already went wrong once.
	 */
	private static final int DEFAULT_PREGEN_CHUNKS_PER_TICK = 4;

	/** Target wall-clock window for a full managed-canvas Pregen. Used for SLO diagnostics only. */
	private static final int DEFAULT_FOREVER_WORLD_TARGET_HOURS = 8;

	/**
	 * On by default. Ocean Canvas uses the configured ocean biome mask so the
	 * blank canvas keeps vanilla ocean spawning/colour/weather semantics even
	 * though its surface terrain is authored by Ocean Canvas. Existing configs
	 * created while this feature was experimental are migrated once below; after
	 * that marker is written, an explicit player choice to disable the mask is
	 * respected.
	 */
	private static final boolean DEFAULT_BIOME_MASK_ENABLED = true;

	/** Default biome id used for the uniform-ocean-color mask, if enabled. */
	private static final String DEFAULT_BIOME_MASK_BIOME = "minecraft:ocean";

	/**
	 * Off by default - see {@link net.oceancanvas.mod.worldgen.ModStarterStructures}'s
	 * class doc for the "generalized this round" note this field exists to
	 * exercise. The always-on guaranteed shipwreck is untouched by this
	 * flag; this only controls the second, new example structure (ocean
	 * ruin) added to prove the generalized mechanism actually works for
	 * more than one structure type, not just in theory. Same
	 * default-off-until-tested posture as pregen/biome mask.
	 */
	private static final boolean DEFAULT_GUARANTEE_SPAWN_OCEAN_RUIN = false;

	/**
	 * Off by default - see {@code net.oceancanvas.mod.hud.OceanCanvasBoundaryHud}.
	 * This one is a data-only flag on purpose: the HUD class itself is
	 * excluded from the default client build (see build.gradle) because,
	 * unlike everything else drafted this round, it's the project's
	 * first-ever use of any client-side rendering API - zero prior
	 * in-game-tested code in this project to lean on for whether the
	 * exact hook/class names guessed at are still correct for this 26.2
	 * build. This field stays defined and wired through config either
	 * way, so turning the feature on later (once the render call is
	 * confirmed and the exclude line is removed) is just flipping this
	 * to true - no separate config plumbing to write at that point.
	 */
	private static final boolean DEFAULT_HUD_ENABLED = false;

	/**
	 * Off by default - see {@code net.oceancanvas.mod.command.BoundaryCommand}
	 * ({@code /oceancanvas worldborder}). Deliberately opt-in, not tied to
	 * the canvas automatically: syncing vanilla's real {@code WorldBorder}
	 * to the canvas bounds gives a genuine, already-proven-vanilla visual
	 * edge, but the world border ALSO physically blocks crossing it once
	 * its size is finite - silently enabling this by default would reverse
	 * the already-shipped M1 "canvas boundary" decision (sailing past the
	 * edge surfaces plain vanilla terrain, on purpose) without anyone
	 * asking for that. See {@code BoundaryCommand}'s class doc for the
	 * full reasoning.
	 */
	private static final boolean DEFAULT_WORLD_BORDER_SYNC_ENABLED = false;

	/**
	 * How many undoable resets (and, separately, redo-able undone resets)
	 * {@code OceanCanvasUndoManager} keeps per player before the oldest
	 * ages out - previously a hardcoded {@code MAX_UNDO_DEPTH_PER_PLAYER}
	 * constant in that class, made configurable this round per the same
	 * "no reason this needs a code change to tune" reasoning already
	 * applied to {@code pregenChunksPerTick}. 3 matches the value this
	 * project shipped and reasoned through when undo/redo were first
	 * drafted (Rounds 4/5) - kept as the default so upgrading doesn't
	 * silently change existing behavior for anyone who hasn't touched
	 * this setting.
	 */
	private static final int DEFAULT_UNDO_DEPTH_PER_PLAYER = 3;

	/**
	 * Real feature the user asked for directly: a way to turn shipwrecks
	 * off entirely, e.g. for canvas expansion where they might not want
	 * new ones. Default {@code INHERIT} ("Default") - shipwrecks (the
	 * guaranteed spawn one, plus relocating naturally-generated ones) are
	 * core, already-proven default behavior; turning them off, or forcing
	 * them everywhere via the same vanilla-like distribution a region's
	 * own "Always" rule uses (v125), is an explicit opt-out/opt-up, not the
	 * starting point.
	 */
	private static final StructureOverride DEFAULT_SHIPWRECKS_RULE = StructureOverride.INHERIT;

	/**
	 * Real feature request: minimap integration showing protected zones,
	 * toggleable. Off by default like every other drafted-this-round
	 * feature until it's actually been tested - see {@code
	 * OceanCanvasJourneyMapIntegration}'s class doc for the integration
	 * itself.
	 */
	private static final boolean DEFAULT_JOURNEYMAP_OVERLAY_ENABLED = false;

	/**
	 * How many chunks the ORDINARY, chunk-load-triggered flattener (see
	 * {@code OceanCanvasSurfaceFlattener#onServerTick}) is allowed to
	 * actually flatten per server tick - drafted per the real gap noticed
	 * while reviewing the project for further work: {@link
	 * #pregenChunksPerTick} throttles the OPT-IN pregen/reset/expand jobs,
	 * but the flattener's own everyday "land converts as you approach"
	 * queue (which runs unconditionally, whether or not pregen is even
	 * enabled) had no rate limit of its own at all - every pending,
	 * neighbor-ready chunk was processed in the same tick it became ready,
	 * with no cap. That's been safe in practice so far because normal
	 * exploration only ever surfaces a handful of newly-ready chunks per
	 * tick, but it's real unbounded-per-tick work with no safety net if a
	 * player ever moves fast enough (an elytra dive, a fast boat, a
	 * teleport into unflattened territory) to have many chunks become
	 * neighbor-ready in the same tick - the exact shape of risk this
	 * project already has one confirmed incident from (see
	 * {@code OceanCanvasSurfaceFlattener#neighborsReady}'s "~900 ticks
	 * behind" doc note), just via a different trigger. Deliberately a much
	 * higher default than {@code pregenChunksPerTick} (16 vs. 4) - pregen
	 * is proactively force-loading chunks nobody asked to see yet, while
	 * this only ever gates chunks already loading for an ordinary,
	 * legitimate gameplay reason (a player moving through the world), so a
	 * generous default that only matters in the genuinely fast-movement
	 * edge case is the right starting point rather than throttling normal
	 * play down visibly. Replaces what used to be a hardcoded
	 * {@code MAX_FLATTENS_PER_TICK} constant in
	 * {@code OceanCanvasSurfaceFlattener} itself, same "no reason this
	 * needs a code change to tune" reasoning as {@code pregenChunksPerTick}.
	 */
	private static final int DEFAULT_FLATTENER_CHUNKS_PER_TICK = 16;

	/**
	 * Whether {@code /oceancanvas reset}/{@code expand} automatically
	 * snapshot the world save folder to a timestamped backup directory
	 * before a large, destructive job actually starts - the "scheduled/
	 * automatic backups tied to canvas milestones... rather than relying
	 * on the server's own backup cadence" brainstormed idea. On by
	 * default (unlike most of this project's newer flags) - this one is
	 * a pure safety net with no behavioral change to worldgen itself, so
	 * there's no reason to make someone opt in to being protected against
	 * their own big mistake, the same reasoning {@code undoDepthPerPlayer}
	 * (also on-by-default) already uses.
	 */
	private static final boolean DEFAULT_BACKUP_ENABLED = true;

	/**
	 * Only reset/expand jobs at or above this many chunks trigger an
	 * automatic backup - see {@link #backupEnabled}'s doc comment. Lower
	 * than {@code PregenManager}'s own {@code CONFIRM_THRESHOLD_CHUNKS}
	 * (500 vs. 2,500) on purpose: a backup is comparatively cheap
	 * insurance (a file copy, not a destructive action of its own), so
	 * it's worth triggering well before a job is large enough to need an
	 * explicit "are you sure" confirmation - by the time a job is big
	 * enough to warrant a confirmation prompt, it should already be
	 * backed up.
	 */
	private static final int DEFAULT_BACKUP_THRESHOLD_CHUNKS = 500;

	/**
	 * How many automatic backups {@code OceanCanvasBackupManager} keeps
	 * before pruning the oldest - a full world-save copy per backup adds
	 * up on disk fast for a large canvas, so this bounds that growth
	 * instead of keeping every backup forever.
	 */
	private static final int DEFAULT_BACKUP_RETENTION_COUNT = 5;

	/**
	 * Off by default - the "soft edge transition" brainstormed idea,
	 * finally designed after 9 rounds of staying an explicitly open
	 * question (see {@code docs/roadmap.md}'s "Open design questions"
	 * section for why: every reasonable implementation collides with the
	 * already-shipped M1 guarantee that past the configured edge, chunks
	 * are left as plain, ordinary vanilla terrain). Same
	 * default-off-until-tested posture as every other first-attempt
	 * worldgen-behavior change in this project (pregen, biome mask, HUD
	 * initially) - this is the single most speculative piece of carving
	 * logic this project has ever written (see {@link
	 * net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener}'s taper
	 * doc for the full design and its honestly-documented residual
	 * imperfection at the very outer edge), so it stays opt-in until
	 * confirmed in-game.
	 */
	private static final boolean DEFAULT_TAPER_ENABLED = false;

	/**
	 * Width, in chunks, of the ring just outside the canvas boundary
	 * where the excavated floor blends back up toward natural terrain
	 * instead of stopping abruptly - the real fix for the "visible wall"
	 * problem the soft-edge-transition open question described. Fixed and
	 * configurable rather than scaling with canvas size, per the user's
	 * own explicit choice - simple, predictable, and consistent with how
	 * every other distance-shaped setting in this file
	 * ({@code oceanFloorTransitionThickness}, etc.) is already a fixed,
	 * not proportional, default. 8 chunks (128 blocks) is roughly half of
	 * a default player's render distance - wide enough that the blend is
	 * genuinely gradual rather than merely relocating the same hard edge
	 * a little further out, without being so wide that it force-processes
	 * an excessive ring of extra chunks around a very large canvas.
	 */
	private static final int DEFAULT_TAPER_WIDTH_CHUNKS = 8;

	/**
	 * Off by default - natural ocean ruins are carved away like ordinary
	 * terrain, matching how every other non-shipwreck structure
	 * (mineshafts, etc.) is already treated. Drafted alongside the new
	 * in-game map screen's per-zone structure-toggle feature (see {@code
	 * net.oceancanvas.mod.gui.OceanCanvasMapScreen} and {@code
	 * net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener#discoverAndPreserveWholeBox})
	 * so the "ocean ruin" half of that toggle has a real global default to
	 * override, symmetric with how {@link #shipwrecksRule} already backs
	 * the "shipwreck" half. A per-region rule (see
	 * {@code net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone}) can
	 * force protection on or off within a specific zone regardless of this
	 * global default. Unlike shipwreck protection, this is unconditional
	 * (not material-filtered) protection when active - see
	 * {@code discoverAndPreserveWholeBox}'s doc for why that's safe here.
	 *
	 * <p>v125: stored as a full {@link StructureOverride} rather than a boolean - see
	 * {@link net.oceancanvas.mod.worldgen.OceanCanvasStructureKind#globalDefault}'s doc for what
	 * the third ("Always"/{@code FORCE_ON}) state means at world scope.</p>
	 */
	private static final StructureOverride DEFAULT_NATURAL_OCEAN_RUINS_RULE = StructureOverride.FORCE_OFF;

	/**
	 * On by default ({@code INHERIT}/"Default") - buried treasure has been relocated to just
	 * beneath the canvas floor since that feature shipped, and it is one of the few rewards a
	 * stranded-in-the-ocean start actually has to find. This flag exists so a region can turn it
	 * OFF via a per-zone rule (see {@link net.oceancanvas.mod.worldgen.OceanCanvasStructureKind}),
	 * not because the default was in question. Buried treasure has no active "Always" placement
	 * pass (single hidden chest, no vanilla-like distribution grid to walk) - see
	 * {@code ModStarterStructures}' class doc for which kinds actually support one.
	 */
	private static final StructureOverride DEFAULT_BURIED_TREASURE_RULE = StructureOverride.INHERIT;

	/**
	 * Off by default, same reasoning as {@link #naturalOceanRuinsRule}: before this, an ocean
	 * monument intersecting the excavated range was carved away like any other non-shipwreck
	 * structure, and silently beginning to preserve them would visibly change every existing
	 * canvas without anyone asking. Turn it on globally here, or per region from the map screen.
	 */
	private static final StructureOverride DEFAULT_NATURAL_OCEAN_MONUMENTS_RULE = StructureOverride.FORCE_OFF;

	/** Ruined portals are cleared from the canvas by default, but can be kept globally or per region. */
	private static final StructureOverride DEFAULT_NATURAL_RUINED_PORTALS_RULE = StructureOverride.FORCE_OFF;

	private static OceanCanvasConfig instance;

	private final int canvasSize;
	private final int centerX;
	private final int centerZ;
	private final boolean expansionEnabled;
	private final int oceanFloorY;
	private final int oceanFloorVariation;
	private final int oceanFloorTransitionThickness;
	private final boolean pregenEnabled;
	private final int pregenChunksPerTick;
	private final int foreverWorldTargetHours;
	private final boolean biomeMaskEnabled;
	private final String biomeMaskBiome;
	private final boolean guaranteeSpawnOceanRuin;
	private final boolean hudEnabled;
	private final boolean worldBorderSyncEnabled;
	private final int undoDepthPerPlayer;
	private final StructureOverride shipwrecksRule;
	private final boolean journeyMapOverlayEnabled;
	private final int flattenerChunksPerTick;
	private final boolean backupEnabled;
	private final int backupThresholdChunks;
	private final int backupRetentionCount;
	private final boolean taperEnabled;
	private final int taperWidthChunks;
	private final StructureOverride naturalOceanRuinsRule;
	private final StructureOverride buriedTreasureRule;
	private final StructureOverride naturalOceanMonumentsRule;
	private final StructureOverride naturalRuinedPortalsRule;

	private OceanCanvasConfig(int canvasSize, int centerX, int centerZ, boolean expansionEnabled,
			int oceanFloorY, int oceanFloorVariation, int oceanFloorTransitionThickness,
			boolean pregenEnabled, int pregenChunksPerTick, int foreverWorldTargetHours,
			boolean biomeMaskEnabled, String biomeMaskBiome,
			boolean guaranteeSpawnOceanRuin, boolean hudEnabled, boolean worldBorderSyncEnabled,
			int undoDepthPerPlayer, StructureOverride shipwrecksRule, boolean journeyMapOverlayEnabled,
			int flattenerChunksPerTick,
			boolean backupEnabled, int backupThresholdChunks, int backupRetentionCount,
			boolean taperEnabled, int taperWidthChunks, StructureOverride naturalOceanRuinsRule,
			StructureOverride buriedTreasureRule, StructureOverride naturalOceanMonumentsRule,
			StructureOverride naturalRuinedPortalsRule) {
		this.canvasSize = canvasSize;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.expansionEnabled = expansionEnabled;
		this.oceanFloorY = oceanFloorY;
		this.oceanFloorVariation = oceanFloorVariation;
		this.oceanFloorTransitionThickness = oceanFloorTransitionThickness;
		this.pregenEnabled = pregenEnabled;
		this.pregenChunksPerTick = Math.max(1, pregenChunksPerTick);
		this.foreverWorldTargetHours = Math.max(1, Math.min(168, foreverWorldTargetHours));
		this.biomeMaskEnabled = biomeMaskEnabled;
		this.biomeMaskBiome = (biomeMaskBiome == null || biomeMaskBiome.isBlank())
				? DEFAULT_BIOME_MASK_BIOME
				: biomeMaskBiome;
		this.guaranteeSpawnOceanRuin = guaranteeSpawnOceanRuin;
		this.hudEnabled = hudEnabled;
		this.worldBorderSyncEnabled = worldBorderSyncEnabled;
		// Same "never let a bad/zero config value break real behavior"
		// guard as pregenChunksPerTick above - a depth of 0 would make
		// undo/redo silently accept nothing, and a negative one would be
		// nonsensical, so floor it at 1 rather than trusting the file.
		this.undoDepthPerPlayer = Math.max(1, undoDepthPerPlayer);
		this.shipwrecksRule = shipwrecksRule == null ? StructureOverride.INHERIT : shipwrecksRule;
		this.journeyMapOverlayEnabled = journeyMapOverlayEnabled;
		// Same "never let a bad/zero config value break real behavior"
		// guard as pregenChunksPerTick/undoDepthPerPlayer above.
		this.flattenerChunksPerTick = Math.max(1, flattenerChunksPerTick);
		this.backupEnabled = backupEnabled;
		this.backupThresholdChunks = Math.max(0, backupThresholdChunks);
		this.backupRetentionCount = Math.max(1, backupRetentionCount);
		this.taperEnabled = taperEnabled;
		// A width of 0 would make taperEnabled meaningless (every taper
		// computation divides by this) - floor at 1 rather than trusting
		// the file, same guard pattern as every other tunable in this
		// constructor.
		this.taperWidthChunks = Math.max(1, taperWidthChunks);
		this.naturalOceanRuinsRule = naturalOceanRuinsRule == null ? StructureOverride.INHERIT : naturalOceanRuinsRule;
		this.buriedTreasureRule = buriedTreasureRule == null ? StructureOverride.INHERIT : buriedTreasureRule;
		this.naturalOceanMonumentsRule = naturalOceanMonumentsRule == null ? StructureOverride.INHERIT : naturalOceanMonumentsRule;
		this.naturalRuinedPortalsRule = naturalRuinedPortalsRule == null ? StructureOverride.INHERIT : naturalRuinedPortalsRule;
	}

	/**
	 * Builds a config without touching disk or Fabric Loader. Used by unit
	 * tests, and available if you ever need an in-memory override. Fills
	 * every new (M2+) field with its default rather than requiring every
	 * call site to be updated.
	 */
	public static OceanCanvasConfig of(int canvasSize, int centerX, int centerZ, boolean expansionEnabled) {
		return new OceanCanvasConfig(canvasSize, centerX, centerZ, expansionEnabled,
				DEFAULT_OCEAN_FLOOR_Y, DEFAULT_OCEAN_FLOOR_VARIATION, DEFAULT_OCEAN_FLOOR_TRANSITION_THICKNESS,
				DEFAULT_PREGEN_ENABLED, DEFAULT_PREGEN_CHUNKS_PER_TICK, DEFAULT_FOREVER_WORLD_TARGET_HOURS,
				DEFAULT_BIOME_MASK_ENABLED, DEFAULT_BIOME_MASK_BIOME,
				DEFAULT_GUARANTEE_SPAWN_OCEAN_RUIN, DEFAULT_HUD_ENABLED, DEFAULT_WORLD_BORDER_SYNC_ENABLED,
				DEFAULT_UNDO_DEPTH_PER_PLAYER, DEFAULT_SHIPWRECKS_RULE, DEFAULT_JOURNEYMAP_OVERLAY_ENABLED,
				DEFAULT_FLATTENER_CHUNKS_PER_TICK,
				DEFAULT_BACKUP_ENABLED, DEFAULT_BACKUP_THRESHOLD_CHUNKS, DEFAULT_BACKUP_RETENTION_COUNT,
				DEFAULT_TAPER_ENABLED, DEFAULT_TAPER_WIDTH_CHUNKS, DEFAULT_NATURAL_OCEAN_RUINS_RULE,
				DEFAULT_BURIED_TREASURE_RULE, DEFAULT_NATURAL_OCEAN_MONUMENTS_RULE,
				DEFAULT_NATURAL_RUINED_PORTALS_RULE);
	}

	public static synchronized OceanCanvasConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Half-width of the canvas, in blocks, from the center. */
	public int radius() {
		return canvasSize / 2;
	}

	public int canvasSize() {
		return canvasSize;
	}

	public int centerX() {
		return centerX;
	}

	public int centerZ() {
		return centerZ;
	}

	public boolean expansionEnabled() {
		return expansionEnabled;
	}

	/**
	 * The minimum depth (Y level) the ocean floor is excavated to
	 * wherever natural terrain is shallower - see the field's doc
	 * comment above for the "never removes real deep terrain" guarantee.
	 */
	public int oceanFloorY() {
		return oceanFloorY;
	}

	/** How far the excavated floor's height varies up/down - see the field's doc comment above. */
	public int oceanFloorVariation() {
		return oceanFloorVariation;
	}

	/** Thickness of the guaranteed-solid stone seal below the floor - see the field's doc comment above. */
	public int oceanFloorTransitionThickness() {
		return oceanFloorTransitionThickness;
	}

	/** Whether {@code /oceancanvas pregen} is allowed to actually run - off by default, see the field's doc comment. */
	public boolean pregenEnabled() {
		return pregenEnabled;
	}

	/** How many chunks a running pregen job is allowed to touch per server tick - see the field's doc comment. */
	public int pregenChunksPerTick() {
		return pregenChunksPerTick;
	}

	/** Full-canvas completion target used by the Forever World SLO predictor. */
	public int foreverWorldTargetHours() {
		return foreverWorldTargetHours;
	}

	/** Whether the uniform-ocean-biome mask is applied - on by default, see the field's doc comment. */
	public boolean biomeMaskEnabled() {
		return biomeMaskEnabled;
	}

	/** The biome id (e.g. {@code minecraft:ocean}) used for the mask, if enabled. */
	public String biomeMaskBiome() {
		return biomeMaskBiome;
	}

	/**
	 * Whether a second guaranteed structure (an ocean ruin, in addition to
	 * the always-on shipwreck) is forced to exist near spawn - off by
	 * default, see the field's doc comment.
	 */
	public boolean guaranteeSpawnOceanRuin() {
		return guaranteeSpawnOceanRuin;
	}

	/**
	 * Whether the ambient canvas-boundary HUD overlay is enabled - off by
	 * default; also has no effect at all unless the HUD class has been
	 * un-excluded from the client build (see this field's doc comment).
	 */
	public boolean hudEnabled() {
		return hudEnabled;
	}

	/**
	 * Whether vanilla's real {@code WorldBorder} is kept synced to the
	 * canvas bounds - off by default, see the field's doc comment for why
	 * this is opt-in rather than automatic.
	 */
	public boolean worldBorderSyncEnabled() {
		return worldBorderSyncEnabled;
	}

	/**
	 * How many undoable resets (and redo-able undone resets) {@code
	 * OceanCanvasUndoManager} keeps per player - see the field's doc
	 * comment above for why this is configurable rather than a hardcoded
	 * constant. Always at least 1 (floored in the constructor).
	 */
	public int undoDepthPerPlayer() {
		return undoDepthPerPlayer;
	}

	/**
	 * If false, the guaranteed spawn shipwreck is never placed and
	 * natural shipwrecks are never discovered/relocated/protected -
	 * they're left exactly as vanilla generated them (still at their
	 * original position/height, still carveable like anything else).
	 * Real feature requested directly by the user for the canvas
	 * expansion case: they may not want new shipwrecks appearing as the
	 * canvas grows. Does NOT retroactively undo an already-relocated
	 * shipwreck if flipped off after the fact - only affects newly
	 * discovered ones going forward.
	 */
	public StructureOverride shipwrecksRule() {
		return shipwrecksRule;
	}

	/**
	 * If true, protected zones (and the canvas boundary) are drawn as an
	 * Legacy compatibility flag retained so existing properties files still parse.
	 * Ocean Canvas no longer publishes protected-region overlays to JourneyMap;
	 * the built-in map is now the sole visual region editor.
	 */
	public boolean journeyMapOverlayEnabled() {
		return journeyMapOverlayEnabled;
	}

	/**
	 * How many chunks the ordinary, chunk-load-triggered flattener may
	 * process per server tick - distinct from {@link #pregenChunksPerTick},
	 * see the field's doc comment above for why these are two separate
	 * throttles. Always at least 1 (floored in the constructor).
	 */
	public int flattenerChunksPerTick() {
		return flattenerChunksPerTick;
	}

	/** Whether a large reset/expand automatically backs up the world save first - see the field's doc comment. */
	public boolean backupEnabled() {
		return backupEnabled;
	}

	/** The chunk-count threshold above which a reset/expand triggers an automatic backup - see the field's doc comment. */
	public int backupThresholdChunks() {
		return backupThresholdChunks;
	}

	/** How many automatic backups are kept before the oldest is pruned - see the field's doc comment. Always at least 1. */
	public int backupRetentionCount() {
		return backupRetentionCount;
	}

	/** Whether the soft edge transition (taper) is active - off by default, see the field's doc comment. */
	public boolean taperEnabled() {
		return taperEnabled;
	}

	/** Width of the taper ring in chunks - see the field's doc comment. Always at least 1. */
	public int taperWidthChunks() {
		return taperWidthChunks;
	}

	/** {@link #taperWidthChunks()} converted to blocks, the unit every taper distance calculation actually needs. */
	public int taperWidthBlocks() {
		return taperWidthChunks * 16;
	}

	/**
	 * Natural ocean ruins' world-wide default rule - {@code FORCE_OFF} ("Never") unless changed,
	 * see the field's doc comment above. A per-zone rule (see {@code
	 * OceanCanvasPlayerZones.Zone#structureOverrides}) always wins over this regardless of what
	 * it's set to.
	 */
	public StructureOverride naturalOceanRuinsRule() {
		return naturalOceanRuinsRule;
	}

	/** Buried treasure's world-wide default rule - see the field's doc comment. Overridable per region. */
	public StructureOverride buriedTreasureRule() {
		return buriedTreasureRule;
	}

	/** Natural ocean monuments' world-wide default rule - see the field's doc comment. Overridable per region. */
	public StructureOverride naturalOceanMonumentsRule() {
		return naturalOceanMonumentsRule;
	}

	/** Natural ruined portals' world-wide default rule; per-region rules can override this. */
	public StructureOverride naturalRuinedPortalsRule() {
		return naturalRuinedPortalsRule;
	}

	/** True if the given block position falls inside the protected canvas. */
	public boolean isInsideCanvas(int blockX, int blockZ) {
		int r = radius();
		return blockX >= centerX - r && blockX < centerX + r
				&& blockZ >= centerZ - r && blockZ < centerZ + r;
	}

	/**
	 * The three states a block position can be in relative to the canvas -
	 * added alongside the soft edge transition, per the user's own
	 * explicit design decision that the taper ring is a genuinely
	 * distinct third state, not folded into "inside" or "outside".
	 */
	public enum CanvasZone {
		/** Inside the canvas proper - fully excavated, exactly as every prior round's flattening logic already does. */
		INSIDE,
		/**
		 * Just outside the canvas, within {@link #taperWidthBlocks()} of
		 * the edge, with {@link #taperEnabled()} on - the excavated floor
		 * blends back toward natural terrain here instead of stopping
		 * abruptly. Never returned when {@code taperEnabled} is off - see
		 * {@link #canvasZone}.
		 */
		TAPER,
		/** Plain, ordinary, completely untouched vanilla terrain - the M1 "canvas boundary" guarantee, unchanged. */
		OUTSIDE
	}

	/**
	 * Classifies a block position into exactly one of {@link CanvasZone}'s
	 * three states. Deliberately reuses {@link #isInsideCanvas}'s own
	 * exact boundary convention for the inner test (rather than a new,
	 * independently-derived geometric check) so this can never disagree
	 * with the already-proven canvas-membership logic every other feature
	 * in this project already relies on - only the OUTER (taper) boundary
	 * is new.
	 */
	public CanvasZone canvasZone(int blockX, int blockZ) {
		if (isInsideCanvas(blockX, blockZ)) {
			return CanvasZone.INSIDE;
		}
		if (!taperEnabled) {
			return CanvasZone.OUTSIDE;
		}
		int outerR = radius() + taperWidthBlocks();
		boolean withinOuter = blockX >= centerX - outerR && blockX < centerX + outerR
				&& blockZ >= centerZ - outerR && blockZ < centerZ + outerR;
		return withinOuter ? CanvasZone.TAPER : CanvasZone.OUTSIDE;
	}

	/**
	 * How far into the taper ring this position is, as a value from 0.0
	 * (right at the canvas edge - the flattener should carve exactly as
	 * it would just inside the canvas, for a seamless inner boundary) to
	 * just under 1.0 (at the outermost taper chunk still classified
	 * {@link CanvasZone#TAPER} - the flattener should do almost nothing).
	 * Only meaningful when {@link #canvasZone} for the same position
	 * returns {@code TAPER} - callers must check the zone first, this
	 * method does no bounds validation of its own and simply computes the
	 * ratio, which can exceed [0, 1] for a position outside the taper ring
	 * entirely (a caller bug, not a value this method tries to guard
	 * against).
	 *
	 * <p>Uses the same "square" (Chebyshev) distance metric as the canvas
	 * boundary itself - {@code max(|x - centerX|, |z - centerZ|)} - for
	 * consistency with the square (not circular) canvas/taper shape
	 * already established by {@link #isInsideCanvas} and {@link
	 * #canvasZone}'s own outer check.</p>
	 */
	public double taperBlend(int blockX, int blockZ) {
		int squareDistance = Math.max(Math.abs(blockX - centerX), Math.abs(blockZ - centerZ));
		double distanceIntoTaper = squareDistance - radius();
		double blend = distanceIntoTaper / (double) taperWidthBlocks();
		return Math.max(0.0, Math.min(1.0, blend));
	}

	/**
	 * A pre-filled, fluent copy of this config you can change and persist.
	 * Used by the in-game config screen (see
	 * {@code net.oceancanvas.mod.integration}) and available for any
	 * future in-game command that wants to change a setting without a
	 * restart.
	 */
	public Builder toBuilder() {
		return new Builder(this);
	}

	private static OceanCanvasConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties props = new Properties();

		if (Files.exists(path)) {
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			} catch (IOException e) {
				OceanCanvas.LOGGER.warn("Failed to read {}, falling back to defaults", FILE_NAME, e);
			}
		}

		int canvasSize = parseIntOrDefault(props.getProperty("canvasSize"), DEFAULT_CANVAS_SIZE);
		int centerX = parseIntOrDefault(props.getProperty("centerX"), DEFAULT_CENTER_X);
		int centerZ = parseIntOrDefault(props.getProperty("centerZ"), DEFAULT_CENTER_Z);
		boolean expansionEnabled = Boolean.parseBoolean(
				props.getProperty("expansionEnabled", String.valueOf(DEFAULT_EXPANSION_ENABLED)));
		int oceanFloorY = parseIntOrDefault(props.getProperty("oceanFloorY"), DEFAULT_OCEAN_FLOOR_Y);
		int oceanFloorVariation = parseIntOrDefault(
				props.getProperty("oceanFloorVariation"), DEFAULT_OCEAN_FLOOR_VARIATION);
		int oceanFloorTransitionThickness = parseIntOrDefault(
				props.getProperty("oceanFloorTransitionThickness"), DEFAULT_OCEAN_FLOOR_TRANSITION_THICKNESS);
		boolean pregenEnabled = Boolean.parseBoolean(
				props.getProperty("pregenEnabled", String.valueOf(DEFAULT_PREGEN_ENABLED)));
		int pregenChunksPerTick = parseIntOrDefault(
				props.getProperty("pregenChunksPerTick"), DEFAULT_PREGEN_CHUNKS_PER_TICK);
		int foreverWorldTargetHours = parseIntOrDefault(
				props.getProperty("foreverWorldTargetHours"), DEFAULT_FOREVER_WORLD_TARGET_HOURS);
		boolean biomeMaskEnabled = Boolean.parseBoolean(
				props.getProperty("biomeMaskEnabled", String.valueOf(DEFAULT_BIOME_MASK_ENABLED)));
		// One-time migration for configs created before Ocean Canvas treated
		// vanilla ocean ecology as baseline behavior. Those configs commonly
		// persisted biomeMaskEnabled=false because the mask was experimental.
		// Force it on exactly once so existing worlds gain normal fish/dolphin/
		// squid/drowned spawn tables; after this marker is written the player
		// may still explicitly toggle the option off again if they choose.
		if (!Boolean.parseBoolean(props.getProperty("oceanEcologyMigrationComplete", "false"))) {
			biomeMaskEnabled = true;
		}
		String biomeMaskBiome = props.getProperty("biomeMaskBiome", DEFAULT_BIOME_MASK_BIOME);
		boolean guaranteeSpawnOceanRuin = Boolean.parseBoolean(
				props.getProperty("guaranteeSpawnOceanRuin", String.valueOf(DEFAULT_GUARANTEE_SPAWN_OCEAN_RUIN)));
		boolean hudEnabled = Boolean.parseBoolean(
				props.getProperty("hudEnabled", String.valueOf(DEFAULT_HUD_ENABLED)));
		boolean worldBorderSyncEnabled = Boolean.parseBoolean(
				props.getProperty("worldBorderSyncEnabled", String.valueOf(DEFAULT_WORLD_BORDER_SYNC_ENABLED)));
		int undoDepthPerPlayer = parseIntOrDefault(
				props.getProperty("undoDepthPerPlayer"), DEFAULT_UNDO_DEPTH_PER_PLAYER);
		StructureOverride shipwrecksRule = parseStructureRule(
				props.getProperty("shipwrecksEnabled"), true, DEFAULT_SHIPWRECKS_RULE);
		boolean journeyMapOverlayEnabled = Boolean.parseBoolean(
				props.getProperty("journeyMapOverlayEnabled", String.valueOf(DEFAULT_JOURNEYMAP_OVERLAY_ENABLED)));
		int flattenerChunksPerTick = parseIntOrDefault(
				props.getProperty("flattenerChunksPerTick"), DEFAULT_FLATTENER_CHUNKS_PER_TICK);
		boolean backupEnabled = Boolean.parseBoolean(
				props.getProperty("backupEnabled", String.valueOf(DEFAULT_BACKUP_ENABLED)));
		int backupThresholdChunks = parseIntOrDefault(
				props.getProperty("backupThresholdChunks"), DEFAULT_BACKUP_THRESHOLD_CHUNKS);
		int backupRetentionCount = parseIntOrDefault(
				props.getProperty("backupRetentionCount"), DEFAULT_BACKUP_RETENTION_COUNT);
		boolean taperEnabled = Boolean.parseBoolean(
				props.getProperty("taperEnabled", String.valueOf(DEFAULT_TAPER_ENABLED)));
		int taperWidthChunks = parseIntOrDefault(
				props.getProperty("taperWidthChunks"), DEFAULT_TAPER_WIDTH_CHUNKS);
		StructureOverride naturalOceanRuinsRule = parseStructureRule(
				props.getProperty("naturalOceanRuinsProtected"), false, DEFAULT_NATURAL_OCEAN_RUINS_RULE);
		StructureOverride buriedTreasureRule = parseStructureRule(
				props.getProperty("buriedTreasureEnabled"), true, DEFAULT_BURIED_TREASURE_RULE);
		StructureOverride naturalOceanMonumentsRule = parseStructureRule(
				props.getProperty("naturalOceanMonumentsProtected"), false, DEFAULT_NATURAL_OCEAN_MONUMENTS_RULE);
		StructureOverride naturalRuinedPortalsRule = parseStructureRule(
				props.getProperty("naturalRuinedPortalsProtected"), false, DEFAULT_NATURAL_RUINED_PORTALS_RULE);

		OceanCanvasConfig config = new OceanCanvasConfig(
				canvasSize, centerX, centerZ, expansionEnabled,
				oceanFloorY, oceanFloorVariation, oceanFloorTransitionThickness,
				pregenEnabled, pregenChunksPerTick, foreverWorldTargetHours, biomeMaskEnabled, biomeMaskBiome,
				guaranteeSpawnOceanRuin, hudEnabled, worldBorderSyncEnabled, undoDepthPerPlayer,
				shipwrecksRule, journeyMapOverlayEnabled,
				flattenerChunksPerTick, backupEnabled, backupThresholdChunks, backupRetentionCount,
				taperEnabled, taperWidthChunks, naturalOceanRuinsRule,
				buriedTreasureRule, naturalOceanMonumentsRule, naturalRuinedPortalsRule);
		config.save(path);
		return config;
	}

	/**
	 * Reads one of the five structure-rule properties, accepting both the current
	 * {@code StructureOverride} name form ("INHERIT"/"FORCE_ON"/"FORCE_OFF") and the legacy
	 * boolean form ("true"/"false") every save from before v125 actually contains - v125 upgraded
	 * these five settings from a plain on/off toggle to the same three-state Default/Always/Never
	 * choice a region's own rule already offered (see {@code OceanCanvasStructureKind#globalDefault}'s
	 * doc), and nobody's existing save should have its structure settings silently reset just
	 * because the file predates that change.
	 *
	 * <p>Legacy migration is exactly the boolean's own old meaning, nothing more: old {@code true}
	 * ("protect/relocate if found") becomes {@code INHERIT} ("Default") - the same behavior under a
	 * new name, never {@code FORCE_ON}, since "Always" (active vanilla-like forced placement) is a
	 * brand-new capability at world scope with no old boolean value that ever meant it. Old
	 * {@code false} ("carved away") becomes {@code FORCE_OFF} ("Never") - likewise the same
	 * behavior under a new name. {@code legacyDefaultTrue} is the specific kind's own old {@code
	 * DEFAULT_*_ENABLED}/{@code DEFAULT_*_PROTECTED} constant value, used only when the property is
	 * entirely absent (a config file that predates even the boolean fields).</p>
	 */
	private static StructureOverride parseStructureRule(String raw, boolean legacyDefaultTrue, StructureOverride fallback) {
		if (raw == null || raw.isBlank()) {
			return legacyDefaultTrue ? StructureOverride.INHERIT : StructureOverride.FORCE_OFF;
		}
		String trimmed = raw.trim();
		try {
			return StructureOverride.valueOf(trimmed.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException notAnEnumName) {
			if ("true".equalsIgnoreCase(trimmed)) return StructureOverride.INHERIT;
			if ("false".equalsIgnoreCase(trimmed)) return StructureOverride.FORCE_OFF;
			return fallback;
		}
	}

	private static int parseIntOrDefault(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void save(Path path) {
		Properties props = new Properties();
		props.setProperty("canvasSize", String.valueOf(canvasSize));
		props.setProperty("centerX", String.valueOf(centerX));
		props.setProperty("centerZ", String.valueOf(centerZ));
		props.setProperty("expansionEnabled", String.valueOf(expansionEnabled));
		props.setProperty("oceanFloorY", String.valueOf(oceanFloorY));
		props.setProperty("oceanFloorVariation", String.valueOf(oceanFloorVariation));
		props.setProperty("oceanFloorTransitionThickness", String.valueOf(oceanFloorTransitionThickness));
		props.setProperty("pregenEnabled", String.valueOf(pregenEnabled));
		props.setProperty("pregenChunksPerTick", String.valueOf(pregenChunksPerTick));
		props.setProperty("foreverWorldTargetHours", String.valueOf(foreverWorldTargetHours));
		props.setProperty("biomeMaskEnabled", String.valueOf(biomeMaskEnabled));
		props.setProperty("biomeMaskBiome", biomeMaskBiome);
		props.setProperty("oceanEcologyMigrationComplete", "true");
		props.setProperty("guaranteeSpawnOceanRuin", String.valueOf(guaranteeSpawnOceanRuin));
		props.setProperty("hudEnabled", String.valueOf(hudEnabled));
		props.setProperty("worldBorderSyncEnabled", String.valueOf(worldBorderSyncEnabled));
		props.setProperty("undoDepthPerPlayer", String.valueOf(undoDepthPerPlayer));
		props.setProperty("shipwrecksEnabled", shipwrecksRule.name());
		props.setProperty("journeyMapOverlayEnabled", String.valueOf(journeyMapOverlayEnabled));
		props.setProperty("flattenerChunksPerTick", String.valueOf(flattenerChunksPerTick));
		props.setProperty("backupEnabled", String.valueOf(backupEnabled));
		props.setProperty("backupThresholdChunks", String.valueOf(backupThresholdChunks));
		props.setProperty("backupRetentionCount", String.valueOf(backupRetentionCount));
		props.setProperty("taperEnabled", String.valueOf(taperEnabled));
		props.setProperty("taperWidthChunks", String.valueOf(taperWidthChunks));
		props.setProperty("naturalOceanRuinsProtected", naturalOceanRuinsRule.name());
		props.setProperty("buriedTreasureEnabled", buriedTreasureRule.name());
		props.setProperty("naturalOceanMonumentsProtected", naturalOceanMonumentsRule.name());
		props.setProperty("naturalRuinedPortalsProtected", naturalRuinedPortalsRule.name());

		try {
			Files.createDirectories(path.getParent());
			try (OutputStream out = Files.newOutputStream(path)) {
				props.store(out, "Ocean Canvas region settings. Edit canvasSize/centerX/centerZ/oceanFloorY/"
						+ "oceanFloorVariation/oceanFloorTransitionThickness before first world creation - "
						+ "changing them after the world already has chunks generated does not move or "
						+ "re-carve them. Everything else is safe to change any time (or via the in-game "
						+ "config screen, if Mod Menu + Cloth Config are installed). foreverWorldTargetHours "
						+ "(default 8) is the completion SLO used by ETA/throughput diagnostics; it does not "
						+ "force scheduler throughput. pregenEnabled, "
						+ "shipwrecksEnabled, backupEnabled, and undoDepthPerPlayer default ON (pregenEnabled "
						+ "also controls whether \"/oceancanvas expand\" proactively flattens the new ring it "
						+ "creates). Boolean defaults are intentionally mixed rather than globally OFF: biomeMaskEnabled and backupEnabled also default on; consult the emitted values below as the authority. "
						+ "worldBorderSyncEnabled is better changed via \"/oceancanvas worldborder on|off\" "
						+ "than by hand - that command also immediately applies/reverts the border itself, "
						+ "not just this persisted flag. undoDepthPerPlayer (default 3) is how many undoable "
						+ "resets/redo-able undone resets OceanCanvasUndoManager keeps per player. "
						+ "shipwrecksEnabled (default true) turns off the guaranteed spawn shipwreck and "
						+ "natural shipwreck relocation/protection entirely when false - does not "
						+ "retroactively undo an already-relocated shipwreck. journeyMapOverlayEnabled "
						+ "(default false) draws protected zones on JourneyMap's map, if it's installed - no "
						+ "effect at all otherwise. flattenerChunksPerTick (default 16) throttles the "
						+ "ordinary, chunk-load-triggered flattener, separately from pregenChunksPerTick "
						+ "(default 4) which only throttles opt-in pregen/reset/expand jobs. backupEnabled/"
						+ "backupThresholdChunks/backupRetentionCount (defaults true/500/5) control the "
						+ "automatic world-save backup taken before a large reset/expand job. taperEnabled/"
						+ "taperWidthChunks (defaults false/8) control the soft edge transition - a ring of "
						+ "chunks just outside the canvas where the excavated floor blends back toward "
						+ "natural terrain instead of stopping at a hard wall. naturalOceanRuinsProtected "
						+ "(default false) protects natural ocean ruins from carving, symmetric with "
						+ "shipwrecksEnabled - either can be overridden per-zone via the Ocean Canvas map "
						+ "screen (\"/oceancanvas map\") or \"/oceancanvas protect override\". "
						+ "buriedTreasureEnabled (default true) and naturalOceanMonumentsProtected (default "
						+ "false) are the same kind of world-wide default for those two structure kinds, "
						+ "overridable per region the same way.");
			}
		} catch (IOException e) {
			OceanCanvas.LOGGER.warn("Failed to write {}", FILE_NAME, e);
		}
	}

	/**
	 * Fluent, pre-filled copy of a config. {@link #save()} persists the
	 * result to {@code oceancanvas.properties} and atomically replaces
	 * the live singleton {@link OceanCanvasConfig#get()} returns from
	 * that point on - existing code that just calls {@code get()} picks
	 * up the change automatically, no restart needed for the fields that
	 * are actually safe to change live (see each setter's doc comment).
	 */
	public static final class Builder {
		private int canvasSize;
		private int centerX;
		private int centerZ;
		private boolean expansionEnabled;
		private int oceanFloorY;
		private int oceanFloorVariation;
		private int oceanFloorTransitionThickness;
		private boolean pregenEnabled;
		private int pregenChunksPerTick;
		private int foreverWorldTargetHours;
		private boolean biomeMaskEnabled;
		private String biomeMaskBiome;
		private boolean guaranteeSpawnOceanRuin;
		private boolean hudEnabled;
		private boolean worldBorderSyncEnabled;
		private int undoDepthPerPlayer;
		private StructureOverride shipwrecksRule;
		private boolean journeyMapOverlayEnabled;
		private int flattenerChunksPerTick;
		private boolean backupEnabled;
		private int backupThresholdChunks;
		private int backupRetentionCount;
		private boolean taperEnabled;
		private int taperWidthChunks;
		private StructureOverride naturalOceanRuinsRule;
		private StructureOverride buriedTreasureRule;
		private StructureOverride naturalOceanMonumentsRule;
		private StructureOverride naturalRuinedPortalsRule;

		private Builder(OceanCanvasConfig base) {
			this.canvasSize = base.canvasSize;
			this.centerX = base.centerX;
			this.centerZ = base.centerZ;
			this.expansionEnabled = base.expansionEnabled;
			this.oceanFloorY = base.oceanFloorY;
			this.oceanFloorVariation = base.oceanFloorVariation;
			this.oceanFloorTransitionThickness = base.oceanFloorTransitionThickness;
			this.pregenEnabled = base.pregenEnabled;
			this.pregenChunksPerTick = base.pregenChunksPerTick;
			this.foreverWorldTargetHours = base.foreverWorldTargetHours;
			this.biomeMaskEnabled = base.biomeMaskEnabled;
			this.biomeMaskBiome = base.biomeMaskBiome;
			this.guaranteeSpawnOceanRuin = base.guaranteeSpawnOceanRuin;
			this.hudEnabled = base.hudEnabled;
			this.worldBorderSyncEnabled = base.worldBorderSyncEnabled;
			this.undoDepthPerPlayer = base.undoDepthPerPlayer;
			this.shipwrecksRule = base.shipwrecksRule;
			this.journeyMapOverlayEnabled = base.journeyMapOverlayEnabled;
			this.flattenerChunksPerTick = base.flattenerChunksPerTick;
			this.backupEnabled = base.backupEnabled;
			this.backupThresholdChunks = base.backupThresholdChunks;
			this.backupRetentionCount = base.backupRetentionCount;
			this.taperEnabled = base.taperEnabled;
			this.taperWidthChunks = base.taperWidthChunks;
			this.naturalOceanRuinsRule = base.naturalOceanRuinsRule;
			this.buriedTreasureRule = base.buriedTreasureRule;
			this.naturalOceanMonumentsRule = base.naturalOceanMonumentsRule;
			this.naturalRuinedPortalsRule = base.naturalRuinedPortalsRule;
		}

		/** Does NOT retroactively move/re-carve already-generated chunks - see the class doc. */
		public Builder canvasSize(int canvasSize) {
			this.canvasSize = canvasSize;
			return this;
		}

		/** Does NOT retroactively move already-generated chunks - see the class doc. */
		public Builder center(int centerX, int centerZ) {
			this.centerX = centerX;
			this.centerZ = centerZ;
			return this;
		}

		public Builder expansionEnabled(boolean expansionEnabled) {
			this.expansionEnabled = expansionEnabled;
			return this;
		}

		/** Does NOT retroactively re-carve already-generated chunks - see the class doc. */
		public Builder oceanFloorY(int oceanFloorY) {
			this.oceanFloorY = oceanFloorY;
			return this;
		}

		/** Does NOT retroactively re-carve already-generated chunks - see the class doc. */
		public Builder oceanFloorVariation(int oceanFloorVariation) {
			this.oceanFloorVariation = oceanFloorVariation;
			return this;
		}

		/** Does NOT retroactively re-carve already-generated chunks - see the class doc. */
		public Builder oceanFloorTransitionThickness(int oceanFloorTransitionThickness) {
			this.oceanFloorTransitionThickness = oceanFloorTransitionThickness;
			return this;
		}

		/** Safe to flip live - only gates whether {@code /oceancanvas pregen} accepts jobs. */
		public Builder pregenEnabled(boolean pregenEnabled) {
			this.pregenEnabled = pregenEnabled;
			return this;
		}

		/** Safe to change live - takes effect for the next pregen tick batch. */
		public Builder pregenChunksPerTick(int pregenChunksPerTick) {
			this.pregenChunksPerTick = pregenChunksPerTick;
			return this;
		}

		/** Safe to change live; affects only SLO/ETA diagnostics, not scheduler admission. */
		public Builder foreverWorldTargetHours(int foreverWorldTargetHours) {
			this.foreverWorldTargetHours = foreverWorldTargetHours;
			return this;
		}

		/** Safe to flip live - takes effect the next time each chunk is (re)processed, not retroactively. */
		public Builder biomeMaskEnabled(boolean biomeMaskEnabled) {
			this.biomeMaskEnabled = biomeMaskEnabled;
			return this;
		}

		/** Safe to change live - takes effect the next time each chunk is (re)processed, not retroactively. */
		public Builder biomeMaskBiome(String biomeMaskBiome) {
			this.biomeMaskBiome = biomeMaskBiome;
			return this;
		}

		/** Safe to flip live - only affects a spawn load that hasn't already placed/skipped the ocean ruin. */
		public Builder guaranteeSpawnOceanRuin(boolean guaranteeSpawnOceanRuin) {
			this.guaranteeSpawnOceanRuin = guaranteeSpawnOceanRuin;
			return this;
		}

		/** Safe to flip live - has no visible effect until the HUD class is un-excluded from the client build. */
		public Builder hudEnabled(boolean hudEnabled) {
			this.hudEnabled = hudEnabled;
			return this;
		}

		/**
		 * Safe to flip live, but prefer {@code /oceancanvas worldborder
		 * on|off} over calling this directly - that command also applies/
		 * reverts the actual vanilla {@code WorldBorder} immediately, not
		 * just this persisted flag (which only re-applies it on the next
		 * server start / canvas expansion, see {@code BoundaryCommand}).
		 */
		public Builder worldBorderSyncEnabled(boolean worldBorderSyncEnabled) {
			this.worldBorderSyncEnabled = worldBorderSyncEnabled;
			return this;
		}

		/** Safe to change live - takes effect the next time undo/redo evicts down to the new depth. Always floored at 1. */
		public Builder undoDepthPerPlayer(int undoDepthPerPlayer) {
			this.undoDepthPerPlayer = undoDepthPerPlayer;
			return this;
		}

		/**
		 * Safe to change live. Only affects shipwrecks discovered from
		 * this point forward - flipping this off does not undo an
		 * already-relocated shipwreck, and flipping it back on does not
		 * retroactively relocate one that was skipped while it was off.
		 * Setting {@code FORCE_ON} ("Always") also queues the same
		 * vanilla-like forced-distribution pass a region's own Always rule
		 * uses, scoped to whatever chunks the next pregen/rewipe/expand
		 * job actually touches - see {@code ModStarterStructures}.
		 */
		public Builder shipwrecksRule(StructureOverride shipwrecksRule) {
			this.shipwrecksRule = shipwrecksRule;
			return this;
		}

		/** Legacy no-op retained for old config files; JourneyMap overlay integration was removed. */
		public Builder journeyMapOverlayEnabled(boolean journeyMapOverlayEnabled) {
			this.journeyMapOverlayEnabled = journeyMapOverlayEnabled;
			return this;
		}

		/** Safe to change live - takes effect on the very next server tick. Always floored at 1. */
		public Builder flattenerChunksPerTick(int flattenerChunksPerTick) {
			this.flattenerChunksPerTick = flattenerChunksPerTick;
			return this;
		}

		/** Safe to flip live - only checked at the moment a reset/expand job is about to start. */
		public Builder backupEnabled(boolean backupEnabled) {
			this.backupEnabled = backupEnabled;
			return this;
		}

		/** Safe to change live - takes effect for the next reset/expand job. */
		public Builder backupThresholdChunks(int backupThresholdChunks) {
			this.backupThresholdChunks = backupThresholdChunks;
			return this;
		}

		/** Safe to change live - takes effect the next time a backup is pruned. Always floored at 1. */
		public Builder backupRetentionCount(int backupRetentionCount) {
			this.backupRetentionCount = backupRetentionCount;
			return this;
		}

		/** Safe to flip live - takes effect the next time each chunk in the taper ring is (re)processed, not retroactively. */
		public Builder taperEnabled(boolean taperEnabled) {
			this.taperEnabled = taperEnabled;
			return this;
		}

		/** Safe to change live - takes effect the next time each chunk in the taper ring is (re)processed, not retroactively. Always floored at 1. */
		public Builder taperWidthChunks(int taperWidthChunks) {
			this.taperWidthChunks = taperWidthChunks;
			return this;
		}

		/**
		 * Safe to flip live - only affects natural ocean ruins discovered
		 * from this point forward, same non-retroactive posture as {@link
		 * #shipwrecksRule}. {@code FORCE_ON} ("Always") also queues the same
		 * single-nearby-placement pass a region's own Always rule uses.
		 */
		public Builder naturalOceanRuinsRule(StructureOverride naturalOceanRuinsRule) {
			this.naturalOceanRuinsRule = naturalOceanRuinsRule;
			return this;
		}

		/** Safe to flip live - only affects buried treasure discovered from this point forward. No "Always" forced-placement pass exists for this kind - see {@code ModStarterStructures}' class doc. */
		public Builder buriedTreasureRule(StructureOverride buriedTreasureRule) {
			this.buriedTreasureRule = buriedTreasureRule;
			return this;
		}

		/**
		 * Safe to flip live - only affects ocean monuments carved from this
		 * point forward. {@code FORCE_ON} ("Always") also queues the same
		 * procedural-monument forcing pass a region's own Always rule uses,
		 * scoped to whatever chunks the next pregen/rewipe job touches.
		 */
		public Builder naturalOceanMonumentsRule(StructureOverride naturalOceanMonumentsRule) {
			this.naturalOceanMonumentsRule = naturalOceanMonumentsRule;
			return this;
		}

		/** Safe to flip live - only affects ruined portals carved from this point forward. No "Always" forced-placement pass exists for this kind - see {@code ModStarterStructures}' class doc. */
		public Builder naturalRuinedPortalsRule(StructureOverride naturalRuinedPortalsRule) {
			this.naturalRuinedPortalsRule = naturalRuinedPortalsRule;
			return this;
		}

		/**
		 * Builds the config in memory only - no disk I/O, no Fabric Loader
		 * dependency, and the live {@link OceanCanvasConfig#get()}
		 * singleton is left untouched. Used by {@link #save()} internally,
		 * and directly by unit tests (which don't run inside a Fabric
		 * environment, so {@link FabricLoader#getInstance()} isn't
		 * available - matching the constraint the rest of this test suite
		 * already works within via {@link OceanCanvasConfig#of}).
		 */
		public OceanCanvasConfig build() {
			return new OceanCanvasConfig(
					canvasSize, centerX, centerZ, expansionEnabled,
					oceanFloorY, oceanFloorVariation, oceanFloorTransitionThickness,
					pregenEnabled, pregenChunksPerTick, foreverWorldTargetHours, biomeMaskEnabled, biomeMaskBiome,
					guaranteeSpawnOceanRuin, hudEnabled, worldBorderSyncEnabled, undoDepthPerPlayer,
					shipwrecksRule, journeyMapOverlayEnabled,
					flattenerChunksPerTick, backupEnabled, backupThresholdChunks, backupRetentionCount,
					taperEnabled, taperWidthChunks, naturalOceanRuinsRule,
					buriedTreasureRule, naturalOceanMonumentsRule, naturalRuinedPortalsRule);
		}

		/** Persists this config and makes it the new live {@link OceanCanvasConfig#get()} value. */
		public OceanCanvasConfig save() {
			OceanCanvasConfig config = build();
			config.save(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
			synchronized (OceanCanvasConfig.class) {
				instance = config;
			}
			return config;
		}
	}
}
