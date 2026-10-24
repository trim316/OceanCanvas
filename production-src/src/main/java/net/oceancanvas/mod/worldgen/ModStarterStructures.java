package net.oceancanvas.mod.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.util.List;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Guarantees the stranded-in-the-ocean start actually has a shipwreck to
 * find. The project decision (see docs/roadmap.md) is: no starter island,
 * no resource continent - the player wakes up in open ocean and depends
 * entirely on shipwreck loot for their first wood/tools. Vanilla's
 * default shipwreck spacing/separation is left completely untouched (no
 * density change) - this only forces a single shipwreck to exist near
 * spawn specifically, so a bad seed can't strand the player with nothing
 * reachable. Everywhere else in the canvas, shipwrecks generate at
 * ordinary vanilla frequency.
 *
 * <p>Template resource paths and the {@code placeInWorld} signature are
 * confirmed against 26.2 - the guaranteed shipwreck has placed
 * successfully in every in-game test round so far.</p>
 *
 * <p><b>Structure preservation:</b> the actual placed bounding box is
 * registered in {@link ProtectedRegions} right after placement - see
 * that class's doc for why this manual registration is needed at all
 * (a direct paste doesn't go through real world generation, so vanilla's
 * own structure manager never learns about it). {@code getSize()}/
 * {@code BoundingBox.fromCorners} are reasonable-confidence guesses, not
 * confirmed against 26.2.</p>
 *
 * <p><b>Paste height matches the canvas floor, not the original
 * terrain:</b> computed with the exact same {@code
 * OceanCanvasConfig.oceanFloorY()} + {@code
 * OceanCanvasSurfaceFlattener#floorOffset} calculation the flattener
 * itself uses, so a guaranteed structure always rests exactly on the
 * real floor from the moment it's placed. Naturally-generated shipwrecks
 * elsewhere in the canvas don't get this treatment automatically - they
 * remain resting at whatever height they naturally generated at unless
 * relocated (see {@code OceanCanvasSurfaceFlattener#relocateShipwreckIfNeeded}).</p>
 *
 * <p><b>Generalized this round into a small, reusable mechanism
 * ({@link #ensureGuaranteedStructure}) instead of shipwreck-only logic,
 * per the brainstormed "configurable starter-structure list" idea -
 * scoped honestly, not overpromised:</b> this direct-template-paste
 * approach only works for structures vanilla itself builds from a pool
 * of simple, mostly-self-contained NBT templates - shipwrecks and ocean
 * ruins are both this kind (confirmed from real, longstanding vanilla
 * structure design, not guessed). It does NOT generalize to jigsaw-based
 * structures (villages, pillager outposts, bastion remnants, ancient
 * cities, trial chambers) - those are procedurally assembled from many
 * interlocking pieces via a completely different placement system, and
 * forcing one to exist at an arbitrary fixed point without going through
 * real jigsaw generation would be a fundamentally different, much larger
 * undertaking, not a parameterization of this class. If a village (or
 * similar) near spawn is ever wanted, it needs its own design, not this
 * mechanism stretched to fit.</p>
 *
 * <p>The second example enabled by this generalization -
 * {@link OceanCanvasConfig#guaranteeSpawnOceanRuin()}, off by default -
 * exercises the generalized path with a real second structure type
 * rather than leaving it untested as pure theory.</p>
 */
public final class ModStarterStructures {

	/** Distance from spawn (in blocks) to force the guaranteed shipwreck. */
	private static final int SPAWN_OFFSET_BLOCKS = 96;

	/** A little further out than the shipwreck, purely so the two don't have a chance of overlapping. */
	private static final int OCEAN_RUIN_SPAWN_OFFSET_BLOCKS = 160;

	private static final List<Identifier> SHIPWRECK_TEMPLATES = List.of(
			Identifier.withDefaultNamespace("shipwreck/rightsideup_full"),
			Identifier.withDefaultNamespace("shipwreck/rightsideup_fronthalf"),
			Identifier.withDefaultNamespace("shipwreck/with_mast")
	);

	/**
	 * Real vanilla ocean ruin template ids, same "pool of templates, try
	 * each until one loads" pattern as {@link #SHIPWRECK_TEMPLATES} -
	 * grounded in vanilla's longstanding warm/cold ocean ruin naming
	 * convention, not confirmed against this specific 26.2 build's exact
	 * available set (flagging honestly, same standard as the rest of this
	 * project's unconfirmed-API notes).
	 */
	private static final List<Identifier> OCEAN_RUIN_TEMPLATES = List.of(
			Identifier.withDefaultNamespace("ocean_ruin/warm_1"),
			Identifier.withDefaultNamespace("ocean_ruin/warm_2"),
			Identifier.withDefaultNamespace("ocean_ruin/cold_1"),
			Identifier.withDefaultNamespace("ocean_ruin/cold_2")
	);

	/**
	 * Runtime-owned staged structure work.  The queues used to be process-global static
	 * deques containing strong {@link ServerLevel} references, so an integrated-server
	 * world switch could mix progress from two server sessions or retain a stopped world
	 * until the next explicit clear.  Keep exactly one small scheduler session per live
	 * MinecraftServer instead.  The weak keys are a final retention guard; normal cleanup
	 * still happens deterministically from SERVER_STOPPING.
	 */
	private static final java.util.Map<net.minecraft.server.MinecraftServer, StructureTaskSession> TASK_SESSIONS =
			java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private static final class StructureTaskSession {
		private final Deque<ForcedMonumentTask> monuments = new ArrayDeque<>();
		private final Deque<ForcedShipwreckTask> shipwrecks = new ArrayDeque<>();
	}

	private static StructureTaskSession taskSession(ServerLevel world) {
		return taskSession(world.getServer());
	}

	private static StructureTaskSession taskSession(net.minecraft.server.MinecraftServer server) {
		synchronized (TASK_SESSIONS) {
			return TASK_SESSIONS.computeIfAbsent(server, ignored -> new StructureTaskSession());
		}
	}

	private static StructureTaskSession existingTaskSession(net.minecraft.server.MinecraftServer server) {
		synchronized (TASK_SESSIONS) {
			return TASK_SESSIONS.get(server);
		}
	}

	private static void clearTaskSession(net.minecraft.server.MinecraftServer server) {
		synchronized (TASK_SESSIONS) {
			TASK_SESSIONS.remove(server);
		}
	}

	// Vanilla Java random-spread placement values for the shipwreck structure set.
	// Keep these together and version-audit them when Minecraft's data pack changes.
	private static final int SHIPWRECK_SPACING_CHUNKS = 24;
	private static final int SHIPWRECK_SEPARATION_CHUNKS = 4;
	private static final long SHIPWRECK_PLACEMENT_SALT = 165745295L;
	private static final long REGION_X_MULTIPLIER = 341873128712L;
	private static final long REGION_Z_MULTIPLIER = 132897987541L;

	/** Lightweight read-only bridge used by PregenManager so the same Rewipe
	 * boss bar remains visible while staged FORCE_ON structure work is running. */
	public record ForcedStructureProgress(String stage, int completedChunks, int totalChunks) {}

	public static java.util.Optional<ForcedStructureProgress> forcedStructureProgress(ServerLevel world,
			java.util.Set<String> zoneNames) {
		if (world == null || zoneNames == null || zoneNames.isEmpty()) return java.util.Optional.empty();
		var vanillaProgress = OceanCanvasVanillaStructureForcer.progress(world, zoneNames);
		if (vanillaProgress.isPresent()) {
			var p = vanillaProgress.get();
			return java.util.Optional.of(new ForcedStructureProgress(p.stage(), p.completed(), p.total()));
		}
		StructureTaskSession session = existingTaskSession(world.getServer());
		if (session == null) return java.util.Optional.empty();
		synchronized (session) {
			for (ForcedShipwreckTask task : session.shipwrecks) {
				if (task.world != world || !zoneNames.contains(task.zoneName)) continue;
				return java.util.Optional.of(new ForcedStructureProgress(
						task.stageName(), task.completedCells(), task.totalCells()));
			}
			int completed = 0;
			int total = 0;
			String stage = "Ocean monument";
			boolean stageSet = false;
			for (ForcedMonumentTask task : session.monuments) {
				if (task.world != world || !zoneNames.contains(task.zoneName)) continue;
				completed += task.completedChunks();
				total += task.totalChunks();
				if (!stageSet) { stage = task.stageName(); stageSet = true; }
			}
			return total == 0 ? java.util.Optional.empty()
					: java.util.Optional.of(new ForcedStructureProgress(stage, completed, total));
		}
	}

	/** Backward-compatible alias for older internal source references; progress is world-scoped. */
	public static java.util.Optional<ForcedStructureProgress> forcedMonumentProgress(ServerLevel world,
			java.util.Set<String> zoneNames) {
		return forcedStructureProgress(world, zoneNames);
	}

	private ModStarterStructures() {
	}

	public static void register() {
		ServerLevelEvents.LOAD.register((server, world) -> {
			if (world.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
				return;
			}
			if (!OceanCanvasConfig.get().isInsideCanvas(0, 0)) {
				return; // spawn isn't even inside the configured canvas - nothing to guarantee
			}

			// Real feature requested directly by the user - see
			// OceanCanvasConfig#shipwrecksEnabled's doc comment. Guards
			// the guaranteed spawn shipwreck specifically; natural
			// shipwreck discovery/relocation is guarded separately in
			// OceanCanvasSurfaceFlattener's discoverAndProtectShipwreck,
			// which checks the same flag.
			if (OceanCanvasConfig.get().shipwrecksRule() != net.oceancanvas.mod.config.StructureOverride.FORCE_OFF) {
				ensureGuaranteedStructure(world, "shipwreck", SHIPWRECK_TEMPLATES, SPAWN_OFFSET_BLOCKS,
						data -> data.isGuaranteedShipwreckPlaced(), data -> data.markGuaranteedShipwreckPlaced());
			}

			// Off by default - see OceanCanvasConfig#guaranteeSpawnOceanRuin's
			// doc comment. Exercises the generalized mechanism above with a
			// genuinely different structure type instead of leaving it
			// untested.
			if (OceanCanvasConfig.get().guaranteeSpawnOceanRuin()) {
				ensureGuaranteedStructure(world, "ocean_ruin", OCEAN_RUIN_TEMPLATES, OCEAN_RUIN_SPAWN_OFFSET_BLOCKS,
						data -> data.isStarterStructurePlaced("ocean_ruin"),
						data -> data.markStarterStructurePlaced("ocean_ruin"));
			}
		});

		// A vanilla monument touches several chunks and performs a large amount of
		// block work. Process only one monument chunk per server tick instead of
		// freezing the integrated server at the end of a Reset.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			OceanCanvasVanillaStructureForcer.tick(server);
			OceanCanvasStructureMetadataCleaner.tick(server);
			StructureTaskSession session = existingTaskSession(server);
			if (session != null) {
				tickForcedShipwrecks(session);
				tickForcedMonuments(session);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			OceanCanvasStructureIntegrity.clear();
			OceanCanvasVanillaStructureForcer.clear(server);
			OceanCanvasStructureMetadataCleaner.clear(server);
			clearTaskSession(server);
		});
	}

	/**
	 * Applies FORCE_ON semantics after an explicit region Rewipe. Natural world
	 * generation cannot retroactively invent a structure in chunks that already
	 * existed, so template-backed ocean structures need one explicit placement
	 * opportunity after the destructive pass has finished.
	 *
	 * <p>Template-backed shipwrecks and ocean ruins are pasted directly. Ocean
	 * monuments take a separate path through vanilla's procedural MonumentBuilding
	 * generator and are then relocated onto the OceanCanvas floor.</p>
	 */
	public static void ensureForcedStructuresForRewipeRegion(ServerLevel world,
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone) {
		ensureForcedStructuresForRegion(world, zone, java.util.Map.of(), "Rewipe");
	}

	/**
	 * v233: operation-aware region forcing. A region's explicit rule wins; otherwise
	 * the staged rule captured by the running operation wins; otherwise the world
	 * default applies. This is intentionally the same precedence used by carve-time
	 * {@code OceanCanvasPlayerZones#structureOverrideAt}. Before this method existed,
	 * the destructive carve could honor a staged Always while post-processing silently
	 * consulted only the persisted region rule, leaving the requested population absent.
	 */
	public static void ensureForcedStructuresForRegion(ServerLevel world,
			OceanCanvasPlayerZones.Zone zone,
			java.util.Map<OceanCanvasStructureKind, net.oceancanvas.mod.config.StructureOverride> stagedRules,
			String operationLabel) {
		if (world == null || zone == null) return;
		java.util.Map<OceanCanvasStructureKind, net.oceancanvas.mod.config.StructureOverride> staged =
				stagedRules == null ? java.util.Map.of() : stagedRules;
		for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
			net.oceancanvas.mod.config.StructureOverride resolved = resolvedOperationRule(zone, kind, staged);
			if (resolved != net.oceancanvas.mod.config.StructureOverride.FORCE_ON) continue;
			java.util.List<OceanCanvasPlayerZones.Zone> exclusions = higherPrecedenceNeverZones(world, zone, kind);
			switch (kind) {
				case SHIPWRECK, OCEAN_RUIN, BURIED_TREASURE, RUINED_PORTAL ->
						OceanCanvasVanillaStructureForcer.queue(world, zone, kind, exclusions, operationLabel);
				case OCEAN_MONUMENT -> ensureOceanMonumentInsideZone(world, zone, exclusions);
			}
		}
	}

	/**
	 * Mirrors structureOverrideAt's insertion-order precedence for post-operation forcing.
	 * An inherited/staged Always loses anywhere any explicit Never region applies. An
	 * explicit Always only loses to explicit Never regions defined before it; later
	 * regions cannot override it because the normal lookup would have returned the
	 * target's explicit rule first. ForceOn overlaps need no exclusion because both
	 * sides request the same result.
	 */
	private static java.util.List<OceanCanvasPlayerZones.Zone> higherPrecedenceNeverZones(
			ServerLevel world, OceanCanvasPlayerZones.Zone target, OceanCanvasStructureKind kind) {
		java.util.List<OceanCanvasPlayerZones.Zone> out=new java.util.ArrayList<>();
		boolean targetExplicit=target.overrideFor(kind)!=net.oceancanvas.mod.config.StructureOverride.INHERIT;
		for(OceanCanvasPlayerZones.Zone candidate:OceanCanvasPlayerZones.get(world).all()) {
			boolean isTarget=candidate.name().equalsIgnoreCase(target.name());
			if(isTarget && targetExplicit) break;
			if(isTarget) continue;
			if(candidate.overrideFor(kind)==net.oceancanvas.mod.config.StructureOverride.FORCE_OFF) out.add(candidate);
		}
		return java.util.List.copyOf(out);
	}

	private static net.oceancanvas.mod.config.StructureOverride resolvedOperationRule(
			OceanCanvasPlayerZones.Zone zone, OceanCanvasStructureKind kind,
			java.util.Map<OceanCanvasStructureKind, net.oceancanvas.mod.config.StructureOverride> stagedRules) {
		net.oceancanvas.mod.config.StructureOverride explicit = zone.overrideFor(kind);
		if (explicit != net.oceancanvas.mod.config.StructureOverride.INHERIT) return explicit;
		net.oceancanvas.mod.config.StructureOverride staged = stagedRules.getOrDefault(
				kind, net.oceancanvas.mod.config.StructureOverride.INHERIT);
		if (staged != net.oceancanvas.mod.config.StructureOverride.INHERIT) return staged;
		return kind.globalDefault(OceanCanvasConfig.get());
	}


	/**
	 * v77 FORCE_ON semantics for shipwrecks.
	 *
	 * <p>"Always" no longer means "paste one ship in the middle if none can be
	 * found". It means Ocean Canvas walks the same random-spread grid vanilla uses
	 * for shipwreck placement (24 chunk spacing, 4 chunk separation, vanilla salt),
	 * derives the deterministic candidate chunk from the world seed, intersects
	 * those candidates with the exact Canvas region, and ensures a shipwreck is
	 * represented at each eligible candidate.</p>
	 *
	 * <p>The scan is over placement CELLS, not every selected chunk. This is
	 * important at 20k scale: a 20k square is ~1.56M chunks but only a few thousand
	 * shipwreck placement cells. The work is then tick-staged so template placement
	 * never creates one enormous server-thread burst.</p>
	 */
	private static void queueVanillaDistributedShipwrecks(ServerLevel world,
			OceanCanvasPlayerZones.Zone zone) {
		queueVanillaDistributedShipwrecks(world, zone, List.of());
	}

	/**
	 * v125: the world-wide "Always" default (see {@link #ensureWorldDefaultForcedStructures})
	 * reuses this exact same cell-distribution pass, scoped to whatever chunks the current
	 * pregen/rewipe/expand job covers - but a region's own explicit rule must still always win
	 * over the world default, even inside that job's own bounds. {@code excludedZones} is checked
	 * once per placement CELL (a few thousand at most, even at full canvas scale - see this
	 * method's own class doc), never by materializing a chunk-level exclusion set, so the
	 * world-wide pass keeps the same "safe at 20k-canvas scale" property the region-only pass
	 * always had. Empty for a real region's own FORCE_ON pass (nothing to exclude from itself).
	 */
	private static void queueVanillaDistributedShipwrecks(ServerLevel world,
			OceanCanvasPlayerZones.Zone zone, List<OceanCanvasPlayerZones.Zone> excludedZones) {
		StructureTaskSession session = taskSession(world);
		ForcedShipwreckTask task;
		synchronized (session) {
			// Check before constructing the task: its constructor begins the integrity
			// ledger, so constructing a duplicate used to erase live progress.
			for (ForcedShipwreckTask pending : session.shipwrecks) {
				if (pending.world == world && pending.zoneName.equals(zone.name())) return;
			}
			task = new ForcedShipwreckTask(world, zone, excludedZones);
			if (task.totalCells() > 0) session.shipwrecks.addLast(task);
		}
		if (task.totalCells() <= 0) {
			OceanCanvasStructureIntegrity.complete(zone.name(), OceanCanvasStructureKind.SHIPWRECK);
			return;
		}
		OceanCanvas.LOGGER.info(
				"Queued vanilla-distribution FORCE_ON shipwreck pass for region '{}' ({} placement cells)",
				zone.name(), task.totalCells());
	}

	/**
	 * Runs the exact same FORCE_ON shipwreck distribution after Pregen that
	 * Rewipe already uses. Pregen normally preserves real vanilla starts, but an
	 * Always rule is stronger than preservation: every deterministic placement
	 * cell intersecting the selected region must be represented even when vanilla
	 * generation did not leave a usable start behind.
	 */
	public static void ensureForcedShipwrecksForPregenRegion(ServerLevel world,
			OceanCanvasPlayerZones.Zone zone) {
		ensureForcedStructuresForRegion(world, zone, java.util.Map.of(), "Pregen");
	}

	/** Preferred v233 entry point; retained shipwreck-named alias above for source compatibility. */
	public static void ensureForcedStructuresForPregenRegion(ServerLevel world,
			OceanCanvasPlayerZones.Zone zone,
			java.util.Map<OceanCanvasStructureKind, net.oceancanvas.mod.config.StructureOverride> stagedRules) {
		ensureForcedStructuresForRegion(world, zone, stagedRules, "Pregen");
	}

	/**
	 * Above this many chunks, a world-wide (not region-scoped) "Always" pass for ocean ruins or
	 * ocean monuments is skipped with a warning rather than attempted. Shipwreck forcing has no
	 * such cap - {@link ForcedShipwreckTask} scans placement CELLS (24-chunk spacing), a few
	 * thousand even at full 20k-canvas scale, never the raw chunk count. Ocean ruin and monument
	 * forcing are different: {@link #ensureTemplateInsideZone} calls {@code zone.exactChunks()} to
	 * pick a placement chunk, and {@link ForcedMonumentTask}'s constructor does too for its spiral
	 * search - both eagerly materialize every chunk in the zone when it has no explicit polygon,
	 * which is fine for an individually hand-drawn region (self-limiting - nobody draws a
	 * million-chunk region by hand) but would be a real, synchronous, single-tick cost if a
	 * one-click world-wide "Always" could reach that same scale as easily as clicking "confirm" on
	 * a large rewipe. This cap keeps the world-wide trigger from being an easy way to hit a cost
	 * the underlying mechanism was never actually verified to be safe at - see docs/roadmap.md's
	 * v125 entry. A region drawn with its own explicit Always rule is NOT capped by this - it goes
	 * through {@link #ensureForcedStructuresForRewipeRegion} unchanged, exactly as before.
	 */
	private static final long WORLD_WIDE_TEMPLATE_FORCE_MAX_CHUNKS = 50_000L;

	/**
	 * The world-wide counterpart to {@link #ensureForcedStructuresForRewipeRegion}/{@link
	 * #ensureForcedShipwrecksForPregenRegion} - v125's world-default "Always" queues the same
	 * forced-placement passes those two already use for a real drawn region, scoped instead to
	 * whatever chunks a plain (non-region) pregen/rewipe/expand job just processed.
	 *
	 * <p>A region's own explicit rule for a kind always wins over the world default, exactly as
	 * {@code OceanCanvasPlayerZones#structureOverrideAt} already resolves it at carve time - real
	 * drawn zones overlapping the job with a non-INHERIT rule for a kind are excluded from that
	 * kind's world-wide pass rather than double-processed or, worse, overridden. For shipwrecks
	 * this exclusion is checked per placement cell (cheap - see {@link ForcedShipwreckTask}); for
	 * ocean ruins and monuments it is applied by pre-computing an explicit excluded-chunk set
	 * before the pass starts, since both of those already require the zone's full chunk set
	 * internally regardless (see {@link #WORLD_WIDE_TEMPLATE_FORCE_MAX_CHUNKS}'s doc).
	 *
	 * <p>Buried treasure and ruined portals have no "Always" forced-placement pass at world scope,
	 * matching their existing region-level scope - see {@code OceanCanvasStructureKind}'s class doc
	 * for which kinds this mechanism can and can't be used for.</p>
	 *
	 * @param jobLabel a short, log-friendly description of the calling job (e.g. its kind and a
	 *        chunk-range summary) - never shown to players, only ever appears in the server log
	 *        and as this pass's synthetic "zone" name.
	 */
	public static void ensureWorldDefaultForcedStructures(ServerLevel world, String jobLabel,
			int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		ensureWorldDefaultForcedStructures(world, jobLabel, minChunkX, maxChunkX, minChunkZ, maxChunkZ, java.util.Map.of());
	}

	public static void ensureWorldDefaultForcedStructures(ServerLevel world, String jobLabel,
			int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
			java.util.Map<OceanCanvasStructureKind, net.oceancanvas.mod.config.StructureOverride> stagedRules) {
		if (world == null || minChunkX > maxChunkX || minChunkZ > maxChunkZ) return;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		java.util.Map<OceanCanvasStructureKind, net.oceancanvas.mod.config.StructureOverride> staged =
				stagedRules == null ? java.util.Map.of() : stagedRules;
		java.util.function.Function<OceanCanvasStructureKind, net.oceancanvas.mod.config.StructureOverride> effective = kind -> {
			net.oceancanvas.mod.config.StructureOverride rule = staged.getOrDefault(kind, net.oceancanvas.mod.config.StructureOverride.INHERIT);
			return rule == net.oceancanvas.mod.config.StructureOverride.INHERIT ? kind.globalDefault(config) : rule;
		};

		for (OceanCanvasStructureKind kind : java.util.List.of(
				OceanCanvasStructureKind.SHIPWRECK, OceanCanvasStructureKind.OCEAN_RUIN,
				OceanCanvasStructureKind.BURIED_TREASURE, OceanCanvasStructureKind.RUINED_PORTAL)) {
			if (effective.apply(kind) != net.oceancanvas.mod.config.StructureOverride.FORCE_ON) continue;
			OceanCanvasPlayerZones.Zone synthetic = worldWideRectangularZone(
					jobLabel + "-" + kind.id(), kind, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
			OceanCanvasVanillaStructureForcer.queue(world, synthetic, kind,
					excludedZonesFor(world, kind, minChunkX, maxChunkX, minChunkZ, maxChunkZ), "World operation");
		}
		if (effective.apply(OceanCanvasStructureKind.OCEAN_MONUMENT) == net.oceancanvas.mod.config.StructureOverride.FORCE_ON) {
			// v237: the strict monument engine no longer materializes the job's full chunk set,
			// so the historical 50k-chunk template safety cap is obsolete for the active path.
			// A 20k Canvas can therefore honor world-default Monument=Always using the same
			// sparse active-placement candidate enumeration as every other managed kind.
			OceanCanvasPlayerZones.Zone synthetic = worldWideRectangularZone(
					jobLabel + "-ocean_monument", OceanCanvasStructureKind.OCEAN_MONUMENT,
					minChunkX, maxChunkX, minChunkZ, maxChunkZ);
			ensureOceanMonumentInsideZone(world, synthetic,
					excludedZonesFor(world, OceanCanvasStructureKind.OCEAN_MONUMENT,
							minChunkX, maxChunkX, minChunkZ, maxChunkZ));
		}
	}

	/** A cheap, purely rectangular (no explicit chunk set) synthetic zone - safe at any size, used for shipwreck forcing. */
	private static OceanCanvasPlayerZones.Zone worldWideRectangularZone(String name, OceanCanvasStructureKind kind,
			int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		BoundingBox bounds = new BoundingBox(minChunkX * 16, 0, minChunkZ * 16,
				maxChunkX * 16 + 15, 255, maxChunkZ * 16 + 15);
		return new OceanCanvasPlayerZones.Zone(name, bounds, java.util.Set.of(), java.util.List.of(), false, null, "",
				java.util.Map.of(kind, net.oceancanvas.mod.config.StructureOverride.FORCE_ON), null, false, null);
	}

	/**
	 * Builds a synthetic zone with an EXPLICIT chunk set (job rectangle minus any real zone's own
	 * non-INHERIT rule for {@code kind}) for the ocean-ruin/monument paths, which already need the
	 * full chunk set internally regardless (see {@link #WORLD_WIDE_TEMPLATE_FORCE_MAX_CHUNKS}'s
	 * doc). Returns {@code null} (meaning "skip this pass") when the job exceeds that safety cap,
	 * or when exclusions leave nothing left to force.
	 */
	private static OceanCanvasPlayerZones.Zone worldWideZoneWithExclusions(ServerLevel world, String name,
			OceanCanvasStructureKind kind, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		long totalChunks = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
		if (totalChunks > WORLD_WIDE_TEMPLATE_FORCE_MAX_CHUNKS) {
			OceanCanvas.LOGGER.warn(
					"World-wide Always for {} skipped for this operation - {} chunks exceeds the {}-chunk safety cap "
							+ "(see ModStarterStructures#WORLD_WIDE_TEMPLATE_FORCE_MAX_CHUNKS's doc). Draw a region and set "
							+ "its own Always rule instead if you need this at full-canvas scale.",
					kind.displayName(), totalChunks, WORLD_WIDE_TEMPLATE_FORCE_MAX_CHUNKS);
			return null;
		}
		java.util.Set<Long> chunks = new java.util.LinkedHashSet<>();
		for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
			for (int cx = minChunkX; cx <= maxChunkX; cx++) {
				chunks.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
			}
		}
		for (OceanCanvasPlayerZones.Zone excluded : excludedZonesFor(world, kind, minChunkX, maxChunkX, minChunkZ, maxChunkZ)) {
			chunks.removeAll(excluded.exactChunks());
		}
		if (chunks.isEmpty()) return null;
		BoundingBox bounds = new BoundingBox(minChunkX * 16, 0, minChunkZ * 16,
				maxChunkX * 16 + 15, 255, maxChunkZ * 16 + 15);
		return new OceanCanvasPlayerZones.Zone(name, bounds, chunks, java.util.List.of(), false, null, "",
				java.util.Map.of(kind, net.oceancanvas.mod.config.StructureOverride.FORCE_ON), null, false, null);
	}

	/** Every real drawn zone overlapping the job bounds that has its own explicit (non-Default) rule for {@code kind} - "region always wins over world default". */
	private static java.util.List<OceanCanvasPlayerZones.Zone> excludedZonesFor(ServerLevel world,
			OceanCanvasStructureKind kind, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		java.util.List<OceanCanvasPlayerZones.Zone> excluded = new java.util.ArrayList<>();
		int minX = minChunkX * 16, maxX = maxChunkX * 16 + 15, minZ = minChunkZ * 16, maxZ = maxChunkZ * 16 + 15;
		for (OceanCanvasPlayerZones.Zone real : OceanCanvasPlayerZones.get(world).all()) {
			if (real.overrideFor(kind) == net.oceancanvas.mod.config.StructureOverride.INHERIT) continue;
			BoundingBox b = real.bounds();
			boolean overlaps = b.minX() <= maxX && b.maxX() >= minX && b.minZ() <= maxZ && b.maxZ() >= minZ;
			if (overlaps) excluded.add(real);
		}
		return excluded;
	}

	private static void tickForcedShipwrecks(StructureTaskSession session) {
		ForcedShipwreckTask task;
		synchronized (session) { task = session.shipwrecks.peekFirst(); }
		if (task == null) return;
		try {
			if (!task.tick()) return;
			removeShipwreckTask(session, task);
		} catch (RuntimeException ex) {
			OceanCanvasStructureIntegrity.placementFailure(task.zoneName, OceanCanvasStructureKind.SHIPWRECK);
			net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.record(
					"structures.shipwreck",
					"Forced shipwreck distribution aborted for region '" + task.zoneName + "' at cell "
							+ task.completedCells() + "/" + task.totalCells(), ex);
			OceanCanvas.LOGGER.error(
					"Forced shipwreck distribution failed; aborting region '{}' at placement cell {}/{}",
					task.zoneName, task.completedCells(), task.totalCells(), ex);
			removeShipwreckTask(session, task);
		}
	}

	private static void removeShipwreckTask(StructureTaskSession session, ForcedShipwreckTask task) {
		boolean lastForScope;
		synchronized (session) {
			if (session.shipwrecks.peekFirst() == task) session.shipwrecks.removeFirst();
			else session.shipwrecks.remove(task);
			lastForScope = session.shipwrecks.stream()
					.noneMatch(t -> t.world == task.world && t.zoneName.equals(task.zoneName));
		}
		if (lastForScope) OceanCanvasStructureIntegrity.complete(task.zoneName, OceanCanvasStructureKind.SHIPWRECK);
	}

	private static final class ForcedShipwreckTask {
		private static final int CELLS_PER_TICK = 12;

		private final ServerLevel world;
		private final OceanCanvasPlayerZones.Zone zone;
		private final String zoneName;
		private final List<OceanCanvasPlayerZones.Zone> excludedZones;
		private final int minRegionX;
		private final int maxRegionX;
		private final int minRegionZ;
		private final int maxRegionZ;
		private final int width;
		private final int totalCells;
		private int nextCell;
		private int placed;
		private int preservedNatural;
		private int alreadyProtected;
		private int excludedByOtherZone;

		private ForcedShipwreckTask(ServerLevel world, OceanCanvasPlayerZones.Zone zone,
				List<OceanCanvasPlayerZones.Zone> excludedZones) {
			this.world = world;
			this.zone = zone;
			this.zoneName = zone.name();
			this.excludedZones = excludedZones == null ? List.of() : excludedZones;
			int minChunkX = Math.floorDiv(zone.bounds().minX(), 16);
			int maxChunkX = Math.floorDiv(zone.bounds().maxX(), 16);
			int minChunkZ = Math.floorDiv(zone.bounds().minZ(), 16);
			int maxChunkZ = Math.floorDiv(zone.bounds().maxZ(), 16);
			this.minRegionX = Math.floorDiv(minChunkX, SHIPWRECK_SPACING_CHUNKS);
			this.maxRegionX = Math.floorDiv(maxChunkX, SHIPWRECK_SPACING_CHUNKS);
			this.minRegionZ = Math.floorDiv(minChunkZ, SHIPWRECK_SPACING_CHUNKS);
			this.maxRegionZ = Math.floorDiv(maxChunkZ, SHIPWRECK_SPACING_CHUNKS);
			this.width = Math.max(0, maxRegionX - minRegionX + 1);
			long cells = (long) width * Math.max(0, maxRegionZ - minRegionZ + 1);
			this.totalCells = (int) Math.min(Integer.MAX_VALUE, cells);
			OceanCanvasStructureIntegrity.begin(zoneName, OceanCanvasStructureKind.SHIPWRECK, "Forced distribution", totalCells);
		}

		private int totalCells() { return totalCells; }
		private int completedCells() { return Math.min(nextCell, totalCells); }
		private String stageName() { return "Shipwreck distribution"; }

		private boolean tick() {
			int budget = CELLS_PER_TICK;
			while (budget-- > 0 && nextCell < totalCells) {
				int index = nextCell++;
				int regionX = minRegionX + (index % width);
				int regionZ = minRegionZ + (index / width);
				if (!processCell(regionX, regionZ)) {
					// v197: candidate chunk was not resident yet - undo the
					// increment so the exact same cell is retried on a later
					// tick, and stop consuming this tick's budget on a chunk
					// that's still loading (matches the retry shape used
					// elsewhere in this file, e.g. tickRegister below).
					nextCell--;
					break;
				}
			}
			if (nextCell < totalCells) return false;
			OceanCanvas.LOGGER.info(
					"[OceanCanvas][Structure:{}] shipwreck distribution complete: {} synthetic placed, {} vanilla starts retained, "
							+ "{} existing protected placements retained, {} deferred to another zone's own rule, across {} placement cells",
					zoneName, placed, preservedNatural, alreadyProtected, excludedByOtherZone, totalCells);
			return true;
		}

		private boolean processCell(int regionX, int regionZ) {
			int window = SHIPWRECK_SPACING_CHUNKS - SHIPWRECK_SEPARATION_CHUNKS;
			long placementSeed = world.getSeed()
					+ (long) regionX * REGION_X_MULTIPLIER
					+ (long) regionZ * REGION_Z_MULTIPLIER
					+ SHIPWRECK_PLACEMENT_SALT;
			java.util.Random random = new java.util.Random(placementSeed);
			int candidateChunkX = regionX * SHIPWRECK_SPACING_CHUNKS + random.nextInt(window);
			int candidateChunkZ = regionZ * SHIPWRECK_SPACING_CHUNKS + random.nextInt(window);

			int centerX = candidateChunkX * 16 + 8;
			int centerZ = candidateChunkZ * 16 + 8;
			if (!zone.contains(centerX, world.getSeaLevel(), centerZ)) return true;

			// A world-wide "Always" pass must still defer to any real drawn region's own
			// explicit rule for this kind, exactly as OceanCanvasPlayerZones#structureOverrideAt
			// already resolves it at carve time - see ensureWorldDefaultForcedStructures' doc.
			// Empty for a real region's own FORCE_ON pass, so this is a no-op there.
			for (OceanCanvasPlayerZones.Zone excluded : excludedZones) {
				if (excluded.contains(centerX, world.getSeaLevel(), centerZ)) {
					excludedByOtherZone++;
					OceanCanvasStructureIntegrity.excluded(zoneName, OceanCanvasStructureKind.SHIPWRECK);
					return true;
				}
			}

			// If the real vanilla start survived/was relocated by the FORCE_ON carve
			// path, it already fulfills this placement cell. Do not duplicate it.
			//
			// v197: was an unguarded world.getChunk(...) - the same blocking
			// race documented at flattenChunk's v182 fix. getChunkNow is
			// atomic; a null result means the candidate chunk is not resident
			// yet, so kick off a non-blocking load and ask the caller to
			// retry this exact cell later instead of falling through to the
			// blocking getChunkBlocking path.
			var candidateChunk = world.getChunkSource().getChunkNow(candidateChunkX, candidateChunkZ);
			if (candidateChunk == null) {
				OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world, candidateChunkX, candidateChunkZ);
				return false;
			}
			for (var entry : candidateChunk.getAllStarts().entrySet()) {
				if (entry.getKey() instanceof net.minecraft.world.level.levelgen.structure.structures.ShipwreckStructure
						&& entry.getValue() != null && entry.getValue().isValid()) {
					preservedNatural++;
					OceanCanvasStructureIntegrity.naturalStart(zoneName, OceanCanvasStructureKind.SHIPWRECK);
					return true;
				}
			}

			OceanCanvasConfig config = OceanCanvasConfig.get();
			int floorY = config.oceanFloorY()
					+ OceanCanvasSurfaceFlattener.floorOffset(centerX, centerZ, config.oceanFloorVariation());
			if (ProtectedRegions.isProtected(world, centerX, floorY + 1, centerZ)) {
				alreadyProtected++;
				OceanCanvasStructureIntegrity.protectedExisting(zoneName, OceanCanvasStructureKind.SHIPWRECK);
				return true;
			}

			StructureTemplateManager manager = world.getStructureManager();
			int first = Math.floorMod((int) (placementSeed ^ (placementSeed >>> 32)), SHIPWRECK_TEMPLATES.size());
			for (int attempt = 0; attempt < SHIPWRECK_TEMPLATES.size(); attempt++) {
				Identifier templateId = SHIPWRECK_TEMPLATES.get((first + attempt) % SHIPWRECK_TEMPLATES.size());
				Optional<StructureTemplate> template = manager.get(templateId);
				if (template.isEmpty()) continue;
				net.minecraft.core.Vec3i size = template.get().getSize();
				int pasteX = centerX - size.getX() / 2;
				int pasteZ = centerZ - size.getZ() / 2;
				BlockPos pastePos = new BlockPos(pasteX, floorY, pasteZ);
				if (!footprintInsideZone(zone, pasteX, pasteZ, size.getX(), size.getZ(), world.getSeaLevel())) return true;

				boolean didPlace = template.get().placeInWorld(world, pastePos, pastePos,
						new StructurePlaceSettings(), world.getRandom(), Block.UPDATE_ALL);
				if (!didPlace) continue;
				BlockPos placedMax = pastePos.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
				BoundingBox box = BoundingBox.fromCorners(pastePos, placedMax);
				ProtectedRegions.protect(world, box);
				OceanCanvasProtectedData.get(world).markPendingRevalidation(pastePos, box);
				placed++;
				// Direct template placement is physically present but vanilla has no real
				// StructureStart/reference graph for it yet. Record that honestly as an
				// integrity failure so this path can never masquerade as release-complete.
				OceanCanvasStructureIntegrity.syntheticPlacement(zoneName, OceanCanvasStructureKind.SHIPWRECK, false);
				return true;
			}
			OceanCanvasStructureIntegrity.placementFailure(zoneName, OceanCanvasStructureKind.SHIPWRECK);
			return true;
		}
	}

	private static boolean footprintInsideZone(OceanCanvasPlayerZones.Zone zone,
			int minX, int minZ, int sizeX, int sizeZ, int y) {
		int maxX = minX + Math.max(0, sizeX - 1);
		int maxZ = minZ + Math.max(0, sizeZ - 1);
		int minChunkX = Math.floorDiv(minX, 16);
		int maxChunkX = Math.floorDiv(maxX, 16);
		int minChunkZ = Math.floorDiv(minZ, 16);
		int maxChunkZ = Math.floorDiv(maxZ, 16);
		for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
			for (int cx = minChunkX; cx <= maxChunkX; cx++) {
				if (!zone.contains(cx * 16 + 8, y, cz * 16 + 8)) return false;
			}
		}
		return true;
	}

	/**
	 * FORCE_ON support for ocean monuments. Monuments are procedural rather than
	 * standalone NBT templates, so first ask vanilla's real MonumentBuilding piece
	 * graph to generate the structure at its native Y. We then capture those blocks
	 * and relocate the completed monument onto the configured OceanCanvas floor using
	 * the same block-preserving strategy used for naturally generated monuments.
	 *
	 * <p>The horizontal footprint must fit wholly inside the region's exact chunk
	 * mask. That keeps polygon/brush Rewipe operations honest: FORCE_ON never spills
	 * a monument into chunks the operator did not select.</p>
	 */
	private static void ensureOceanMonumentInsideZone(ServerLevel world,
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone) {
		ensureOceanMonumentInsideZone(world, zone, java.util.List.of());
	}

	private static void ensureOceanMonumentInsideZone(ServerLevel world,
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone,
			java.util.List<OceanCanvasPlayerZones.Zone> excludedZones) {
		/*
		 * v236: monuments use the same active Minecraft placement policy as every
		 * other strict Always kind.  We enumerate only vanilla placement candidates,
		 * never every chunk in a 20k region, then feed each candidate to the staged
		 * monument physical-integration task below.
		 */
		StructureTaskSession session = taskSession(world);
		synchronized (session) {
			// A resumed caller may ask for the same scope while candidates are still
			// staged. Do not reset the integrity ledger underneath the live tasks.
			if (session.monuments.stream().anyMatch(t -> t.world == world && t.zoneName.equals(zone.name()))) return;
		}
		var candidates = OceanCanvasVanillaStructureForcer.discoverCandidates(
				world, zone, OceanCanvasStructureKind.OCEAN_MONUMENT, false);
		OceanCanvasStructureIntegrity.begin(zone.name(), OceanCanvasStructureKind.OCEAN_MONUMENT,
				"Forced monument / active vanilla placement", candidates.size());
		if (candidates.isEmpty()) {
			OceanCanvasStructureIntegrity.complete(zone.name(), OceanCanvasStructureKind.OCEAN_MONUMENT);
			return;
		}
		synchronized (session) {
			for (var candidate : candidates) {
				boolean duplicate = session.monuments.stream().anyMatch(t -> t.world == world
						&& t.zoneName.equals(zone.name()) && t.candidateChunk.equals(candidate.chunk()));
				if (!duplicate) session.monuments.addLast(new ForcedMonumentTask(world, zone, candidate, excludedZones));
			}
		}
		OceanCanvas.LOGGER.info("Queued strict FORCE_ON ocean-monument distribution for region '{}' ({} active vanilla candidates)",
				zone.name(), candidates.size());
	}

	private static void tickForcedMonuments(StructureTaskSession session) {
		ForcedMonumentTask task;
		synchronized (session) { task = session.monuments.peekFirst(); }
		if (task == null) return;
		try {
			if (task.tick()) removeMonumentTask(session, task);
		} catch (RuntimeException ex) {
			OceanCanvasStructureIntegrity.placementFailure(task.zoneName, OceanCanvasStructureKind.OCEAN_MONUMENT);
			task.abortAfterFailure();
			net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.record(
					"structures.monument",
					"Forced ocean monument aborted for region '" + task.zoneName + "' candidate " + task.candidateChunk, ex);
			OceanCanvas.LOGGER.error("Forced ocean monument generation failed; aborting candidate {} for region '{}'", task.candidateChunk, task.zoneName, ex);
			removeMonumentTask(session, task);
		}
	}

	private static void removeMonumentTask(StructureTaskSession session, ForcedMonumentTask task) {
		boolean lastForScope;
		synchronized (session) {
			if (session.monuments.peekFirst() == task) session.monuments.removeFirst();
			else session.monuments.remove(task);
			lastForScope = session.monuments.stream()
					.noneMatch(t -> t.world == task.world && t.zoneName.equals(task.zoneName));
		}
		if (lastForScope) OceanCanvasStructureIntegrity.complete(task.zoneName, OceanCanvasStructureKind.OCEAN_MONUMENT);
	}

	private static final class ForcedMonumentTask {
		private enum Phase { PREPARE, CAPTURE_LAYOUT, FOUNDATION, PLACE_BLOCKS, REGISTER, DONE }
		private static final int CAPTURE_TILE_SIZE = 8;
		private static final int BLOCKS_PER_TICK = 192;
		private static final int FOUNDATION_BLOCKS_PER_TICK = 256;

		private final ServerLevel world;
		private final OceanCanvasPlayerZones.Zone zone;
		private final String zoneName;
		private final OceanCanvasVanillaStructureForcer.Candidate candidate;
		private final net.minecraft.world.level.ChunkPos candidateChunk;
		private final java.util.List<OceanCanvasPlayerZones.Zone> excludedZones;
		private Phase phase = Phase.PREPARE;
		private net.minecraft.world.level.levelgen.structure.StructureStart monumentStart;
		private net.minecraft.world.level.levelgen.structure.Structure monumentStructure;
		private net.minecraft.world.level.levelgen.structure.BoundingBox finalBox;
		private long seed;
		private int buildMinChunkX, buildMaxChunkX, buildMinChunkZ, buildMaxChunkZ;
		private long startRef;
		private int nextRegisterIndex;
		private int captureTilesX, captureTilesZ, nextCaptureIndex;
		private int foundationX, foundationY, foundationZ, foundationMinY;
		private long foundationTotal, foundationDone;
		private final java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState> capturedBlocks = new java.util.LinkedHashMap<>();
		private final java.util.ArrayList<net.minecraft.world.entity.Entity> capturedEntities = new java.util.ArrayList<>();
		private java.util.ArrayList<java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState>> placementEntries;
		private int nextPlacementIndex;

		private ForcedMonumentTask(ServerLevel world, OceanCanvasPlayerZones.Zone zone,
				OceanCanvasVanillaStructureForcer.Candidate candidate,
				java.util.List<OceanCanvasPlayerZones.Zone> excludedZones) {
			this.world=world; this.zone=zone; this.zoneName=zone.name(); this.candidate=candidate; this.candidateChunk=candidate.chunk();
			this.excludedZones=excludedZones==null?java.util.List.of():java.util.List.copyOf(excludedZones);
		}

		private String stageName() { return switch(phase) {
			case PREPARE -> "Preparing monument candidate";
			case CAPTURE_LAYOUT -> "Preparing monument layout";
			case FOUNDATION -> "Building monument foundation";
			case PLACE_BLOCKS -> "Placing monument blocks";
			case REGISTER -> "Registering monument";
			case DONE -> "Finishing monument";
		}; }
		private int totalChunks(){ return switch(phase){
			case PREPARE -> 1;
			case CAPTURE_LAYOUT -> Math.max(1,captureTilesX*captureTilesZ);
			case FOUNDATION -> (int)Math.min(Integer.MAX_VALUE,Math.max(1L,foundationTotal));
			case PLACE_BLOCKS -> Math.max(1,placementEntries==null?capturedBlocks.size():placementEntries.size());
			case REGISTER -> Math.max(1,registerTotalChunks());
			case DONE -> 1;
		}; }
		private int completedChunks(){ return switch(phase){
			case PREPARE -> 0; case CAPTURE_LAYOUT -> nextCaptureIndex;
			case FOUNDATION -> (int)Math.min(Integer.MAX_VALUE,foundationDone);
			case PLACE_BLOCKS -> nextPlacementIndex; case REGISTER -> nextRegisterIndex; case DONE -> 1;
		}; }
		private int registerTotalChunks(){ return finalBox==null?0:(buildMaxChunkX-buildMinChunkX+1)*(buildMaxChunkZ-buildMinChunkZ+1); }
		private boolean tick(){ return switch(phase){
			case PREPARE -> tickPrepare(); case CAPTURE_LAYOUT -> tickCaptureLayout(); case FOUNDATION -> tickFoundation();
			case PLACE_BLOCKS -> tickPlaceBlocks(); case REGISTER -> tickRegister(); case DONE -> true;
		}; }

		private boolean tickPrepare() {
			var ownerChunk=world.getChunkSource().getChunkNow(candidateChunk.x(),candidateChunk.z());
			if(ownerChunk==null){OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world,candidateChunk.x(),candidateChunk.z());return false;}
			for(var holder:candidate.alternatives()) {
				var existing=ownerChunk.getStartForStructure(holder.value());
				if(existing!=null && existing.isValid()) {
					OceanCanvasStructureIntegrity.naturalStart(zoneName,OceanCanvasStructureKind.OCEAN_MONUMENT);
					phase=Phase.DONE; return true;
				}
			}
			var generator=world.getChunkSource().getGenerator(); var state=world.getChunkSource().getGeneratorState();
			boolean threw=false;
			for(var holder:candidate.alternatives()) {
				try {
					var structure=holder.value();
					var generated=structure.generate(holder,world.dimension(),world.registryAccess(),generator,generator.getBiomeSource(),
							state.randomState(),world.getStructureManager(),world.getSeed(),candidateChunk,0,world,structure.biomes()::contains);
					if(generated!=null && generated.isValid()){monumentStart=generated;monumentStructure=structure;break;}
				} catch(RuntimeException ex){threw=true;OceanCanvas.LOGGER.error("Vanilla monument generation threw at {}",candidateChunk,ex);}
			}
			if(monumentStart==null){ if(threw)OceanCanvasStructureIntegrity.placementFailure(zoneName,OceanCanvasStructureKind.OCEAN_MONUMENT); else OceanCanvasStructureIntegrity.excluded(zoneName,OceanCanvasStructureKind.OCEAN_MONUMENT); phase=Phase.DONE;return true; }
			var sourceBox=monumentStart.getBoundingBox();
			int minCX=Math.floorDiv(sourceBox.minX(),16),maxCX=Math.floorDiv(sourceBox.maxX(),16),minCZ=Math.floorDiv(sourceBox.minZ(),16),maxCZ=Math.floorDiv(sourceBox.maxZ(),16);
			for(int cz=minCZ;cz<=maxCZ;cz++)for(int cx=minCX;cx<=maxCX;cx++){
				int bx=cx*16+8,bz=cz*16+8;
				if(!zone.contains(bx,world.getSeaLevel(),bz)){OceanCanvasStructureIntegrity.excluded(zoneName,OceanCanvasStructureKind.OCEAN_MONUMENT);phase=Phase.DONE;return true;}
				for(var excluded:excludedZones)if(excluded.contains(bx,world.getSeaLevel(),bz)){OceanCanvasStructureIntegrity.excluded(zoneName,OceanCanvasStructureKind.OCEAN_MONUMENT);phase=Phase.DONE;return true;}
			}
			int centerX=(sourceBox.minX()+sourceBox.maxX())/2,centerZ=(sourceBox.minZ()+sourceBox.maxZ())/2;
			var cfg=OceanCanvasConfig.get(); int floorY=cfg.oceanFloorY()+OceanCanvasSurfaceFlattener.floorOffset(centerX,centerZ,cfg.oceanFloorVariation());
			int dy=floorY-sourceBox.minY(); for(var piece:monumentStart.getPieces())piece.move(0,dy,0);
			finalBox=monumentStart.getBoundingBox();
			buildMinChunkX=Math.floorDiv(finalBox.minX(),16);buildMaxChunkX=Math.floorDiv(finalBox.maxX(),16);buildMinChunkZ=Math.floorDiv(finalBox.minZ(),16);buildMaxChunkZ=Math.floorDiv(finalBox.maxZ(),16);
			captureTilesX=Math.max(1,Math.floorDiv(finalBox.maxX()-finalBox.minX(),CAPTURE_TILE_SIZE)+1);captureTilesZ=Math.max(1,Math.floorDiv(finalBox.maxZ()-finalBox.minZ(),CAPTURE_TILE_SIZE)+1);
			seed=world.getSeed() ^ net.minecraft.world.level.ChunkPos.pack(candidateChunk.x(),candidateChunk.z()); nextCaptureIndex=0; phase=Phase.CAPTURE_LAYOUT;
			return false;
		}

		private boolean tickCaptureLayout(){
			if(nextCaptureIndex==0){capturedBlocks.clear();capturedEntities.clear();}
			int total=Math.max(1,captureTilesX*captureTilesZ);
			if(nextCaptureIndex>=total){
				long elders=capturedEntities.stream().filter(e -> e instanceof net.minecraft.world.entity.monster.ElderGuardian).count();
				if(elders!=3L){OceanCanvasStructureIntegrity.invariantFailure(zoneName,OceanCanvasStructureKind.OCEAN_MONUMENT,"vanilla monument capture produced "+elders+" elder guardians; expected 3");throw new IllegalStateException("monument elder guardian invariant failed: "+elders);}
				placementEntries=new java.util.ArrayList<>(capturedBlocks.entrySet());nextPlacementIndex=0;foundationMinY=world.getMinY();foundationX=finalBox.minX();foundationY=foundationMinY;foundationZ=finalBox.minZ();foundationDone=0L;
				foundationTotal=(long)(finalBox.maxX()-finalBox.minX()+1)*(finalBox.maxZ()-finalBox.minZ()+1)*Math.max(0,finalBox.minY()-foundationMinY);phase=Phase.FOUNDATION;return false;
			}
			int tileX=nextCaptureIndex%captureTilesX,tileZ=nextCaptureIndex/captureTilesX;nextCaptureIndex++;
			int minX=finalBox.minX()+tileX*CAPTURE_TILE_SIZE,minZ=finalBox.minZ()+tileZ*CAPTURE_TILE_SIZE,maxX=Math.min(finalBox.maxX(),minX+CAPTURE_TILE_SIZE-1),maxZ=Math.min(finalBox.maxZ(),minZ+CAPTURE_TILE_SIZE-1);
			var clip=new net.minecraft.world.level.levelgen.structure.BoundingBox(minX,finalBox.minY()-16,minZ,maxX,finalBox.maxY()+16,maxZ);
			var recorder=createRecordingLevel(); var generator=world.getChunkSource().getGenerator();
			monumentStart.placeInChunk(recorder,world.structureManager(),generator,net.minecraft.util.RandomSource.create(seed ^ (((long)tileX)<<32)^tileZ),clip,new net.minecraft.world.level.ChunkPos(Math.floorDiv(minX,16),Math.floorDiv(minZ,16)));
			return false;
		}
		private net.minecraft.world.level.WorldGenLevel createRecordingLevel(){
			java.lang.reflect.InvocationHandler handler=(proxy,method,args)->{String name=method.getName();
				if("setBlock".equals(name)&&args!=null&&args.length>=2&&args[0] instanceof BlockPos pos&&args[1] instanceof net.minecraft.world.level.block.state.BlockState state){capturedBlocks.put(pos.immutable(),state);return true;}
				if(("removeBlock".equals(name)||"destroyBlock".equals(name))&&args!=null&&args.length>=1&&args[0] instanceof BlockPos pos){capturedBlocks.put(pos.immutable(),Blocks.AIR.defaultBlockState());return true;}
				if("getBlockState".equals(name)&&args!=null&&args.length==1&&args[0] instanceof BlockPos pos){var state=capturedBlocks.get(pos);if(state!=null)return state;}
				if("getFluidState".equals(name)&&args!=null&&args.length==1&&args[0] instanceof BlockPos pos){var state=capturedBlocks.get(pos);if(state!=null)return state.getFluidState();}
				if(("addFreshEntity".equals(name)||"addFreshEntityWithPassengers".equals(name))&&args!=null&&args.length>=1&&args[0] instanceof net.minecraft.world.entity.Entity entity){capturedEntities.add(entity);return true;}
				try{return method.invoke(world,args);}catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}};
			return (net.minecraft.world.level.WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(net.minecraft.world.level.WorldGenLevel.class.getClassLoader(),new Class<?>[]{net.minecraft.world.level.WorldGenLevel.class},handler);
		}
		private boolean tickFoundation(){int budget=FOUNDATION_BLOCKS_PER_TICK;while(budget-->0&&foundationDone<foundationTotal){world.setBlock(new BlockPos(foundationX,foundationY,foundationZ),Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS);foundationDone++;foundationY++;if(foundationY>=finalBox.minY()){foundationY=foundationMinY;foundationX++;if(foundationX>finalBox.maxX()){foundationX=finalBox.minX();foundationZ++;}}}if(foundationDone>=foundationTotal)phase=Phase.PLACE_BLOCKS;return false;}
		private boolean tickPlaceBlocks(){int end=Math.min(placementEntries.size(),nextPlacementIndex+BLOCKS_PER_TICK);for(;nextPlacementIndex<end;nextPlacementIndex++){var e=placementEntries.get(nextPlacementIndex);world.setBlock(e.getKey(),e.getValue(),Block.UPDATE_CLIENTS);}if(nextPlacementIndex>=placementEntries.size()){for(var entity:capturedEntities)world.addFreshEntity(entity);prepareRegistration();}return false;}
		private void prepareRegistration(){
			startRef=net.minecraft.world.level.ChunkPos.pack(candidateChunk.x(),candidateChunk.z());nextRegisterIndex=0;phase=Phase.REGISTER;
		}
		private boolean tickRegister(){int total=registerTotalChunks();if(nextRegisterIndex>=total)return finish();int width=buildMaxChunkX-buildMinChunkX+1,cx=buildMinChunkX+(nextRegisterIndex%width),cz=buildMinChunkZ+(nextRegisterIndex/width);var chunk=world.getChunkSource().getChunkNow(cx,cz);if(chunk==null){OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world,cx,cz);return false;}nextRegisterIndex++;if(cx==candidateChunk.x()&&cz==candidateChunk.z())chunk.setStartForStructure(monumentStructure,monumentStart);chunk.addReferenceForStructure(monumentStructure,startRef);chunk.markUnsaved();return nextRegisterIndex>=total&&finish();}
		private boolean finish(){ProtectedRegions.protect(world,finalBox);OceanCanvasProtectedData.get(world).markPendingRevalidation(new BlockPos(finalBox.minX(),finalBox.minY(),finalBox.minZ()),finalBox);OceanCanvasStructureIntegrity.syntheticPlacement(zoneName, OceanCanvasStructureKind.OCEAN_MONUMENT, true);phase=Phase.DONE;OceanCanvas.LOGGER.info("[OceanCanvas][Structure:{}] strict monument complete at candidate {}",zoneName,candidateChunk);return true;}
		private void abortAfterFailure(){
			if(monumentStructure==null||finalBox==null||startRef==0L)return;
			var owner=world.getChunkSource().getChunkNow(candidateChunk.x(),candidateChunk.z());
			if(owner!=null){owner.setStartForStructure(monumentStructure,net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START);owner.markUnsaved();}
			for(int cz=buildMinChunkZ;cz<=buildMaxChunkZ;cz++)for(int cx=buildMinChunkX;cx<=buildMaxChunkX;cx++){
				var chunk=world.getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;
				var refs=chunk.getAllReferences().get(monumentStructure);if(refs!=null&&refs.remove(startRef))chunk.markUnsaved();
			}
			OceanCanvas.LOGGER.warn("[OceanCanvas][Structure:{}] rolled back partial monument metadata for failed candidate {}",zoneName,candidateChunk);
		}
	}

	private static void ensureTemplateInsideZone(ServerLevel world,
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone,
			String structureTagKey, List<Identifier> templates) {
		OceanCanvasStructureKind auditKind = "ocean_ruin".equals(structureTagKey)
				? OceanCanvasStructureKind.OCEAN_RUIN : OceanCanvasStructureKind.SHIPWRECK;
		OceanCanvasStructureIntegrity.begin(zone.name(), auditKind, "Template forced placement", 1);
		OceanCanvasStructureIntegrity.recordDistributionApproximation(zone.name(), auditKind, "Template forced placement");
		// If vanilla still has a real structure start inside this exact region,
		// FORCE_ON is already satisfied and no synthetic copy is needed.
		int centerX = (zone.bounds().minX() + zone.bounds().maxX()) / 2;
		int centerZ = (zone.bounds().minZ() + zone.bounds().maxZ()) / 2;
		int radius = Math.max(32, Math.max(zone.bounds().maxX() - zone.bounds().minX(),
				zone.bounds().maxZ() - zone.bounds().minZ()) + 32);
		BlockPos existing = world.findNearestMapStructure(
				net.minecraft.tags.TagKey.create(Registries.STRUCTURE,
						Identifier.withDefaultNamespace(structureTagKey)),
				new BlockPos(centerX, world.getSeaLevel(), centerZ), radius, false);
		if (existing != null && zone.contains(existing.getX(), existing.getY(), existing.getZ())) {
			OceanCanvasStructureIntegrity.naturalStart(zone.name(), auditKind);
			OceanCanvasStructureIntegrity.complete(zone.name(), auditKind);
			return;
		}

		// Pick an actual selected chunk nearest the region envelope centre. This
		// matters for polygon/brush regions where the bounding-box centre can be a
		// hole that is not part of the region at all.
		long chosen = 0L;
		long best = Long.MAX_VALUE;
		boolean found = false;
		for (long packed : zone.exactChunks()) {
			int cx = net.minecraft.world.level.ChunkPos.getX(packed);
			int cz = net.minecraft.world.level.ChunkPos.getZ(packed);
			long bx = (long) cx * 16L + 8L - centerX;
			long bz = (long) cz * 16L + 8L - centerZ;
			long d = bx * bx + bz * bz;
			if (!found || d < best) { chosen = packed; best = d; found = true; }
		}
		if (!found) return;
		int blockX = net.minecraft.world.level.ChunkPos.getX(chosen) * 16 + 8;
		int blockZ = net.minecraft.world.level.ChunkPos.getZ(chosen) * 16 + 8;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int floorY = config.oceanFloorY()
				+ OceanCanvasSurfaceFlattener.floorOffset(blockX, blockZ, config.oceanFloorVariation());
		BlockPos pastePos = new BlockPos(blockX, floorY, blockZ);

		StructureTemplateManager templateManager = world.getStructureManager();
		for (Identifier templateId : templates) {
			Optional<StructureTemplate> template = templateManager.get(templateId);
			if (template.isEmpty()) continue;
			boolean placed = template.get().placeInWorld(world, pastePos, pastePos,
					new StructurePlaceSettings(), world.getRandom(), Block.UPDATE_ALL);
			if (!placed) continue;
			net.minecraft.core.Vec3i size = template.get().getSize();
			BlockPos placedMax = pastePos.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
			BoundingBox box = BoundingBox.fromCorners(pastePos, placedMax);
			ProtectedRegions.protect(world, box);
			OceanCanvasProtectedData.get(world).markPendingRevalidation(pastePos, box);
			OceanCanvasStructureIntegrity.syntheticPlacement(zone.name(), auditKind, false);
			OceanCanvasStructureIntegrity.complete(zone.name(), auditKind);
			OceanCanvas.LOGGER.info("FORCE_ON placed {} template {} inside rewipe region '{}' at {}",
					structureTagKey, templateId, zone.name(), pastePos);
			return;
		}
		OceanCanvasStructureIntegrity.placementFailure(zone.name(), auditKind);
		OceanCanvasStructureIntegrity.complete(zone.name(), auditKind);
		OceanCanvas.LOGGER.warn("FORCE_ON could not place {} inside rewipe region '{}' because none of its known templates loaded",
				structureTagKey, zone.name());
	}

	/**
	 * Guarantees one structure of the given kind exists near spawn,
	 * pasting a random-ish candidate from {@code templates} if vanilla
	 * generation hasn't already placed a real one nearby. Shared by the
	 * shipwreck (always on) and ocean ruin (off by default) call sites -
	 * see the class doc's "generalized this round" note for what kinds
	 * of structures this can and can't be used for.
	 *
	 * @param structureTagKey the vanilla structure tag (under {@code
	 *        minecraft:} - e.g. {@code "shipwreck"}, {@code
	 *        "ocean_ruin"}) used both for the "does one already exist"
	 *        search and as the persisted key for "already guaranteed".
	 * @param isPlaced reads the persisted already-placed flag for this
	 *        structure kind - passed in rather than looked up by string
	 *        key so the always-on shipwreck path can keep using its
	 *        original dedicated boolean field (zero behavior/schema
	 *        change risk to something already confirmed working) while
	 *        new kinds use the generalized string-keyed set added this
	 *        round.
	 * @param markPlaced persists the already-placed flag for this
	 *        structure kind.
	 */
	private static void ensureGuaranteedStructure(ServerLevel world, String structureTagKey,
			List<Identifier> templates, int spawnOffsetBlocks,
			java.util.function.Predicate<OceanCanvasProtectedData> isPlaced,
			java.util.function.Consumer<OceanCanvasProtectedData> markPlaced) {
		// Persisted flag, checked first - see the class doc's restart-bug
		// note (same underlying reasoning as OceanCanvasProtectedData's
		// class doc): a direct paste never registers a real StructureStart
		// with vanilla's structure manager, so findNearestMapStructure
		// alone can never tell a fresh load "yes, one's already here".
		if (isPlaced.test(OceanCanvasProtectedData.get(world))) {
			OceanCanvas.LOGGER.info(
					"Guaranteed spawn {} already placed in a previous session - skipping", structureTagKey);
			return;
		}

		BlockPos existing = world.findNearestMapStructure(
				net.minecraft.tags.TagKey.create(Registries.STRUCTURE,
						Identifier.withDefaultNamespace(structureTagKey)),
				BlockPos.ZERO,
				spawnOffsetBlocks * 2,
				false
		);

		if (existing != null) {
			OceanCanvas.LOGGER.info("Spawn {} already present near origin at {}", structureTagKey, existing);

			// Real gap fixed here, found by tracing this exact path
			// after a report of a natural shipwreck sitting unrelocated
			// (still at its original, un-lowered position, embedded in
			// whatever vanilla terrain generated around it) even after
			// the player had already visited it: this early return used
			// to do nothing else at all. The one thing that's actually
			// supposed to relocate and protect a natural structure is
			// OceanCanvasSurfaceFlattener#relocateShipwreckIfNeeded, a
			// completely separate mechanism that only runs reactively
			// as a chunk (or one of its 8 neighbors) fires CHUNK_LOAD -
			// so in the window before that happens to fire for this
			// exact structure, it was carveable like ordinary terrain
			// same as anything else this chunk's flatten pass touches.
			// A generous, approximate box around the found position
			// (not the real structure bounds - findNearestMapStructure
			// only returns a single BlockPos, not a StructureStart or
			// its pieces) held as an interim measure only: it's
			// replaced by the real, precise bounding box the moment
			// relocateShipwreckIfNeeded actually processes this
			// structure (that call registers its own, more accurate
			// protection independently - this doesn't block or
			// duplicate that, it just closes the gap beforehand).
			// Deliberately not attempting the actual relocation/capture
			// dance here too - that logic already exists in exactly one
			// place, and duplicating it here risks both paths racing to
			// relocate the same structure at once.
			int interimRadius = 16;
			ProtectedRegions.protect(world, BoundingBox.fromCorners(
					existing.offset(-interimRadius, -interimRadius, -interimRadius),
					existing.offset(interimRadius, interimRadius, interimRadius)
			));

			return;
		}

		OceanCanvas.LOGGER.info(
				"No {} found near spawn - pasting a guaranteed one for the stranded start", structureTagKey);

		StructureTemplateManager templateManager = world.getStructureManager();

		for (Identifier templateId : templates) {
			Optional<StructureTemplate> template = templateManager.get(templateId);
			if (template.isEmpty()) {
				continue;
			}

			BlockPos origin = new BlockPos(spawnOffsetBlocks, world.getSeaLevel(), spawnOffsetBlocks);
			// The NEW canvas floor, not the original natural ocean floor -
			// see the class doc's "paste height matches the canvas floor"
			// note.
			OceanCanvasConfig config = OceanCanvasConfig.get();
			int floorY = config.oceanFloorY()
					+ OceanCanvasSurfaceFlattener.floorOffset(
							origin.getX(), origin.getZ(), config.oceanFloorVariation());
			BlockPos pastePos = new BlockPos(origin.getX(), floorY, origin.getZ());

			boolean placed = template.get().placeInWorld(
					world,
					pastePos,
					pastePos,
					new StructurePlaceSettings(),
					world.getRandom(),
					Block.UPDATE_ALL
			);

			if (placed) {
				OceanCanvas.LOGGER.info("Placed guaranteed spawn {} ({}) at {}", structureTagKey, templateId, pastePos);

				net.minecraft.core.Vec3i size = template.get().getSize();
				BlockPos placedMax = pastePos.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);

				// Real fix for "the stairs on the shipwreck still don't
				// get waterlogged", found the same way as the natural-
				// shipwreck path's fix: calling
				// revalidatePlacedStructureEnvironment immediately here
				// (right after placement) never actually worked, because
				// this whole method runs at world-load time - before ANY
				// chunk has fired CHUNK_LOAD, let alone been carved. The
				// real ambient water this checks for doesn't exist yet at
				// this point, no matter what. Persisted as pending
				// instead (OceanCanvasProtectedData#markPendingRevalidation)
				// and picked up later by
				// OceanCanvasSurfaceFlattener#flattenChunk, once a chunk
				// actually overlapping this position has done its own
				// real carving.
				OceanCanvasProtectedData.get(world).markPendingRevalidation(pastePos,
						BoundingBox.fromCorners(pastePos, placedMax));

				// Register the actual placed footprint so the flattener
				// never carves into it - see ProtectedRegions' class doc
				// for why. getSize() assumes default (unrotated)
				// placement, matching the plain StructurePlaceSettings()
				// used above.
				ProtectedRegions.protect(world, BoundingBox.fromCorners(pastePos, placedMax));
				markPlaced.accept(OceanCanvasProtectedData.get(world));
                try{net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).addWorldEvent("STRUCTURE",structureTagKey,"CREATED","Guaranteed structure placed",templateId+" at "+pastePos,"system","structure:starter",System.currentTimeMillis());}catch(RuntimeException ignored){}

				return;
			}
		}

		OceanCanvas.LOGGER.warn(
				"Could not paste a guaranteed spawn {} from any known template id - check the relevant "
						+ "template list in ModStarterStructures against the real 26.2 structure template "
						+ "paths (Loom genSources or the vanilla jar's data/minecraft/structure/ folder).",
				structureTagKey);
	}
}
