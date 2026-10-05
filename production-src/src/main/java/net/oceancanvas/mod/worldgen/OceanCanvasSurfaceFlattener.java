package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.levelgen.Heightmap;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.mixin.LevelChunkBlockEntityAccessor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Forces the ocean canvas surface, without touching Minecraft's own
 * chunk generator at all.
 *
 * <p><b>Why this exists instead of a custom ChunkGenerator:</b> the
 * original approach subclassed {@code NoiseBasedChunkGenerator} and
 * overrode {@code buildSurface}. On 26.2, that class is {@code final} -
 * Mojang no longer allows subclassing it, so that approach is a dead
 * end, not a bug to patch. This class gets the same net effect (vanilla
 * underground untouched, ocean above sea level, only inside the
 * canvas) a different way:</p>
 *
 * <ol>
 *   <li>The world preset ({@code ocean_canvas.json}) uses vanilla's own
 *       {@code minecraft:noise} generator, completely unmodified,
 *       paired with vanilla's own standard multi-noise overworld biome
 *       source (deliberately NOT a fixed "ocean everywhere" biome - see
 *       the spawn-hang note below). Terrain, caves, aquifers, ores, and
 *       structures all generate exactly as vanilla would.</li>
 *   <li>This class listens for {@link ServerChunkEvents#CHUNK_LOAD} and,
 *       for any chunk inside the configured canvas, clears anything at
 *       or above sea level back down to water/air.</li>
 * </ol>
 *
 * <p><b>Why the biome source isn't just "ocean everywhere" (found on
 * the second in-game test, after the first performance fix below still
 * hung):</b> vanilla's world-spawn search deliberately avoids placing
 * the player in an ocean biome, and expands its search outward until it
 * finds a non-ocean spot. A {@code minecraft:fixed} ocean biome source
 * means literally every candidate position in the entire world reports
 * back "ocean" - the search can never succeed, so it burns through its
 * full search radius (potentially hundreds of chunks, each one forcing
 * real generation) every single time a world is created. Using vanilla's
 * normal biome source instead means real land exists nearby as usual,
 * so the search finishes fast like it does in any ordinary world. The
 * player's actual in-game experience is unaffected - the canvas still
 * gets flattened to open water everywhere by this class regardless of
 * what biome any given position happens to be labeled underneath. The
 * biome label no longer being "ocean" is a cosmetic-only gap (ambient
 * sounds, fog color, some mob spawn rules) that overlaps with the
 * still-unsolved "what biome does hand-built terrain get" question
 * from the project spec (see docs/roadmap.md) - worth revisiting
 * together later, not a regression to fix in isolation now.</p>
 *
 * <p><b>Performance note (found during first in-game test):</b> vanilla's
 * noise generator doesn't care what biome a chunk is labeled - hills and
 * mountains generate at completely normal height near spawn even though
 * the biome source reports "ocean". That means this class isn't just
 * touching up a few stray high points; it's clearing real, full-height
 * terrain for every chunk near spawn the first time it loads.
 *
 * <p><b>Second, more serious performance issue (found on the third
 * in-game test, after both fixes above still hung for 20+ minutes):</b>
 * this originally used {@code ServerLevel#setBlock}, which triggers
 * light propagation. Clearing terrain right at a chunk's edge can open
 * a light shaft into a neighboring chunk that isn't loaded yet - and
 * Minecraft can respond by force-loading/generating that neighbor to
 * compute lighting correctly across the boundary. That neighbor then
 * fires this same listener, which can do the same thing to *its*
 * neighbors, and so on - a feedback loop that keeps expanding outward
 * rather than settling down, which better explains a hang that never
 * seemed to finish rather than one that was merely slow.</p>
 *
 * <p>Fixed by writing directly to the chunk's raw block storage
 * ({@link net.minecraft.world.level.chunk.ChunkAccess#setBlockState})
 * instead of going through the world-level API at all. That skips light
 * propagation, physics, and neighbor notification entirely - no
 * cascade is possible. This is safe specifically because these edits
 * happen during initial world/spawn-chunk generation, before any player
 * has actually connected to receive these chunks over the network yet;
 * the first real chunk-send to a client happens later and will pick up
 * the corrected block data as of whenever it's sent. v253.3 keeps the raw write for safety but explicitly invalidates lighting
 * after each owned mutation, once the required neighborhood is ticking-ready.
 * This preserves the non-recursive write architecture without leaving stale
 * skylight silhouettes behind.</p>
 *
 * <p><b>Real root cause of the "Preparing spawn area" stall, found via
 * a thread dump (not guessed):</b> {@code chunk.setBlockState} isn't as
 * side-effect-free as assumed - placing a WATER block still triggers
 * {@code LiquidBlock#onPlace}, a behavior baked into
 * {@code LevelChunk.setBlockState} itself for any already-FULL chunk,
 * not something that only fires via the world-level notify path. That
 * behavior looks at neighboring chunks to check fluid flow, and since
 * this all runs synchronously on the single game thread while THAT
 * thread is already mid-generation, the neighbor lookup ends up
 * waiting on the very thread that would need to service it - a
 * self-referential stall. This explains why it happened at the exact
 * same point every time regardless of seed: it's deterministic, not
 * seed-dependent bad luck. Removing the {@code checkBlock} relight call
 * didn't fix it because that was never the actual cause.</p>
 *
 * <p>First attempted fix: defer the block placement to run via
 * {@code server.execute(...)} on a later tick, instead of doing it
 * synchronously inside {@code CHUNK_LOAD}. That still stalled at the
 * identical point. Reason: {@code server.execute(...)} queues onto the
 * same task queue that {@code managedBlock} - the exact wait that was
 * already stuck - actively drains while parked. The "deferred" task
 * could still get pulled and run *nested inside* that same busy-wait,
 * hitting the identical self-referential problem one level deeper.</p>
 *
 * <p>Second attempted fix: gate startup-phase chunks behind
 * {@code ServerLifecycleEvents.SERVER_STARTED}, assuming that event
 * fires only after spawn-area pregeneration genuinely finishes. A
 * follow-up thread dump proved that assumption wrong - a chunk from
 * during that phase was still processed via the "server already
 * started" branch, hitting the identical stall. Worse, the dump
 * revealed the deeper problem: {@code MinecraftServer.waitUntilNextTick}
 * calls {@code managedBlock} as a routine, general-purpose way of
 * pumping queued tasks any time the server is idly waiting between
 * ticks - not something specific to the startup phase. That means
 * *any* deferred task, from *any* context, can end up running nested
 * inside another wait. There is no reliably "safe later" reachable via
 * {@code execute(...)} in this architecture - relocating *when* the
 * code runs can't fix this on its own.</p>
 *
 * <p><b>Real fix:</b> stop trying to control timing and instead remove
 * the actual precondition that causes {@code LiquidBlock#onPlace} to
 * block in the first place - it only waits if the neighboring chunk it
 * looks up isn't loaded yet. So a chunk is only ever flattened once its
 * position and all 8 neighbors are confirmed already loaded (checked
 * via {@code ServerLevel#hasChunk}, a non-blocking query - it just
 * reports whether a chunk is currently resident, without generating
 * anything). Chunks that aren't ready yet stay queued and are
 * re-checked once per real server tick (via
 * {@code ServerTickEvents.END_SERVER_TICK}, a genuine tick boundary,
 * not a task pulled from the same queue that caused the problem).
 * Once neighbors are confirmed loaded, the water placement inside
 * {@code onPlace} resolves immediately with no waiting at all, so the
 * self-referential stall can't occur.</p>
 *
 * <p><b>Follow-up bug found in-game (confirmed permanent, not
 * transient - it stayed broken even after the user waited on the spot):
 * </b> chunks near the edge of the player's render distance could stay
 * stuck unflattened forever, because {@code hasChunk} only reports
 * chunks Minecraft has already decided to load (tied to the player's
 * view distance) - simply waiting nearby doesn't make it load extra
 * chunks beyond that on its own.</p>
 *
 * <p><b>Attempted fix, reverted after causing a worse regression:</b>
 * actively requesting any missing neighbor via the async
 * {@code ServerChunkCache#getChunkFuture(...)}. This backfired badly -
 * every force-loaded neighbor chunk fires its own {@code CHUNK_LOAD}
 * event, gets queued, and then force-loads ITS OWN missing neighbors -
 * an essentially unbounded cascade. The very next test showed the
 * server falling ~900 ticks behind ("Can't keep up! Running 44732ms
 * behind") and vast areas near spawn left unflattened, not because
 * flattening broke, but because the server was too overwhelmed
 * generating far more of the world than any player was actually near
 * to make timely progress on anything. Reverted back to the simpler,
 * passive {@code hasChunk}-only check (no active force-loading). The
 * original edge-of-render-distance bug this was meant to fix is a real,
 * still-open issue - but a slow, working world beats a fast, broken
 * one, so this needs a more careful fix later (e.g. capping how many
 * "rings" of force-loading are allowed, or accepting a smaller/relaxed
 * neighbor requirement) rather than the current unbounded version.</p>
 *
 * <p><b>Real bug found spawning fresh into a new survival world (not
 * exploring by flight):</b> the player spawned directly onto
 * unflattened land, with a completely clean log - no exceptions, no
 * "can't keep up" warnings, "Preparing spawn area" finishing normally
 * in under 2 seconds. That combination points to {@code CHUNK_LOAD}
 * simply never firing for the initial spawn-chunk pregeneration batch
 * in the first place (unlike normal gameplay chunk loads while
 * exploring on foot, which worked). First fix attempt: a one-time
 * sweep on {@code ServerLifecycleEvents.SERVER_STARTED}. The mixin log
 * confirmed spawn was correctly forced to y=63, but the player still
 * ended up on solid ground at y=67 - Minecraft's own "safe spawn"
 * placement logic evidently ran before our one-time sweep had finished
 * flattening chunk (0,0), found real unflattened terrain there, and
 * stood the player on top of it. A genuine timing race, not a failure
 * of either system individually.</p>
 *
 * <p>Fixed by making the startup sweep persistent instead of one-shot:
 * it re-queues every loaded chunk within
 * {@value #STARTUP_SWEEP_RADIUS_CHUNKS} chunks of spawn on every server
 * tick for the first {@value #STARTUP_SWEEP_TICKS} ticks after start,
 * rather than trying to time a single sweep precisely against an
 * uncertain event ordering. Cheap (just a queue add, and re-adding an
 * already-flattened chunk is a harmless no-op via the existing
 * current==target check), and gives the flattener many more chances to
 * win the race against the player's actual join.</p>
 *
 * <p><b>None of that ever actually fixed it, across every subsequent
 * trial - the land at spawn never went away even after long waits. That
 * was the real signal that this was never a timing problem at all.</b>
 * The true cause: {@link #flattenChunk} deliberately uses the raw,
 * non-notifying chunk-write API (see the light-propagation note
 * earlier in this doc) on the reasoning that it's safe because "the
 * client hasn't received this chunk yet" - true for chunks explored
 * later, which is why walking/flying around worked - but guaranteed
 * FALSE for the spawn chunk specifically, since that's sent to the
 * client almost immediately on login. The server-side data was very
 * likely being corrected the whole time; the client was just never
 * told, so it kept rendering the stale copy forever, regardless of how
 * long anyone waited or how many times the sweep/timing logic was
 * adjusted. Fixed by explicitly forcing a re-send of the chunk to
 * nearby players (see {@link #resendToNearbyPlayers}) right after
 * flattening it.</p>
 *
 * <p><b>Needs on-device testing like the rest of the worldgen code</b> -
 * {@code ServerChunkEvents.CHUNK_LOAD}'s exact callback signature is the
 * part most likely to need a small fix if it doesn't match here, and
 * {@code ServerLevel#hasChunk} is a reasonable-confidence guess rather
 * than a confirmed API.</p>
 *
 * <p><b>Deep, blended ocean floor (user's core design requirement, not
 * just a thin surface layer):</b> the canvas is meant to be
 * imperceptible from true vanilla terrain both from above (looking down
 * through the water) and below (from caves) - the only deliberate
 * deviation is a deeper floor than standard vanilla ocean, to give more
 * room to build. This is implemented as a small but important change
 * to the existing flattening loop: instead of starting the per-column
 * scan at the water surface, it starts at
 * {@link OceanCanvasConfig#oceanFloorY()} (configurable the same way
 * canvas size is) and carves everything up to the surface into water
 * (or air, above the surface). Real terrain already deeper than that
 * floor - an actual trench, a natural cave system - is never touched,
 * since the scan simply never reaches below the configured floor. That
 * satisfies "never touched from below" by construction, not by trying
 * to detect caves specially.</p>
 *
 * <p><b>Two real bugs found once the deep floor was in-game, both
 * fixed:</b></p>
 * <ol>
 *   <li>The flat floor looked unnatural, as predicted above - fixed
 *       with {@link #floorOffset}, a cheap sine-based smooth variation
 *       (no Minecraft noise API involved, deliberately, given how many
 *       internal APIs have needed correcting already - this one is
 *       self-contained and low-risk).</li>
 *   <li>The guaranteed spawn shipwreck disappeared - the carve was
 *       unconditionally overwriting anything in its range, including
 *       placed structures, and the deeper floor now reaches well into
 *       the depth shipwrecks sit at. Fixed with
 *       {@link #isPreservedStructureBlock}, a targeted (not general)
 *       fix - see that method's doc for why.</li>
 * </ol>
 *
 * <p><b>Real limitation identified here, then actually fixed (not a
 * partial mitigation) once the user provided an exact design:</b>
 * excavating a column-by-column vertical range initially had no
 * awareness of 3D cave void shapes - a natural cave's wall or ceiling
 * inside the excavated range got carved away like any other terrain,
 * reported as "cutting into caves and leaving weird artifacts". The
 * first fix ({@link #findSafeColumnTop}, below) addressed the specific
 * floating-debris symptom - a fixed "+4 above the heightmap" scan
 * margin was missing real terrain (cave ceiling fragments, overhangs)
 * that sat higher than the margin covered, left floating once
 * everything supporting it got carved away. That's still in place and
 * still needed. But the deeper problem - a cave wall/ceiling actually
 * getting carved open wherever it intersects the excavated range at
 * all - needed a different kind of fix, not just a bigger margin.</p>
 *
 * <p><b>The actual fix: a guaranteed-solid stone transition layer.</b>
 * Rather than attempting real 3D cave-void detection (flood-fill,
 * etc.) to decide what to preserve, {@link #flattenChunk} now forces a
 * solid, unbroken layer of stone for
 * {@link OceanCanvasConfig#oceanFloorTransitionThickness()} blocks
 * directly beneath the excavated floor, on every column across the
 * whole canvas - regardless of what was there before. Any cave void
 * that would otherwise have been exposed right at the boundary gets
 * filled in instead. Real caves/underground only ever start below that
 * sealed buffer, completely undisturbed - matching the user's own
 * diagram exactly: water, then a stone ocean floor, then a stone
 * transition layer, then untouched natural cave/underground below.
 * Much simpler and more robust than flood-fill for this specific
 * requirement, at the cost of the transition layer itself being a
 * uniform seal rather than an organically-varying blend - a reasonable
 * tradeoff per the user's own spec, not a shortcut taken unilaterally.</p>
 *
 * <p><b>Structure preservation redone properly:</b> the block-type skip
 * list ({@code isPreservedStructureBlock}) risked both missing real
 * structures built from other materials and incorrectly protecting an
 * unrelated natural structure (a mineshaft, say) that happens to share
 * the same block types. Replaced with real bounding-box checks: natural
 * vanilla structures are queried per-chunk via
 * {@code ChunkAccess#getAllStarts()}, and the one manually-placed
 * exception (the guaranteed spawn shipwreck, which doesn't go through
 * real world generation so vanilla's structure manager never learns
 * about it) is tracked separately in {@link ProtectedRegions}. See that
 * class's doc comment for the full reasoning.</p>
 *
 * <p><b>Follow-up bug: sand/gravel "blobs" left untouched on the ocean
 * floor.</b> The structure check above initially used each
 * {@code StructureStart}'s own overall bounding box. That's too coarse
 * for sprawling structures like mineshafts, which generate as a set of
 * scattered pieces with a combined bounding box covering the whole span
 * between them - large stretches of completely ordinary terrain that
 * had nothing to do with the actual structure were being protected
 * just because they fell inside that span. Fixed by checking each
 * individual {@code StructurePiece}'s own (much tighter) bounding box
 * instead of the structure's overall one.</p>
 *
 * <p><b>Floor variation rewritten after user feedback:</b> the original
 * {@link #floorOffset} used two long-wavelength (~80-125 block) sine
 * waves, which produced broad "rolling hills" instead of the subtle
 * high/low texture that was actually wanted, and being pure sine waves
 * was also mathematically periodic - it would eventually repeat
 * visibly. Fixed with much shorter wavelengths (roughly 6-20 blocks)
 * for localized bumps instead of broad swells, four layered waves at
 * frequencies with no simple common ratio so nothing lines up into an
 * obvious repeat, and a lower default amplitude (2, down from 5).</p>
 *
 * <p><b>A real, naturally-generated shipwreck was fully destroyed
 * despite the per-piece protection above.</b> The log confirmed
 * vanilla's own structure search found it and correctly reported it as
 * already existing (so the guaranteed-shipwreck placement was
 * correctly skipped) - the structure genuinely existed, but its blocks
 * were gone. Cause: {@code ChunkAccess#getAllStarts()} only returns
 * structures that START in that exact chunk. A shipwreck long enough
 * to physically cross into a neighboring chunk has its overflow
 * portion sitting in a chunk that has no record of it as a "start" -
 * that chunk's own protection check found nothing to protect, and
 * carved straight through it. Fixed by checking the same 3x3
 * neighborhood of chunks {@link #neighborsReady} already confirmed are
 * loaded/ticking, not just the one chunk currently being flattened -
 * a structure starting in any of those 9 chunks is now protected
 * regardless of which one is actually being processed.</p>
 *
 * <p><b>Serious design flaw found once structures were finally being
 * preserved: mineshafts (and presumably other structure types) were
 * ALSO being protected, including parts poking up into open water -
 * exactly the "peeking through the seal" the sealed transition layer
 * exists to prevent.</b> Root cause: protection was keyed on "is this
 * inside a structure's box AND not natural terrain material" -
 * mineshaft supports/fences/rails aren't natural terrain material
 * either, so they qualified for protection just like ship planks did.
 * Per the user's explicit spec, only shipwrecks are meant to be visible
 * above the floor; everything else should be carved away wherever it
 * intersects the excavated range, same as ordinary terrain, and simply
 * stays untouched wherever it remains below the seal (which it always
 * will, since the carve never reaches below transitionBottom anyway).
 * Fixed by filtering the natural-structure search to only shipwrecks,
 * by actual Java type
 * ({@code start.getStructure() instanceof ShipwreckStructure}) - not
 * by guessing at what its blocks look like. (First attempt used a
 * registry-key lookup via {@code RegistryAccess#registryOrThrow},
 * which doesn't exist under that name in 26.2 - the direct type check
 * is simpler anyway and avoids the registry API entirely.) This should
 * also incidentally help with the "gravel blob" artifacts reported
 * around the same time, if their real cause was over-broad structure
 * protection rather than something separate - worth confirming on the
 * next test rather than assuming.</p>
 *
 * <p><b>Ecology ownership:</b> the core terrain pass intentionally does not plant
 * seagrass, kelp, or other decoration. Ocean Canvas prepares terrain; vanilla biome
 * behavior and the player's own building choices own ecology.</p>
 *
 * <p><b>Soft edge transition ("taper") - designed after 9 rounds of
 * staying an explicitly open design question.</b> Off by default ({@link
 * OceanCanvasConfig#taperEnabled()}). The problem this solves: past the
 * canvas edge, terrain is deliberately left as plain, untouched vanilla
 * generation (the already-shipped M1 boundary guarantee) - but if that
 * untouched terrain happens to be tall (a mountain, a cliff) right next
 * to the canvas's excavated, comparatively deep ocean floor, the result
 * is a visible near-vertical "wall" at the exact column where carving
 * stops. The fix, per the user's own explicit design decisions: a
 * fixed-width ring of chunks just outside the canvas ({@link
 * OceanCanvasConfig#taperWidthChunks()}, a genuinely distinct third
 * {@link OceanCanvasConfig.CanvasZone#TAPER} state, not folded into
 * "inside" or "outside") where three carve parameters - the floor
 * height, the sealed-transition-layer thickness, and the "clear to air"
 * ceiling - all blend continuously from their normal in-canvas values
 * (right at the canvas edge) toward values that carve nothing at all (at
 * the ring's outer edge), using {@link OceanCanvasConfig#taperBlend}, a
 * 0..1 ratio computed from the same square (Chebyshev) distance metric
 * the canvas boundary itself already uses. See {@link #flattenChunk}'s
 * taper branch for the exact formulas and why each of the three
 * parameters needed to blend together (blending only the floor height
 * alone left a fixed-width band of terrain still getting touched no
 * matter how far into the ring a column was - blending the transition
 * thickness and ceiling too is what makes the carved range actually
 * collapse toward nothing near the outer edge, not just move the floor
 * around within a still-fully-carved range).</p>
 *
 * <p><b>Honestly documented residual, not hidden:</b> because a column at
 * the very outermost taper chunk still classifies as {@code TAPER}
 * (never {@code OUTSIDE} - see {@link OceanCanvasConfig#canvasZone}),
 * its blend value is mathematically always strictly less than 1.0, so a
 * very thin residual band (bounded by integer rounding, at most on the
 * order of the configured transition thickness) can still be touched
 * right at the taper/outside seam. This is a deliberate, bounded
 * tradeoff: it replaces what used to be a full, unbounded vertical cliff
 * (up to the entire floor depth, hundreds of blocks for a deep floor)
 * with, at worst, a sub-transition-thickness ledge - a large, genuine
 * improvement, explicitly not claimed as a mathematically perfect one.</p>
 *
 * <p><b>Per-zone shipwreck/ocean-ruin overrides, and natural ocean ruin
 * protection (both new this round)</b> - the "manually tweak structures
 * within a region" half of the user's in-game map screen request (see
 * {@code net.oceancanvas.mod.gui.OceanCanvasMapScreen}). {@link
 * #discoverAndProtectShipwreck} now consults {@link
 * OceanCanvasPlayerZones#structureOverrideAt} before falling back to the
 * global {@link OceanCanvasConfig#shipwrecksRule()} flag, and a new
 * {@link #discoverAndPreserveWholeBox} does the same for ocean ruins and monuments
 * against {@link OceanCanvasConfig#naturalOceanRuinsRule()} (off by
 * default - natural ocean ruins previously had zero special handling at
 * all, carved away exactly like a mineshaft). See that method's own doc
 * for why ocean ruin protection is deliberately simpler than the
 * shipwreck path: no relocation, and unconditional (not material-filtered)
 * carving-around within its bounding box.</p>
 */
public final class OceanCanvasSurfaceFlattener {
	/** Monotonic clock for transient leases/retries; immune to wall-clock jumps. */
	private static long monotonicMillis(){ return System.nanoTime()/1_000_000L; }

	private static OceanCanvasLightFinalizerSession lightFinalizerSession() {
		OceanCanvasServerRuntime runtime = OceanCanvasServerRuntime.onlyActiveOrNull();
		if (runtime == null) throw new IllegalStateException("No active Ocean Canvas server runtime for lighting finalizer state");
		return runtime.state(OceanCanvasLightFinalizerSession.class, OceanCanvasLightFinalizerSession::new);
	}

	private static OceanCanvasLightRecoverySession lightRecoverySession() {
		OceanCanvasServerRuntime runtime = OceanCanvasServerRuntime.onlyActiveOrNull();
		if (runtime == null) throw new IllegalStateException("No active Ocean Canvas server runtime for lighting recovery state");
		return runtime.state(OceanCanvasLightRecoverySession.class, OceanCanvasLightRecoverySession::new);
	}

	private static OceanCanvasLightTelemetrySession lightTelemetrySession() {
		OceanCanvasServerRuntime runtime = OceanCanvasServerRuntime.onlyActiveOrNull();
		if (runtime == null) throw new IllegalStateException("No active Ocean Canvas server runtime for lighting telemetry state");
		return runtime.state(OceanCanvasLightTelemetrySession.class, OceanCanvasLightTelemetrySession::new);
	}

	private static OceanCanvasPregenFlattenerSession pregenSession() {
		OceanCanvasServerRuntime runtime = OceanCanvasServerRuntime.onlyActiveOrNull();
		if (runtime == null) throw new IllegalStateException("No active Ocean Canvas server runtime for Pregen flattener state");
		return runtime.state(OceanCanvasPregenFlattenerSession.class,
				() -> new OceanCanvasPregenFlattenerSession(PREGEN_FULL_DEMAND_MAX_ACTIVE, STARTUP_SWEEP_TICKS));
	}


	// v253.7: raw ChunkAccess writes + LightEngine#checkBlock update the server-side
	// light graph asynchronously, but sendBlockUpdated only carries block-state
	// changes. The client can therefore keep the OLD sky/block-light section arrays
	// and render dark silhouettes of terrain that no longer exists. Queue a delayed
	// authoritative chunk+light packet after the light engine has had real tick
	// boundaries to drain. v253.61.7 keeps neighborhood awareness only while a
	// neighbor is still unfinished: once a canonical chunk has passed the actual
	// skylight-field verifier, later adjacent CANVAS carving cannot make its direct
	// vertical skylight darker, so its certificate is stable and is not re-armed.
	// v253.72.9: persistent SKY failures are dormant correctness debt, not active
	// per-tick finalizer work. Keep them outside the active countdown map while
	// they sleep, then wake a tiny fair cohort when their absolute retry time is
	// due. This prevents thousands of persistent chunks from being scanned and
	// re-verified in the same tick, while pendingLightSyncCount() still includes
	// them so completion remains strictly fail-closed.
	// v253.104: ordinary and quarantine retry lanes plus backoff identity are isolated
	// in a Minecraft-free ledger. The queues remain physically separate by design.

	// v253.49: only freshly-authored terrain entries may use the light finalizer's
	// physical-profile repair. Restart/late-load/adjacent reconciliation is LIGHT-ONLY
	// and must never rewrite blocks or player work.
	// v253.9: the final quiet-period pass is now a real vanilla light rebuild,
	// not another resend of potentially stale cached section arrays. v253.61.7
	// coalesces adjacent mutations into unfinished entries instead of recursively
	// invalidating already-certified neighbors.
	// v253.14: do not call ThreadedLevelLightEngine#lightChunk on already-live FULL chunks.
	// C2ME owns that chunk-status pipeline and mutates setLightCorrect asynchronously.
	// Ocean Canvas instead uses the normal live-chunk incremental light-update API.

	// v253.13: final relighting owns a small, bounded radius-1 residency ticket.
	// This is separate from Pregen ownership because Pregen may legitimately retire
	// a target before the delayed quiet-period light pass runs. Radius 1 keeps the
	// center + its 3x3 border context resident without any synchronous getChunkFuture
	// call. Tickets are installed/removed only from the server tick.
	// v253.103: Java ownership for relight residency lives in one bounded state ledger.
	private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_RADIUS = 1;
	// v253.69 runtime hardening: the 20k/40k soak showed the 32-ticket cohort
	// spending most of its time reloading already-authored chunks after the terrain
	// frontier had moved on. A still-bounded 64-entry cohort keeps enough adjacent
	// finalized work resident to overlap C2ME lighting without creating an unbounded
	// ticket pool. Radius-1 tickets overlap heavily for row-major Pregen work.
	private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48;
	// v253.125.29: do not let the 64-ticket maximum become a fixed memory target.
	// .28 repeatedly reached 90-99% heap while all 64 relight tickets were active.
	// This governor only limits NEW installs; productive tickets retire naturally.
	private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM = 32;
	private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT = 16;
	private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_CRITICAL = 8;
	// v253.61.6: a saturated ticket cohort must never monopolize all 32 slots.
	// The 61.4 20k runtime proved a head-of-line lock where ticket holders fell
	// outside the finalizer's fixed scan window, so none could be revisited/released.
	// v253.69: 15s rotation actively interrupted stage-1 relights in the runtime log;
	// some entries then reported 80s+ wall-clock relight age because their ticket was
	// repeatedly discarded while C2ME was still converging/loading. Treat rotation as
	// a true cold-load recovery only, not ordinary fairness.
	private static final long LIGHT_RELIGHT_RESIDENCY_TICKET_MAX_HOLD_NS = 60_000_000_000L;
	// v253.125.22: stage-0+ tickets normally stay pinned until proof completes, but a
	// staged ticket whose center/3x3 context has remained cold for 90 seconds is no
	// longer productive. Keeping it forever can saturate all 64 slots below the 512
	// lighting high-water mark, leaving the terrain headroom clamp at zero forever.
	// Reset only the transient stage/fingerprint and release residency; the lighting
	// obligation, recovery escalation, quarantine identity, and global certificate
	// debt all remain fail-closed and must be re-proven after reacquisition.
	private static final long LIGHT_RELIGHT_STAGED_TICKET_MAX_HOLD_NS = 90_000_000_000L;
	private static final int LIGHT_RELIGHT_STALE_ROTATE_PER_TICK = 2;

	// v253.49: lighting must drain continuously during large Pregen runs. Keep a
	// hard bound on authored-but-not-yet-published light work so terrain admission
	// can yield before the queue grows into the hundreds of thousands again.
	// v253.69: 2048/1024 allowed the light frontier to fall several rows behind the
	// terrain frontier. Those chunks unloaded, forcing every finalization through the
	// recovery ticket path and pinning the queue near the 4096 emergency ceiling. Keep
	// the queue close enough to the freshly-authored/resident frontier instead.
	private static final int LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER = 512;
	private static final int LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER = 256;
	// v253.125.31: administrative work must be bounded independently from the
	// expensive 8ms light budget. The .30 diagnostic reached ~24k pending entries,
	// making a full key copy + candidate-object sort every tick a major GC source.
	// v253.125.34: the .33 soak still observed a 299ms scheduler-administration
	// maximum while only <=64 entries can perform expensive work per tick. Halve
	// the rotating sample cohort; fairness is preserved by queue rotation and the
	// visible-priority lane, while each tick performs materially less map/queue work.
	private static final int LIGHT_SCHEDULER_SAMPLE_NORMAL = 256;
	private static final int LIGHT_SCHEDULER_SAMPLE_BACKLOG = 512;
	// v253.125.36: stale event-lane nodes are bookkeeping history, not useful work.
	// Bound raw polls even when every node is stale and compact lanes only when stale
	// history materially outweighs authoritative membership.
	private static final int LIGHT_SCHEDULER_STALE_POLL_MULTIPLIER = 4;
	private static final int LIGHT_SCHEDULER_STALE_POLL_FLOOR = 128;
	private static final int LIGHT_SCHEDULER_LANE_COMPACT_RATIO = 3;
	private static final int LIGHT_SCHEDULER_LANE_COMPACT_SLACK = 256;
	private static final int LIGHT_PENDING_EXACT_COUNT_THRESHOLD = 4096;
	private static final long LIGHT_RESIDENCY_RECOVERY_HOLD_TICKS = 200L;
	private static final long LIGHT_HEAVY_GLOBAL_ESCAPE_INTERVAL_TICKS = 2L;
	// v253.125.34: the .33 runtime installed 18k+ radius-1 historical relight
	// tickets and then Save & Quit waited for 191k overworld chunks to unload.
	// Pace only NEW historical LIGHT_ONLY forced residency. Fresh terrain and
	// player-visible repair bypass this limiter. A short warm linger lets adjacent
	// historical obligations reuse the already-paid 3x3 residency before C2ME unloads.
	private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_TERRAIN_TICKS = 10L;
	private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS = 5L;
	private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_IDLE_TICKS = 2L;
	private static final long LIGHT_HISTORICAL_WARM_RESIDENCY_TICKS = 40L;
	private static final double LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION = 0.88D;


	// v253.88: structure-relocation rescue state/ticket ownership lives in a
	// server-runtime-owned service. SurfaceFlattener retains only the terrain/light
	// operations that the rescue lane calls back into; this prevents another liveness
	// subsystem from accumulating world/session state inside this god class.
	private static final OceanCanvasStructureRelocationRescueService.Hooks STRUCTURE_RELOCATION_RESCUE_HOOKS =
			new OceanCanvasStructureRelocationRescueService.Hooks() {
		@Override public boolean allChunksReadyForRelocation(ServerLevel world,
				net.minecraft.world.level.levelgen.structure.BoundingBox box) {
			return OceanCanvasSurfaceFlattener.allChunksReadyForRelocation(
					world, box.minX(), box.minZ(), box.maxX(), box.maxZ());
		}
		@Override public net.minecraft.world.level.levelgen.structure.BoundingBox relocateShipwreckIfNeeded(
				ServerLevel world, net.minecraft.world.level.levelgen.structure.StructureStart start) {
			return OceanCanvasSurfaceFlattener.relocateShipwreckIfNeeded(world, start);
		}
		@Override public void revalidatePlacedStructureEnvironment(ServerLevel world, BlockPos min, BlockPos max) {
			OceanCanvasStructureEnvironmentService.revalidatePlacedEnvironment(world, min, max);
		}
		@Override public void rearmLightingAfterStructureMutation(ServerLevel world,
				net.minecraft.world.level.levelgen.structure.BoundingBox box) {
			OceanCanvasSurfaceFlattener.rearmLightingAfterStructureMutation(world, box);
		}
		@Override public Iterable<Long> pendingLightChunks() { return lightFinalizerSession().pendingTicks.boxedKeySnapshot(); }
		@Override public int pendingLightCount() { return lightFinalizerSession().pendingTicks.size(); }
	};

	// v253.11 proactive lighting diagnostics. These are intentionally observation-only:
	// they do not change terrain, lighting, tickets, or Pregen scheduling.
	private static final int LIGHT_SYNC_INITIAL_DELAY_TICKS = 4;
	// v253.69: the actual server skylight verifier is authoritative. Four quiet ticks
	// are enough for the common path; genuinely late C2ME lighting automatically gets
	// the existing passive retry/escalation windows instead of making every healthy
	// chunk pay a fixed half-second minimum.
	private static final int LIGHT_SYNC_FINAL_SETTLE_TICKS = 4;
	private static final int LIGHT_SYNC_VERIFY_RETRY_TICKS = 8;
	// v253.61.8: C2ME lighting is threaded. v253.61.7 proved that sampling only
	// three ticks after a sweep outruns normal convergence and turns transient
	// darkness into thousands of unnecessary source reseeds/hard SKY resets.
	// Require two passive failed proofs, separated by real tick boundaries, before
	// any repair/escalation is allowed. This keeps the verifier strict while
	// distinguishing "not converged yet" from a persistent bad light field.
	private static final int LIGHT_SYNC_PASSIVE_VERIFY_ATTEMPTS = 2;
	// v253.69: RELIGHT_SLOW used 750ms even though the staged finalizer itself intentionally
	// waits across tick boundaries. Under backlog this classified ~90% of successful
	// chunks as warnings. Only multi-second scheduling/residency delays are actionable.
	private static final long LIGHT_RELIGHT_SLOW_WARN_MS = 2500L;
	// v253.36: v253.34 proved that unchanged-block checkBlock sweeps can become a
	// permanent no-op for a small number of deep skylight islands. After a few
	// ordinary retries, escalate by rebuilding the chunk sky-source table and
	// explicitly propagating light sources. If that still fails, clear only the
	// target chunk's SKY nibble storage, wait for the threaded light engine to
	// observe that clear, then re-enable and re-seed it from current blocks. This
	// never calls lightChunk() and never toggles LevelChunk#lightCorrect, preserving
	// the C2ME safety rule established in v253.14.
	private static final int LIGHT_SOURCE_RESEED_ESCALATION = 4;
	private static final int LIGHT_HARD_SKY_RESET_ESCALATION = 8;
	private static final int LIGHT_HARD_SKY_RESET_INTERVAL = 8;
	private static final int LIGHT_HARD_SKY_RESET_CLEAR_TICKS = 6;
	// v253.69.2: a valid future structure mutation can hold one chunk in an
	// intentionally non-final light state for seconds. The old repair ladder could
	// hard-reset SKY storage every eight failed proofs forever. Bound destructive
	// light-storage recovery to two attempts per physical epoch; after that, wait
	// passively for five seconds at a time until blocks change or C2ME converges.
	private static final int LIGHT_MAX_HARD_SKY_RESETS_PER_PHYSICAL_EPOCH = 2;
	private static final int LIGHT_PERSISTENT_SKY_BACKOFF_TICKS = 100;
	// v253.72.9: exponential+jittered persistent retry cadence. The first retry
	// remains about five seconds, then backs off to at most one minute. Jitter is
	// deterministic from the chunk key so a 1024-entry cohort cannot wake on one
	// server tick again.
	private static final int LIGHT_PERSISTENT_SKY_BACKOFF_MAX_TICKS = 6000;
	// v253.73.11: persistent historical faults are correctness debt, not urgent active
	// work. Once they have exhausted the bounded scrub/reseed/hard-reset ladder,
	// repeated wakeups must become progressively rarer so a Forever World cannot
	// spend its entire active-light budget re-proving the same known-bad cohort.
	private static final int LIGHT_PERSISTENT_SKY_BACKOFF_MAX_SHIFT = 6;
	private static final int LIGHT_PERSISTENT_SKY_BACKOFF_JITTER_TICKS = 80;
	/**
	 * v253.73.17. Wall-clock nanos of the last time any light-sync entry actually
	 * retired. The v253.73.16 runtime froze with active=487 for 162 seconds while
	 * persistentWakePressureHolds climbed at exactly 20/s - one hold per tick, the
	 * fingerprint of a gate that can never open. Liveness escapes need a measured
	 * "nothing has finished" signal, not another watermark.
	 */
	/** How long the finalizer may retire nothing before pressure-only gates yield. */
	private static final long LIGHT_FINALIZER_STALL_ESCAPE_NS = 30_000_000_000L;
	/**
	 * v253.73.19 quarantine tier. Correctness semantics are unchanged from the
	 * strict model: a quarantined chunk is NEVER certified, never published with an
	 * authoritative light packet, and keeps counting as lighting-pending. What
	 * changes is only how much of the per-tick finalizer it may consume.
	 *
	 * The v253.73.17 runtime showed ~280 chunks cycling wake -> full strict proof ->
	 * fail -> defer, 16,980 wakes against 956 first-time defers. Each of those wakes
	 * costs a complete analyzeSkyLight (surface + deep + seam + ceiling, ~660 deep
	 * samples) and re-enters lightFinalizerSession().pendingTicks, so a permanently unrepairable
	 * chunk inflates the same `active` pressure number that gates terrain admission.
	 * A fault that has survived the entire ladder twice is not going to be fixed by
	 * running the ladder a third time this minute.
	 */
	/** Consecutive persistent-backoff streak at which a fault becomes quarantined. */
	private static final int LIGHT_SKY_QUARANTINE_DEFER_STREAK = 6;
	/** Retry cadence for a quarantined fault: 10 minutes, not the 5-minute cap. */
	private static final int LIGHT_SKY_QUARANTINE_RETRY_TICKS = 12_000;
	/** Quarantined wakes come from their OWN budget so they can never displace an
	 * ordinary persistent retry. One per this many ticks, world-wide. */
	private static final int LIGHT_SKY_QUARANTINE_WAKE_INTERVAL_TICKS = 100;
	/** Bounded evidence capture. A probe is read-only but not free; a handful of
	 * full column walks per session is enough to classify the fault. */
	private static final int LIGHT_QUARANTINE_MAX_PROBES = 3;
	private static final int LIGHT_QUARANTINE_PROBE_COLUMNS = 4;
	private static final int LIGHT_PERSISTENT_WAKE_SCAN_PER_TICK = 96;
	private static final int LIGHT_PERSISTENT_WAKE_BUDGET_PER_TICK = 4;
	// Bound all expensive finalizer stages independently from the terrain scheduler.
	// One broken lighting cohort must never manufacture 200+ ms server ticks.
	// v253.125.28: cooperative phases are already bounded by the 8ms wall-time
	// envelope, so the old 16/8 operation-count ceilings were leaving cheap fast
	// certifications unused inside that envelope. Raise only the count guard; the
	// same wall-time budget remains authoritative, preserving tick performance.
	private static final int LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_NORMAL = 64;
	private static final int LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_BACKLOG = 64;
	private static final long LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L;
	// 8x8 chunk locality tile: target ordering/ownership is untouched. This only
	// orders already-admitted lighting debt so overlapping 3x3 neighborhoods reuse
	// residency and cache warmth instead of thrashing the 64-ticket pool.
	private static final int LIGHT_LOCALITY_TILE_SHIFT = 3;
	private static final int LIGHT_PATHOLOGY_BACKGROUND_PASS = 16;
	private static final int LIGHT_PATHOLOGY_FAIR_SHARE_DIVISOR = 4;
	// v253.125.20: the .19 global certificate correctly made every stale deep-SKY
	// field correctness debt, but it also let background debt enter the same atomic
	// 23k-41k checkBlock cluster waves used for player-visible repair while never-
	// submitted terrain still existed. A wall-time guard cannot preempt one already-
	// entered Java method. Keep background debt fail-closed but dormant while terrain
	// ownership exists; visible repair remains immediate and global repair resumes
	// automatically once the terrain frontier is drained.
	private static final int LIGHT_GLOBAL_BACKGROUND_PARK_TICKS = 100;
	private static final int LIGHT_GENERIC_PRESSURE_PARK_PER_TICK = 512;
	private static final int LIGHT_GENERIC_PRESSURE_SCAN_LIMIT_PER_TICK = 1024;
	private static final long LIGHT_DEEP_ZERO_REPAIR_TIME_BUDGET_NS = 6_000_000L;
	// v253.72.9: detailed light diagnostics are evidence, not work. The 12-hour
	// soak emitted ~47k duplicate WARN lines and log I/O became part of the stall.
	// Rate-limit per-chunk detail and globally cap detail per tick; suppressed
	// evidence is retained in a periodic aggregate counter instead of discarded.
	private static final int LIGHT_DIAG_DETAIL_WARNINGS_PER_TICK = 2;
	private static final int LIGHT_DIAG_DETAIL_MIN_INTERVAL_TICKS = 1200;
	private static final int LIGHT_DIAG_AGGREGATE_INTERVAL_TICKS = 600;
	// v253.73.10: count boundary-only neighbor re-arms that preserve the existing
	// strict recovery epoch instead of pretending the neighbor itself was physically
	// mutated. This is the runtime proof that a moving forever-world frontier can no
	// longer reset old deep-SKY failures indefinitely.
	// v253.72.1: v253.61.7 treated a previously verified neighbor as permanently
	// immune to later frontier changes. The 20k runtime disproved that assumption:
	// a verified chunk can regress after a later adjacent carve while its client keeps
	// the old light packet. Count the cheap delayed proof/republish work separately.
	// v253.72.3: pure WATER can be a flowing LiquidBlock state. Older physical
	// audits accepted every Blocks.WATER state as canonical and restart light-only
	// recovery never revisited physical terrain, allowing orphan waterfalls to
	// survive indefinitely. Track the narrow, fluid-only repair path separately
	// from full canonicalization so visible recovery can self-heal without touching
	// solid/player-authored blocks.
	// v253.27 staged finalization diagnostics. A block fingerprint is captured only
	// after the physical profile/heightmaps are certified and compared again after
	// the lighting settle window. Any difference proves that something mutated the
	// chunk AFTER Ocean Canvas believed terrain work was finished.
	// v253.61.11: give scheduled falling-block/fluid ticks from the carve time to
	// materialize before a durable physical/light certificate is even attempted.
	// The bounded light settle/verifier follows this gate, so a fresh target gets
	// two independent quiet windows without restoring the old 20-tick initial
	// debounce that caused unload/reload ticket churn.
	// v253.69: vanilla lava can schedule farther out than the old 12-tick window. The
	// soak caught three flowing-water and one flowing-lava mutation after physical
	// certification. Forty ticks covers delayed water/falling work and the normal
	// overworld lava cadence before stage 0 is allowed to certify the profile.
	private static final int LIGHT_TERRAIN_QUIET_TICKS = 40;
	// v253.72.6 introduced deep-water overbright recovery, but later runtime proof
	// (v253.73.14/15) showed that directly replacing live SKY storage with all-zero
	// DataLayers can manufacture hard chunk-aligned black squares. The retained
	// "scrub" ladder now means a bounded, canonical-water-gated re-prime through
	// supported threaded/public light-engine operations only. No live SKY DataLayer
	// is replaced; strict surface/deep-seam verification remains the sole certificate.
	private static final int LIGHT_MAX_DEEP_ZERO_SCRUBS_PER_PHYSICAL_EPOCH = 2;
	private static final int LIGHT_DEEP_ZERO_SCRUB_SETTLE_TICKS = 6;
	// v253.73.4+: the v253.72.8/73.3 direct-map fallback is runtime-disproven.
	// After bounded re-prime attempts fail, recovery may only rebuild/reseed the
	// public threaded source graph and re-prime safe resident sections behind the
	// same canonical-water proof. The strict verifier still runs after recovery;
	// successful queueing is never equivalent to certification.
	private static final int LIGHT_MAX_DEEP_ZERO_PUBLIC_RECOVERIES_PER_PHYSICAL_EPOCH = 2;
	private static final int LIGHT_DEEP_ZERO_PUBLIC_RECOVERY_SETTLE_TICKS = 4;
	// v253.125.14 introduced player-visible dense deep-water decrease propagation.
	// v253.125.15 runtime proof showed two defects in that first implementation:
	// (1) requiring all 4,096 cells in the section to be canonical water made one
	// unrelated preserved/protected block veto repair of a locally proven plain-water
	// anomaly, and (2) six settle ticks could consume both attempts before the
	// threaded/C2ME light engine had applied the first dense wave. Keep the repair
	// visible-only and bounded, but target the exact locally proven bad section and
	// give each queued wave a real three-second settle window.
	private static final int LIGHT_MAX_VISIBLE_DEEP_DENSE_REPAIRS_PER_PHYSICAL_EPOCH = 2;
	private static final int LIGHT_VISIBLE_DEEP_DENSE_REPAIR_SETTLE_TICKS = 60;
	private static final int LIGHT_VISIBLE_DEEP_DENSE_REPAIR_MAX_SECTIONS = 1;
	// v253.125.16: the 125.15 runtime proved a second class of visible defect.
	// A correctly targeted center section can drop to zero while an adjacent chunk
	// remains at SKY 7-9 and immediately re-feeds the seam. After both local dense
	// waves fail, repair the connected light graph rather than repeating the same
	// center-only operation. Attempt 1 uses center+cardinals (5 chunk-sections);
	// attempt 2 uses the full resident radius-1 3x3 (9 chunk-sections).
	// v253.125.17 adds one final visible-only attempt across every ocean-water
	// section from the configured floor band through sea level in that resident 3x3.
	// All three use only public light-engine operations and never load chunks.
	private static final int LIGHT_MAX_VISIBLE_DEEP_CLUSTER_REPAIRS_PER_PHYSICAL_EPOCH = 3;
	private static final int LIGHT_VISIBLE_DEEP_CLUSTER_REPAIR_SETTLE_TICKS = 120;
	// v253.125.17: if both connected same-section waves fail, the remaining ocean-floor
	// class may be fed vertically through a stale section above/below the sampled y. The
	// third and final visible-only escalation rechecks the complete canonical ocean-water
	// column across the resident 3x3 and therefore needs a longer threaded-light settle.
	private static final int LIGHT_VISIBLE_DEEP_CLUSTER_COLUMN_SETTLE_TICKS = 240;
	private static final int LIGHT_VISIBLE_DEEP_CLUSTER_GLOBAL_SPACING_TICKS = 40;
	// v253.125.25: never submit a 4,608-200k+ public-light wave atomically. The
	// .24 runtime recorded a 714ms flattener sample and 99.5% heap while deep repair
	// was active. Slice deterministic checkBlock work across ticks; strict proof still
	// runs only after the entire wave is submitted and settled.
	private static final int LIGHT_DEEP_REPAIR_SLICE_CHECK_BUDGET = 512;
	private static final long LIGHT_DEEP_REPAIR_SLICE_TIME_BUDGET_NS = 2_500_000L;
	private static final double LIGHT_DEEP_REPAIR_HEAP_PAUSE_FRACTION = 0.88D;
	private static final int LIGHT_DEEP_REPAIR_HEAP_PAUSE_TICKS = 10;
	private static final int LIGHT_DEEP_REPAIR_MAX_CONSECUTIVE_HEAP_DEFERRALS = 5;
	// v253.125.26: read-only proof/audit phases showed 200-974ms atomic tails in
	// the .25 runtime. Cooperative slices use coarser operation checkpoints than
	// deep checkBlock repair, but they persist cursor state instead of restarting.
	private static final long LIGHT_PROOF_SLICE_TIME_BUDGET_NS = 4_000_000L;
	private static final int LIGHT_PHYSICAL_AUDIT_MAX_COLUMNS_PER_SLICE = 64;
	private static final int LIGHT_FINGERPRINT_MAX_COLUMNS_PER_SLICE = 64;
	private static final int LIGHT_SWEEP_MAX_CHECKS_PER_SLICE = 128;
	private static final long LIGHT_SWEEP_SLICE_TIME_BUDGET_NS = 2_500_000L;
	private static final double LIGHT_HEAVY_PHASE_HEAP_PAUSE_FRACTION = 0.82D;
	private static final int LIGHT_HEAVY_PHASE_HEAP_PAUSE_TICKS = 4;
	private static final int LIGHT_HEAVY_PHASE_MAX_CONSECUTIVE_HEAP_DEFERRALS = 5;
	// Demonstrated late block physics gets a chunk-local longer quiet window instead
	// of globally penalizing every healthy chunk. The first late mutation doubles the
	// normal 40-tick window; repeated instability escalates to a bounded 320 ticks.
	private static final int LIGHT_TERRAIN_QUIET_MAX_TICKS = 320;
	private static final int LIGHT_PATHOLOGY_HOTSPOT_ESCALATION = 16;
	private static final long LIGHT_PHASE_SLOW_WARN_NS = 25_000_000L;
	// v253.35: a chunk can look healthy during the staged finalizer and then regress
	// after its residency ticket is released / an async lighting commit lands. The
	// end-of-job integrity gate records those regressions and re-arms the normal
	// finalizer instead of logging the defect and falsely declaring success.

	// v253.61.9 restart/rejoin durability gate. The persistent lighting certificate
	// proves that a chunk's live server skylight was healthy at the authoritative
	// publication boundary, but it does not prove that the exact SKY nibble arrays
	// later written to disk and reloaded into a new server/client session are still
	// identical. A real save/rejoin test exposed long chunk-aligned dark seams while
	// the certificate caused those chunks to be metadata-skipped. Audit each loaded
	// certified Canvas chunk once per server session, and explicitly re-publish it
	// after a player joins so packet ordering cannot leave the new client with a stale
	// light snapshot. This is intentionally lazy/visible-only: it never turns a
	// restart into a 1.5M-chunk relight.
	// v253.107: persisted-light audit scheduling is one explicit session component.

	private static final int PERSISTED_LIGHT_AUDIT_LOAD_DELAY_TICKS = 2;
	private static final int PERSISTED_LIGHT_AUDITS_PER_TICK = 16;
	// v253.125.6 spawn/rejoin visibility gate. The supplied runtime logged
	// loadedCandidates=0 on both JOIN callbacks, proving the old one-shot audit ran
	// before tracking chunks existed. Keep a short bounded window and inspect only
	// already-loaded + actually-tracked chunks, nearest to the player first.
	private static final int REJOIN_LIGHT_AUDIT_WINDOW_TICKS = 400;
	private static final int REJOIN_LIGHT_AUDIT_CHUNKS_PER_TICK = 2;
	private static final int REJOIN_LIGHT_AUDIT_RADIUS_CAP_CHUNKS = 16;
	// v253.125.18 runtime proof: after the ten-second rejoin window ended, the
	// server still found real deep-overbright chunks around the player's second
	// location, but visibleDeepDenseRepairs/visibleDeepClusterRepairs stayed at zero
	// and the client reported zero deep failures. The visible repair ladder therefore
	// had no admission path after rejoin. Continuously audit a tiny rotating subset
	// of already-resident, actually-tracked Canvas chunks. This never loads chunks.
	private static final int VISIBLE_LIGHT_SENTINEL_RADIUS_CAP_CHUNKS = 8;
	private static final int VISIBLE_LIGHT_SENTINEL_PROOFS_PER_TICK = 4;
	// v253.125.54: visible persisted chunks can carry old missing/flowing-water
	// corruption even while their durable physical seal still says verified.  Keep
	// this repair deliberately narrow and bounded: only AIR/flowing-WATER cells in
	// an otherwise explicit Canvas water column may be restored, never solid blocks.
	private static final int VISIBLE_WATER_VOID_REPAIR_MAX_BLOCKS_PER_CHUNK_PASS = 256;
	// v253.125.10 fast-proof evidence floors. A normal open-water Canvas chunk
	// contributes 256 surface columns and hundreds of deep samples. Requiring a
	// meaningful subset prevents a structure/protected chunk with zero eligible
	// columns from receiving a certificate through the shortcut.
	private static final int FAST_LIGHT_PROOF_MIN_SURFACE_SAMPLES = 64;
	private static final int FAST_LIGHT_PROOF_MIN_DEEP_SAMPLES = 64;
	private static final int FAST_PRESSURE_PROOFS_PER_TICK = 2;
	private static final int VISIBLE_PRIORITY_SCAN_LIMIT = 96;


	// Tracks every ChunkPos ever added to pregenSession().PENDING_CHUNKS, so the
	// repeated startup sweep below never re-queues the same chunk
	// twice. Without this, sweeping the same ~1000-chunk area on every
	// one of 100 startup ticks could add well over 100,000 redundant
	// entries - almost certainly the real cause of the server falling
	// behind on ticks, and likely delaying real progress by burying it
	// under duplicate work rather than actually fixing the timing race
	// this sweep exists to win.

	// Explicit reset/regeneration bypass. Ordinary chunk loads and ordinary
	// pregen never enter this set, so once a chunk has been processed its
	// terrain and entities are immutable to Ocean Canvas during gameplay.

	// Pregen ownership is tracked separately from the shared flattener queue.
	// This is what lets /oceancanvas pregen cancel withdraw work that the
	// cancelled pregen submitted without deleting ordinary gameplay chunk
	// work or destructive Reset work from the same queue.
	// v253.72: transient marker for restart/crash-quarantine targets. A persisted
	// physical/light certificate is not sufficient evidence after an interrupted
	// save boundary, so these targets must pass the physical-aware finalizer even
	// when their old metadata says they were complete. The durable identity lives
	// in PregenManager's recovery journal; this set is rebuilt on every resume.
	// v253.73.11 proactive Forever World partition. Track journal-proven LIGHT_ONLY
	// recovery separately from ordinary new-terrain work so a huge historical debt
	// cohort can be kept in a small active window instead of flooding the finalizer.
	// v253.73.13: PHYSICAL_AWARE restart recovery needs an independent bounded
	// finalizer window. The v253.73.12 runtime proved that bounding only LIGHT_ONLY
	// debt still allowed the 823 physical-recovery chunks to refill the same global
	// light queue from 276 -> 1025 active entries. TRACKED is recovery identity;
	// ACTIVE is derived only while the chunk is in lightFinalizerSession().pendingTicks. These
	// sets never grant a certificate and never change physical-repair permission.
	// Session-local proof used by the adaptive controller. Replay retirement and
	// metadata skips MUST NOT earn new-terrain capacity. Only a non-recovery target
	// that actually reaches the post-flatten authoritative retirement path counts.
	/**
	 * v253.73.16. pregenSession().PREGEN_NEW_TERRAIN_RETIREMENTS deliberately counts only freshly
	 * carved, non-recovery targets. That is the right meaning for "new terrain",
	 * but it is the WRONG meaning for "is the job moving", and the v253.73.14
	 * runtime proved the difference matters: while the cursor demonstrably walked
	 * the z=512 frontier at ~4 chunks/s (visible in FLUID-DIAG's advancing chunk
	 * coordinates), every retirement took the "existing-seal" or crash-recovery
	 * path, so the new-terrain counter never moved and the overnight predictor
	 * reported normalTerrainRate=0.00 with projectedEta=52300.0h. This counter
	 * records EVERY retirement that releases PREGEN_TARGET ownership - i.e. every
	 * chunk the job will never have to visit again - and is the honest input for
	 * forward-progress questions.
	 */
	// Subset of recovery targets that were not durably committed and may safely
	// canonicalize a late physical mutation. Committed safety-tail chunks are
	// LIGHT-ONLY so crash recovery cannot erase legitimate post-Pregen player work.
	// First time a pregen target was requested. v68 uses this to recover the
	// rare C2ME/FULL-future case where a target remains forever in
	// "loading-not-queued" even though the rest of the job has drained.
	// The normal async path stays untouched; only a genuinely stale request is
	// synchronously resolved during final drain so 6 dead futures cannot pin a
	// job at 99.9% forever.
	// v228.2: a radius-3 ownership ticket is the normal residency primitive, but the
	// real v228.1 run exposed a second, distinct failure mode: a bounded cohort can
	// remain pregenSession().PREGEN_TARGET_CHUNKS-but-not-pregenSession().PREGEN_QUEUED_CHUNKS for 5-15s while C2ME
	// has not delivered the target's FULL LevelChunk yet. Do not reintroduce the old
	// per-target radius-0 ticket layer globally. Instead, pulse only genuinely stale
	// target loads through the already-bounded nonblocking force-load path. Each target
	// is throttled and the pulse auto-releases when FULL arrives.
	private static final long PREGEN_TARGET_LOAD_RESCUE_MIN_AGE_MS = 2500L;
	private static final long PREGEN_TARGET_LOAD_RESCUE_RETRY_MS = 5000L;

	// v228.2 physical-audit repair telemetry. A stale pre-v73 seal mismatch is not
	// itself a failure if the immediately-following flatten repairs it. Persistently
	// failing the post-flatten audit IS a failure. Track the distinction explicitly so
	// the next log tells us whether old terrain is simply being repaired or a current
	// flattener invariant has regressed.
	// v96: retain handles only for Ocean Canvas-owned async chunk requests. C2ME
	// can otherwise keep a very large generation/unload tail alive when a huge
	// Pregen is stopped. The maps are bounded by the adaptive outstanding
	// window/support retries and are cancelled explicitly during Save & Quit.
	// v228.4: a FORCED ticket establishes desired ticket level, but the v228.3
	// runtime proved that ticket ownership alone can leave a cold target waiting
	// 5-15s before C2ME actually advances it to FULL. Explicitly demand FULL via
	// ServerChunkCache#getChunkFuture(..., true), but NEVER invoke that API from
	// the server thread: real watchdog dumps from v137/v207 proved the main-thread
	// call can enter BlockableEventLoop.managedBlock and freeze ticks. The tiny
	// daemon dispatcher below calls it off-thread, which is the supported vanilla
	// branch that schedules getChunkFutureMainThread onto the server executor
	// without making the server thread itself managed-block waiting for completion.
	// World mutation remains on the server thread; the completion callback only
	// posts a getChunkNow/enqueue check back to MinecraftServer#execute.
	private static final int PREGEN_FULL_DEMAND_MAX_ACTIVE = 2;
	private static final long PREGEN_FULL_DEMAND_INITIAL_DELAY_MS = 1000L;

	private static final long PREGEN_FULL_DEMAND_RETRY_MS = 5000L;
	private static final long PREGEN_TARGET_STALE_LOAD_MS = 15000L;
	// v70: recovery timing is measured from the moment a forced ticket is
	// installed, NOT from the target's original submission time. v69 used the
	// original request timestamp for both decisions, so a target submitted more
	// than 60s earlier could receive its recovery ticket and be failed in the
	// very same tick before Minecraft/C2ME had any chance to honor it.
	private static final long PREGEN_FINAL_DRAIN_RETRY_MS = 30000L;
	private static final int PREGEN_FINAL_DRAIN_TICKET_RADIUS = 3;
	// v95 20k-scale recovery: a stalled feed can contain dozens of old C2ME
	// requests. Installing a radius-2 FORCED ticket for every one at once can
	// itself retain roughly a thousand support chunks and make heap pressure
	// worse. Recovery is therefore intentionally a small moving window.
	// v102 restored the pre-v96 224/320-target outstanding window, but these
	// two caps were never rescaled from the ~48-64-target era they were tuned
	// for. At a 224+ window almost every outstanding target can legitimately
	// be mid-flight at once, so a cap/slice sized for 48 leaves the vast
	// majority of a stalled window completely unserviced per pass. Scaled up
	// by the same rough factor as the window itself; still an order of
	// magnitude below the window so worst-case retained support-chunk memory
	// stays bounded (16 * radius-2 tickets, not 224 of them).
	// v114: a real 20k overnight run sustained missingNeighbor at 83-97% of a
	// 224-target queue (up to 185 simultaneously stalled) for minutes at a
	// time. A 32-target slice only reaches the full outstanding window once
	// every ~10 nudge calls (~10s while stalled), and most of each slice's
	// stalled entries were skipped anyway once PREGEN_PROCESSING_LEASE_MAX
	// below was already full - so the vast majority of a stalled window sat
	// completely unserviced, cycle after cycle. Scaled up so a full window is
	// re-scanned in ~3-4 calls instead of ~10.
	private static final int PREGEN_RECOVERY_MAX_TICKETS = 32;
	private static final int PREGEN_RECOVERY_TARGETS_PER_NUDGE = 96;
	// v253.99: Java-side final-drain ticket ownership moved into a dedicated
	// state ledger. Engine ticket mutation deliberately remains in this class.

	// v209 note: final-drain tickets also use radius 3 for the same reason as the
// processing leases below: a target cannot complete while its immediate 3x3
// support ring remains below the ticking tier.
// v100: a FULL future is not residency. The v99 20k run proved that an
	// entire active window can reach LevelChunk and then deadlock at
	// neighborsReady(): queued=48, neighbor=48, loading=0. Support futures
	// completed but C2ME was free to unload those support chunks before the
	// target got its flattener turn, so the watchdog merely re-requested the
	// same neighbors every five seconds forever. Keep a tiny, bounded set of
	// target-centered radius-2 leases alive through physical retirement.
	// Four overlapping leases were enough to break the residency stalemate
	// at the 48-64-target window this was tuned against. v102 restored the
	// 224/320-target window without rescaling this cap, so a run where most
	// of a 224-wide window converges on "neighbor-stalled" (the exact
	// outstanding==neighbor, loading=0, completed~0 signature seen on the
	// 20k run) had only 4 targets able to make progress at any time -
	// effectively zero throughput relative to the window size. Scaled up
	// proportionally; still well below the window so worst-case retained
	// support-chunk memory (16 * radius-2 tickets) stays bounded.
	// v103: leases install correctly but ALL install in the very same tick the
	// nudge call runs, since nothing previously throttled how many NEW leases
	// a single nudgeOutstandingPregenTargets call could install - only the
	// total concurrent cap (PREGEN_PROCESSING_LEASE_MAX). On the 20k run this
	// produced 16 fresh radius-2 (5x5=25 chunk) FORCED tickets in one burst -
	// up to ~400 chunk load requests in a single tick - immediately followed
	// by "Can't keep up! Running Nms or N ticks behind" warnings each time the
	// window refilled to capacity. Capping new installs per call spreads that
	// same total lease budget across several ticks instead of one spike.
	// v114: 16 concurrent leases against a real 185-target simultaneous stall
	// (83-97% of a 224-target window, sustained for minutes on the v106 run)
	// is roughly 9% coverage - the breaker paused new admission correctly but
	// had nothing that could actually drain a backlog that size, so it kept
	// re-engaging every time the queue crept back over the pause ratio.
	// Raised the concurrent cap; each lease's marginal load cost is small
	// (the 3x3 support neighborhood is already right next to an
	// already-resident target, typically 1-3 genuinely new chunks, not a
	// full fresh 5x5), so this is a materially different risk than the v103
	// burst (16 leases into never-before-touched territory in one tick).
	// v209 halves new-per-nudge from 12 to 6 because radius-3 leases are materially stronger; it remains well under the cap -
	// so a single nudge call cannot itself reproduce that burst.
	private static final int PREGEN_PROCESSING_LEASE_NEW_PER_NUDGE = 2;
	// v209: Minecraft ticket radius and ticking-distance are not the same thing. A
// radius-2 FORCED ticket puts the CENTER at level 31, but its adjacent support
// chunks inherit level 32. neighborsReady() requires every member of the 3x3
// window to satisfy isPositionTicking(), so the old radius-2 processing lease
// could never guarantee the very gate it existed to satisfy. The v208 runtime
// log proved this directly: leased targets stayed open ~11s while the queue
// repeatedly returned to neighborStale=outstanding, loading=0. Radius 3 puts
// the adjacent ring at the required ticking level. Because each lease is now
// one ring stronger, hard-freeze recovery installs only one new lease per
// watchdog pass to keep burst cost bounded.
// v202.42: a hard-freeze "hold new requests" state must still be able to
	// create a TINY number of recovery leases for targets that are already
	// resident and already owned by Pregen. The 2026-08-31 runtime log proved
	// the previous gate could deadlock all 28 live targets at neighborStale:
	// loading=0, leases=0, zero retirements for 127s. Two leases per watchdog
	// pass is enough to create forward progress without recreating v103's
	// radius-2 ticket burst.
	private static final int PREGEN_PROCESSING_LEASE_NEW_WHILE_HELD = 1;
	private static final int PREGEN_PROCESSING_LEASE_MAX = 8;
	private static final int PREGEN_CARVE_LANE_NEW_PER_TICK = 2;
	private static final int PREGEN_CARVE_LANE_SUPPORT_REQUESTS_PER_TICK = 1;
	private static final int PREGEN_PROCESSING_LEASE_RADIUS = 3;
	// v253.100: logical CARVE-lane membership plus optional physical recovery-ticket
	// bookkeeping now live in a state-only component. Readiness and every engine
	// ticket mutation remain in this flattener.
	// v132.6: stale-age threshold remains policy here; the ledger owns only the
	// timestamp and one-shot stale-report identity for each logical promotion.
	private static final long PREGEN_PROCESSING_LEASE_STALE_LOG_MS = 10000L;

	// Relocated-shipwreck tracking used to live here as a plain static
	// in-memory Set - that was the actual root cause of the chest
	// getting destroyed again on every restart. It's now persisted
	// per-world via OceanCanvasProtectedData (see that class's doc for
	// the full story), keyed the same way as before: the structure's
	// original min-corner, stable/deterministic for a given generated
	// structure regardless of how many times its chunk is re-processed.

	// How far out (in chunks) to sweep around spawn, catching any chunk
	// the initial "Preparing spawn area" pregeneration loaded without
	// firing CHUNK_LOAD. Generous on purpose - a few extra harmless
	// re-checks cost nothing, missing real spawn chunks does.
	private static final int STARTUP_SWEEP_RADIUS_CHUNKS = 16;

	// How many server ticks after start to keep re-sweeping, instead of
	// a single one-shot attempt - see the class doc for why timing a
	// single sweep precisely isn't reliable enough on its own.
	private static final int STARTUP_SWEEP_TICKS = 100; // ~5 seconds

	// Only actually re-scan the full sweep radius every this-many ticks,
	// not literally every tick - see the comment where this is used.
	private static final int STARTUP_SWEEP_SCAN_INTERVAL_TICKS = 5;

	// v222: a fixed "chunks per tick" cap is not a sufficient server-thread
	// safety bound because flattenChunk cost varies wildly with structures, old
	// physical-audit repair, falling-block cleanup, and notification work. During
	// live Pregen, stop launching additional destructive flattens once this much
	// wall time has already been spent in the flattener for the current tick.
	// One expensive chunk is still allowed to finish atomically; the budget only
	// prevents stacking several expensive chunks into the same server tick.
	private static final long PREGEN_FLATTEN_WALL_BUDGET_NS = 20_000_000L;
	private static final int PREGEN_READINESS_CHECK_BUDGET = 96;

	// Real bug fixed here, found from an actual "Can't keep up! ...
	// Running 51784ms or 1035 ticks behind" report: nothing in this file
	// capped how many chunks flattenChunk actually ran for in a single
	// tick. The startup sweep alone can queue on the order of a
	// thousand chunk positions near spawn; once enough of their
	// neighbors finish loading at once, all of them became "ready" in
	// the same tick and every one of them ran its full per-column carve
	// (now also the falling-block cleanup pass) synchronously before
	// the tick could end - exactly what a multi-second-to-a-minute
	// stall looks like, and also why a chunk (e.g. one containing a
	// natural shipwreck) can genuinely still be sitting unflattened /
	// unrelocated the first time a player reaches it, purely because it
	// was stuck behind the pileup. Caps the number of chunks that
	// actually get the expensive flattenChunk treatment per tick;
	// re-checking readiness for the rest stays uncapped since that part
	// (hasChunk/neighborsReady) is cheap. A large startup burst now
	// spreads across many ticks instead of overwhelming one - e.g. the
	// sweep's ~1,089 positions take roughly 1089/flattenerChunksPerTick
	// ticks to fully drain instead of however many happen to become
	// ready simultaneously in a single tick.
	//
	// Now config-driven ({@link OceanCanvasConfig#flattenerChunksPerTick()},
	// default 16) instead of this hardcoded constant - real gap noticed
	// while reviewing the project for further work: {@link
	// OceanCanvasConfig#pregenChunksPerTick} already made the OPT-IN
	// pregen/reset/expand throttle tunable without a code change, but this
	// method's own everyday "land converts as you approach" throttle
	// stayed a hardcoded constant. See that config field's doc comment
	// for the full reasoning on why its default (16) is deliberately
	// higher than pregenChunksPerTick's (4).


	private OceanCanvasSurfaceFlattener() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register(OceanCanvasSurfaceFlattener::onChunkLoad);
		ServerChunkEvents.CHUNK_UNLOAD.register(OceanCanvasSurfaceFlattener::onChunkUnload);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				beginJoinPersistedLightAudit(handler.getPlayer()));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
            long started=System.nanoTime();
            try { OceanCanvasSurfaceFlattener.onServerTick(server); }
            finally { net.oceancanvas.mod.diagnostic.OceanCanvasWorkloadPhaseProfiler.record("terrain.flattener",System.nanoTime()-started); }
        });
	}

	// CHUNK_LOAD's 3-argument signature (ServerLevel, LevelChunk, boolean)
	// is confirmed against 26.2 - this has compiled and run through every
	// in-game test round so far. The boolean (newlyGenerated) still isn't
	// used for anything - see the class doc's tradeoff note for the
	// once-per-chunk-instead-of-every-load idea it could enable later.

	private static void onChunkLoad(ServerLevel world, LevelChunk chunk, boolean newlyGenerated) {
		if (world.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			return;
		}
		// v253.125.26: C2ME can legally complete CHUNK_LOAD callbacks while
		// MinecraftServer.stopServer is draining storage, after Ocean Canvas has
		// already closed its server-scoped runtime. Never recreate/reopen state and
		// never call the throwing pregenSession() accessor from this callback after
		// shutdown begins. The .25 runtime captured four such callbacks.
		OceanCanvasServerRuntime runtime = OceanCanvasServerRuntime.onlyActiveOrNull();
		if (runtime == null) {
			// v253.125.31: once the server runtime is closed there is deliberately no
			// process-global counter to mutate. Late C2ME callbacks are expected during
			// storage shutdown; ignore them without recreating server-scoped state.
			if (OceanCanvas.LOGGER.isDebugEnabled()) OceanCanvas.LOGGER.debug(
					"(Ocean Canvas) SHUTDOWN-LATE-CHUNK-LOAD-IGNORED build={} chunk={},{} state=runtime-closed action=do-not-reopen-server-runtime",
					net.oceancanvas.mod.OceanCanvas.VERSION, chunk.getPos().x(), chunk.getPos().z());
			return;
		}
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) {
			OceanCanvasLightTelemetrySession telemetry = runtime.state(OceanCanvasLightTelemetrySession.class, OceanCanvasLightTelemetrySession::new);
			long ignored = telemetry.LIGHT_DIAG_LATE_SHUTDOWN_CHUNK_LOADS_IGNORED.incrementAndGet();
			if (ignored <= 4L || (ignored & 63L) == 0L) {
				OceanCanvas.LOGGER.info("(Ocean Canvas) SHUTDOWN-LATE-CHUNK-LOAD-IGNORED build={} chunk={},{} count={} action=do-not-reopen-server-runtime",
						net.oceancanvas.mod.OceanCanvas.VERSION, chunk.getPos().x(), chunk.getPos().z(), ignored);
			}
			return;
		}
		OceanCanvasPregenFlattenerSession pregen = runtime.state(OceanCanvasPregenFlattenerSession.class,
				() -> new OceanCanvasPregenFlattenerSession(PREGEN_FULL_DEMAND_MAX_ACTIVE, STARTUP_SWEEP_TICKS));

		long loadedKey = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
		// Ordering hint only. The finalizer still re-reads the authoritative chunk
		// from ServerChunkCache immediately before doing any proof/repair work.
		runtime.state(OceanCanvasLightFinalizerSession.class, OceanCanvasLightFinalizerSession::new)
				.loadedChunkHints.add(loadedKey);
		pregen.PREGEN_SUPPORT_LAST_REQUEST_MS.remove(loadedKey);
		// v163.4: do NOT call releaseFinalDrainTicket here - see
		// PREGEN_FINAL_DRAIN_PENDING_RELEASE's doc comment for why mutating
		// tickets synchronously inside CHUNK_LOAD caused a real crash. Queue
		// it; onServerTick drains this every tick at a safe, non-reentrant
		// boundary.
		if (pregen.PREGEN_FINAL_DRAIN_LEDGER.contains(loadedKey)) {
			pregen.PREGEN_FINAL_DRAIN_LEDGER.queueDeferredRelease(loadedKey);
		}

		// v253.125.31: historical persisted-certificate audits are irrelevant for a
		// newly generated chunk and redundant for an admitted Pregen target whose own
		// strict finalizer already owns the authoritative certificate boundary. The
		// .30 diagnostic caught this SavedData/terrain-state lookup in several load
		// stalls, so keep it only for genuine historical loads.
		if (!newlyGenerated && !pregen.PREGEN_TARGET_CHUNKS.contains(loadedKey)) {
			schedulePersistedLightAuditIfCertified(world, chunk, PERSISTED_LIGHT_AUDIT_LOAD_DELAY_TICKS, false);
		}

		// Never flatten immediately here - always queue and let the
		// tick-based check below confirm neighbors are actually ready
		// first. See the class doc for why this is the only approach
		// that actually avoids the self-referential stall. Deduped via
		// pregen.QUEUED_CHUNK_POSITIONS - see that field's comment.
		if (pregen.QUEUED_CHUNK_POSITIONS.add(chunk.getPos())) {
			pregen.PENDING_CHUNKS.add(chunk);
			long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			if (pregen.PREGEN_TARGET_CHUNKS.contains(packed)) {
				pregen.PREGEN_QUEUED_CHUNKS.add(packed);
			}
		}
	}

	private static void onChunkUnload(ServerLevel world, LevelChunk chunk) {
		if (world.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
		OceanCanvasServerRuntime runtime = OceanCanvasServerRuntime.onlyActiveOrNull();
		if (runtime == null) return;
		runtime.state(OceanCanvasLightFinalizerSession.class, OceanCanvasLightFinalizerSession::new)
				.loadedChunkHints.remove(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
	}

	/**
	 * Entry point for {@code net.oceancanvas.mod.pregen.PregenManager}
	 * (the drafted, off-by-default Chunky-like pre-generation feature -
	 * see that class's doc comment for the full design). Deliberately
	 * identical in shape to {@link #onChunkLoad}'s body - a chunk that's
	 * already resident just needs to enter the same queue an organically
	 * loaded chunk would, so this reuses that exact queue instead of
	 * introducing a second, parallel one. Safe to call for a chunk
	 * that's already queued or already flattened (a no-op via
	 * {@link #pregenSession().QUEUED_CHUNK_POSITIONS}'s existing dedup, same as always).
	 */
	/**
	 * v137: the real, non-blocking way to force a chunk toward FULL status.
	 * {@code ServerChunkCache#getChunkFuture(x, z, status, require=true)} LOOKS
	 * async (it returns a CompletableFuture) but Mojang's own implementation is
	 * NOT actually async when called from the main thread with require=true: it
	 * internally calls {@code BlockableEventLoop#managedBlock}, which
	 * synchronously blocks the calling thread - here, the server tick itself -
	 * until the chunk genuinely reaches the requested status. This is a
	 * real, independently-documented Minecraft quirk (see e.g. Mekanism's own
	 * "getChunkFuture isnt actually async on main thread" fix), not a guess.
	 *
	 * <p>Confirmed directly against a real user thread dump (round 37): a 20s
	 * stall watchdog caught "Server thread" TIMED_WAITING 27+ seconds inside
	 * exactly this call (via {@link #neighborsReady}'s one-hop exploration
	 * force-load), parked under {@code ServerChunkCache.getChunkFuture ->
	 * BlockableEventLoop.managedBlock -> waitForTasks}. This is the real root
	 * cause of the "Can't keep up!" freezes that persisted even after v135's
	 * unrelated (but also real) {@code chunk.getBlockState} fix - v135 fixed a
	 * genuine bug, just not this one.</p>
	 *
	 * <p>{@code TicketType.FORCED} + {@code addTicketWithRadius}/
	 * {@code removeTicketWithRadius} - NOT a guessed API. This exact call
	 * shape is already real, compiled code elsewhere in this project (see
	 * {@code OceanCanvasRestoreManager}'s native-regen path), so it's the
	 * highest-confidence non-blocking primitive available rather than a new
	 * unverified one. {@code FORCED} is a permanent ticket with no built-in
	 * expiry (unlike a custom self-expiring TicketType would be), so unlike
	 * Restore's single-chunk case this general-purpose helper tracks every
	 * chunk it force-loads and releases the ticket itself the moment
	 * {@code world.hasChunk} goes true, via {@link #releaseSettledForceLoadTickets},
	 * called once per tick from {@link #onServerTick}. A safety-net max age
	 * releases a ticket even if the chunk somehow never settles, so a single
	 * pathological chunk can never leak a ticket forever.
	 *
	 * <p><b>v206: added a real size cap, closing a gap v204 left open.</b>
	 * v204 capped how many entries {@link #releaseSettledForceLoadTickets}
	 * removes per tick, but never capped how many can be INSTALLED. If
	 * installs ever outpace that per-tick drain cap for long enough - very
	 * plausible at this project's scale, since this map is written from
	 * many call sites across the whole file - the map grows without bound
	 * regardless of the drain cap, and since {@code
	 * TicketStorage#getAllChunksWithTicketThat} scans ALL live FORCED
	 * tickets on every single removal (confirmed directly from a real
	 * escalating-stall thread dump, see v205's self-ticket-pool-cap doc),
	 * an ever-growing map here makes every removal in the whole file
	 * slower forever, the same failure family v205 just fixed for the
	 * self-ticket pool. Same fix, same reasoning, applied here too: once
	 * this map reaches {@link #OCEANCANVAS_FORCE_LOAD_TICKET_MAX} live
	 * entries, a new caller simply doesn't get a ticket installed (it
	 * still gets ordinary chunk-load handling) until enough existing
	 * entries settle or age out. Also shortened the safety-net max age
	 * from 30s to 10s in the same pass - real stalls in this project have
	 * consistently resolved within single-digit seconds when they resolve
	 * at all, so 30s of guaranteed residency for a genuinely stuck chunk
	 * was mostly just delaying this pool's own cleanup, not helping
	 * anything settle. */
	// v253.102: generic force-load Java ownership is centralized in a Minecraft-free ledger.
	private static final long OCEANCANVAS_FORCE_LOAD_TICKET_MAX_AGE_MS = 10_000L;
	private static final int OCEANCANVAS_FORCE_LOAD_TICKET_MAX = 3000;

	// v253.73.3: Save & Quit must prove the REAL TicketStorage close transition,
	// not merely clear Ocean Canvas' Java-side bookkeeping. The 11:42 runtime
	// reproduced a false PASS followed by FastQuit waiting indefinitely with 3,688
	// chunks pinned. 26.2 exposes ServerChunkCache#deactivateTicketsOnClosing()
	// specifically for this lifecycle boundary; use that one bulk native transition
	// instead of thousands of removeTicketWithRadius calls (a previously proven
	// O(n^2)-like stall path when the global FORCED set is large).

	// v163.4: onChunkLoad used to release a final-drain ticket synchronously
	// (removeTicketWithRadius, mutating vanilla's DistanceManager ticket-level
	// map) from inside the CHUNK_LOAD event. A real crash showed a
	// NullPointerException from inside DistanceManager.forEachEntityTickingChunk's
	// own iteration over that same map (Long2ByteOpenHashMap$MapIterator -
	// "this.wrapped is null", the signature of a hash map resize invalidating
	// an in-progress iterator) - c2me's rewritten async chunk system can fire
	// CHUNK_LOAD from within its own chunk-ticking pass, making synchronous
	// ticket mutation there reentrant and unsafe. This queue defers the actual
	// removeTicketWithRadius call to onServerTick (END_SERVER_TICK), the same
	// safe point already used for releaseSettledForceLoadTickets - a tick
	// boundary is never reentrant with mid-tick chunk iteration.
	// v217: ticket removal is not O(1) in real runtime traces. Never drain an
	// arbitrarily large CHUNK_LOAD release cohort in one server tick.
	private static final int PREGEN_FINAL_DRAIN_RELEASES_PER_TICK = 32;

	/** v137: made public - the exact same blocking getChunkFuture(...,true)
	 * anti-pattern turned out to exist in several other files too
	 * (PregenManager's own main generation loop, OceanCanvasUndoManager,
	 * OceanCanvasRestoreManager) - all of them
	 * now share this one real fix instead of re-solving it differently each
	 * place. */
	public static void requestNonBlockingChunkLoad(ServerLevel world, int cx, int cz) {
		long packed = ChunkPos.pack(cx, cz);
		try {
			OceanCanvasForceLoadTicketLedger.InstallResult result = pregenSession().OCEANCANVAS_FORCE_LOAD_LEDGER.install(
					packed, monotonicMillis(), OCEANCANVAS_FORCE_LOAD_TICKET_MAX,
					p -> net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(
							world, new ChunkPos(ChunkPos.getX(p), ChunkPos.getZ(p)), 0));
			if (result == OceanCanvasForceLoadTicketLedger.InstallResult.CAP_REJECTED_FIRST) {
				OceanCanvas.LOGGER.warn("(Ocean Canvas) v206 force-load ticket pool reached its cap ({}) - further "
						+ "requests will proceed without a ticket until earlier ones settle or age out. This message "
						+ "logs once per process.", OCEANCANVAS_FORCE_LOAD_TICKET_MAX);
			}
		} catch (Throwable t) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not install non-blocking force-load ticket at {},{}: {}",
					cx, cz, t.toString());
		}
	}

	/**
	 * v137: releases every force-load ticket {@link #requestNonBlockingChunkLoad}
	 * installed whose chunk has since actually loaded, plus any that have
	 * been outstanding past the safety-net max age regardless of state.
	 * Called once per tick from {@link #onServerTick}.
	 *
	 * <p><b>v204: the "cheap" assumption in this method's original comment
	 * was wrong, confirmed directly from a real user thread dump.</b> Two
	 * separate hard freezes (50s, then 111s, both worse than any other
	 * single-tick stall in this project's history) both showed the Server
	 * thread RUNNABLE - not blocked/waiting - inside exactly this call's
	 * {@code removeTicketWithRadius -> TicketStorage.removeTicket ->
	 * updateForcedChunks -> getAllChunksWithTicketThat}. That is real CPU
	 * work, not a stuck future: vanilla's ticket removal is NOT O(1) per
	 * call, it scans/rebuilds forced-chunk bookkeeping in a way that scales
	 * with how many FORCED tickets (of any kind, from this map or any other
	 * in this file) are currently live. This map is installed/removed at
	 * very high frequency - once per pregen target, once per row-lookahead
	 * neighbor, once per support-neighbor request, from several call sites
	 * across this file - which was fine at the scale earlier successful
	 * sessions ran at, but a genuinely huge run (a 1.56M-chunk pregen job)
	 * can build up far more concurrently-live entries than "the small number
	 * genuinely in flight" this method's own doc assumed, especially while
	 * the separate chronic neighbor-stall/throughput problem (open item)
	 * keeps chunks from settling quickly. Once the live set is large enough,
	 * removing entries here gets slower per call as the set grows, and this
	 * method removes an unbounded number of them in one pass - the classic
	 * shape of an O(n^2)-per-tick blowup, matching the stall growing from
	 * 50s to 111s later in the same session as more got queued.</p>
	 *
	 * <p><b>Fix: cap how many tickets this method actually releases in a
	 * single tick.</b> This does not reduce the total real-world cost of
	 * clearing a very large backlog, but it converts one potential
	 * catastrophic single-tick freeze into many small, bounded per-tick
	 * costs spread across however many ticks it takes - the same tradeoff
	 * already made deliberately elsewhere in this file (e.g. {@code
	 * MAX_FLATTENS_PER_TICK}, {@code PREGEN_RECOVERY_TARGETS_PER_NUDGE}).
	 * Entries not reached this tick are simply left in the map and picked up
	 * on a later call - nothing here is lost, only deferred.</p>
	 *
	 * <p><b>v218 closes the remaining class-level risk:</b> bulk cleanup for
	 * self tickets, processing leases, and final-drain tickets is now bounded
	 * too, and orphaned entries drain incrementally from the server tick. The
	 * force-load pool itself is tightened from 200 to 32 removals/tick. This
	 * keeps every known high-volume {@code removeTicketWithRadius} path under
	 * an explicit per-tick budget instead of leaving cancel/session cleanup as
	 * an unbounded exception.</p>
	 */
	// v218: 200 removals/tick is still far too aggressive for an operation that a
	// real thread dump proved can become expensive as the global FORCED-ticket set grows.
	private static final int MAX_FORCE_LOAD_TICKET_RELEASES_PER_TICK = 32;
	private static final int MAX_SELF_TICKET_RELEASES_PER_TICK = 16;
	private static final int MAX_PROCESSING_LEASE_RELEASES_PER_TICK = 8;
	private static final int MAX_FINAL_DRAIN_BULK_RELEASES_PER_TICK = 8;

	private static void releaseSettledForceLoadTickets(ServerLevel world) {
		try {
			pregenSession().OCEANCANVAS_FORCE_LOAD_LEDGER.releaseSettled(
					monotonicMillis(), OCEANCANVAS_FORCE_LOAD_TICKET_MAX_AGE_MS,
					MAX_FORCE_LOAD_TICKET_RELEASES_PER_TICK,
					packed -> world.hasChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed)),
					packed -> net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.removeForcedTicket(
							world, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)), 0));
		} catch (Throwable t) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Bounded force-load ticket retirement failed: {}", t.toString());
		}
	}

	/** v96 bounded async future-tracking - originally kept one live FULL future
	 * per Pregen target so shutdown could cancel transient generation work.
	 * v137 believed calling this with require=false made it a "passive
	 * observer that reports progress without ever forcing/blocking" (see the
	 * struck-through claim this replaces) and kept it purely for dedup/
	 * shutdown-cancel/diagnostics bookkeeping, on top of the real (and
	 * genuinely non-blocking) ticket-based force-load above.
	 *
	 * <p><b>v207: that require=false assumption is confirmed FALSE by a real
	 * thread dump, not merely suspected.</b> Two separate hard freezes (one
	 * ~40s TIMED_WAITING, one ~95s RUNNABLE) in the same real v206 session
	 * both showed the Server thread stuck at the EXACT same stack - inside
	 * this call's {@code getChunkFuture(...,false)} -> {@code
	 * ServerChunkCache.getChunkFutureMainThread} -> {@code
	 * BlockableEventLoop.managedBlock}, and in the RUNNABLE sample, actually
	 * executing real work inside {@code runDistanceManagerUpdates} / C2ME's
	 * own {@code consolidateSchedules} wrapper - genuine main-thread CPU
	 * work, not a stuck future. The self-ticket pool was only at 12 entries
	 * at the time (nowhere near the v205 cap), and the force-load ticket
	 * pool wasn't implicated either - this is a DIFFERENT, previously
	 * undiscovered variant of the same v137 blocking-call family, hiding in
	 * the one place believed already fixed because it passed {@code
	 * require=false}. Calling {@code getChunkFuture} synchronously from the
	 * main thread is not safely non-blocking regardless of that flag - the
	 * flag only affects whether the resulting future is FORCED to complete
	 * quickly, not whether producing it does real synchronous work first.
	 *
	 * <p><b>v207 fix:</b> stop calling {@code getChunkFuture} synchronously from
	 * the server thread. v228.4 deliberately reintroduces an explicit FULL demand
	 * only through a dedicated off-thread dispatcher after v228.3 proved that
	 * ticket ownership alone can leave cold targets unscheduled for 5-15s. The
	 * actual residency ownership this whole system needs remains handled
	 * by {@link #requestNonBlockingChunkLoad}'s ticket mechanism above,
	 * which has been proven genuinely non-blocking across many real,
	 * stress-tested sessions (it only adds/removes tickets, never calls
	 * getChunkFuture). What this future bought beyond that - dedup (already
	 * redundant with the generic force-load ledger's own
	 * dedup), a Save & Quit cancel-in-flight-work optimization, and a
	 * diagnostic counter - are all minor conveniences, not correctness
	 * requirements, and none of them justify a real, confirmed, user-facing
	 * freeze. {@link #trackBoundedFuture}/{@link #pregenSession().PREGEN_TARGET_FUTURES}/
	 * {@link #pregenSession().PREGEN_SUPPORT_FUTURES}/{@link #cancelOutstandingPregenFutures}
	 * are left in place (harmless, always-empty/no-op now) rather than torn
	 * out, to keep this fix scoped to removing the one dangerous call rather
	 * than a wider structural change under time pressure. */
	public static boolean requestPregenTargetLoad(ServerLevel world, ChunkPos pos) {
		long recoveryPacked = ChunkPos.pack(pos.x(), pos.z());
		boolean lightOnlyRecovery = pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.contains(recoveryPacked)
				&& !pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.contains(recoveryPacked);
		if (!preparePregenAdmission(world, pos)) {
			OceanCanvas.LOGGER.error("(Ocean Canvas) v230.5 refused Pregen admission at {},{} because the required radius-{} self-ticket could not be secured. Cursor ownership must not advance.",
					pos.x(), pos.z(), PREGEN_SELF_TICKET_RADIUS);
			return false;
		}
		markPregenTarget(world, pos);
		if (lightOnlyRecovery) {
			// Tracking is established at quarantine-arm time. Keep this idempotent guard
			// for older/in-session callers, but do not count a target as active lighting
			// work until armLightSyncEntry actually enqueues its finalizer.
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.add(recoveryPacked);
		} else if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.contains(recoveryPacked)) {
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.add(recoveryPacked);
		}
		// v228.5: ticket-first admission. v228.4 explicitly demanded FULL for every
		// target immediately. The runtime showed that this improved throughput, but
		// also produced a 32-second server-loop stall while hundreds of off-thread
		// getChunkFuture requests were being funneled back through Minecraft's main
		// executor. The normal radius-3 ownership ticket gets a short grace period
		// first; only targets still cold after that grace are escalated by the bounded
		// explicit-demand service below. This keeps explicit scheduler demand as a
		// cold-tail accelerator instead of a second hot-path for every admission.
		return true;
	}

	/**
	 * v228.5 bounded cold-tail FULL-demand bridge. Ticketing and demand are intentionally
	 * separate concepts: the radius-3 FORCED ticket owns residency/ticking
	 * geometry, while this explicit request tells the chunk scheduler that FULL
	 * is needed now. The call itself is dispatched off the server thread because
	 * main-thread getChunkFuture(create=true) is a previously proven freeze path.
	 */
	private static void requestPregenFullDemandOffThread(ServerLevel world, ChunkPos pos) {
		if (world == null || pos == null) return;
		long packed = ChunkPos.pack(pos.x(), pos.z());
		if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed) || pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) return;
		java.util.concurrent.CompletableFuture<LevelChunk> bridge = new java.util.concurrent.CompletableFuture<>();
		java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>> demandedRef =
				new java.util.concurrent.atomic.AtomicReference<>();
		if (pregenSession().PREGEN_TARGET_FUTURES.putIfAbsent(packed, bridge) != null) return;
		pregenSession().PREGEN_FULL_DEMAND_LAST_MS.put(packed, monotonicMillis());
		pregenSession().PREGEN_FULL_DEMAND_REQUESTS.incrementAndGet();
		bridge.whenComplete((loaded, error) -> {
			pregenSession().PREGEN_TARGET_FUTURES.remove(packed, bridge);
			if (bridge.isCancelled()) {
				// v253.125.22: cancellations are an expected fourth terminal state, not
				// a silent req-done-fail accounting gap. Count them explicitly so a
				// canceled cold-tail bridge cannot be mistaken for a stranded future.
				pregenSession().PREGEN_FULL_DEMAND_CANCELLATIONS.incrementAndGet();
				java.util.concurrent.CompletableFuture<?> demanded = demandedRef.get();
				if (demanded != null && !demanded.isDone()) demanded.cancel(false);
				return;
			}
			if (error != null || loaded == null) {
				pregenSession().PREGEN_FULL_DEMAND_FAILURES.incrementAndGet();
				OceanCanvas.LOGGER.warn("(Ocean Canvas) v230.5 off-thread FULL demand failed before authoritative server-thread handoff at {},{}: {}",
						pos.x(), pos.z(), error == null ? "null LevelChunk" : error.toString());
				return;
			}
			// v230.5: completion is deliberately counted only after the server-thread
			// handoff below has run. v230.3.2 completed this bridge on the C2ME callback
			// thread and merely queued enqueueForPregen afterward. That released one of
			// the two bounded FULL-demand slots too early, allowing ~2 new demands/sec
			// to pile server-executor handoffs behind the oldest cold cohort.
			pregenSession().PREGEN_FULL_DEMAND_COMPLETIONS.incrementAndGet();
		});
		try {
			pregenSession().PREGEN_FULL_DEMAND_EXECUTOR.execute(() -> {
				if (bridge.isCancelled() || !pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) {
					bridge.cancel(false);
					return;
				}
				try {
					java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> demanded = world.getChunkSource().getChunkFuture(
							pos.x(), pos.z(), ChunkStatus.FULL, true);
					demandedRef.set(demanded);
					// Cancellation may have raced the call above. Propagate it immediately
					// instead of leaving untracked C2ME generation alive after Ocean Canvas
					// has already reported its transient future map as empty.
					if (bridge.isCancelled()) {
						demanded.cancel(false);
						return;
					}
					demanded.whenComplete((result, error) -> {
						if (error != null) {
							bridge.completeExceptionally(error);
							return;
						}
						net.minecraft.world.level.chunk.ChunkAccess access = result == null ? null : result.orElse(null);
						if (!(access instanceof LevelChunk live)) {
							bridge.completeExceptionally(new IllegalStateException(
									"FULL demand returned no LevelChunk at " + pos.x() + "," + pos.z()
											+ (result == null ? " (null ChunkResult)" : ": " + result.getError())));
							return;
						}
						// v230.5: keep this bridge ACTIVE until its authoritative server-thread
						// handoff executes. The active=2 cap now bounds both C2ME FULL work and
						// pending main-executor delivery instead of only the former.
						try {
							world.getServer().execute(() -> {
								if (bridge.isCancelled()) return;
								if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) {
									bridge.cancel(false);
									return;
								}
								if (!pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) enqueueForPregen(world, live);
								bridge.complete(live);
							});
						} catch (Throwable t) {
							bridge.completeExceptionally(t);
						}
					});
				} catch (Throwable t) {
					bridge.completeExceptionally(t);
				}
			});
		} catch (Throwable t) {
			bridge.completeExceptionally(t);
		}
	}

	/**
	 * v115: the main scan submits row-major (x fastest, then z) with no
	 * lookahead into the next row. A target's full 3x3 neighborhood always
	 * needs its z+1 row, and that row's chunks are never even requested
	 * until the scan literally finishes the current row and advances - so
	 * for the ENTIRE time the scan works through a row (up to rowWidth
	 * targets), essentially every target in that row is structurally
	 * "missing neighbor", not merely occasionally stalled. The v100/v106
	 * lease/breaker machinery was quietly doing 100% of the real completion
	 * work for most of a run rather than acting as the safety net it was
	 * designed to be - matching the real v114 log's near-constant 90-100%
	 * missingNeighbor ratio and the very slow (~2-3 chunks/s) completion
	 * rate despite no more severe tick stalls. Fire-and-forget request the
	 * direct north neighbor (cz+1) one row ahead of the scan itself, so by
	 * the time the scan reaches that row most of it is already loading or
	 * loaded. The diagonal (cx±1, cz+1) targets get covered for free as the
	 * scan's own x cursor sweeps across the row calling this once per
	 * column. Support-only: does not mark a target, does not affect
	 * completion tracking, and reuses the existing bounded/deduplicated
	 * future wrapper.
	 */
	public static void requestPregenRowLookaheadLoad(ServerLevel world, int cx, int cz) {
		// v216: intentionally no-op. The old radius-0 next-row lookahead was
		// needed while routine ownership was radius 1. Since v211, each admitted
		// target owns radius 3 and therefore preconditions the full 3x3 support
		// neighborhood itself. Keeping a second lookahead ticket layer only adds
		// TicketStorage churn without extending the readiness footprint.
	}

	/** Same fix as {@link #requestPregenTargetLoad} - see that method's v207
	 * doc for the real confirmed evidence (this exact call site is the one
	 * the actual thread dump caught). No longer calls {@code getChunkFuture}. */
	private static void requestPregenSupportLoad(ServerLevel world, int cx, int cz) {
		requestNonBlockingChunkLoad(world, cx, cz);
	}

	/**
	 * v163.5: the real cause of the support-futures leak (Round 38-40's log
	 * evidence showed support:N(done=N) - every entry already complete but
	 * never removed). The old code did
	 * {@code map.computeIfAbsent(key, k -> { var f = ...; f.whenComplete(() ->
	 * map.remove(k, f)); return f; })}. If the chunk future returned by
	 * {@code getChunkFuture} is ALREADY DONE at that point - very common for
	 * support/neighbor requests, since a neighbor of an already-loaded chunk
	 * is frequently already loaded itself - {@code whenComplete}'s callback
	 * fires SYNCHRONOUSLY, immediately, from inside the mapping function
	 * itself, before {@code computeIfAbsent} has inserted anything. The
	 * {@code remove(key, future)} call is then a no-op (nothing to remove
	 * yet), and once the mapping function returns, computeIfAbsent inserts
	 * the already-finished future into the map anyway - permanently, since
	 * its only removal trigger already fired and will never fire again.
	 *
	 * <p>Fix: build the future OUTSIDE any map-mutating lambda first. If it's
	 * already done, there's nothing to track - don't insert it at all. Only
	 * if it's still pending do we insert (via {@code putIfAbsent}, not
	 * {@code computeIfAbsent} - no recursive mapping-function call) and THEN
	 * attach {@code whenComplete}, so if it races and completes anyway, the
	 * entry already exists and removal works correctly this time.
	 *
	 * <p>Applies to both {@link #pregenSession().PREGEN_TARGET_FUTURES} and
	 * {@link #pregenSession().PREGEN_SUPPORT_FUTURES} - both used the identical faulty
	 * pattern. Target futures showed the same bug far less visibly in past
	 * logs only because most target requests are for genuinely cold,
	 * not-yet-generated chunks, so their futures are rarely already done at
	 * request time - not because the code was actually safe.
	 */
	private static void trackBoundedFuture(
			OceanCanvasPrimitiveLongObjectMap<java.util.concurrent.CompletableFuture<?>> map,
			long packed,
			java.util.function.Supplier<java.util.concurrent.CompletableFuture<?>> futureSupplier) {
		if (map.containsKey(packed)) return;
		java.util.concurrent.CompletableFuture<?> future = futureSupplier.get();
		if (future.isDone()) return;
		if (map.putIfAbsent(packed, future) == null) {
			future.whenComplete((ignored, error) -> map.remove(packed, future));
		}
	}

	/** Snapshot used by v96 persistence. Small by construction (bounded Pregen
	 * outstanding window) and intentionally copied so SavedData never observes a
	 * concurrently mutating set. */
	public static java.util.List<Long> outstandingPregenTargetsSnapshot() {
		LongArrayList out = new LongArrayList(pregenSession().PREGEN_TARGET_CHUNKS.size());
		pregenSession().PREGEN_TARGET_CHUNKS.forEachLong(out::add);
		return out;
	}

	/** v253.61.11 restart checkpoint support: terrain can retire before its staged
	 * lighting certificate is written. Persist those physical-only chunks in the
	 * job replay lane so a restart can resume the true row-major cursor safely.
	 * v253.125.39 keeps the exact same unique union but builds it in reusable
	 * primitive scratch and stores the durable snapshot in a primitive-backed list. */
	/** v253.125.42 allocation-free authoritative membership query used by restart
	 * gate reconciliation. This checks the same three debt stores as the persisted
	 * snapshot without materializing a six-figure temporary HashSet. */
	public static boolean hasPendingLightFinalization(long packed) {
		return lightFinalizerSession().pendingTicks.containsKey(packed)
				|| lightRecoverySession().skyBackoffUntilTick.containsKey(packed)
				|| lightRecoverySession().pressureParkUntilTick.containsKey(packed);
	}

	public static java.util.List<Long> pendingLightFinalizationChunksSnapshot() {
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		LongLinkedOpenHashSet all = session.checkpointPendingScratch;
		all.clear();
		session.pendingTicks.forEachKey(all::add);
		lightRecoverySession().skyBackoffUntilTick.forEachKey(all::add);
		lightRecoverySession().pressureParkUntilTick.forEachKey(all::add);
		LongArrayList out = new LongArrayList(all.size());
		for (LongIterator it = all.iterator(); it.hasNext();) out.add(it.nextLong());
		all.clear();
		return out;
	}

	/** v253.72 arms one persisted recovery target. Its old light certificate is
	 * invalidated immediately so replay cannot metadata-skip it before the live
	 * server state has been proven again. */
	public static void markPregenCrashRecoveryTarget(ServerLevel world, ChunkPos pos) {
		markPregenCrashRecoveryTarget(world, pos, true);
	}

	/** Arm a recovery target with an explicit physical-repair policy. Committed
	 * safety tails use {@code allowPhysicalRepair=false}; only genuinely
	 * uncommitted/terrain-dirty work is permitted to canonicalize blocks. */
	public static void markPregenCrashRecoveryTarget(ServerLevel world, ChunkPos pos, boolean allowPhysicalRepair) {
		long packed = ChunkPos.pack(pos.x(), pos.z());
		pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.add(packed);
		if (allowPhysicalRepair) {
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.add(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.add(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.remove(packed);
			if (lightFinalizerSession().pendingTicks.containsKey(packed)) {
				pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.add(packed);
			}
			// A target may first appear in a conservative LIGHT_ONLY halo and later be
			// upgraded by the committed-frontier rescan. Physical ownership wins.
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
		} else if (!pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.contains(packed)
				&& !pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) {
			// v253.125.13 log-driven refinement: classify historical LIGHT_ONLY recovery
			// when its bounded replay admission is actually attempted, not when the full
			// durable quarantine is armed. v253.73.12 correctly made tracking visible to
			// the pressure governor, but eager classification of 58,077 entries in the
			// v253.125.12 run defeated the 128-entry admission window before it could act.
			// The caller now preserves the full durable obligation separately and invokes
			// this method only for the admitted center. Tracking remains correctness-neutral;
			// the lighting certificate stays dirty until AUTHORITATIVE_SEND succeeds.
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.add(packed);
			if (lightFinalizerSession().pendingTicks.containsKey(packed)) {
				pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.add(packed);
			}
		}
		// Never downgrade an already-armed physical recovery target merely because
		// it also belongs to a broader light-only halo/safety band.
		OceanCanvasProtectedData.get(world).markChunkLightingDirty(pos);
		lightFinalizerSession().persistedAuditSession.clearAudited(packed);
	}

	public static boolean isPregenCrashRecoveryTarget(long packed) {
		return pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.contains(packed);
	}

	/** v253.72.2 terrain-quiescence helper. A committed safety-tail replay is
	 * deliberately LIGHT_ONLY and therefore cannot mutate terrain. Only an
	 * explicitly physical-aware recovery target may block a neighboring durable
	 * light proof while it is still waiting for terrain admission. */
	public static boolean isPregenCrashRecoveryPhysicalTarget(long packed) {
		return pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.contains(packed)
				|| lightFinalizerSession().allowPhysicalRepair.contains(packed);
	}

	/** Exact recovery chunks that still carry permission for physical repair.
	 * Includes entries already handed from the recovery target set into the
	 * staged light finalizer. */
	public static java.util.List<Long> physicalRepairRecoveryChunksSnapshot() {
		LongLinkedOpenHashSet scratch = lightFinalizerSession().checkpointPendingScratch;
		scratch.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.forEachLong(scratch::add);
		// v253.73.13: use recovery-specific identity rather than the generic
		// allowPhysicalRepair set, which also contains ordinary freshly flattened terrain.
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.forEachLong(scratch::add);
		LongArrayList out = new LongArrayList(scratch.size());
		for (LongIterator it = scratch.iterator(); it.hasNext();) out.add(it.nextLong());
		scratch.clear();
		return out;
	}

	public static int activePhysicalRecoveryWorkCount() {
		return pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.size();
	}

	public static int trackedPhysicalRecoveryWorkCount() {
		return pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.size();
	}

	/** v253.125.52 session-local admission proof. The chunk still retains its
	 * persisted PHYSICAL_AWARE identity and physical-repair permission until strict
	 * lighting publication; this only says the exhaustive live physical audit has
	 * already passed in this server lifetime. */
	public static boolean isPhysicalRecoveryTerrainSafe(long packed) {
		return pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.contains(packed);
	}

	/** Active journal-proven LIGHT_ONLY recovery work. Dormant persistent-backoff
	 * entries remain tracked for correctness but are intentionally excluded here. */
	public static int activeLightOnlyRecoveryWorkCount() {
		return pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.size();
	}

	public static int trackedLightOnlyRecoveryWorkCount() {
		return pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.size();
	}

	/**
	 * v253.125.13 self-healing active-window invariant. LIGHT_ONLY identity is
	 * established once, at bounded replay admission; this cheap reconciliation only
	 * derives the ACTIVE subset from currently pending finalizer work. It therefore
	 * scales with live pressure (normally hundreds), not the full persisted recovery
	 * ledger (38,721 chunks in the supplied log). A future active-bookkeeping
	 * call-order regression fails safe without turning every Pregen tick into an
	 * O(recovery-ledger) scan. No chunk is completed, loaded, or granted a lighting
	 * certificate here.
	 */
	public static int reconcileLightOnlyRecoveryTracking() {
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		LongArrayList scratch = session.recoveryIterationScratch;
		synchronized (scratch) {
			int repaired = 0;
			// Copy primitive pending keys first, then release pending-map stripe locks
			// before consulting recovery sets. The list retains capacity between calls.
			scratch.clear();
			session.pendingTicks.forEachKey(scratch::add);
			for (int i = 0; i < scratch.size(); i++) {
				long packed = scratch.getLong(i);
				if (!pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed)) continue;
				if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.contains(packed)
						|| session.allowPhysicalRepair.contains(packed)) {
					pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(packed);
					if (pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed)) repaired++;
					continue;
				}
				if (pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.add(packed)) repaired++;
			}

			// Copy ACTIVE membership before validating it against pending/tracked state.
			// This avoids holding an ACTIVE stripe while acquiring a pending-map stripe.
			scratch.clear();
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.forEachLong(scratch::add);
			for (int i = 0; i < scratch.size(); i++) {
				long packed = scratch.getLong(i);
				if (!session.pendingTicks.containsKey(packed)
						|| !pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed)
						|| pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.contains(packed)
						|| session.allowPhysicalRepair.contains(packed)) {
					if (pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed)) repaired++;
				}
			}
			scratch.clear();
			return repaired;
		}
	}


	/**
	 * v253.73.13 self-healing PHYSICAL_AWARE active-window invariant. Recovery
	 * identity is separate from generic allowPhysicalRepair so ordinary new terrain
	 * can never be mistaken for historical physical recovery. As with LIGHT_ONLY,
	 * reconciliation scales with live pending work plus the bounded ACTIVE set.
	 */
	public static int reconcilePhysicalRecoveryTracking() {
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		LongArrayList scratch = session.recoveryIterationScratch;
		synchronized (scratch) {
			int repaired = 0;
			scratch.clear();
			session.pendingTicks.forEachKey(scratch::add);
			for (int i = 0; i < scratch.size(); i++) {
				long packed = scratch.getLong(i);
				if (!pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) continue;
				// Once the exhaustive live physical audit has passed, this recovery entry
				// remains fail-closed lighting debt but must stop occupying the small
				// PHYSICAL admission window. A restart clears this session-only proof.
				if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.contains(packed)) {
					if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed)) repaired++;
					continue;
				}
				if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.add(packed)) repaired++;
				pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(packed);
				pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
			}
			scratch.clear();
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.forEachLong(scratch::add);
			for (int i = 0; i < scratch.size(); i++) {
				long packed = scratch.getLong(i);
				if (!session.pendingTicks.containsKey(packed)
						|| !pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)
						|| pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.contains(packed)) {
					if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed)) repaired++;
				}
			}
			scratch.clear();
			return repaired;
		}
	}


	private static void markPhysicalRecoveryTerrainSafe(long packed) {
		if (!pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) return;
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.add(packed);
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed);
	}

	private static void rearmPhysicalRecoveryTerrainUnsafe(long packed) {
		if (!pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) return;
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.remove(packed);
		if (lightFinalizerSession().pendingTicks.containsKey(packed)) {
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.add(packed);
		}
	}

	/** Monotonic session-local proof of genuinely new terrain retirement. */
	public static long newTerrainRetirementCount() {
		return pregenSession().PREGEN_NEW_TERRAIN_RETIREMENTS.get();
	}

	/**
	 * Monotonic session-local proof that the job cursor is actually moving: every
	 * retirement that released PREGEN_TARGET ownership, including re-walked
	 * existing seals and crash-recovery targets. Always &gt;= newTerrainRetirementCount().
	 * Use this to answer "is the run progressing"; use newTerrainRetirementCount()
	 * to answer "is new ocean being carved".
	 */
	public static long jobForwardRetirementCount() {
		return pregenSession().PREGEN_JOB_FORWARD_RETIREMENTS.get();
	}

	/**
	 * Proactively park excess historical LIGHT_ONLY finalizer work. This is a pure
	 * scheduling move: no lighting certificate is granted, no recovery epoch is
	 * reset, and unfinished debt remains in the dedicated scheduler-only pressure
	 * park lane. It is deliberately NOT inserted into persistent SKY backoff because
	 * being pressure-parked is not evidence of a strict skylight failure. Relight
	 * residency tickets are released immediately so a large audit/restart cohort
	 * cannot monopolize the active window before ordinary Pregen gets a chance to
	 * run. PREGEN_TARGET ownership is never parked here; only entries already inside
	 * the non-destructive light finalizer are eligible.
	 */
	public static int parkExcessLightOnlyRecoveryForPressure(ServerLevel world, int targetActive, int maxToPark) {
		if (world == null || maxToPark <= 0 || targetActive < 0) return 0;
		// Do not trust a derived bookkeeping set at the exact point where it controls
		// emergency flow. Reconcile it from recovery/pending authoritative state first.
		reconcileLightOnlyRecoveryTracking();
		int excess = pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.size() - targetActive;
		if (excess <= 0) return 0;
		int limit = Math.min(excess, maxToPark);
		int parked = 0;
		long now = world.getGameTime();
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		LongArrayList scratch = session.recoveryIterationScratch;
		synchronized (scratch) {
			scratch.clear();
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.forEachLong(scratch::add);
			for (int i = 0; i < scratch.size() && parked < limit; i++) {
				long packed = scratch.getLong(i);
				if (pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) continue;
				if (!session.pendingTicks.containsKey(packed)) continue;
			int jitter = Math.floorMod((int)(packed ^ (packed >>> 32)), LIGHT_PERSISTENT_SKY_BACKOFF_JITTER_TICKS);
			long due = now + Math.max(20L, LIGHT_PERSISTENT_SKY_BACKOFF_TICKS + jitter);
			long previous = lightRecoverySession().pressureParkUntilTick.put(packed, due);
			lightFinalizerSession().pendingTicks.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
			releaseLightRelightResidencyTicket(world, packed);
			if (previous == OceanCanvasPrimitiveLongLongMap.ABSENT) lightFinalizerSession().retryLedger.offerPressurePark(packed, due);
				lightTelemetrySession().LIGHT_DIAG_FOREVER_WORLD_PRESSURE_PARKS.incrementAndGet();
				parked++;
			}
			scratch.clear();
		}
		return parked;
	}


	/**
	 * v253.73.13 counterpart to the LIGHT_ONLY pressure park. Once a recovery
	 * target has retired PREGEN_TARGET ownership and entered the strict finalizer,
	 * its physical mutation is already complete for this attempt. Dormantizing the
	 * finalizer therefore changes only retry timing: physical-repair permission,
	 * recovery identity and the missing lighting certificate all remain intact.
	 */
	public static int parkExcessPhysicalRecoveryForPressure(ServerLevel world, int targetActive, int maxToPark) {
		if (world == null || maxToPark <= 0 || targetActive < 0) return 0;
		reconcilePhysicalRecoveryTracking();
		int excess = pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.size() - targetActive;
		if (excess <= 0) return 0;
		int limit = Math.min(excess, maxToPark);
		int parked = 0;
		long now = world.getGameTime();
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		LongArrayList scratch = session.recoveryIterationScratch;
		synchronized (scratch) {
			scratch.clear();
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.forEachLong(scratch::add);
			for (int i = 0; i < scratch.size() && parked < limit; i++) {
				long packed = scratch.getLong(i);
				// Never park a target that still owns terrain/load admission. This keeps the
				// proven v230.5 scheduler and crash-recovery physical liveness path untouched.
				if (pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) continue;
				if (!session.pendingTicks.containsKey(packed)) continue;
			int jitter = Math.floorMod((int)(packed ^ (packed >>> 32)), LIGHT_PERSISTENT_SKY_BACKOFF_JITTER_TICKS);
			long due = now + Math.max(20L, LIGHT_PERSISTENT_SKY_BACKOFF_TICKS + jitter);
			long previous = lightRecoverySession().pressureParkUntilTick.put(packed, due);
			lightFinalizerSession().pendingTicks.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed);
			releaseLightRelightResidencyTicket(world, packed);
			if (previous == OceanCanvasPrimitiveLongLongMap.ABSENT) lightFinalizerSession().retryLedger.offerPressurePark(packed, due);
				lightTelemetrySession().LIGHT_DIAG_FOREVER_WORLD_PRESSURE_PARKS.incrementAndGet();
				parked++;
			}
			scratch.clear();
		}
		return parked;
	}


	/**
	 * v253.125.20 bounds non-visible LIGHT_ONLY debt that is not owned by the crash-
	 * recovery tracking sets (persisted audits, boundary audits, global certificate
	 * migration, etc.). The .19 runtime reached active=13,255 even though recovery
	 * handoffs were only 17, proving recovery-only pressure parking could not bound
	 * the actual global inlet. This is scheduler state only: certificates stay dirty,
	 * no recovery epoch is reset, and no terrain ownership is surrendered.
	 */
	private static int parkExcessGenericLightOnlyForPressure(ServerLevel world, int targetActive, int maxToPark) {
		if (world == null || maxToPark <= 0 || targetActive < 0) return 0;
		int excess = lightFinalizerSession().pendingTicks.size() - targetActive;
		if (excess <= 0) return 0;
		int parked = 0;
		int examined = 0;
		long now = world.getGameTime();
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		// v253.125.31: pressure parking is scheduler administration too. Use the same
		// fair rotating queue and a hard scan ceiling instead of walking a 20k+ entry
		// ConcurrentHashMap every tick before the light-work budget starts.
		while (parked < maxToPark && examined < LIGHT_GENERIC_PRESSURE_SCAN_LIMIT_PER_TICK
				&& session.pendingTicks.size() > targetActive) {
			long packed = session.pendingWorkOrder.poll();
			if (packed == OceanCanvasPrimitiveLongQueue.EMPTY) break;
			if (!session.pendingWorkMembership.contains(packed) || !session.pendingTicks.containsKey(packed)) {
				session.pendingWorkMembership.remove(packed);
				continue;
			}
			examined++;
			boolean eligible = !session.visibleLightPriority.contains(packed)
					&& !session.allowPhysicalRepair.contains(packed)
					&& !pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)
					&& !pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed)
					&& !pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed);
			if (!eligible) {
				session.pendingWorkOrder.offer(packed);
				continue;
			}
			int jitter = Math.floorMod((int)(packed ^ (packed >>> 32)), LIGHT_PERSISTENT_SKY_BACKOFF_JITTER_TICKS);
			long due = now + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter;
			long previous = lightRecoverySession().pressureParkUntilTick.put(packed, due);
			if (session.pendingTicks.remove(packed) == OceanCanvasPrimitiveLongIntMap.ABSENT) {
				session.pendingWorkOrder.offer(packed);
				continue;
			}
			session.pendingWorkMembership.remove(packed);
			releaseLightRelightResidencyTicket(world, packed);
			if (previous == OceanCanvasPrimitiveLongLongMap.ABSENT) session.retryLedger.offerPressurePark(packed, due);
			lightTelemetrySession().LIGHT_DIAG_FOREVER_WORLD_PRESSURE_PARKS.incrementAndGet();
			parked++;
		}
		return parked;
	}

	/** Park one non-visible global-certificate entry before it can enter an atomic
	 * deep repair wave while terrain ownership still exists. */
	private static boolean parkGlobalBackgroundRepairForTerrainLiveness(ServerLevel world, long packed) {
		if (world == null) return false;
		if (lightFinalizerSession().visibleLightPriority.contains(packed)) return false;
		if (net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets() <= 0) return false;
		int jitter = Math.floorMod((int)(packed ^ (packed >>> 32)), LIGHT_PERSISTENT_SKY_BACKOFF_JITTER_TICKS);
		long due = world.getGameTime() + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter;
		long previous = lightRecoverySession().pressureParkUntilTick.put(packed, due);
		lightFinalizerSession().pendingTicks.remove(packed);
		releaseLightRelightResidencyTicket(world, packed);
		if (previous == OceanCanvasPrimitiveLongLongMap.ABSENT) lightFinalizerSession().retryLedger.offerPressurePark(packed, due);
		lightTelemetrySession().LIGHT_DIAG_FOREVER_WORLD_PRESSURE_PARKS.incrementAndGet();
		return true;
	}

	public static void clearPregenCrashRecoveryTargets() {
		pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.clear();
	}

	/**
	 * v97: withdraw one pathological live Pregen target WITHOUT declaring it
	 * complete. PregenManager immediately places the packed position into its
	 * persisted deferred-recovery lane. This is the in-session equivalent of the
	 * proven Save & Quit/replay behavior: release C2ME ownership so one chunk can
	 * no longer freeze a maintenance barrier, while preserving zero-failure
	 * completion semantics.
	 */
	public static boolean deferOutstandingPregenTarget(ServerLevel world, long packed) {
		if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) return false;
		int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
		ChunkPos pos = new ChunkPos(cx, cz);
		pregenSession().PENDING_CHUNKS.removeIf(chunk -> chunk.getPos().equals(pos));
		pregenSession().QUEUED_CHUNK_POSITIONS.remove(pos);
		pregenSession().PREGEN_QUEUED_CHUNKS.remove(packed);
		boolean retiredOwnership = pregenSession().PREGEN_TARGET_CHUNKS.remove(packed);
		if (retiredOwnership) {
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
            net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.recordDeferral(packed, "persisted-deferred-recovery");
            net.oceancanvas.mod.diagnostic.OceanCanvasHotRegionMap.onDeferral(packed);
        }
		pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.remove(packed);
		pregenSession().PREGEN_FULL_DEMAND_LAST_MS.remove(packed);
		pregenSession().PREGEN_TARGET_LOAD_RESCUE_LAST_MS.remove(packed);
		pregenSession().PREGEN_TARGET_LOAD_RESCUED.remove(packed);
		pregenSession().PREGEN_AUDIT_REJECTION_COUNTS.remove(packed);
		pregenSession().PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS.remove(packed);
		pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.remove(packed);
		java.util.concurrent.CompletableFuture<?> future = pregenSession().PREGEN_TARGET_FUTURES.remove(packed);
		if (future != null) future.cancel(false);
		releaseFinalDrainTicket(world, packed);
		releaseProcessingLease(world, packed);
		releaseSelfTicket(world, packed);
		return true;
	}

	/**
	 * v98 generalized live-stall escape. Returns the oldest currently-owned
	 * targets whose request age is at least {@code minAgeMs}, withdrawing only
	 * their transient queue/future/ticket ownership. PregenManager persists the
	 * returned coordinates in its deferred lane, so this never means success or
	 * data loss. Oldest-first avoids repeatedly sacrificing fresh requests while
	 * a genuinely wedged C2ME tail monopolizes the active window.
	 */
	public static java.util.List<Long> deferOldestStalledPregenTargets(ServerLevel world, int maxTargets, long minAgeMs) {
		if (maxTargets <= 0 || pregenSession().PREGEN_TARGET_CHUNKS.isEmpty()) return java.util.List.of();
		// v253.125.32: this escape is for a pure async-load tail only. Do not copy and
		// globally sort the whole outstanding set: select the oldest bounded candidates
		// in one pass, and never withdraw a target that has already crossed into the
		// CHUNK_LOAD/carve queue. Exact coordinates remain mandatory deferred debt.
		int limit = Math.min(2, maxTargets);
		long now = monotonicMillis();
		long oldestPacked = 0L, secondPacked = 0L;
		long oldestFirst = Long.MAX_VALUE, secondFirst = Long.MAX_VALUE;
		boolean haveOldest = false, haveSecond = false;
		long[] stalled = oldestPregenTargets(limit, now, packed -> {
			if (pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) return false;
			long first = pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.get(packed);
			return first != OceanCanvasPrimitiveLongLongMap.ABSENT && now - first >= minAgeMs;
		});
		if (stalled.length == 0) return java.util.List.of();
		oldestPacked = stalled[0]; haveOldest = true;
		if (stalled.length > 1) { secondPacked = stalled[1]; haveSecond = true; }
		if (!haveOldest) return java.util.List.of();
		java.util.ArrayList<Long> deferred = new java.util.ArrayList<>(limit);
		if (deferOutstandingPregenTarget(world, oldestPacked)) deferred.add(oldestPacked);
		if (limit > 1 && haveSecond && deferOutstandingPregenTarget(world, secondPacked)) deferred.add(secondPacked);
		return deferred;
	}

	private static void cancelOutstandingPregenFutures() {
		for (java.util.concurrent.CompletableFuture<?> future : pregenSession().PREGEN_TARGET_FUTURES.valuesSnapshot()) future.cancel(false);
		for (java.util.concurrent.CompletableFuture<?> future : pregenSession().PREGEN_SUPPORT_FUTURES.valuesSnapshot()) future.cancel(false);
		pregenSession().PREGEN_TARGET_FUTURES.clear();
		pregenSession().PREGEN_SUPPORT_FUTURES.clear();
	}

	/**
	 * v215 admission precondition. A target is not allowed to become counted
	 * Ocean Canvas Pregen ownership unless its required radius-3 self ticket can
	 * first be secured. This closes the one-second gap where v213 could only
	 * discover an ownership failure after the cursor/submitted counters had
	 * already advanced. Safe to call repeatedly; ticket installation is deduped.
	 */
	public static boolean preparePregenAdmission(ServerLevel world, ChunkPos pos) {
		if (world == null || pos == null) return false;
		long packed = ChunkPos.pack(pos.x(), pos.z());
		if (targetHasSelfOwnership(packed)) return true;
		// v218: bounded cleanup can intentionally leave orphan tickets alive for a
		// few ticks. If a new session immediately needs a slot while the pool is at
		// cap, reclaim at most ONE orphan synchronously rather than falsely failing
		// the startup canary or doing an unbounded bulk teardown.
		// Shared anchors make the old raw-ticket orphan scan invalid: ownership debt
		// is tracked by target->anchor mappings and drained below.
		installSelfTicket(world, packed);
		return targetHasSelfOwnership(packed);
	}

	public static void markPregenTarget(ServerLevel world, ChunkPos pos) {
		long packed = ChunkPos.pack(pos.x(), pos.z());
		boolean newlyOwned = pregenSession().PREGEN_TARGET_CHUNKS.add(packed);
		if (newlyOwned) {
            net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.recordAdmission(packed, "markPregenTarget");
            net.oceancanvas.mod.diagnostic.OceanCanvasHotRegionMap.onAdmission(packed,monotonicMillis());
        }
		pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.putIfAbsent(packed, monotonicMillis());
		// v119: this was the real gap in v118. installSelfTicket() was only ever
		// called from enqueueForPregen(), which is reached solely via the
		// world.hasChunk() branch in PregenManager - i.e. only for targets that
		// happened to already be resident the instant the scan visited them. The
		// dominant path for a genuinely cold pregen (requestPregenTargetLoad ->
		// here, for a chunk that is NOT yet resident) never installed a ticket at
		// all, so v118's fix protected only incidentally-warm targets near the
		// scan front (spawn-adjacent or previously-touched chunks) and did
		// nothing once the scan pushed into truly untouched territory - exactly
		// where the generate-then-evict churn v118 targeted would still occur.
		// Installing the ticket here, the instant a target is marked regardless
		// of residency, closes that gap: addTicketWithRadius is safe to call on
		// an unloaded position (it is what causes the chunk system to load and
		// then hold it), so this also makes onChunkLoad's job easier once the
		// async future resolves - the chunk is already pinned by the time it
		// arrives.
		installSelfTicket(world, packed);
	}

	// v118: neither a target nor a neighbor chunk was ever held loaded once its
	// async FULL future completed - nothing here retains it, and pregen targets
	// sit far outside the player's actual view/simulation distance (view=8,
	// simDistance=6 from spawn; targets can be hundreds of chunks out). A real
	// comparison run showed the raw ceiling is high (Chunky: 30-70+ chunks/s on
	// identical hardware/mods) while Ocean Canvas sat at 1-5/s with EVERY
	// missing-neighbor target reporting stale (3000ms+), not just freshly
	// submitted ones - i.e. genuinely stuck, not a measurement artifact. The
	// likely mechanism: a neighbor generates, is momentarily resident, then
	// unloads again (nothing retains it) before the slow-moving scan (rate
	// throttled to 1-3/tick) actually reaches the target that needed it as a
	// neighbor - so the same columns can regenerate and unload repeatedly
	// instead of ever being "ready" at once. Pin every target to a FORCED
	// ticket the instant it becomes a queued pregen target, and hold it until
	// the target is retired/deferred. Once every target holds itself
	// resident, any later target's neighbor check finds its earlier-processed
	// neighbors already pinned rather than racing to reload them.
	//
	// v203: this ticket was radius 0 from v118 through v198, on the reasoning
	// that "resident" was the goal and radius 0 keeps memory cost to one
	// chunk per outstanding target. That reasoning was wrong about what
	// "resident" needs to mean here. Real chunk-loading tier research (this
	// exact distinction independently confirmed via a Paper engine issue,
	// PaperMC/Paper#10299): a ticket's radius sets its level as
	// (33 - radius), and radius 0 -> level 33 only ever reaches FULL status,
	// never any ticking tier. But neighborsReady() gates on
	// ServerChunkCache#isPositionTicking - which per that same research checks
	// the TICKING tier specifically (level <= 32, i.e. radius >= 1), not the
	// stricter ENTITY_TICKING tier (level <= 31, radius >= 2) the v49 open
	// item worried about. So a radius-0 self-ticket was never capable of
	// getting its own target to pass neighborsReady on its own - every target
	// was structurally dependent on either a player physically being nearby,
	// or on crossing the staleness threshold and being picked up by the
	// separate, deliberately rare/capped radius-2 final-drain or processing-
	// lease recovery tickets below (PREGEN_FINAL_DRAIN_TICKET_RADIUS /
	// PREGEN_PROCESSING_LEASE_RADIUS, both previously 2 = center level 31; v209 raises them to 3 so the adjacent ring also reaches the ticking tier). That almost
	// certainly explains the chronic "neighbor-stall breaker" engagement and
	// low steady-state throughput (4-8 chunks/s) reported across many
	// sessions: the common case was routing through the rare/capped recovery
	// path instead of the ticket that's supposed to be the routine one.
	//
	// Fix: radius 1 (level 32, TICKING) instead of radius 0. This is the
	// minimum radius that actually satisfies isPositionTicking, still far
	// cheaper than radius 2 (9 chunks of ticket footprint per outstanding
	// target instead of 25) since this ticket - unlike the capped recovery
	// leases - is meant to be held unconditionally for the whole queue.
	// NEEDS REAL-WORLD LOG VERIFICATION: watch for whether "neighbor-stall
	// breaker" engagement drops and throughput rises without a matching new
	// memory/tick-cost regression from the larger per-target footprint.
	/**
	 * v205: this pool was uncapped since v118, on the reasoning that a
	 * radius-0 (later radius-1) ticket per outstanding target was cheap
	 * enough to apply unconditionally to the whole queue. Real evidence
	 * proves that reasoning wrong at scale: a real v204 session showed the
	 * exact same {@code releaseSettledForceLoadTickets} stall from v204
	 * escalating across the SAME run - 40s, then 136s, then 201s, then
	 * 337s - not a one-off spike but a monotonically growing cost. Root
	 * cause: {@code TicketStorage.getAllChunksWithTicketThat} scans ALL
	 * live FORCED tickets on every single removal, regardless of which
	 * ticket type or map triggered the call - so as the count of
	 * concurrently-held self-tickets grows (which it will, unboundedly,
	 * whenever admission of new pregen targets outpaces their retirement -
	 * exactly the chronic neighbor-stall/low-throughput problem this
	 * project has had open since Round 44), every removal anywhere in the
	 * file gets slower, including the v204 fix's own bounded-per-tick
	 * removals. v204's per-tick cap bounds how many calls happen per tick,
	 * but not the cost of each individual call, so it could not have fixed
	 * this - it only spread a still-unbounded total cost across more
	 * ticks, which is consistent with this pool being allowed to grow
	 * indefinitely for however long the session ran.
	 *
	 * Fix: cap the number of concurrently-held self-tickets. Once the cap
	 * is reached, a newly-marked pregen target simply doesn't get a
	 * self-ticket (it still gets its short-lived requestNonBlockingChunkLoad
	 * ticket and ordinary chunk-load handling) until enough earlier targets
	 * retire to free room - trading a smaller number of naturally-slower
	 * remote targets for keeping the underlying forced-ticket set bounded.
	 * This is the same capped-pool pattern already used successfully for
	 * PREGEN_FINAL_DRAIN_TICKETS/PREGEN_PROCESSING_LEASE_TICKETS elsewhere
	 * in this file - extended here to the one pool that was deliberately
	 * left uncapped.
	 * NEEDS REAL-WORLD LOG VERIFICATION - watch specifically for the
	 * escalating-stall pattern not recurring, and for whether the cap is
	 * ever actually hit (logged once) versus throughput staying limited by
	 * something else entirely.
	 */
	// v213: this pool must never be allowed to drift anywhere near the old 5000-ticket
	// ceiling. PregenManager's absolute hard outstanding ceiling is 512 even in
	// Overnight/Custom profiles. Keep modest emergency headroom for asynchronous
	// handoff, but make a controller/bookkeeping regression fail bounded rather than
	// silently recreating the v204 O(n)-per-removal FORCED-ticket disaster.
	// v217 tightens 640 -> 384: current profile target ceilings top out at 320,
	// so 64 emergency slots are sufficient and a larger pool only worsens the
	// known TicketStorage removal-cost failure mode.
	private static final int PREGEN_SELF_TICKET_MAX = 384;

	// v211: the routine self-ticket must satisfy the SAME 3x3 ticking-neighborhood
	// contract as the authoritative readiness gate. v210 proved the previous
	// radius-1 ticket only ticked the target itself: every frontier batch became
	// neighborStale once loading reached zero, then could retire only after the
	// watchdog installed the stronger radius-3 processing leases. That made the
	// five-second recovery path the normal path by construction. The stalled
	// coordinates also marched along one frontier row (z=-515, then z=-514),
	// which is exactly what happens when not-yet-admitted support chunks on the
	// next row have no ticket of their own. Radius 3 is now the empirically proven
	// strength already used by v209+ processing/final-drain leases; overlapping
	// tickets keep the actual loaded footprint compact for the contiguous scan,
	// while the adaptive outstanding window still bounds ticket count.
	private static final int PREGEN_SELF_TICKET_RADIUS = 3;
	// v221 shared frontier ownership: align sharing with the actual row-major scan.
	// v220 used 3x3 cells + radius 5. That was mathematically sufficient in an
	// ideal ticket graph, but it loaded a much larger diamond and attempted to
	// share ownership across rows that are visited thousands of admissions apart.
	// More importantly, C2ME can invoke chunk-load callbacks concurrently, and the
	// old target->anchor/refcount update was not atomic. Two targets racing onto a
	// fresh anchor could both observe it as new and one could overwrite the other's
	// refcount, allowing the underlying ticket to retire while a target still had a
	// target->anchor mapping.
	//
	// The normal scan is contiguous in X, so v221+ shares exactly three horizontal
	// targets on the same Z row. The anchor is the middle target. v223 corrects an
	// off-by-one in the ticket-level proof exposed by the v221 runtime: the old
	// per-target radius-3 ticket is empirically sufficient for a target's immediate
	// support neighbor, which means the effective ticking threshold corresponds to
	// one stronger level than v220/v221 assumed. v224 changes the role of this ticket completely. v223 proved that even a
	// radius-5 shared anchor can coexist with required neighbors that are not
	// isPositionTicking(), so shared ownership is no longer treated as a carve-
	// readiness primitive. The strip ticket is now only a bounded generation/
	// residency primitive. Radius 1 is enough for the center anchor to keep both
	// horizontal strip-edge targets at FULL while avoiding the large radius-5
	// worldgen footprint. Actual carving is promoted through a small target-
	// centered radius-3 processing-lease lane below.
	// v253.101: the C2ME-sensitive target/anchor/refcount ownership transaction now
	// lives in one Minecraft-free ledger. Engine add/remove callbacks still execute
	// from this flattener while the ledger monitor is held, preserving v221 ordering.

	/** v223 maps each target into a stable horizontal 3x1 scan strip. The center
	 * of [3k..3k+2] is 3k+1, while Z is unchanged. The farthest support chunk
	 * required by an edge target is Manhattan distance 3 from that anchor. The anchor is intentionally only a generation/residency owner in v225;
	 * target-centered carve leases, not this shared strip, prove ticking readiness. */
	private static long selfTicketAnchor(long targetPacked) {
		// v227: the target-centered radius-3 ticket is the one ownership geometry
		// repeatedly proven by runtime to make the 3x3 carve neighborhood usable.
		// Stop sharing a weaker strip anchor and then adding the proven ticket late.
		return targetPacked;
	}

	private static boolean targetHasSelfOwnership(long packed) {
		return pregenSession().PREGEN_SELF_TICKET_LEDGER.hasOwnership(packed);
	}

	/** v219 exact ownership invariant: validate target->anchor ownership, not
	 * cardinality. Shared anchors intentionally mean self ticket count is lower
	 * than outstanding target count. */
	public static int missingPregenSelfTicketCount() {
		return pregenSession().PREGEN_TARGET_CHUNKS.countMatching(packed -> !targetHasSelfOwnership(packed));
	}

	public static int repairMissingPregenSelfTickets(ServerLevel world, int maxRepairs) {
		if (world == null || maxRepairs <= 0 || pregenSession().PREGEN_TARGET_CHUNKS.isEmpty()) return 0;
		int repaired = 0;
		long[] missing = pregenSession().PREGEN_TARGET_CHUNKS.firstMatching(maxRepairs, packed -> !targetHasSelfOwnership(packed));
		for (long packed : missing) {
			installSelfTicket(world, packed);
			if (targetHasSelfOwnership(packed)) repaired++;
		}
		if (repaired > 0) OceanCanvas.LOGGER.warn("(Ocean Canvas) v219 proactively repaired {} missing shared Pregen ownership(s) before watchdog recovery.", repaired);
		return repaired;
	}

	private static void installSelfTicket(ServerLevel world, long targetPacked) {
		long anchor = selfTicketAnchor(targetPacked);
		try {
			OceanCanvasPregenSelfTicketLedger.InstallResult result = pregenSession().PREGEN_SELF_TICKET_LEDGER.install(
					targetPacked, anchor, PREGEN_SELF_TICKET_MAX,
					() -> net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(world,
							new ChunkPos(ChunkPos.getX(anchor), ChunkPos.getZ(anchor)), PREGEN_SELF_TICKET_RADIUS));
			if (result == OceanCanvasPregenSelfTicketLedger.InstallResult.CAP_REJECTED_FIRST) {
				OceanCanvas.LOGGER.warn("(Ocean Canvas) v224 shared residency-ticket pool reached cap ({} anchors); admission will fail closed until an anchor retires.", PREGEN_SELF_TICKET_MAX);
			}
		} catch (Throwable t) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not install v224 shared residency-ticket anchor at {},{}: {}", ChunkPos.getX(anchor), ChunkPos.getZ(anchor), t.toString());
		}
	}

	private static void releaseSelfTicket(ServerLevel world, long targetPacked) {
		final long[] removedAnchor = { selfTicketAnchor(targetPacked) };
		try {
			pregenSession().PREGEN_SELF_TICKET_LEDGER.release(targetPacked, world == null ? null :
					anchor -> {
						removedAnchor[0] = anchor;
						net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.removeForcedTicket(world,
								new ChunkPos(ChunkPos.getX(anchor), ChunkPos.getZ(anchor)), PREGEN_SELF_TICKET_RADIUS);
					});
		} catch (Throwable t) {
			long anchor = removedAnchor[0];
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not remove v224 shared residency-ticket anchor at {},{}: {}", ChunkPos.getX(anchor), ChunkPos.getZ(anchor), t.toString());
		}
	}

	private static void releaseAllSelfTickets(ServerLevel world) {
		// Withdraw target ownership in bounded batches. Shared anchors disappear only
		// when their final target ref is released.
		int released = 0;
		for (long target : pregenSession().PREGEN_SELF_TICKET_LEDGER.targetSnapshot()) {
			if (released >= MAX_SELF_TICKET_RELEASES_PER_TICK) break;
			releaseSelfTicket(world, target);
			released++;
		}
	}

	public static void enqueueForPregen(ServerLevel world, LevelChunk chunk) {
		long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
		if (pregenSession().PREGEN_TARGET_LOAD_RESCUED.remove(packed)) pregenSession().PREGEN_TARGET_LOAD_RESCUE_SUCCESSES.incrementAndGet();
		pregenSession().PREGEN_TARGET_LOAD_RESCUE_LAST_MS.remove(packed);
		boolean newlyOwned = pregenSession().PREGEN_TARGET_CHUNKS.add(packed);
		if (newlyOwned) net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.recordAdmission(packed, "enqueueForPregen");
		pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.putIfAbsent(packed, monotonicMillis());
		pregenSession().PREGEN_QUEUED_CHUNKS.add(packed);
		if (pregenSession().QUEUED_CHUNK_POSITIONS.add(chunk.getPos())) {
			pregenSession().PENDING_CHUNKS.add(chunk);
		}
		installSelfTicket(world, packed);
	}

	/**
	 * v228.3 retired compatibility hook for v228.2 stale-load rescue. The old
	 * implementation added a radius-0 FORCED ticket to a target that already held
	 * the normal radius-3 FORCED ticket, so it could not strengthen ticket level.
	 * Kept as a no-op so adjacent source remains source-compatible while the real
	 * hard-stall escape lives in PregenManager's mandatory persisted deferral lane.
	 */
	/**
	 * Selects only the bounded oldest Pregen targets needed by recovery/diagnostic paths.
	 * This replaces backlog-sized boxed snapshots and full sorts with an allocation bounded
	 * by {@code limit}. Ordering is oldest request first with packed-coordinate tie-break.
	 */
	private static long[] oldestPregenTargets(int limit, long now, java.util.function.LongPredicate eligible) {
		if (limit <= 0 || pregenSession().PREGEN_TARGET_CHUNKS.isEmpty()) return new long[0];
		long[] keys = new long[limit];
		long[] times = new long[limit];
		java.util.Arrays.fill(times, Long.MAX_VALUE);
		final int[] count = {0};
		pregenSession().PREGEN_TARGET_CHUNKS.forEachLong(packed -> {
			if (eligible != null && !eligible.test(packed)) return;
			long first = pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.getOrDefault(packed, now);
			int used = count[0];
			int insert = Math.min(used, limit - 1);
			if (used >= limit) {
				long worstTime = times[limit - 1], worstKey = keys[limit - 1];
				if (first > worstTime || (first == worstTime && packed >= worstKey)) return;
			}
			while (insert > 0 && (first < times[insert - 1] || (first == times[insert - 1] && packed < keys[insert - 1]))) {
				if (insert < limit) { times[insert] = times[insert - 1]; keys[insert] = keys[insert - 1]; }
				insert--;
			}
			times[insert] = first; keys[insert] = packed;
			if (used < limit) count[0] = used + 1;
		});
		return count[0] == limit ? keys : java.util.Arrays.copyOf(keys, count[0]);
	}

	public static int serviceStalledPregenTargetLoads(ServerLevel world, int maxRequests) {
		// v228.5: explicit FULL demand is an escalation, not routine admission. Give
		// the already-installed radius-3 ownership ticket one second to deliver FULL.
		// If it does not, dispatch oldest-first, with both a per-sample budget and a
		// global active bridge cap. A completed/failed bridge may be retried only after
		// five seconds. This bounds how much getChunkFutureMainThread work can be
		// injected into Minecraft's main executor at once.
		if (world == null || maxRequests <= 0 || pregenSession().PREGEN_TARGET_CHUNKS.isEmpty()) return 0;
		long now = monotonicMillis();
		int active = pregenSession().PREGEN_TARGET_FUTURES.size();
		if (active >= PREGEN_FULL_DEMAND_MAX_ACTIVE) return 0;
		int available = Math.min(maxRequests, PREGEN_FULL_DEMAND_MAX_ACTIVE - active);
		long[] candidates = oldestPregenTargets(available, now, packed ->
				!pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)
				&& !pregenSession().PREGEN_TARGET_FUTURES.containsKey(packed));
		int dispatched = 0;
		for (long packed : candidates) {
			if (active + dispatched >= PREGEN_FULL_DEMAND_MAX_ACTIVE) break;
			if (pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed) || pregenSession().PREGEN_TARGET_FUTURES.containsKey(packed)) continue;
			long first = pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.getOrDefault(packed, now);
			if (now - first < PREGEN_FULL_DEMAND_INITIAL_DELAY_MS) continue;
			long last = pregenSession().PREGEN_FULL_DEMAND_LAST_MS.get(packed);
			if (last != OceanCanvasPrimitiveLongLongMap.ABSENT && now - last < PREGEN_FULL_DEMAND_RETRY_MS) continue;
			requestPregenFullDemandOffThread(world, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
			dispatched++;
			if (dispatched >= maxRequests) break;
		}
		if (dispatched > 0) OceanCanvas.LOGGER.debug("(Ocean Canvas) v230.5 dispatched {} bounded cold-tail FULL demand(s); active bridge cap={}", dispatched, PREGEN_FULL_DEMAND_MAX_ACTIVE);
		return dispatched;
	}

	/**
	 * Withdraws only work owned by a cancelled pregen. Ordinary chunk-load
	 * work and Reset's FORCE_REPROCESS chunks remain untouched.
	 */
	public static int cancelQueuedPregenWork(ServerLevel world) {
		if (pregenSession().PREGEN_TARGET_CHUNKS.isEmpty() && pregenSession().PREGEN_QUEUED_CHUNKS.isEmpty()) return 0;
		int removed = 0;
		java.util.Iterator<LevelChunk> it = pregenSession().PENDING_CHUNKS.iterator();
		while (it.hasNext()) {
			LevelChunk chunk = it.next();
			long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed) && !pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) continue;
			if (pregenSession().FORCE_REPROCESS_CHUNKS.contains(packed)) continue;
			it.remove();
			pregenSession().QUEUED_CHUNK_POSITIONS.remove(chunk.getPos());
			pregenSession().PREGEN_QUEUED_CHUNKS.remove(packed);
			if (pregenSession().PREGEN_TARGET_CHUNKS.remove(packed)) {
				net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.recordCancellation(packed, "cancelQueuedPregenWork");
                net.oceancanvas.mod.diagnostic.OceanCanvasHotRegionMap.onDeferral(packed);
				removed++;
			}
		}
		// Targets that were requested asynchronously but had not reached
		// CHUNK_LOAD yet are invalidated too. If they eventually load, the
		// normal CHUNK_LOAD path may still queue them as ordinary gameplay
		// work, but they no longer count as cancelled-pregen work.
		pregenSession().PREGEN_TARGET_CHUNKS.forEachLong(packed -> {
			net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.recordCancellation(packed, "cancelOutstandingPregenWork");
            net.oceancanvas.mod.diagnostic.OceanCanvasHotRegionMap.onDeferral(packed);
        });
		pregenSession().PREGEN_TARGET_CHUNKS.clear();
		pregenSession().PREGEN_QUEUED_CHUNKS.clear();
		pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.clear();
		pregenSession().PREGEN_FULL_DEMAND_LAST_MS.clear();
		pregenSession().PREGEN_TARGET_LOAD_RESCUE_LAST_MS.clear();
		pregenSession().PREGEN_TARGET_LOAD_RESCUED.clear();
		pregenSession().PREGEN_AUDIT_REJECTION_COUNTS.clear();
		pregenSession().PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS.clear();
		cancelOutstandingPregenFutures();
		releaseAllFinalDrainTickets(world);
		return removed;
	}

	/**
	 * Amount of work owned by the CURRENT pregen that is waiting in the
	 * shared flattener queue.
	 *
	 * Do not use pregenSession().PENDING_CHUNKS.size() for pregen backpressure: that deque
	 * also contains ordinary CHUNK_LOAD/startup-sweep work and, in worlds
	 * upgraded from older OceanCanvas builds, may contain legacy entries
	 * that cannot be attributed to any current pregen. Counting the whole
	 * shared queue is what made a brand-new pregen immediately say
	 * "281 queued" even after the previous pregen had been cancelled.
	 */
	public static int pendingPregenChunkCount() {
		return pregenSession().PREGEN_QUEUED_CHUNKS.size();
	}

	/**
	 * Every current pregen target that has been requested but has not yet
	 * completed OceanCanvas carving. Unlike pendingPregenChunkCount(), this
	 * includes C2ME async requests that have not reached CHUNK_LOAD yet.
	 */
	public static int outstandingPregenTargetCount() {
		return pregenSession().PREGEN_TARGET_CHUNKS.size();
	}

	/**
	 * v132.5 diagnostic snapshot. Read-only: deliberately exposes only Ocean Canvas's
	 * own bookkeeping, never Minecraft internals and never mutates ticket state.
	 */
	public record PregenTicketDiagnostics(
			int selfActive, long selfInstalls, long selfReleases,
			int carveLaneActive,
			int processingLeaseActive, long processingLeaseInstalls, long processingLeaseReleases,
			int finalDrainActive, long finalDrainInstalls, long finalDrainReleases,
			int targetFutures, int supportFutures, int supportFuturesDone,
			long fullDemandRequests, long fullDemandCompletions, long fullDemandFailures, long fullDemandCancellations,
			int loadRescueActive, long loadRescueRequests, long loadRescueSuccesses,
			int auditPending, int auditRepeatPending, long auditRejections, long auditRepairs, long auditRepeatFailures) {}

	public static PregenTicketDiagnostics pregenTicketDiagnostics() {
		// v163.4 diagnostic: count pregenSession().PREGEN_SUPPORT_FUTURES entries whose future
		// is already CompletableFuture#isDone() but still sitting in the map.
		// This is the fork in the road for the still-unexplained support-futures
		// growth (v163.3's reorder fix did not stop it): a large doneCount here
		// means completed futures are for some reason never reaching their
		// whenComplete removal (a real leak, mechanism still unknown); a
		// doneCount near zero means these are genuinely still-pending requests
		// (a backlog/throughput problem, not a leak) and chasing "why isn't it
		// removed" further would be the wrong direction entirely. Snapshot
		// iteration is safe - pregenSession().PREGEN_SUPPORT_FUTURES is a ConcurrentHashMap.
		int supportDone = pregenSession().PREGEN_SUPPORT_FUTURES.countValues(java.util.concurrent.CompletableFuture::isDone);
		int auditRepeatPending = 0;
		auditRepeatPending = pregenSession().PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS.countValuesAtLeast(2);
		return new PregenTicketDiagnostics(
				pregenSession().PREGEN_SELF_TICKET_LEDGER.activeAnchorCount(), pregenSession().PREGEN_SELF_TICKET_LEDGER.installCount(), pregenSession().PREGEN_SELF_TICKET_LEDGER.releaseCount(),
				pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.carveLaneCount(),
				pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.physicalTicketCount(), pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.installCount(), pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.releaseCount(),
				pregenSession().PREGEN_FINAL_DRAIN_LEDGER.activeCount(), pregenSession().PREGEN_FINAL_DRAIN_LEDGER.installCount(), pregenSession().PREGEN_FINAL_DRAIN_LEDGER.releaseCount(),
				pregenSession().PREGEN_TARGET_FUTURES.size(), pregenSession().PREGEN_SUPPORT_FUTURES.size(), supportDone,
				pregenSession().PREGEN_FULL_DEMAND_REQUESTS.get(), pregenSession().PREGEN_FULL_DEMAND_COMPLETIONS.get(), pregenSession().PREGEN_FULL_DEMAND_FAILURES.get(), pregenSession().PREGEN_FULL_DEMAND_CANCELLATIONS.get(),
				pregenSession().PREGEN_TARGET_LOAD_RESCUED.size(), pregenSession().PREGEN_TARGET_LOAD_RESCUE_REQUESTS.get(), pregenSession().PREGEN_TARGET_LOAD_RESCUE_SUCCESSES.get(),
				pregenSession().PREGEN_AUDIT_REJECTION_COUNTS.size(), auditRepeatPending, pregenSession().PREGEN_AUDIT_REJECTIONS.get(), pregenSession().PREGEN_AUDIT_REPAIRS.get(), pregenSession().PREGEN_AUDIT_REPEAT_FAILURES.get());
	}

	/** OC-F208 authoritative inventory of every Ocean Canvas ticket/lease owned by this flattener. */
	public record TicketOwnership(String pool,int chunkX,int chunkZ,long ageMillis,String owner,String purpose,String releaseCondition) {}
	public static java.util.List<TicketOwnership> ticketOwnershipSnapshot(){
		long now=monotonicMillis();java.util.ArrayList<TicketOwnership> out=new java.util.ArrayList<>();
		for (var e : pregenSession().OCEANCANVAS_FORCE_LOAD_LEDGER.entrySnapshot())
			addTicket(out,"force-load",e.packed(),now-e.installedMillis(),"loader","non-blocking chunk residency","chunk loads or 10s safety age");
		for (OceanCanvasPregenFinalDrainTicketLedger.Entry e : pregenSession().PREGEN_FINAL_DRAIN_LEDGER.entrySnapshot(now)) addTicket(out,"final-drain",e.packed(),now-e.installedMillis(),"pregen","retire stalled target","target retires or operation drains");
		for (OceanCanvasPregenProcessingLeaseLedger.Entry e : pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.physicalEntrySnapshot(now)) addTicket(out,"processing",e.packed(),now-e.installedMillis(),"pregen","keep 3x3 carve neighborhood ticking","target leaves carve lane");
		for(long p:pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.carveLaneSnapshot())if(!pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.hasPhysicalTicket(p))addTicket(out,"carve-lease",p,now-pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.getOrDefault(p,now),"pregen","logical carve-lane ownership","target retires from carve lane");
		for (OceanCanvasPregenSelfTicketLedger.AnchorEntry e : pregenSession().PREGEN_SELF_TICKET_LEDGER.anchorSnapshot()) { long oldest=now; for(long target:e.targets()) oldest=Math.min(oldest,pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.getOrDefault(target,now)); addTicket(out,"self",e.anchor(),now-oldest,"pregen","target residency owner; refs="+e.refs(),"all target references retire"); }
		for(long p:lightFinalizerSession().relightResidencyLedger.activeSnapshot()){long ns=lightFinalizerSession().relightStartedNs.get(p);long age=ns==OceanCanvasPrimitiveLongLongMap.ABSENT?0L:Math.max(0L,(System.nanoTime()-ns)/1_000_000L);addTicket(out,"relight",p,age,"lighting","authoritative relight residency","relight verification completes");}
		out.sort(java.util.Comparator.comparing(TicketOwnership::pool).thenComparingLong(TicketOwnership::ageMillis).reversed());return java.util.List.copyOf(out);
	}
	private static void addTicket(java.util.List<TicketOwnership> out,String pool,long packed,long age,String owner,String purpose,String release){out.add(new TicketOwnership(pool,ChunkPos.getX(packed),ChunkPos.getZ(packed),Math.max(0L,age),owner,purpose,release));}

	/** Number of transient FORCED tickets currently owned solely by Pregen final-drain recovery. */
	public static int finalDrainTicketCount() {
		return pregenSession().PREGEN_FINAL_DRAIN_LEDGER.activeCount() + pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.physicalTicketCount();
	}

	/**
	 * v73 authoritative Pregen completion gate. There is exactly one legal way
	 * for a live Pregen target to leave pregenSession().PREGEN_TARGET_CHUNKS: its CURRENT block
	 * state must pass the full physical Canvas audit, or project state must
	 * explicitly identify the chunk as CUSTOM_OR_MODIFIED. Historical processed
	 * seals, legacy adoption, and a successful return from flattenChunk are not
	 * completion evidence by themselves.
	 *
	 * <p>The audit is intentionally repeated at retirement time even when a
	 * physical seal already exists. That makes the target set itself the source
	 * of truth and closes the v72 bug where final drain could retire a target
	 * using an older processed seal without ever consulting the physical gate.
	 */
	private static boolean tryCompletePregenTarget(ServerLevel world, LevelChunk chunk, String path) {
		long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
		if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) return true;
		boolean crashRecoveryAtEntry = pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.contains(packed);

		var terrainState = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(chunk.getPos());
		if (terrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED) {
			pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(packed);
			retireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);
			OceanCanvasActiveTerrainOperationBridge.recoveryExemptionCommitted(world, chunk.getPos());
			OceanCanvas.LOGGER.debug("(Ocean Canvas) Pregen target {},{} accepted via {}: explicit CUSTOM_OR_MODIFIED exemption.",
					chunk.getPos().x(), chunk.getPos().z(), path);
			return true;
		}

		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		// v253.72 recovery quarantine: a chunk that was physically sealed before an
		// interrupted save may still contain a late lava/ice/falling-block mutation,
		// and its persisted SKY arrays may be stale. Route that already-physical chunk
		// through the authoritative finalizer instead of accepting the old
		// certificate. Only genuinely uncommitted targets carry physical-repair
		// permission; committed safety-tail chunks are intentionally light-only.
		if (pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.contains(packed)
				&& terrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS
				&& protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos())) {
			// v253.72.3: committed safety-tail recovery remains forbidden from
			// general terrain canonicalization, but a physically-verified old seal can
			// itself be from a build whose WATER audit accepted flowing states. Run the
			// narrow active-Pregen fluid-only proof before LIGHT_ONLY publication.
			FluidSurvivorRepair survivorRepair =
					repairTrackedPregenFluidSurvivors(world, chunk, "crash-recovery-existing-seal");
			boolean allowPhysicalRepair = pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.remove(packed);
			retireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);
			// v253.125.10: committed LIGHT_ONLY recovery often reloads an already
			// canonical field. Prove and publish it immediately instead of forcing every
			// one of the 41k recovery-tail chunks through stage-0 sweep + settle. A real
			// fluid mutation, or any failed/insufficient proof, falls back to the full
			// finalizer. PHYSICAL_AWARE targets retain their stronger drift-repair path.
			if (!allowPhysicalRepair && !survivorRepair.changed()
					&& tryFastCertifyResidentCanvasLighting(
							world, chunk, false, Long.MAX_VALUE, "CRASH_RECOVERY_LIGHT_ONLY")) {
				return true;
			}
			// Repair permission is not mutation evidence. Preserve the stronger recovery
			// lane when allowed, but invalidate neighbour lighting only if the narrow
			// fluid-survivor pass actually changed blocks.
			scheduleLightSync(world, chunk.getPos(), allowPhysicalRepair, survivorRepair.changed());
			if (!allowPhysicalRepair) {
				OceanCanvasActiveTerrainOperationBridge.recoveryLightOnlyEnteredFinalizer(world, chunk.getPos());
			}
			long handoffs = lightTelemetrySession().LIGHT_DIAG_CRASH_RECOVERY_HANDOFFS.incrementAndGet();
			OceanCanvas.LOGGER.debug("(Ocean Canvas) CRASH-RECOVERY target {},{} entered authoritative finalizer via {} mode={}.",
					chunk.getPos().x(), chunk.getPos().z(), path, allowPhysicalRepair ? "PHYSICAL_AWARE" : "LIGHT_ONLY");
			if (handoffs <= 8L || (handoffs & 255L) == 0L) {
				OceanCanvas.LOGGER.info("(Ocean Canvas) CRASH-RECOVERY-HANDOFF build={} count={} latest={},{} mode={} pendingLight={} action=rate-limited-aggregate",
						net.oceancanvas.mod.OceanCanvas.VERSION, handoffs, chunk.getPos().x(), chunk.getPos().z(),
						allowPhysicalRepair ? "PHYSICAL_AWARE" : "LIGHT_ONLY", lightFinalizerSession().pendingTicks.size());
			}
			return true;
		}
		// v253.49 migration/restart fast path. A v253.48 physical seal is still
		// authoritative physical evidence; what was missing was the separately
		// persisted light-finalization fact. Do not destructively flatten or rescan
		// the entire column profile just to repair lighting. Arm a non-destructive
		// finalizer, retire terrain ownership, and let the job's lighting gate hold
		// completion until AUTHORITATIVE_SEND certifies this chunk.
		if (terrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS
				&& protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos())
				&& !protectedData.isChunkLightingVerified(chunk.getPos())) {
			retireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);
			if (tryFastCertifyResidentCanvasLighting(
					world, chunk, false, Long.MAX_VALUE, "PHYSICAL_ONLY_LEGACY")) {
				return true;
			}
			scheduleLightSync(world, chunk.getPos(), false);
			OceanCanvas.LOGGER.debug("(Ocean Canvas) Pregen target {},{} accepted via {} as physical-only legacy/restart state; strict fast proof did not pass, queued non-destructive lighting finalization.",
					chunk.getPos().x(), chunk.getPos().z(), path);
			return true;
		}

		PhysicalProfileMismatch mismatch = firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get());
		if (mismatch != null) {
			OceanCanvasProtectedData.get(world).clearChunkProcessed(chunk.getPos());
			int rejects = pregenSession().PREGEN_AUDIT_REJECTION_COUNTS.incrementCapped(packed, Integer.MAX_VALUE);
			pregenSession().PREGEN_AUDIT_REJECTIONS.incrementAndGet();
			boolean postFlatten = path != null && path.contains("post-flatten");
			if (postFlatten) {
				int postFailures = pregenSession().PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS.incrementCapped(packed, Integer.MAX_VALUE);
				if (postFailures >= 2) {
					pregenSession().PREGEN_AUDIT_REPEAT_FAILURES.incrementAndGet();
					OceanCanvas.LOGGER.error("(Ocean Canvas) v230.5 REPEATED post-flatten audit failure #{} at target {},{} via {}: {}. This indicates a current flattener/neighbor invariant regression; target remains owned.",
							postFailures, chunk.getPos().x(), chunk.getPos().z(), path, mismatch.describe());
				} else {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) v230.5 post-flatten audit rejected target {},{} via {}: {}. Target remains outstanding and will be retried.",
							chunk.getPos().x(), chunk.getPos().z(), path, mismatch.describe());
				}
			} else {
				OceanCanvas.LOGGER.info("(Ocean Canvas) v230.5 stale-seal audit mismatch at {},{} via {}: {}. Immediate flatten/repair follows; this is not counted as a current-pipeline failure unless post-flatten audit also rejects it.",
						chunk.getPos().x(), chunk.getPos().z(), path, mismatch.describe());
			}
			return false;
		}

		if (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkCompletionAllowed(chunk)) {
			protectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());
		}
		Integer priorRejects = pregenSession().PREGEN_AUDIT_REJECTION_COUNTS.remove(packed);
		pregenSession().PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS.remove(packed);
		if (priorRejects != null && priorRejects > 0) {
			pregenSession().PREGEN_AUDIT_REPAIRS.incrementAndGet();
			OceanCanvas.LOGGER.info("(Ocean Canvas) v230.5 audit repair PASS at {},{} via {} after {} prior rejection(s).",
					chunk.getPos().x(), chunk.getPos().z(), path, priorRejects);
		}
		boolean recoveryLightOnlyFinalizer = false;
		if (pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.remove(packed)) {
			// A recovery target that reached this point was not previously physical-sealed.
			// Preserve the recovery policy: uncommitted work may repair physical drift,
			// while committed safety-tail work remains light-only.
			boolean allowPhysicalRepair = pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.remove(packed);
			// This branch just PROVED the current physical profile; it did not mutate it.
			// Keep repair permission for a later genuine drift, but do not reset the
			// lighting epoch or re-arm neighbours merely because permission exists.
			scheduleLightSync(world, chunk.getPos(), allowPhysicalRepair, false);
			recoveryLightOnlyFinalizer = !allowPhysicalRepair;
		}
		retireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);
		if (recoveryLightOnlyFinalizer) {
			OceanCanvasActiveTerrainOperationBridge.recoveryLightOnlyEnteredFinalizer(world, chunk.getPos());
		}
		OceanCanvas.LOGGER.debug("(Ocean Canvas) Pregen target {},{} accepted via {} after authoritative physical audit.",
				chunk.getPos().x(), chunk.getPos().z(), path);
		return true;
	}

	private static void retireVerifiedPregenTarget(ServerLevel world, LevelChunk chunk, long packed, String path, boolean crashRecoveryAtEntry) {
		pregenSession().PENDING_CHUNKS.remove(chunk);
		pregenSession().FORCE_REPROCESS_CHUNKS.remove(packed);
		pregenSession().QUEUED_CHUNK_POSITIONS.remove(chunk.getPos());
		pregenSession().PREGEN_QUEUED_CHUNKS.remove(packed);
		boolean retiredOwnership = pregenSession().PREGEN_TARGET_CHUNKS.remove(packed);
		if (retiredOwnership) {
			// v253.73.16: forward progress first, carve-specific second. Every branch
			// that reaches here has permanently retired one owned target.
			pregenSession().PREGEN_JOB_FORWARD_RETIREMENTS.incrementAndGet();
			if (!crashRecoveryAtEntry && path != null && path.contains("post-flatten")) {
				pregenSession().PREGEN_NEW_TERRAIN_RETIREMENTS.incrementAndGet();
			}
            net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.recordRetirement(packed, "authoritative-physical-audit");
            net.oceancanvas.mod.diagnostic.OceanCanvasHotRegionMap.onRetirement(packed,monotonicMillis());
        }
		pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.remove(packed);
		pregenSession().PREGEN_FULL_DEMAND_LAST_MS.remove(packed);
		pregenSession().PREGEN_TARGET_LOAD_RESCUE_LAST_MS.remove(packed);
		pregenSession().PREGEN_TARGET_LOAD_RESCUED.remove(packed);
		pregenSession().PREGEN_AUDIT_REJECTION_COUNTS.remove(packed);
		pregenSession().PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS.remove(packed);
		pregenSession().PREGEN_TARGET_FUTURES.remove(packed);
		releaseFinalDrainTicket(world, packed);
		releaseProcessingLease(world, packed);
		releaseSelfTicket(world, packed);
	}

	/**
	 * Final-drain escape hatch. A READY target may be processed directly, but
	 * v73 deliberately routes retirement through tryCompletePregenTarget so
	 * final drain cannot use a weaker definition of completion than the normal
	 * queue. Returns true only when a target was actually retired.
	 *
	 * <p><b>v208: found a real gap in that "no weaker definition" guarantee,
	 * from a real session log.</b> The readiness check right here -
	 * {@link #hasMissingLoadedNeighbor}/{@link #hasMissingManagedStructureOwner}
	 * - only checks {@code world.hasChunk(...)} (FULL status), not the
	 * stricter ticking tier. That's the EXACT same weaker check v198 (Round
	 * 49) already found and fixed in {@link #neighborsReady} for the main
	 * queue-drain loop, specifically because a neighbor can be loaded but not
	 * ticking, and carving against a non-ticking neighbor risks reintroducing
	 * a real historical crash ("Trying to schedule tick in not loaded
	 * position" - see {@link #neighborsReady}'s own doc). This method was
	 * never updated to match. A real session log showed exactly the
	 * signature this predicts: v96's "Pregen liveness recovery" diagnostic
	 * (which ALSO uses the same weak {@link #pregenQueueDiagnostics}, not
	 * fixed here but tracked separately below) reported growing counts of
	 * targets that LOOK ready by the weak definition while final drain made
	 * no real progress retiring them.
	 *
	 * <p>Fix: use the same authoritative {@link #neighborsReady}/{@link
	 * #structureReferencesReady} pair the main queue-drain loop already
	 * uses, instead of this method's own separate, weaker checks - closing
	 * the actual gap the class doc above already promises doesn't exist.
	 */
	public static boolean finishOneReadyPregenTarget(ServerLevel world) {
		LevelChunk selected = null;
		long selectedPacked = 0L;
		for (LevelChunk chunk : pregenSession().PENDING_CHUNKS) {
			long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed) || !pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) continue;
			if (!neighborsReady(world, chunk.getPos())) continue;
			if (!structureReferencesReady(world, chunk)) continue;
			selected = chunk; selectedPacked = packed; break;
		}
		if (selected == null) return false;
		LevelChunk chunk = selected; long packed = selectedPacked;
		if (!pregenSession().FORCE_REPROCESS_CHUNKS.contains(packed)
				&& OceanCanvasProtectedData.get(world).isChunkProcessed(chunk.getPos())
				&& tryCompletePregenTarget(world, chunk, "final-drain-existing-seal")) {
			return true;
		}

		pregenSession().PENDING_CHUNKS.remove(chunk);
		boolean flattened = flattenChunk(world, chunk);
		if (flattened && tryCompletePregenTarget(world, chunk, "final-drain-post-flatten")) return true;

		// The authoritative gate rejected this target. Keep it owned and
		// queued so the normal repair path can try again; do not report
		// progress for it merely because flattenChunk returned.
		pregenSession().PREGEN_QUEUED_CHUNKS.add(packed);
		pregenSession().QUEUED_CHUNK_POSITIONS.add(chunk.getPos());
		if (!pregenSession().PENDING_CHUNKS.contains(chunk)) pregenSession().PENDING_CHUNKS.add(chunk);
		return false;
	}

	/**
	 * @param holdNewRequests v202.42: when true, suppress ordinary new target
	 *        admission/final-drain loading bursts, but still permit a tiny
	 *        bounded number of radius-2 processing leases for targets that are
	 *        ALREADY resident and ALREADY owned by Pregen. Runtime evidence
	 *        showed the old absolute prohibition could livelock the entire
	 *        queue at neighborStale with loading=0 and leases=0, because the
	 *        only mechanism capable of keeping the 3x3 neighborhood resident
	 *        was itself disabled by the freeze state.
	 */
	public static void nudgeOutstandingPregenTargets(ServerLevel world, boolean holdNewRequests) {
		long now = monotonicMillis();
		long[] targets = pregenSession().PREGEN_TARGET_CHUNKS.windowFromOrdinal(
				pregenSession().PREGEN_RECOVERY_CURSOR, PREGEN_RECOVERY_TARGETS_PER_NUDGE);
		if (targets.length == 0) return;

		// v95: service a rotating bounded slice rather than every outstanding
		// target. The 20k runtime gate plateaued around 40 old requests with heap
		// near 80%; the old final-drain helper would have installed a radius-2
		// ticket for all of them in one pass. A moving eight-target window keeps
		// recovery effective without turning the watchdog into a memory amplifier.
		int count = targets.length;
		pregenSession().PREGEN_RECOVERY_CURSOR += count;
		int newLeasesThisCall = 0;
		int newFinalDrainTicketsThisCall = 0;
		for (int i = 0; i < count; i++) {
			long packed = targets[i];
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			// v182: was hasChunk(cx, cz) then a separate world.getChunk(cx, cz)
			// below on the true branch - the same non-atomic pair that caused
			// flattenChunk's 220+ second stall (see that method's v182 fix
			// comment). getChunkNow folds the check and the fetch into one
			// call that can't be raced by C2ME's background eviction.
			LevelChunk target = world.getChunkSource().getChunkNow(cx, cz);
			if (target == null) {
				long first = pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.get(packed);
				long age = first == OceanCanvasPrimitiveLongLongMap.ABSENT ? 0L : now - first;

				// v70 zero-failure policy: a target is never retired merely because
				// an async FULL future is slow. Once it is genuinely stale, install
				// a forced ticket with a small support radius so the target and the
				// neighboring generation dependencies C2ME may require are kept
				// active. Most importantly, the recovery timer starts HERE. v103:
				// same per-call new-install throttle as the processing lease below -
				// a whole cohort of targets can cross the staleness threshold
				// together right after a resume, and this path shares the same
				// burst risk once PREGEN_RECOVERY_MAX_TICKETS was raised.
				if (!holdNewRequests
						&& first != OceanCanvasPrimitiveLongLongMap.ABSENT && age >= PREGEN_TARGET_STALE_LOAD_MS
						&& pregenSession().PREGEN_FINAL_DRAIN_LEDGER.activeCount() < PREGEN_RECOVERY_MAX_TICKETS
						&& newFinalDrainTicketsThisCall < PREGEN_PROCESSING_LEASE_NEW_PER_NUDGE
						&& pregenSession().PREGEN_FINAL_DRAIN_LEDGER.reserve(packed)) {
					try {
						net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(
								world, new ChunkPos(cx, cz), PREGEN_FINAL_DRAIN_TICKET_RADIUS);
						pregenSession().PREGEN_FINAL_DRAIN_LEDGER.markInstalled(packed, now);
						newFinalDrainTicketsThisCall++;
						OceanCanvas.LOGGER.warn("(Ocean Canvas) Stalled pregen FULL load at {},{} after {} ms; installed radius-{} final-drain loading ticket. This target will not be dropped.",
								cx, cz, age, PREGEN_FINAL_DRAIN_TICKET_RADIUS);
					} catch (Throwable t) {
						pregenSession().PREGEN_FINAL_DRAIN_LEDGER.rollbackReservation(packed);
						OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not install final-drain ticket for {},{}: {}", cx, cz, t.toString());
					}
				}

				if (!holdNewRequests) {
					long last = pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.get(packed);
					if (last == OceanCanvasPrimitiveLongLongMap.ABSENT || now - last >= PREGEN_SUPPORT_RETRY_MS) {
						pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.put(packed, now);
						requestPregenTargetLoad(world, new ChunkPos(cx, cz));
					}
				}

				// If a ticketed target is still unresolved after a recovery epoch,
				// refresh the ticket and future instead of declaring failure. This
				// deliberately trades a potentially longer final drain for the
				// project's required invariant: successful Pregen has zero failures.
				long ticketAt = pregenSession().PREGEN_FINAL_DRAIN_LEDGER.installedAt(packed);
				if (ticketAt != OceanCanvasPrimitiveLongLongMap.ABSENT && now - ticketAt >= PREGEN_FINAL_DRAIN_RETRY_MS) {
					releaseFinalDrainTicket(world, packed);
					pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.remove(packed);
					try {
						net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(
								world, new ChunkPos(cx, cz), PREGEN_FINAL_DRAIN_TICKET_RADIUS);
						pregenSession().PREGEN_FINAL_DRAIN_LEDGER.reserve(packed);
						pregenSession().PREGEN_FINAL_DRAIN_LEDGER.markInstalled(packed, now);
						requestPregenTargetLoad(world, new ChunkPos(cx, cz));
						OceanCanvas.LOGGER.warn("(Ocean Canvas) Refreshed final-drain recovery for {},{} after {} ms ticketed; target remains mandatory.",
								cx, cz, now - ticketAt);
					} catch (Throwable t) {
						pregenSession().PREGEN_FINAL_DRAIN_LEDGER.rollbackReservation(packed);
						OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not refresh final-drain recovery for {},{}: {}", cx, cz, t.toString());
					}
				}
				continue;
			}
			enqueueForPregen(world, target);
			// v100: when the target itself is resident but its 3x3 support
			// neighborhood is not, a request-only retry can oscillate forever
			// under C2ME. Lease a very small number of such targets until they
			// actually pass the authoritative completion gate. v103: cap how
			// many NEW leases this single call installs, not just the total
			// concurrent count - see PREGEN_PROCESSING_LEASE_NEW_PER_NUDGE's
			// doc for why an uncapped burst caused real tick stalls.
			//
			// v208: this used to trigger on hasMissingLoadedNeighbor (a
			// neighbor is not even loaded, FULL status) while neighborsReady
			// (the actual authoritative gate flattenChunk depends on, which
			// checks isPositionTicking - the stricter TICKING tier, per v198/
			// Round 49) was called separately, right below, with its result
			// simply discarded. That meant this recovery escalation only ever
			// fired for the rare case of a genuinely unloaded neighbor, never
			// for the far more common real-world case of a neighbor that IS
			// loaded but not yet ticking - exactly the gap a real session log
			// showed (v96 liveness-recovery diagnostics reporting large,
			// growing counts of targets that looked "ready" by the weak
			// definition while making no real retirement progress). Calling
			// neighborsReady FIRST and using its real result as the trigger
			// closes that gap: the one mechanism designed to push a stuck
			// target over the ticking threshold now actually fires when a
			// target needs it, not only in the much rarer case it doesn't.
			boolean ready = neighborsReady(world, target.getPos());
			int processingLeaseBudget = holdNewRequests
					? PREGEN_PROCESSING_LEASE_NEW_WHILE_HELD
					: PREGEN_PROCESSING_LEASE_NEW_PER_NUDGE;
			if (!ready
					&& newLeasesThisCall < processingLeaseBudget
					&& !pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.isCarveLaneTarget(packed)) {
				// v202.42: this is recovery of an already-owned resident target,
				// not admission of a new target. Keep the target-centered radius-2
				// window resident long enough for neighborsReady() and flattenChunk()
				// to observe the same neighborhood instead of racing C2ME eviction.
				installProcessingLease(world, packed);
				newLeasesThisCall++;
			}
			logStaleProcessingLeaseIfNeeded(packed, now);
			if (!holdNewRequests) {
				for (java.util.Map.Entry<net.minecraft.world.level.levelgen.structure.Structure,
						it.unimi.dsi.fastutil.longs.LongSet> entry : target.getAllReferences().entrySet()) {
					if (!isManagedStructure(entry.getKey())) continue;
					it.unimi.dsi.fastutil.longs.LongIterator it = entry.getValue().iterator();
					while (it.hasNext()) {
						long owner = it.nextLong();
						int ox = ChunkPos.getX(owner), oz = ChunkPos.getZ(owner);
						if (!world.hasChunk(ox, oz)) {
							long last = pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.get(owner);
							if (last == OceanCanvasPrimitiveLongLongMap.ABSENT || now - last >= PREGEN_SUPPORT_RETRY_MS) {
								pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.put(owner, now);
								requestPregenSupportLoad(world, ox, oz);
							}
						}
					}
				}
			}
		}
	}


	/**
	 * v224 two-lane CARVE controller. Shared strip tickets now keep targets resident
	 * only; they are deliberately NOT considered proof that a target's 3x3 support
	 * window is ticking. A small target-centered radius-3 processing-lease lane is
	 * promoted proactively as soon as all 3x3 chunks are FULL. This turns the one
	 * mechanism runtime evidence repeatedly proved effective from a five-second
	 * watchdog rescue into normal bounded flow control.
	 *
	 * Missing FULL support is requested with a separate tiny budget, so a single
	 * tick cannot fan out a large worldgen wave. Managed structure owners are kept
	 * on the same bounded support path. The lane cap is intentionally small because
	 * each promoted target owns a stronger radius-3 ticket until physical retirement.
	 */
	public static void servicePregenCarveLane(ServerLevel world) {
		if (world == null || pregenSession().PREGEN_TARGET_CHUNKS.isEmpty()) return;

		// v228.5: the logical CARVE lane is a ready-to-carve execution lane, not a
		// waiting room. v228.4 promoted as soon as support reached FULL, then up to
		// eight targets could all lose/wait for TICKING readiness and monopolize every
		// lane slot. Runtime showed exactly lane=8/ready=0/neighbor=8. Demote any
		// queued lane member that is no longer tick-ready; its normal radius-3 self
		// ownership remains untouched, so this frees scheduler capacity without
		// releasing residency or adding a new ticket.
		for (long packed : pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.carveLaneSnapshot()) {
			if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed) || !pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) {
				releaseProcessingLease(world, packed);
				continue;
			}
			ChunkPos lanePos = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
			if (hasMissingTickingNeighbor(world, lanePos)) releaseProcessingLease(world, packed);
		}

		int active = pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.carveLaneCount();
		int promoted = 0;
		int supportRequests = 0;
		long now = monotonicMillis();
		for (LevelChunk chunk : pregenSession().PENDING_CHUNKS) {
			if (active >= PREGEN_PROCESSING_LEASE_MAX || promoted >= PREGEN_CARVE_LANE_NEW_PER_TICK) break;
			long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			if (!pregenSession().PREGEN_TARGET_CHUNKS.contains(packed) || !pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) continue;
			if (pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.isCarveLaneTarget(packed)) continue;

			boolean supportFull = true;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int nx = chunk.getPos().x() + dx, nz = chunk.getPos().z() + dz;
					if (world.hasChunk(nx, nz)) continue;
					supportFull = false;
					if (supportRequests < PREGEN_CARVE_LANE_SUPPORT_REQUESTS_PER_TICK) {
						long key = ChunkPos.pack(nx, nz);
						long last = pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.get(key);
						if (last == OceanCanvasPrimitiveLongLongMap.ABSENT || now - last >= PREGEN_SUPPORT_RETRY_MS) {
							pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.put(key, now);
							requestPregenSupportLoad(world, nx, nz);
							supportRequests++;
						}
					}
				}
			}
			if (!supportFull) continue;

			boolean ownersFull = true;
			for (java.util.Map.Entry<net.minecraft.world.level.levelgen.structure.Structure,
					it.unimi.dsi.fastutil.longs.LongSet> entry : chunk.getAllReferences().entrySet()) {
				if (!isManagedStructure(entry.getKey())) continue;
				it.unimi.dsi.fastutil.longs.LongIterator owners = entry.getValue().iterator();
				while (owners.hasNext()) {
					long owner = owners.nextLong();
					int ox = ChunkPos.getX(owner), oz = ChunkPos.getZ(owner);
					if (world.hasChunk(ox, oz)) continue;
					ownersFull = false;
					if (supportRequests < PREGEN_CARVE_LANE_SUPPORT_REQUESTS_PER_TICK) {
						long last = pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.get(owner);
						if (last == OceanCanvasPrimitiveLongLongMap.ABSENT || now - last >= PREGEN_SUPPORT_RETRY_MS) {
							pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.put(owner, now);
							requestPregenSupportLoad(world, ox, oz);
							supportRequests++;
						}
					}
				}
			}
			if (!ownersFull) continue;

			// Do not consume a bounded lane slot until the exact authoritative 3x3
			// ticking gate is already true. This removes head-of-line blocking where
			// eight FULL-but-not-yet-ticking targets starved otherwise-ready work.
			if (hasMissingTickingNeighbor(world, chunk.getPos())) continue;

			installProcessingLease(world, packed);
			if (pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.isCarveLaneTarget(packed)) {
				active++;
				promoted++;
			}
		}
	}

	private static void releaseFinalDrainTicket(ServerLevel world, long packed) {
		if (!pregenSession().PREGEN_FINAL_DRAIN_LEDGER.contains(packed)) return;
		try {
			net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.removeForcedTicket(
					world, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)),
					PREGEN_FINAL_DRAIN_TICKET_RADIUS);
			if (!pregenSession().PREGEN_FINAL_DRAIN_LEDGER.retire(packed)) return;
			long releases = pregenSession().PREGEN_FINAL_DRAIN_LEDGER.recordRelease();
			OceanCanvas.LOGGER.debug("(Ocean Canvas) v132.5 FINAL-DRAIN-TICKET - {},{} | active={}, installs={}, releases={}",
					ChunkPos.getX(packed), ChunkPos.getZ(packed), pregenSession().PREGEN_FINAL_DRAIN_LEDGER.activeCount(), pregenSession().PREGEN_FINAL_DRAIN_LEDGER.installCount(), releases);
		} catch (Throwable t) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not remove final-drain ticket at {},{}: {}",
					ChunkPos.getX(packed), ChunkPos.getZ(packed), t.toString());
		}
	}

	private static void installProcessingLease(ServerLevel world, long packed) {
		// v228.1: promotion and physical recovery-ticket ownership are separate.
		// Normal Pregen targets already own a target-centered radius-3 self ticket,
		// so promotion must still become visible to neighborsReady()/diagnostics even
		// though no duplicate TicketStorage entry is required.
		if (!pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.promote(packed, monotonicMillis(), PREGEN_PROCESSING_LEASE_MAX)) return;
		if (targetHasSelfOwnership(packed)) return;
		if (pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.hasPhysicalTicket(packed)) return;
		try {
			net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(
					world, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)),
					PREGEN_PROCESSING_LEASE_RADIUS);
			pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.markPhysicalInstalled(packed, monotonicMillis());
			OceanCanvas.LOGGER.warn(
					"(Ocean Canvas) v100 leased stalled neighbor window at {},{} (radius {}, {}/{} leases). Lease remains only until this target retires or is deferred.",
					ChunkPos.getX(packed), ChunkPos.getZ(packed), PREGEN_PROCESSING_LEASE_RADIUS,
					pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.physicalTicketCount(), PREGEN_PROCESSING_LEASE_MAX);
		} catch (Throwable t) {
			pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.rollbackPromotion(packed);
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not install v100 processing lease at {},{}: {}",
					ChunkPos.getX(packed), ChunkPos.getZ(packed), t.toString());
		}
	}

	/**
	 * v132.6: logs once, the first time a single already-leased target crosses
	 * {@link #PREGEN_PROCESSING_LEASE_STALE_LOG_MS} without retiring - names
	 * the exact coordinate and how long its lease has been open, the missing
	 * counterpart to the final-drain ticket's existing per-target staleness
	 * log. Read-only against ownership/ticket state; only touches the
	 * dedicated "already logged" set so this never fires twice for the same
	 * lease.
	 */
	private static void logStaleProcessingLeaseIfNeeded(long packed, long now) {
		long installedAt = pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.installedAt(packed);
		if (installedAt == OceanCanvasPrimitiveLongLongMap.ABSENT) return;
		long age = now - installedAt;
		if (age < PREGEN_PROCESSING_LEASE_STALE_LOG_MS) return;
		if (!pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.markStaleLogged(packed)) return;
		OceanCanvas.LOGGER.warn(
				"(Ocean Canvas) v132.6 processing lease at {},{} has been open {} ms without this target retiring - "
						+ "its FULL future (or one of its 3x3 support neighbors') is the one actually stuck.",
				ChunkPos.getX(packed), ChunkPos.getZ(packed), age);
	}

	private static void releaseProcessingLease(ServerLevel world, long packed) {
		// v228.1: always clear logical promotion, even when self ownership meant no
		// extra physical recovery ticket was installed. This prevents stale lane
		// occupancy/timestamps from surviving retirement, deferral, cancel, or replay.
		boolean hadPhysicalTicket = pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.retireLogical(packed);
		if (!hadPhysicalTicket || world == null) return;
		try {
			net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.removeForcedTicket(
					world, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)),
					PREGEN_PROCESSING_LEASE_RADIUS);
			if (!pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.commitPhysicalRetirement(packed)) return;
			long releases = pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.recordRelease();
			OceanCanvas.LOGGER.debug("(Ocean Canvas) v132.5 RECOVERY-LEASE - {},{} | active={}, installs={}, releases={}",
					ChunkPos.getX(packed), ChunkPos.getZ(packed), pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.physicalTicketCount(), pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.installCount(), releases);
		} catch (Throwable t) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not remove v100 processing lease at {},{}: {}",
					ChunkPos.getX(packed), ChunkPos.getZ(packed), t.toString());
		}
	}

	private static void releaseAllProcessingLeases(ServerLevel world) {
		// v228.1: logical promotions can exist without a physical lease ticket.
		// Drain the logical lane, not only TicketStorage-backed recovery leases, so
		// cancel/session transitions cannot leave up to eight phantom active slots.
		// The lane itself is capped at PREGEN_PROCESSING_LEASE_MAX, preserving v218's
		// bounded TicketStorage-removal guarantee.
		int released = 0;
		for (long packed : pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.carveLaneSnapshot()) {
			if (released >= MAX_PROCESSING_LEASE_RELEASES_PER_TICK) break;
			releaseProcessingLease(world, packed);
			released++;
		}
	}

	/** Release every transient FORCED ticket installed only for Pregen final drain. */
	public static void releaseAllFinalDrainTickets(ServerLevel world) {
		releaseAllProcessingLeases(world);
		releaseAllSelfTickets(world);
		if (world == null) {
			pregenSession().PREGEN_FINAL_DRAIN_LEDGER.clearActiveTickets();
			pregenSession().PREGEN_RECOVERY_CURSOR = 0L;
			return;
		}
		int finalDrainReleased = 0;
		for (long packed : pregenSession().PREGEN_FINAL_DRAIN_LEDGER.activeSnapshot()) {
			if (finalDrainReleased >= MAX_FINAL_DRAIN_BULK_RELEASES_PER_TICK) break;
			releaseFinalDrainTicket(world, packed);
			finalDrainReleased++;
		}
		// Keep install timestamps for entries that remain live; orphan draining will
		// remove the matching timestamp when each ticket is actually released.
		pregenSession().PREGEN_RECOVERY_CURSOR = 0L;
	}

	public static void beginPregenSession(ServerLevel world) {
		releaseAllFinalDrainTickets(world);
		// A new Job reconstructs crash-recovery obligations from its durable journal.
		// Never let a static marker from a prior completed/cancelled session leak in.
		pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.clear();
	}

	/** Diagnostic only: total shared flattener work from every source. */
	public static int pendingChunkCount() {
		return pregenSession().PENDING_CHUNKS.size();
	}

	/**
	 * Read-only diagnostic snapshot for the current pregen queue.
	 *
	 * This intentionally performs NO chunk requests and changes no queue state.
	 * It exists so the boss bar/log can tell us why backpressure is not
	 * draining instead of reducing every failure mode to "192 queued".
	 */
	public record PregenQueueDiagnostics(
			int queued,
			int ready,
			int missingNeighbor,
			int missingNeighborStale,
			int missingStructureOwner,
			int loadingNotQueued,
			int loadingStale,
			long oldestLoadingMs,
			int ownedNeighborMisses,
			int farthestMissingFromAnchor) {

		public String shortText() {
			return "queued=" + queued
					+ ", ready=" + ready
					+ ", neighbor=" + missingNeighbor
					+ ", neighborStale=" + missingNeighborStale
					+ ", structure=" + missingStructureOwner
					+ ", loading=" + loadingNotQueued
					+ ", loadingStale=" + loadingStale
					+ ", oldestLoadMs=" + oldestLoadingMs
					+ ", ownedNeighborMiss=" + ownedNeighborMisses
					+ ", missAnchorDist=" + farthestMissingFromAnchor;
		}
	}

	// v117: a freshly-submitted target is, by definition, missing its neighbors
	// for the first stretch of its life - the scan has to reach those columns
	// first. That is completely normal in-flight state, not a stall. The v106
	// breaker's raw missingNeighbor count could not tell the two apart, so on
	// a healthy, fast-draining queue where most targets are simply new, it
	// still measured "queued=30, neighbor=30 (100%)" and paused admission
	// anyway - throttling a queue that was actually working fine. A genuine
	// stall (Chunky's own run on identical hardware/mods reached 30-70+
	// chunks/s; Ocean Canvas's pregen sat at 1-5/s) only shows up once a
	// target has been queued for a while and STILL lacks a neighbor.
	private static final long PREGEN_NEIGHBOR_STALE_MS = 3000L;

	/**
	 * v208: this used to call {@link #hasMissingLoadedNeighbor} (hasChunk /
	 * FULL status), the same weak check {@link #finishOneReadyPregenTarget}
	 * was just found and fixed to no longer use. Since v198 (Round 49), the
	 * REAL readiness gate ({@link #neighborsReady}) requires the stricter
	 * ticking tier, so this diagnostic's "ready"/"neighbor" counts had been
	 * silently out of sync with reality since then - capable of reporting a
	 * target as "ready" while the authoritative gate would still reject it,
	 * which is exactly the misleading signature a real session log showed
	 * (large, growing "ready" counts alongside a final drain making no real
	 * progress). Fixed by checking {@code isPositionTicking} directly here
	 * too - deliberately NOT calling {@link #neighborsReady} itself, since
	 * that has real side effects (force-loading missing neighbors) this
	 * method's own doc comment above promises it never has.
	 */
	private static boolean hasMissingTickingNeighbor(ServerLevel world, ChunkPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				long neighborKey = new ChunkPos(pos.x() + dx, pos.z() + dz).pack();
				if (!world.getChunkSource().isPositionTicking(neighborKey)) {
					return true;
				}
			}
		}
		return false;
	}

	/** OC-F209: per-target queue causality. Read-only; never requests or force-loads chunks. */
	public record PregenTargetCause(int chunkX,int chunkZ,String lane,String cause,long ageMillis,String dependency,boolean stale) {}

	public static java.util.List<PregenTargetCause> pregenTargetCausalitySnapshot(ServerLevel world,int limit){
		int cap=Math.max(1,Math.min(128,limit));long now=monotonicMillis();java.util.ArrayList<PregenTargetCause> out=new java.util.ArrayList<>();
		long[] targets=oldestPregenTargets(cap,now,packed->true);
		for(long packed:targets){
			if(out.size()>=cap)break;int cx=ChunkPos.getX(packed),cz=ChunkPos.getZ(packed);long age=Math.max(0L,now-pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.getOrDefault(packed,now));
			LevelChunk chunk=world.getChunkSource().getChunkNow(cx,cz);String lane,cause,dep;boolean stale=false;
			if(chunk==null){lane="LOAD";cause="WAITING_TARGET_FULL";dep="target FULL chunk is not resident";stale=age>=PREGEN_TARGET_LOAD_RESCUE_MIN_AGE_MS;}
			else if(!pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)){lane="ADMISSION";cause="WAITING_QUEUE_OWNERSHIP";dep="resident target has not entered the bounded flatten queue";stale=age>=PREGEN_TARGET_LOAD_RESCUE_MIN_AGE_MS;}
			else if(!pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.isCarveLaneTarget(packed)){lane="SUPPORT";if(hasMissingLoadedNeighbor(world,chunk.getPos())){cause="SUPPORT_LOADING";dep="one or more 3x3 FULL support chunks are missing";}else if(hasMissingManagedStructureOwner(world,chunk)){cause="STRUCTURE_OWNER";dep="managed structure owner chunk is not resident";}else{cause="READY_FOR_CARVE_LANE";dep="all FULL support is resident; waiting bounded carve-lane promotion";}stale=age>=PREGEN_NEIGHBOR_STALE_MS;}
			else if(hasMissingTickingNeighbor(world,chunk.getPos())){lane="CARVE";cause="NEIGHBOR_TICKING";dep="one or more 3x3 support chunks are not ticking";long installed=pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.installedAtOr(packed,now);stale=now-installed>=PREGEN_NEIGHBOR_STALE_MS;}
			else if(hasMissingManagedStructureOwner(world,chunk)){lane="CARVE";cause="STRUCTURE_OWNER";dep="managed structure owner chunk is unavailable";stale=age>=PREGEN_NEIGHBOR_STALE_MS;}
			else{lane="RETIRE";cause="READY_TO_FINISH";dep="all known readiness gates are satisfied; awaiting bounded retirement/audit";}
			out.add(new PregenTargetCause(cx,cz,lane,cause,age,dep,stale));
		}
		return java.util.List.copyOf(out);
	}

	public static PregenQueueDiagnostics pregenQueueDiagnostics(ServerLevel world) {
		int queued = 0;
		int ready = 0;
		int missingNeighbor = 0;
		int missingNeighborStale = 0;
		int missingStructureOwner = 0;
		int supportLoading = 0;
		int ownedNeighborMisses = 0;
		int farthestMissingFromAnchor = 0;
		long now = monotonicMillis();

		for (LevelChunk chunk : pregenSession().PENDING_CHUNKS) {
			long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			if (!pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) continue;
			queued++;

			// v224: unpromoted resident targets are generation-lane work, not
			// neighbor-stalled carve work. First wait for all FULL support; only a
			// promoted radius-3 target is judged by isPositionTicking().
			if (!pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.isCarveLaneTarget(packed)) {
				if (hasMissingLoadedNeighbor(world, chunk.getPos())) supportLoading++;
				else if (hasMissingManagedStructureOwner(world, chunk)) supportLoading++;
				continue;
			}

			if (hasMissingTickingNeighbor(world, chunk.getPos())) {
				missingNeighbor++;
				ownedNeighborMisses++;
				// For v224 this distance is diagnostic only: processing leases are
				// target-centered, so the distance from target itself is what matters.
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						int nx = chunk.getPos().x() + dx, nz = chunk.getPos().z() + dz;
						if (!world.getChunkSource().isPositionTicking(ChunkPos.pack(nx, nz))) {
							farthestMissingFromAnchor = Math.max(farthestMissingFromAnchor, Math.abs(dx) + Math.abs(dz));
						}
					}
				}
				long leaseAt = pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.installedAt(packed);
				if (leaseAt != OceanCanvasPrimitiveLongLongMap.ABSENT && now - leaseAt >= PREGEN_NEIGHBOR_STALE_MS) missingNeighborStale++;
				continue;
			}
			if (hasMissingManagedStructureOwner(world, chunk)) {
				missingStructureOwner++;
				continue;
			}
			ready++;
		}

		int targetLoading = Math.max(0, pregenSession().PREGEN_TARGET_CHUNKS.size() - pregenSession().PREGEN_QUEUED_CHUNKS.size());
		final int[] loadingStaleRef = {0};
		final long[] oldestLoadingMsRef = {0L};
		pregenSession().PREGEN_TARGET_CHUNKS.forEachLong(packed -> {
			if (pregenSession().PREGEN_QUEUED_CHUNKS.contains(packed)) return;
			long first = pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.get(packed);
			if (first == OceanCanvasPrimitiveLongLongMap.ABSENT) return;
			long age = Math.max(0L, now - first);
			oldestLoadingMsRef[0] = Math.max(oldestLoadingMsRef[0], age);
			if (age >= PREGEN_TARGET_LOAD_RESCUE_MIN_AGE_MS) loadingStaleRef[0]++;
		});
		int loadingStale = loadingStaleRef[0];
		long oldestLoadingMs = oldestLoadingMsRef[0];
		int loadingNotQueued = targetLoading + supportLoading;
		return new PregenQueueDiagnostics(
				queued, ready, missingNeighbor, missingNeighborStale, missingStructureOwner, loadingNotQueued,
				loadingStale, oldestLoadingMs, ownedNeighborMisses, farthestMissingFromAnchor);
	}

	private static boolean hasMissingLoadedNeighbor(ServerLevel world, ChunkPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!world.hasChunk(pos.x() + dx, pos.z() + dz)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasMissingManagedStructureOwner(ServerLevel world, LevelChunk chunk) {
		return OceanCanvasManagedStructureReferences.hasMissingOwner(world, chunk);
	}


	/** Marks a chunk as belonging to an explicit destructive repair/reset pass,
	 * even before C2ME has finished loading it. CHUNK_LOAD will then queue the
	 * live LevelChunk and the forced flag prevents an old processed seal from
	 * turning the requested Rewipe into a no-op. */
	public static void markRegenerationTarget(ChunkPos pos) {
		pregenSession().FORCE_REPROCESS_CHUNKS.add(ChunkPos.pack(pos.x(), pos.z()));
	}

	/** Queues a chunk for an explicit destructive repair/reset pass. */
	public static void enqueueForRegeneration(LevelChunk chunk) {
		markRegenerationTarget(chunk.getPos());
		if (pregenSession().QUEUED_CHUNK_POSITIONS.add(chunk.getPos())) {
			pregenSession().PENDING_CHUNKS.add(chunk);
		}
	}

	/** True while a reset still has explicitly-forced chunks waiting in this rectangle. */
	public static boolean hasPendingRegeneration(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		return pregenSession().FORCE_REPROCESS_CHUNKS.anyMatch(packed -> {
			int x = ChunkPos.getX(packed);
			int z = ChunkPos.getZ(packed);
			return x >= minChunkX && x <= maxChunkX && z >= minChunkZ && z <= maxChunkZ;
		});
	}

	/**
	 * Exact-mask variant used by arbitrary-shaped Region Reset jobs.
	 *
	 * The old rectangle test could keep a completed polygon/brush Reset alive
	 * forever because ANY forced chunk inside its bounding rectangle counted,
	 * including chunks that were not part of that region at all (or stale work
	 * from another operation). Region Reset must wait only for the chunks the
	 * user actually selected.
	 */
	public static boolean hasPendingRegeneration(java.util.Set<Long> exactChunks) {
		if (exactChunks == null || exactChunks.isEmpty()) return false;
		return pregenSession().FORCE_REPROCESS_CHUNKS.anyMatch(exactChunks::contains);
	}

	/**
	 * Final-drain escape hatch for explicit Rewipe work. This mirrors the
	 * authoritative Pregen final-drain path: if a forced target is already
	 * resident and its immediate dependencies are loaded, process one target
	 * directly instead of waiting forever for ordinary queue rotation.
	 */
	public static boolean finishOneReadyRegenerationTarget(ServerLevel world, java.util.Set<Long> exactChunks) {
		if (exactChunks == null || exactChunks.isEmpty()) return false;
		LevelChunk selected = null; long selectedPacked = 0L;
		for (LevelChunk chunk : pregenSession().PENDING_CHUNKS) {
			long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			if (!exactChunks.contains(packed) || !pregenSession().FORCE_REPROCESS_CHUNKS.contains(packed)) continue;

			// v88: the old final-drain helper only *checked* for missing neighbors.
			// It never invoked neighborsReady(), so a remote Rewipe could sit at
			// 100% forever waiting for dependencies that nothing would ever request.
			// Reuse the normal flattener's bounded one-hop dependency loader here.
			if (!neighborsReady(world, chunk.getPos())) continue;
			if (!managedStructureOwnersReadyForRewipe(world, chunk)) continue;
			selected = chunk; selectedPacked = packed; break;
		}
		if (selected == null) return false;
		LevelChunk chunk = selected; long packed = selectedPacked;
		pregenSession().PENDING_CHUNKS.remove(chunk);
		boolean flattened = flattenChunk(world, chunk);
		if (flattened && !pregenSession().FORCE_REPROCESS_CHUNKS.contains(packed)) return true;

		pregenSession().QUEUED_CHUNK_POSITIONS.add(chunk.getPos());
		if (!pregenSession().PENDING_CHUNKS.contains(chunk)) pregenSession().PENDING_CHUNKS.add(chunk);
		return false;
	}

	private static boolean managedStructureOwnersReadyForRewipe(ServerLevel world, LevelChunk chunk) {
		return OceanCanvasManagedStructureReferences.ownersReadyOrRequest(
				world, chunk, (w, cx, cz) -> requestNonBlockingChunkLoad(w, cx, cz));
	}

	/**
	 * Re-request exact Rewipe targets that were submitted to C2ME but never
	 * reached CHUNK_LOAD/the flattener queue. No target is retired here; this
	 * only restores forward progress for work that is still explicitly owned.
	 */
	public static void nudgeOutstandingRegenerationTargets(ServerLevel world, java.util.Set<Long> exactChunks) {
		if (exactChunks == null || exactChunks.isEmpty()) return;
		// Never re-request the entire region in one tick.  The previous helper
		// could fire hundreds of FULL requests every second during final drain,
		// exactly the kind of burst that makes an integrated server appear frozen.
		int nudged = 0;
		final int maxNudges = 8;
		for (long packed : pregenSession().FORCE_REPROCESS_CHUNKS.firstMatching(maxNudges, exactChunks::contains)) {
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			// v182: getChunkNow - see flattenChunk's v182 fix comment for why
			// the old hasChunk()-then-getChunk() pair here was still racy.
			LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
			if (chunk != null) {
				if (pregenSession().QUEUED_CHUNK_POSITIONS.add(chunk.getPos())) pregenSession().PENDING_CHUNKS.add(chunk);
				neighborsReady(world, chunk.getPos());
				managedStructureOwnersReadyForRewipe(world, chunk);
			} else {
				// v137: non-blocking - see requestNonBlockingChunkLoad's doc comment.
				requestNonBlockingChunkLoad(world, cx, cz);
			}
			nudged++;
		}
	}

	/** Total chunks still waiting in the forced-reset queue, for health/diagnostics. */
	public static int pendingRegenerationCount() {
		return pregenSession().FORCE_REPROCESS_CHUNKS.size();
	}

	/** Number of exact selected chunks still waiting in the forced-reset queue. */
	public static int pendingRegenerationCount(java.util.Set<Long> exactChunks) {
		if (exactChunks == null || exactChunks.isEmpty()) return 0;
		return pregenSession().FORCE_REPROCESS_CHUNKS.countMatching(exactChunks::contains);
	}

	/** v253.73.3 count of OC-side transient ticket bookkeeping before shutdown. */
	public static int trackedTransientTicketCount() {
		return pregenSession().OCEANCANVAS_FORCE_LOAD_LEDGER.activeCount()
				+ pregenSession().PREGEN_SELF_TICKET_LEDGER.activeAnchorCount()
				+ pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.physicalTicketCount()
				+ pregenSession().PREGEN_FINAL_DRAIN_LEDGER.activeCount()
				+ lightFinalizerSession().relightResidencyLedger.activeCount()
				+ OceanCanvasStructureRelocationRescueService.activeTicketCount();
	}

	public static boolean lastServerStopTicketDeactivationSucceeded() {
		return pregenSession().LAST_SERVER_STOP_TICKET_DEACTIVATION_SUCCEEDED;
	}

	public static int lastServerStopTrackedTransientTickets() {
		return pregenSession().LAST_SERVER_STOP_TRACKED_TRANSIENT_TICKETS;
	}

	/**
	 * v253.73.3 close all chunk-loading tickets through Minecraft 26.2's native
	 * shutdown transition before throwing away Ocean Canvas bookkeeping.
	 *
	 * <p>Do NOT replace this with a loop of removeTicketWithRadius calls. Runtime
	 * dumps already proved that each FORCED removal can rescan global forced-ticket
	 * state; thousands of them in one shutdown tick is itself a freeze risk. The
	 * native deactivateTicketsOnClosing() operation is the engine's bulk close path
	 * and is exactly the lifecycle operation vanilla uses for a dying chunk source.</p>
	 */
	private static void deactivateTicketsForServerStop(ServerLevel world) {
		if (world == null) {
			pregenSession().LAST_SERVER_STOP_TRACKED_TRANSIENT_TICKETS = trackedTransientTicketCount();
			pregenSession().LAST_SERVER_STOP_TICKET_DEACTIVATION_SUCCEEDED = true;
			return;
		}
		// OceanCanvas.java retains a belt-and-suspenders flattener cleanup after
		// PregenManager.onServerStopping(). If an active Pregen already performed the
		// native close, do not invoke it twice or erase the tracked-at-stop evidence.
		if (pregenSession().LAST_SERVER_STOP_DEACTIVATED_WORLD == world && pregenSession().LAST_SERVER_STOP_TICKET_DEACTIVATION_SUCCEEDED) return;
		pregenSession().LAST_SERVER_STOP_TRACKED_TRANSIENT_TICKETS = trackedTransientTicketCount();
		pregenSession().LAST_SERVER_STOP_TICKET_DEACTIVATION_SUCCEEDED = false;
		long started = System.nanoTime();
		try {
			world.getChunkSource().deactivateTicketsOnClosing();
			pregenSession().LAST_SERVER_STOP_DEACTIVATED_WORLD = world;
			pregenSession().LAST_SERVER_STOP_TICKET_DEACTIVATION_SUCCEEDED = true;
			OceanCanvas.LOGGER.info(
					"(Ocean Canvas) SAVE-QUIT-TICKET-DEACTIVATE build={} trackedTransient={} elapsedMs={} action=native-bulk-ticket-close-before-bookkeeping-clear",
					net.oceancanvas.mod.OceanCanvas.VERSION, pregenSession().LAST_SERVER_STOP_TRACKED_TRANSIENT_TICKETS,
					(System.nanoTime() - started) / 1_000_000L);
		} catch (Throwable t) {
			pregenSession().LAST_SERVER_STOP_TICKET_DEACTIVATION_SUCCEEDED = false;
			OceanCanvas.LOGGER.error(
					"(Ocean Canvas) SAVE-QUIT-TICKET-DEACTIVATE build={} FAILED trackedTransient={} elapsedMs={} error={}. Crash marker remains armed; shutdown must not report a clean transient-ticket invariant.",
					net.oceancanvas.mod.OceanCanvas.VERSION, pregenSession().LAST_SERVER_STOP_TRACKED_TRANSIENT_TICKETS,
					(System.nanoTime() - started) / 1_000_000L, t.toString());
		}
	}

	/** Drop transient generation work when the integrated server is stopping.
	 * Nothing in these queues is authoritative; active pregen/reset jobs persist
	 * their own cursor and will safely requeue work on the next start. */
	public static void onServerStopping(ServerLevel world) {
		// Critical ordering: deactivate real engine tickets FIRST. v253.72.9 cleared
		// the sets below and then claimed cleanup PASS while those FORCED tickets
		// still pinned thousands of chunks during FastQuit.
		deactivateTicketsForServerStop(world);
		pregenSession().PENDING_CHUNKS.clear();
		pregenSession().QUEUED_CHUNK_POSITIONS.clear();
		pregenSession().FORCE_REPROCESS_CHUNKS.clear();
		pregenSession().PREGEN_TARGET_CHUNKS.forEachLong(packed ->
			net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.recordCancellation(packed, "cancelOutstandingPregenWork"));
		pregenSession().PREGEN_TARGET_CHUNKS.clear();
		pregenSession().PREGEN_QUEUED_CHUNKS.clear();
		pregenSession().FORCE_LOADED_CHUNK_KEYS.clear();
		pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.clear();
		pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.clear();
		pregenSession().PREGEN_FULL_DEMAND_LAST_MS.clear();
		pregenSession().PREGEN_TARGET_LOAD_RESCUE_LAST_MS.clear();
		pregenSession().PREGEN_TARGET_LOAD_RESCUED.clear();
		pregenSession().PREGEN_AUDIT_REJECTION_COUNTS.clear();
		pregenSession().PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS.clear();
		cancelOutstandingPregenFutures();
		// v253.73.3: real TicketStorage ownership was deactivated in one native bulk
		// transition above. It is now safe to clear every matching Java-side tracker
		// without either leaking real tickets or running the known-expensive per-ticket
		// removal loop during shutdown.
		pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.clearAfterNativeDeactivation();
		pregenSession().PREGEN_FINAL_DRAIN_LEDGER.clearAfterNativeDeactivation();
		pregenSession().OCEANCANVAS_FORCE_LOAD_LEDGER.clearAfterNativeDeactivation();
		int abandonedSelfTickets = pregenSession().PREGEN_SELF_TICKET_LEDGER.clearAfterNativeDeactivation();
		if (abandonedSelfTickets > 0) OceanCanvas.LOGGER.debug("(Ocean Canvas) v253.73.3 server-stop cleared {} shared-ticket bookkeeping entr{} after native ticket deactivation.", abandonedSelfTickets, abandonedSelfTickets == 1 ? "y" : "ies");
		// v253.49: pending lighting correctness is persisted by the absence of the
		// per-chunk lighting certificate, so JVM-only queues/tickets are disposable
		// at server destruction. Clear them here after Job.persist() has run. The
		// next session's bounded metadata scan / ordinary chunk-load self-heal will
		// re-arm any physically-complete-but-lighting-unverified chunk.
		int abandonedLight = lightFinalizerSession().pendingTicks.size() + lightRecoverySession().skyBackoffUntilTick.size() + lightRecoverySession().pressureParkUntilTick.size();
		lightFinalizerSession().pendingTicks.clear();
		lightFinalizerSession().pendingWorkOrder.clear();
		lightFinalizerSession().pendingWorkMembership.clear();
		lightFinalizerSession().visibleWorkOrder.clear();
		lightFinalizerSession().visibleWorkMembership.clear();
		lightFinalizerSession().terrainWorkOrder.clear();
		lightFinalizerSession().terrainWorkMembership.clear();
		lightFinalizerSession().debtMembershipGeneration.incrementAndGet();
		lightFinalizerSession().cachedPendingCountGeneration = Long.MIN_VALUE;
		lightFinalizerSession().pendingPasses.clear();
		lightFinalizerSession().allowPhysicalRepair.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.clear();
		lightRecoverySession().verifyEscalations.clear();
		lightRecoverySession().skyQuarantine.clear();
		lightFinalizerSession().stagedBlockFingerprint.clear();
		lightFinalizerSession().terrainLastMutationTick.clear();
		lightFinalizerSession().physicalMutationGenerationTick.clear();
		lightFinalizerSession().boundaryMutationGenerationTick.clear();
		lightFinalizerSession().terrainInstabilityStreak.clear();
		lightRecoverySession().hardSkyResetRadius1.clear();
		lightRecoverySession().hardSkyResetCounts.clear();
		lightRecoverySession().skyBackoffUntilTick.clear();
		lightRecoverySession().pressureParkUntilTick.clear();
		lightFinalizerSession().retryLedger.clear();
		lightTelemetrySession().LIGHT_DIAG_LAST_DETAIL_WARN_TICK.clear();
		lightTelemetrySession().LIGHT_DEEP_ZERO_PUBLIC_RECOVERY_UNAVAILABLE_LOGGED.set(false);
		lightTelemetrySession().lightDiagDetailBudgetTick = Long.MIN_VALUE;
		lightTelemetrySession().lightDiagDetailBudgetUsed = 0;
		lightTelemetrySession().lightDiagLastAggregateTick = Long.MIN_VALUE;
		lightTelemetrySession().LIGHT_DIAG_SUPPRESSED_DETAIL_WARNINGS.set(0L);
		lightRecoverySession().deepZeroScrubCounts.clear();
		lightRecoverySession().visibleDeepDenseRepairCounts.clear();
		lightRecoverySession().visibleDeepClusterRepairCounts.clear();
		lightRecoverySession().visibleDeepDenseSectionY.clear();
		lightRecoverySession().visibleDeepDenseCursor.clear();
		lightRecoverySession().visibleDeepDenseChecksAccumulated.clear();
		lightRecoverySession().visibleDeepClusterAttemptInFlight.clear();
		lightRecoverySession().visibleDeepClusterSectionY.clear();
		lightRecoverySession().visibleDeepClusterCursor.clear();
		lightRecoverySession().visibleDeepClusterChecksAccumulated.clear();
		lightRecoverySession().deepRepairHeapDeferralCounts.clear();
		lightRecoverySession().pathologyHotspotLevel.clear();
		lightRecoverySession().visibleDeepClusterNextAllowedTick.set(Long.MIN_VALUE);
		lightRecoverySession().deepZeroPublicRecoveryCounts.clear();
		lightRecoverySession().deepZeroPublicRecoveryRetryAfterTick.clear();
		lightRecoverySession().postAuditRegressions.clear();
		lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAITS.clear();
		lightFinalizerSession().relightStartedNs.clear();
		lightTelemetrySession().LIGHT_DIAG_PRE_PRIME.clear();
		lightFinalizerSession().relightResidencyLedger.clearAfterNativeDeactivation();
		lightFinalizerSession().historicalWarmResidencyUntilTick.clear();
		lightFinalizerSession().nextHistoricalResidencyInstallTick.set(Long.MIN_VALUE);
		// Native close deactivated the underlying engine tickets before this clear.
		// Pending structure protection itself is persisted and reconstructs rescue work
		// after restart if the operation was not complete.
		OceanCanvasStructureRelocationRescueService.clearAfterTicketDeactivation(world);
		lightFinalizerSession().scanCursor.set(0L);
		lightFinalizerSession().lastProductiveTileKey.set(Long.MIN_VALUE);
		lightFinalizerSession().persistedAuditSession.clear();
		if (abandonedLight > 0) {
			OceanCanvas.LOGGER.info("(Ocean Canvas) v253.61 server-stop discarded {} transient light-finalizer entr{}; missing persisted lighting certificates will re-arm them after restart.",
					abandonedLight, abandonedLight == 1 ? "y" : "ies");
		}
		pregenSession().startupSweepTicksRemaining = 0;
	}

	/** v218 bounded orphan-ticket cleanup. Cancel/session transitions clear target
	 * ownership immediately, but expensive ticket removals are amortized across
	 * ticks. A target re-admitted before its old ticket drains simply ceases to be
	 * an orphan and reuses the still-valid ownership ticket. */
	private static void releaseOrphanedPregenTickets(ServerLevel world) {
		int selfReleased = 0;
		for (long target : pregenSession().PREGEN_SELF_TICKET_LEDGER.targetSnapshot()) {
			if (selfReleased >= MAX_SELF_TICKET_RELEASES_PER_TICK) break;
			if (pregenSession().PREGEN_TARGET_CHUNKS.contains(target)) continue;
			releaseSelfTicket(world, target);
			selfReleased++;
		}
		int leaseReleased = 0;
		for (long packed : pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.physicalTicketSnapshot()) {
			if (leaseReleased >= MAX_PROCESSING_LEASE_RELEASES_PER_TICK) break;
			if (pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) continue;
			releaseProcessingLease(world, packed);
			leaseReleased++;
		}
		int finalReleased = 0;
		for (long packed : pregenSession().PREGEN_FINAL_DRAIN_LEDGER.activeSnapshot()) {
			if (finalReleased >= MAX_FINAL_DRAIN_BULK_RELEASES_PER_TICK) break;
			if (pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) continue;
			releaseFinalDrainTicket(world, packed);
			finalReleased++;
		}
	}

	private static void onServerTick(net.minecraft.server.MinecraftServer server) {
		ServerLevel world = server.overworld();
		if (world == null) {
			return;
		}
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(server)) {
			net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.logObservedOnce();
			// The client HEAD mixin can give us one or more ticks before SERVER_STOPPING.
			// Use the already-proven bounded release path while that window exists; the
			// final native bulk deactivation remains the shutdown authority.
			releaseSettledForceLoadTickets(world);
			return;
		}

		// v137: release any force-load tickets requestNonBlockingChunkLoad
		// installed whose chunk has since loaded (or has been stuck long
		// enough to hit the safety-net max age) - see that method's doc.
		releaseSettledForceLoadTickets(world);

		// v218: amortize cancel/session cleanup for the other FORCED-ticket pools too.
		releaseOrphanedPregenTickets(world);

		// v163.4: drain final-drain-ticket releases CHUNK_LOAD deferred here
		// instead of doing them reentrantly - see
		// PREGEN_FINAL_DRAIN_PENDING_RELEASE's doc comment.
		if (pregenSession().PREGEN_FINAL_DRAIN_LEDGER.hasDeferredReleases()) {
			for (long packed : pregenSession().PREGEN_FINAL_DRAIN_LEDGER.drainDeferredReleaseBatch(PREGEN_FINAL_DRAIN_RELEASES_PER_TICK)) {
				releaseFinalDrainTicket(world, packed);
			}
		}

		// Persistent startup sweep - see the class doc for why this
		// replaced a single one-shot attempt at server start. Runs for
		// the first STARTUP_SWEEP_TICKS ticks, but only actually
		// re-scans the full radius every few ticks (not literally every
		// tick) - scanning ~1,089 positions 100 times over was measurable
		// overhead on its own even with deduplication in place, and the
		// timing race this exists to win doesn't need tick-by-tick
		// granularity, just enough repeated attempts across the window.
		if (pregenSession().startupSweepTicksRemaining > 0) {
			pregenSession().startupSweepTicksRemaining--;
			if (pregenSession().startupSweepTicksRemaining % STARTUP_SWEEP_SCAN_INTERVAL_TICKS == 0) {
				for (int cx = -STARTUP_SWEEP_RADIUS_CHUNKS; cx <= STARTUP_SWEEP_RADIUS_CHUNKS; cx++) {
					for (int cz = -STARTUP_SWEEP_RADIUS_CHUNKS; cz <= STARTUP_SWEEP_RADIUS_CHUNKS; cz++) {
						ChunkPos pos = new ChunkPos(cx, cz);
						// v182: getChunkNow - same fix as flattenChunk's v182
						// comment; hasChunk()-then-getChunk() is not atomic.
						LevelChunk sweepChunk = world.getChunkSource().getChunkNow(cx, cz);
						if (sweepChunk != null && pregenSession().QUEUED_CHUNK_POSITIONS.add(pos)) {
							pregenSession().PENDING_CHUNKS.add(sweepChunk);
						}
					}
				}
			}
		}

		// v253.125.6: JOIN itself is too early on 26.2: the supplied runtime hit the
		// callback twice with loadedCandidates=0. Service the bounded player-visible
		// audit window first, after tracking has actually materialized.
		drainJoinPersistedLightAuditWindows(world);
		// v253.125.18: the join audit is intentionally bounded, but visual correctness
		// must follow the player for the rest of the session. Inspect only resident,
		// actually-tracked chunks with a tiny rotating budget and promote proven bad
		// chunks into the existing visible finalizer.
		drainContinuousVisibleLightSentinel(world);

		// v253.61.9: first prove/re-publish any persisted light state that has crossed
		// a save/rejoin boundary. If it is genuinely stale this schedules the ordinary
		// non-destructive finalizer below; healthy chunks are simply republished after
		// client tracking has settled.
		drainPersistedLightAudits(world);

		// v253.69.6: structure rescue is deliberately serviced BEFORE lighting. A
		// pending-original structure is itself a lighting barrier; completing it first
		// lets the same tick's finalizer immediately start draining entries that were
		// blocked by that footprint, even when Pregen terrain admission is at zero.
		OceanCanvasStructureRelocationRescueService.tick(world, STRUCTURE_RELOCATION_RESCUE_HOOKS);

		// v253.7 client-light convergence. LightEngine#checkBlock is queued by every
		// raw terrain mutation, but the resulting light section data is not carried by
		// sendBlockUpdated. Drain this AFTER the normal end-of-tick ticket work and
		// BEFORE the flattener's early return so lighting still converges even when no
		// terrain chunks remain queued.
		drainPendingLightSync(world);

		if (pregenSession().PENDING_CHUNKS.isEmpty()) {
			return;
		}

		// Re-check every pending chunk once per tick. Cheap: hasChunk is
		// a non-blocking lookup, not a generation trigger. Only the
		// actual flattenChunk call is budgeted - see
		// OceanCanvasConfig#flattenerChunksPerTick's doc for why this cap
		// exists at all.
		int flattenerChunksPerTick = OceanCanvasConfig.get().flattenerChunksPerTick();
		boolean pregenActive = !pregenSession().PREGEN_TARGET_CHUNKS.isEmpty();
		long flattenerTickStartedNanos = System.nanoTime();
		int remaining = pregenSession().PENDING_CHUNKS.size();
		if (pregenActive) remaining = Math.min(remaining, PREGEN_READINESS_CHECK_BUDGET);
		int flattensThisTick = 0;
		for (int i = 0; i < remaining; i++) {
			if (pregenActive && flattensThisTick > 0
					&& System.nanoTime() - flattenerTickStartedNanos >= PREGEN_FLATTEN_WALL_BUDGET_NS) {
				break;
			}
			LevelChunk chunk = pregenSession().PENDING_CHUNKS.poll();
			if (chunk == null) {
				break;
			}

			if (flattensThisTick >= flattenerChunksPerTick) {
				pregenSession().PENDING_CHUNKS.add(chunk); // ready or not, over budget this tick - try again next tick
				continue;
			}

			long packedChunkPos = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			boolean forcedRegeneration = pregenSession().FORCE_REPROCESS_CHUNKS.contains(packedChunkPos);
			boolean ownedByPregen = pregenSession().PREGEN_TARGET_CHUNKS.contains(packedChunkPos);
			OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);

			// v253.1: CHUNK_LOAD is transport only. Never let ordinary chunk loads,
			// the startup sweep, or the legacy-adoption path create Canvas completion
			// state for terrain that no explicit Ocean Canvas job owns. A fresh-world
			// runtime proved that ambient work could race ahead of Pregen and write
			// processed/verified seals for chunks whose later physical audit still
			// found vanilla stone, dirt, water and above-surface terrain. Pregen would
			// then cheap-skip those coordinates based on metadata alone.
			//
			// Explicit Pregen ownership (pregenSession().PREGEN_TARGET_CHUNKS) and explicit destructive
			// regeneration ownership (pregenSession().FORCE_REPROCESS_CHUNKS) are now the ONLY routes
			// into destructive flattening/sealing. Normal exploration simply drops the
			// queued CHUNK_LOAD notification. This makes the authoritative lifecycle:
			// job owns -> FULL chunk arrives -> flatten -> exhaustive physical audit ->
			// physical seal -> retire ownership.
			if (!ownedByPregen && !forcedRegeneration) {
				// v253.49 late-load self-heal. A chunk physically authored by an
				// older/interrupted Pregen may first become visible long after the
				// original terrain publication. If its persistent lighting certificate
				// is missing, repair lighting only; never re-carve gameplay terrain.
				var terrainState = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(chunk.getPos());
				if (terrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS
						&& protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos())
						&& !protectedData.isChunkLightingVerified(chunk.getPos())) {
					scheduleLightSync(world, chunk.getPos(), false);
					OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-SELF-HEAL build={} chunk={},{} trigger=ordinary-chunk-load action=non-destructive-finalizer",
							net.oceancanvas.mod.OceanCanvas.VERSION, chunk.getPos().x(), chunk.getPos().z());
				}
				pregenSession().QUEUED_CHUNK_POSITIONS.remove(chunk.getPos());
				continue;
			}

			// Migration safety for worlds created by older Ocean Canvas builds.
			// Those builds counted completed chunks but did not persist their exact
			// coordinates. If a loaded chunk already has the unmistakable canvas
			// floor/water profile, adopt it as processed BEFORE any destructive pass.
			// This prevents the first load after upgrading from deleting boats,
			// livestock, builds, or other gameplay entities that were added after
			// the original pregen. A deliberately requested reset bypasses this.
			//
			// v196: gated on !ownedByPregen (checked via pregenSession().PREGEN_TARGET_CHUNKS
			// directly, since the ownedByPregen local below is not yet in scope
			// here). This heuristic only samples a sparse 4x4 grid of columns
			// and needs just 2/16 to look canvas-like, versus the strict v71
			// audit below which scans far more columns and fails the whole
			// chunk on a single bad one. Without this guard, a live Pregen
			// target with even one isolated bad column (e.g. a stray water
			// block in the transition seal, or a surviving glow_lichen) would
			// get re-adopted as verified by this loose check, immediately
			// rejected again by the strict audit a few lines down, cleared,
			// and re-adopted again on the very next tick - forever, burning a
			// full tick's worth of CPU every tick with the underlying bad
			// column never actually getting repaired. This heuristic's own
			// doc is explicit that it exists for one-time legacy-world
			// migration, not for re-litigating a chunk Pregen is still
			// actively responsible for and will keep re-auditing regardless.
			// v253.1: automatic legacy adoption is intentionally disabled in the live
			// CHUNK_LOAD path. Migration evidence must never be allowed to manufacture
			// authoritative completion for a fresh chunk. Old processed history is still
			// migrated safely when an explicit Pregen owns and physically audits it.

			if (!forcedRegeneration && protectedData.isChunkProcessed(chunk.getPos())) {

				// v71: v67-v70's "verified" seal only proved that flattenChunk ran.
				// The v70 playtest reached 15876/15876 while visible vanilla fluid
				// columns still survived, so Pregen must physically audit every old
				// verified seal once before trusting it. A passing audit is persisted
				// separately; a failing audit clears the stale seal and routes the
				// chunk through the normal destructive flattener again.
				if (ownedByPregen && protectedData.isChunkProcessedVerified(chunk.getPos())
						&& !protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos())) {
					PhysicalProfileMismatch mismatch = firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get());
					if (mismatch == null) {
						protectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());
					} else {
						OceanCanvas.LOGGER.warn("(Ocean Canvas) v230.5 physical audit rejected legacy verified seal at {},{}: {}. Pregen will repair this chunk now.",
								chunk.getPos().x(), chunk.getPos().z(), mismatch.describe());
						protectedData.clearChunkProcessed(chunk.getPos());
					}
				}

				if (ownedByPregen && !protectedData.isChunkProcessedVerified(chunk.getPos())) {
					var intent = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(chunk.getPos());
					if (intent == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.VANILLA) {
						// A Restore-to-Vanilla seal is not a Canvas-complete seal. A later
						// explicit Pregen is allowed to prepare that terrain again.
						protectedData.clearChunkProcessed(chunk.getPos());
					} else {
						LegacySealCheck check = checkLegacyProcessedSeal(world, chunk, OceanCanvasConfig.get());
						if (check == LegacySealCheck.CANVAS_CONFIRMED) {
							protectedData.markChunkProcessedVerified(chunk.getPos());
						} else if (check == LegacySealCheck.OBVIOUS_STALE_TERRAIN
								&& intent != net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED) {
							OceanCanvas.LOGGER.warn("(Ocean Canvas) Recovering stale pre-v67 processed seal at {},{}: physical terrain is clearly still vanilla-like; Pregen will carve it now.",
									chunk.getPos().x(), chunk.getPos().z());
							protectedData.clearChunkProcessed(chunk.getPos());
						} else if (intent == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED) {
							// Positive project-state evidence of player/custom terrain is the
							// one case where a migration Pregen must not guess destructively.
							OceanCanvas.LOGGER.warn("(Ocean Canvas) Preserving unverified processed seal at {},{} because terrain state is explicitly CUSTOM_OR_MODIFIED.",
									chunk.getPos().x(), chunk.getPos().z());
						} else {
							// v69 stale-seal rule: ambiguity is NOT positive evidence of player
							// content. These seals were written by builds now known to have
							// falsely marked vanilla chunks complete. Clear the seal and run the
							// normal flattener; explicit protected zones/structure bounds are
							// still preserved by flattenChunk itself. This removes the long
							// chunk-aligned cliff bands seen in v67/v68.
							OceanCanvas.LOGGER.warn("(Ocean Canvas) Recovering ambiguous pre-v67 processed seal at {},{}: no explicit CUSTOM_OR_MODIFIED state exists, so Pregen will carve unprotected terrain now.",
									chunk.getPos().x(), chunk.getPos().z());
							protectedData.clearChunkProcessed(chunk.getPos());
						}
					}
				}

				if (protectedData.isChunkProcessed(chunk.getPos())) {
					// v73: an old/legacy seal is never itself a completion path for
					// Pregen. Normal gameplay may still trust the persisted seal, but a
					// live target must pass the same authoritative retirement audit as
					// final drain.
					if (ownedByPregen) {
						if (tryCompletePregenTarget(world, chunk, "normal-queue-existing-seal")) {
							continue;
						}
						// Gate rejected it and cleared stale completion metadata; fall
						// through to the normal flatten/repair path below.
					} else {
						pregenSession().QUEUED_CHUNK_POSITIONS.remove(chunk.getPos());
						continue;
					}
				}
			}

			// structureReferencesReady is checked alongside
			// neighborsReady, not folded into it - see that method's
			// doc for exactly what real gap it closes and why it
			// deliberately never force-loads (unlike neighborsReady's
			// capped one-hop force-load).
			if (neighborsReady(world, chunk.getPos()) && structureReferencesReady(world, chunk)) {
				if (!flattenChunk(world, chunk)) {
					// v163.6: aborted mid-flatten because a neighbor/owner
					// chunk was unloaded again after the readiness checks
					// above confirmed it - same "not ready yet" handling as
					// the else branch below, not a completed flatten.
					pregenSession().PENDING_CHUNKS.add(chunk);
					continue;
				}
				flattensThisTick++;

				if (pregenSession().PREGEN_TARGET_CHUNKS.contains(packedChunkPos)) {
					if (!tryCompletePregenTarget(world, chunk, "normal-queue-post-flatten")) {
						pregenSession().PENDING_CHUNKS.add(chunk);
					}
					continue;
				}

				// Non-Pregen work retains the ordinary cleanup semantics.
				pregenSession().FORCE_REPROCESS_CHUNKS.remove(packedChunkPos);
				pregenSession().PREGEN_QUEUED_CHUNKS.remove(packedChunkPos);
				pregenSession().PREGEN_TARGET_FIRST_REQUEST_MS.remove(packedChunkPos);
				pregenSession().QUEUED_CHUNK_POSITIONS.remove(chunk.getPos());
			} else {
				pregenSession().PENDING_CHUNKS.add(chunk); // not ready yet - try again next tick
			}
		}
	}

	// Chunks force-loaded purely to satisfy someone else's neighbor
	// check - see neighborsReady's doc for the full reasoning. Tracked
	// so (a) the same chunk is never force-load-requested twice, and
	// (b) a force-loaded chunk is never itself allowed to trigger
	// further force-loading of ITS OWN missing neighbors, which is
	// what actually causes unbounded expansion.

	/**
	 * Remote pregeneration must be able to re-request a support neighbor if
	 * Minecraft/C2ME unloads it before the target chunk gets its turn.
	 * Normal exploration keeps the old one-hop "request once" behavior to
	 * prevent cascading world generation. Pregen-owned targets get a bounded
	 * retry (at most once per support chunk per second).
	 */
	private static final long PREGEN_SUPPORT_RETRY_MS = 1000L;

	/**
	 * <p><b>Real bug this exists to fix, found via a close-up screenshot
	 * of what turned out to be a genuinely un-flattened stone pillar,
	 * not a material/structure issue at all:</b> a chunk sitting at the
	 * edge of the player's render distance can end up with a neighbor
	 * that Minecraft never decides to load on its own - since loading
	 * is driven by what the player needs to see, not by our own
	 * internal requirements. That chunk's {@code isPositionTicking}
	 * check then never passes, so it sits in {@link #pregenSession().PENDING_CHUNKS}
	 * forever, permanently un-flattened - almost certainly the actual
	 * explanation for the repeated "gravel blob" / floating-debris
	 * reports across multiple earlier rounds, none of which were ever
	 * actually about structure protection or block-type material at
	 * all (which is why those fixes never resolved it).</p>
	 *
	 * <p>An earlier attempt at this exact fix (force-loading a missing
	 * neighbor via {@code getChunkFuture}) was tried and reverted
	 * because it caused a server-overload cascade: every force-loaded
	 * neighbor's own {@code CHUNK_LOAD} event queued IT for processing
	 * too, and ITS missing neighbors got force-loaded as well,
	 * expanding outward without any bound - essentially generating far
	 * more of the world than any player was ever near.</p>
	 *
	 * <p><b>This version is deliberately capped to exactly one hop:</b>
	 * force-loading a missing neighbor is only ever allowed when the
	 * chunk asking for it is itself a normal, organically-loaded chunk
	 * (tracked via {@link #pregenSession().FORCE_LOADED_CHUNK_KEYS} - if the chunk
	 * being checked is itself in that set, it was force-loaded by an
	 * earlier check, and is never allowed to trigger further
	 * force-loading of its own neighbors, no matter how many are
	 * missing. That chunk simply waits, exactly like before - it'll
	 * resolve naturally if the player later explores closer, or remain
	 * one of a much smaller number of genuine edge cases otherwise,
	 * rather than cascading.</p>
	 *
	 * <p><b>This alone still wasn't enough - the same class of "stuck"
	 * artifact (sand this time, plus a separate floating ice sheet)
	 * recurred on the very next test.</b> The real, larger explanation,
	 * found afterward: see {@code OceanCanvas#onInitialize}'s
	 * simulation-distance fix. Vanilla keeps a ring of chunks loaded and
	 * VISIBLE but not "ticking" (the gap between view distance and
	 * simulation distance), and no amount of force-loading chunk DATA
	 * changes that - simulation distance is a separate, harder limit.
	 * This one-hop fix and that one are complementary, not competing:
	 * this handles genuine data-loading edge cases, the simulation-
	 * distance fix closes the much larger gap that was actually
	 * responsible for most of what was being reported.</p>
	 */
	/**
	 * Discovers, interim-protects, and (if ready) relocates ONE shipwreck
	 * {@code StructureStart}. Shared by both discovery passes in {@link
	 * #flattenChunk} - the original 3x3-neighborhood {@code getAllStarts()}
	 * scan, and the newer reference-based pass that closes the real gap
	 * the 3x3 scan alone couldn't (see {@link #flattenChunk}'s discovery
	 * block for the full story of why a second pass exists at all).
	 * Mutates {@code naturalStructureBounds} directly rather than
	 * returning something to merge, matching how the caller already
	 * treats that list elsewhere in this method. {@code
	 * newlyRelocatedBounds} is separate: only ever added to when THIS
	 * call is the one that actually performed a relocation (not on a
	 * "already relocated earlier" no-op) - see {@link #flattenChunk}'s
	 * end-of-method revalidation pass for why that distinction matters.
	 */
	/**
	 * Corner-to-corner extent of every piece in a structure, or {@code
	 * null} when it has none.
	 *
	 * <p>Extracted because all four structure-kind handlers below need the
	 * same thing for the same reason: deciding whether a region's rule
	 * applies means first knowing WHERE the structure is, so the box has to
	 * be computed before the enabled/disabled question can even be asked.
	 * Each handler used to inline its own copy of this loop.</p>
	 */
	private static net.minecraft.world.level.levelgen.structure.BoundingBox pieceExtent(
			net.minecraft.world.level.levelgen.structure.StructureStart start) {
		return OceanCanvasStructureGeometry.pieceExtent(start);
	}

	/**
	 * Whether a structure kind is preserved at a position, folding the
	 * region rule together with the world default - the single question
	 * every handler below asks. See {@link OceanCanvasStructureKind} for
	 * what "preserved" means per kind.
	 */
	private static boolean structureKindEnabled(ServerLevel world, OceanCanvasStructureKind kind,
			net.minecraft.world.level.levelgen.structure.BoundingBox extent) {
		return OceanCanvasPlayerZones.get(world).isStructureKindEnabledAt(
				kind, extent.minX(), extent.minY(), extent.minZ(), OceanCanvasConfig.get());
	}

	private static void discoverAndProtectShipwreck(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> naturalStructureBounds,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> newlyRelocatedBounds) {
		if (start == null || !start.isValid()) {
			return;
		}
		// Direct Java type check instead of a registry-key lookup
		// (RegistryAccess#registryOrThrow doesn't exist under that name
		// in 26.2) - simpler and avoids guessing at another registry API
		// entirely.
		if (!(start.getStructure()
				instanceof net.minecraft.world.level.levelgen.structure.structures.ShipwreckStructure)) {
			return;
		}

		// Real fix, found from an actual "as the chunks were cleared,
		// parts of the boat, including the chests, were deleted" report:
		// this used to protect nothing at all until
		// relocateShipwreckIfNeeded's own precondition
		// (allChunksReadyForRelocation) was satisfied - which can
		// genuinely take multiple chunk-load cycles for a structure
		// spanning more than one chunk. See OceanCanvasProtectedData's
		// "Rev 5" class doc note for the full story. Registered here,
		// immediately, every time this structure is (re)discovered -
		// idempotent and cheap, and this is exactly the same
		// origin/bounds computation relocateShipwreckIfNeeded does
		// internally, just computed slightly earlier so protection
		// exists before relocation even attempts anything.
		//
		// The global-flag early return that used to sit at the very top
		// of this method (before even checking start.isValid()/the
		// structure type) moved to AFTER this bounding-box computation -
		// real change needed once per-zone overrides were added: deciding
		// "is this shipwreck protected" now needs to know WHERE it is
		// (to look up a possible zone override), and the bounding box is
		// the cheapest thing already being computed here that gives us
		// that. Real cost: getPieces() is now always called even when
		// shipwrecks are globally off, in case a zone forces them back on
		// - a small, one-time-per-discovery cost, not a per-tick one.
		net.minecraft.world.level.levelgen.structure.BoundingBox extent = pieceExtent(start);
		if (extent == null) {
			// Nothing to protect and no position to check a region rule
			// against - fall back to the plain world default, matching the
			// old behaviour for this (very unlikely in practice) edge case.
			if (OceanCanvasConfig.get().shipwrecksRule() == net.oceancanvas.mod.config.StructureOverride.FORCE_OFF) {
				return;
			}
		} else {
			// v253.69 boundary hardening: decide the rule BEFORE the full-footprint
			// ownership gate. If a structure is enabled, a partial block-exact
			// operation must preserve the whole structure. If it is disabled, preserve
			// nothing inside the owned columns; only the structure-wide metadata removal
			// is deferred until an operation owns the full footprint. This avoids the
			// v253.68 boundary pattern where prismarine/structure blocks were preserved
			// by the primary carve and then immediately removed by the physical verifier.
			boolean enabled = structureKindEnabled(world, OceanCanvasStructureKind.SHIPWRECK, extent);
			if (!OceanCanvasActiveTerrainOperationBridge.coversStructureFootprint(extent)) {
				if (enabled) naturalStructureBounds.add(extent);
				return;
			}
			// A region's shipwreck rule (see OceanCanvasStructureKind) can
			// force preservation on or off for this specific shipwreck
			// regardless of the world default - checked at the structure's
			// min corner, the same representative position
			// protectPendingOriginal below already keys protection on.
			if (!enabled) {
				OceanCanvasStructureMetadataCleaner.queue(world, start, OceanCanvasStructureKind.SHIPWRECK);
				return;
			}
			OceanCanvasProtectedData structureData = OceanCanvasProtectedData.get(world);
			BlockPos extentOrigin = new BlockPos(extent.minX(), extent.minY(), extent.minZ());
			int extentCenterX = extent.minX() + (extent.maxX() - extent.minX() + 1) / 2;
			int extentCenterZ = extent.minZ() + (extent.maxZ() - extent.minZ() + 1) / 2;
			int deterministicTargetY = OceanCanvasConfig.get().oceanFloorY()
					+ floorOffset(extentCenterX, extentCenterZ, OceanCanvasConfig.get().oceanFloorVariation());
			if (extent.minY() == deterministicTargetY) {
				// v253.69.6: once StructureStart metadata has followed a relocated wreck
				// to floor Y, it is already integrated. Do not manufacture a brand-new
				// pending-original entry using the relocated min-corner on every later
				// discovery. Clean a legacy stale entry if 69.5 already wrote one.
				if (!structureData.isPermanentlyProtected(extent.minX(), extent.minY(), extent.minZ()))
					ProtectedRegions.protect(world, extent);
				structureData.clearPendingOriginalProtection(extentOrigin);
			} else {
				structureData.protectPendingOriginal(extentOrigin, extent);
			}
		}

		// Relocate it down to the real floor if this hasn't been done
		// yet - see relocateShipwreckIfNeeded's doc for why a
		// naturally-generated shipwreck needs this at all
		// (ModStarterStructures only fixes the height for the ONE
		// shipwreck it places itself).
		net.minecraft.world.level.levelgen.structure.BoundingBox relocatedBounds =
				relocateShipwreckIfNeeded(world, start);
		if (relocatedBounds != null) {
			naturalStructureBounds.add(relocatedBounds);
			newlyRelocatedBounds.add(relocatedBounds);
			return;
		}
		// null here just means "already relocated on an earlier pass"
		// (or no pieces at all) - the pieces below reflect the
		// structure's ORIGINAL, now-empty position (cleared during
		// relocation), so adding their boxes is harmless. The relocated
		// copy's actual protection now comes from ProtectedRegions
		// (registered permanently inside relocateShipwreckIfNeeded), not
		// from this list.
		for (net.minecraft.world.level.levelgen.structure.StructurePiece piece : start.getPieces()) {
			naturalStructureBounds.add(piece.getBoundingBox());
		}
	}

	/**
	 * Ocean-ruin counterpart to {@link #discoverAndProtectShipwreck} -
	 * drafted this round as part of the per-zone structure-override UI
	 * feature (see {@code net.oceancanvas.mod.gui.OceanCanvasMapScreen}).
	 * This helper is retained only for read-only Physical Health discovery. The
	 * live generation path uses the relocation handlers below; this method must
	 * never perform cleanup or relocation itself.
	 *
	 * Historical rationale (kept for protection semantics):
	 *
	 * <ol>
	 *   <li><b>No relocation.</b> {@link #relocateShipwreckIfNeeded} exists
	 *       because the canvas floor sits deeper than vanilla's normal
	 *       ocean floor, so a naturally-generated shipwreck needs to be
	 *       physically moved down to rest on it. Ocean ruins have no
	 *       equivalent handling here - they're left at whatever height
	 *       they naturally generated at. If the configured floor is
	 *       deeper than that height, a protected ocean ruin can end up
	 *       appearing to "float" above the real floor - a known,
	 *       honestly-documented limitation, not a bug being silently
	 *       hidden. Fixing that would need the same kind of capture/paste
	 *       relocation dance the shipwreck path already has, which is its
	 *       own separate undertaking with its own testing needs, not
	 *       something to bolt on here unverified.</li>
	 *   <li><b>Unconditional protection, not material-filtered.</b>
	 *       {@link #isShipMaterial} exists because a shipwreck's bounding
	 *       box can overlap surrounding natural terrain the ship doesn't
	 *       fully fill (see that method's doc). An equivalent allowlist
	 *       for ocean ruins would need new, unconfirmed research (their
	 *       block palette varies meaningfully between the warm/cold
	 *       variants) this project doesn't need to take on: vanilla ocean
	 *       ruins are compact, single-piece structures whose bounding box
	 *       is already tight to the generated structure - unlike a
	 *       sprawling mineshaft (the actual case that made material
	 *       filtering necessary in the first place - see the class doc's
	 *       "serious design flaw" note), so protecting the whole box
	 *       unconditionally is safe here. This is the same unconditional-
	 *       protection precedent {@link OceanCanvasPlayerZones} already
	 *       established for player-defined zones, for the same underlying
	 *       reason - see {@link #flattenChunk}'s carve loop for where this
	 *       is actually enforced (a dedicated, unconditional check, kept
	 *       deliberately separate from the ship-material-filtered one).</li>
	 * </ol>
	 *
	 * <p>Gated by the kind's own world default (see {@link
	 * OceanCanvasStructureKind}), which a region's rule overrides, exactly
	 * as for shipwrecks in {@link #discoverAndProtectShipwreck} above.</p>
	 *
	 * <p><b>Parameterised by kind rather than written once per structure
	 * type.</b> Ocean ruins and ocean monuments want identical treatment -
	 * preserve the whole box, do not relocate - and writing that twice
	 * would mean two places to keep in step every time the rule model
	 * changes. Uses {@code Class#isInstance} instead of {@code instanceof}
	 * purely so the structure type can be a parameter; the check itself is
	 * the same direct Java type test this file has used since the registry-
	 * key approach was abandoned, not a return to registry lookups.</p>
	 */
	private static void discoverAndPreserveWholeBox(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			OceanCanvasStructureKind kind, Class<?> structureType,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedBounds) {
		if (start == null || !start.isValid()) {
			return;
		}
		if (!structureType.isInstance(start.getStructure())) {
			return;
		}
		net.minecraft.world.level.levelgen.structure.BoundingBox extent = pieceExtent(start);
		if (extent == null) {
			return;
		}
		// Read-only audit helper: a disabled structure is simply not an audit
		// exemption. Never queue cleanup, relocate pieces, or change persistence
		// from this path; Health must not mutate the world it is measuring.
		if (!structureKindEnabled(world, kind, extent)) return;
		preservedBounds.add(extent);
	}

	/**
	 * v237 compact-structure relocation for natural ocean ruins and ocean ruined
	 * portals. These structures were previously preserved at their vanilla Y while
	 * their forced Always counterparts were aligned to the Canvas floor. That made
	 * Default/Always physically inconsistent and could leave a real vanilla start
	 * floating above the flattened seabed.
	 *
	 * <p>The existing vanilla StructureStart remains authoritative. Once every
	 * footprint chunk is resident, its real pieces are shifted vertically and
	 * re-placed chunk-by-chunk through StructureStart#placeInChunk. The old piece
	 * boxes are then returned to canonical Canvas terrain, excluding any overlap
	 * with the new footprint or explicitly protected player/structure content. If
	 * placement throws, the piece graph is moved back before carving sees it.</p>
	 */
	private static void discoverAndRelocateCompactWholeBox(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			OceanCanvasStructureKind kind, Class<?> structureType,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> newlyRelocatedBounds,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedBounds) {
		if(start==null||!start.isValid()||!structureType.isInstance(start.getStructure()))return;
		var extent=pieceExtent(start);if(extent==null)return;
		boolean enabled=structureKindEnabled(world,kind,extent);
		if(!OceanCanvasActiveTerrainOperationBridge.coversStructureFootprint(extent)){
			if(enabled) preservedBounds.add(extent);
			OceanCanvas.LOGGER.debug("Deferred {} structure-wide metadata mutation at {} because the active operation does not own its full footprint; enabled={} so selected blocks are {}",kind.displayName(),start.getChunkPos(),enabled,enabled?"preserved":"carved block-exactly");
			return;
		}
		if(!enabled){OceanCanvasStructureMetadataCleaner.queue(world,start,kind);return;}
		var cfg=OceanCanvasConfig.get();int centerX=(extent.minX()+extent.maxX())/2,centerZ=(extent.minZ()+extent.maxZ())/2;
		int targetY=cfg.oceanFloorY()+floorOffset(centerX,centerZ,cfg.oceanFloorVariation());
		if(extent.minY()==targetY){preservedBounds.add(extent);return;}
		if(!allChunksReadyForRelocation(world,extent.minX(),extent.minZ(),extent.maxX(),extent.maxZ())){preservedBounds.add(extent);return;}

		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> oldPieceBoxes=new java.util.ArrayList<>();
		for(var piece:start.getPieces()){var b=piece.getBoundingBox();oldPieceBoxes.add(new net.minecraft.world.level.levelgen.structure.BoundingBox(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()));}
		int dy=targetY-extent.minY();for(var piece:start.getPieces())piece.move(0,dy,0);
		var moved=pieceExtent(start);if(moved==null){for(var piece:start.getPieces())piece.move(0,-dy,0);preservedBounds.add(extent);return;}
		try{
			var generator=world.getChunkSource().getGenerator();long ownerRef=net.minecraft.world.level.ChunkPos.pack(start.getChunkPos().x(),start.getChunkPos().z());
			for(int cz=Math.floorDiv(moved.minZ(),16);cz<=Math.floorDiv(moved.maxZ(),16);cz++)for(int cx=Math.floorDiv(moved.minX(),16);cx<=Math.floorDiv(moved.maxX(),16);cx++){
				var chunk=world.getChunkSource().getChunkNow(cx,cz);if(chunk==null)throw new IllegalStateException("compact structure footprint unloaded after readiness gate");
				var clip=new net.minecraft.world.level.levelgen.structure.BoundingBox(cx*16,world.getMinY(),cz*16,cx*16+15,world.getMaxY(),cz*16+15);
				start.placeInChunk(world,world.structureManager(),generator,net.minecraft.util.RandomSource.create(world.getSeed()^ownerRef^net.minecraft.world.level.ChunkPos.pack(cx,cz)),clip,new net.minecraft.world.level.ChunkPos(cx,cz));
				chunk.markUnsaved();
			}
			markStructureMetadataUnsaved(world,start,moved);
			OceanCanvasProtectedData.get(world).markRelocatedStructure(kind,new BlockPos(extent.minX(),extent.minY(),extent.minZ()),moved);
			clearRelocatedCompactOriginal(world,oldPieceBoxes,moved);
			preservedBounds.add(moved);newlyRelocatedBounds.add(moved);
			OceanCanvas.LOGGER.info("Relocated natural {} from minY {} to Canvas minY {} and moved its vanilla StructureStart metadata",kind.displayName(),extent.minY(),moved.minY());
		}catch(RuntimeException ex){
			for(var piece:start.getPieces())piece.move(0,-dy,0);
			preservedBounds.add(extent);
			OceanCanvas.LOGGER.error("Failed to relocate natural {} at owner {}; restored original StructureStart piece positions",kind.displayName(),start.getChunkPos(),ex);
		}
	}

	private static void clearRelocatedCompactOriginal(ServerLevel world,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> oldPieceBoxes,
			net.minecraft.world.level.levelgen.structure.BoundingBox moved) {
		var cfg=OceanCanvasConfig.get();var zones=OceanCanvasPlayerZones.get(world);var protectedData=OceanCanvasProtectedData.get(world);
		for(var box:oldPieceBoxes)for(BlockPos pos:BlockPos.betweenClosed(new BlockPos(box.minX(),box.minY(),box.minZ()),new BlockPos(box.maxX(),box.maxY(),box.maxZ()))){
			if(moved.isInside(pos)||zones.isProtected(pos.getX(),pos.getY(),pos.getZ())||protectedData.isProtected(pos.getX(),pos.getY(),pos.getZ()))continue;
			int floor=cfg.oceanFloorY()+floorOffset(pos.getX(),pos.getZ(),cfg.oceanFloorVariation());
			world.removeBlockEntity(pos);
			world.setBlock(pos,pos.getY()<floor?Blocks.STONE.defaultBlockState():pos.getY()<=OceanCanvasConfig.WATER_SURFACE_Y?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
		}
	}

	/**
	 * Buried-treasure counterpart to {@link #discoverAndProtectShipwreck} -
	 * see {@link #relocateBuriedTreasureIfNeeded}'s doc for the feature
	 * itself. Deliberately does NOT register interim
	 * {@code protectPendingOriginal} protection the way the shipwreck
	 * path does: that fix exists because a multi-block ship can take
	 * several chunk-load cycles to become fully relocatable, leaving a
	 * real window where part of it could be carved through in the
	 * meantime. A single-block structure has no such window - either
	 * {@code allChunksReadyForRelocation} passes and it relocates
	 * immediately, or it doesn't and nothing has touched it yet either
	 * way.
	 */
	/**
	 * Whether this mod has any rule at all about a structure type - the
	 * one place that list lives, so the readiness gate and both discovery
	 * passes can never drift apart about which types matter.
	 *
	 * <p>Everything not named here is carved away wherever it intersects
	 * the excavated range and left completely untouched below the seal,
	 * exactly as before - see the class doc's mineshaft note for why that
	 * is the correct default rather than an omission.</p>
	 */
	private static boolean isManagedStructure(net.minecraft.world.level.levelgen.structure.Structure structure) {
		return OceanCanvasManagedStructureReferences.isManaged(structure);
	}

	/**
	 * Runs every structure-kind handler against one {@code StructureStart},
	 * letting each decide for itself whether it applies.
	 *
	 * <p>Both discovery passes in {@link #flattenChunk} call exactly this,
	 * rather than each repeating a chain of type tests and handler calls -
	 * which is what let the second pass quietly fall out of step with the
	 * first when a kind was added. Adding a kind is now editing this one
	 * method.</p>
	 */
	private static void dispatchStructure(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> naturalStructureBounds,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> newlyRelocatedBounds,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds) {
		discoverAndProtectShipwreck(world, start, naturalStructureBounds, newlyRelocatedBounds);
		discoverAndRelocateBuriedTreasure(world, start, newlyRelocatedBounds, preservedWholeBounds);
		discoverAndRelocateCompactWholeBox(world, start, OceanCanvasStructureKind.OCEAN_RUIN,
				net.minecraft.world.level.levelgen.structure.structures.OceanRuinStructure.class,
				newlyRelocatedBounds,preservedWholeBounds);
		discoverAndRelocateOceanMonument(world, start, newlyRelocatedBounds, preservedWholeBounds);
		discoverAndRelocateCompactWholeBox(world, start, OceanCanvasStructureKind.RUINED_PORTAL,
				net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure.class,
				newlyRelocatedBounds,preservedWholeBounds);
	}

	/**
	 * Keeps monuments by moving them vertically onto the generated canvas
	 * floor. Vanilla's StructureStart remains at the original Y, so the
	 * relocated bounds are persisted and reused on later explicit repairs.
	 */
	private static void discoverAndRelocateOceanMonument(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> newlyRelocatedBounds,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds) {
		if (start == null || !start.isValid()
				|| !(start.getStructure() instanceof net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure)) {
			return;
		}

		net.minecraft.world.level.levelgen.structure.BoundingBox extent = pieceExtent(start);
		if (extent == null) {
			return;
		}
		BlockPos origin = new BlockPos(extent.minX(), extent.minY(), extent.minZ());
		OceanCanvasProtectedData data = OceanCanvasProtectedData.get(world);
		boolean enabled = structureKindEnabled(world, OceanCanvasStructureKind.OCEAN_MONUMENT, extent);
		if (!OceanCanvasActiveTerrainOperationBridge.coversStructureFootprint(extent)) {
			if (enabled) preservedWholeBounds.add(extent);
			return;
		}
		if (!enabled) {
			OceanCanvasStructureMetadataCleaner.queue(world, start, OceanCanvasStructureKind.OCEAN_MONUMENT);
			// If an operator deliberately disables monuments and performs a
			// reset, the relocated copy is allowed to be carved away. Forget
			// its stale protection metadata as part of that decision.
			data.clearRelocatedStructure(OceanCanvasStructureKind.OCEAN_MONUMENT, origin);
			return;
		}

		net.minecraft.world.level.levelgen.structure.BoundingBox alreadyRelocated =
				data.relocatedStructureBounds(OceanCanvasStructureKind.OCEAN_MONUMENT, origin);
		if (alreadyRelocated != null) {
			preservedWholeBounds.add(alreadyRelocated);
			return;
		}

		net.minecraft.world.level.levelgen.structure.BoundingBox relocated =
				relocateMonumentToFloor(world, start, extent, origin);
		if (relocated != null) {
			// Whole monuments do not need the shipwreck-specific stair/fence
			// environment revalidation pass; keeping them only in the whole-box
			// protection list avoids an unnecessary full-monument scan.
			preservedWholeBounds.add(relocated);
		}
	}

	private static void discoverAndRelocateBuriedTreasure(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> newlyRelocatedBounds,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds) {
		if (start == null || !start.isValid()) {
			return;
		}
		if (!(start.getStructure()
				instanceof net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure)) {
			return;
		}
		// Buried treasure gained a region rule this round like every other
		// kind (it previously relocated unconditionally). Same position-
		// first ordering as the shipwreck path: the extent has to be known
		// before the rule covering it can be looked up.
		net.minecraft.world.level.levelgen.structure.BoundingBox extent = pieceExtent(start);
		boolean enabled = extent == null || structureKindEnabled(world, OceanCanvasStructureKind.BURIED_TREASURE, extent);
		if (extent != null && !OceanCanvasActiveTerrainOperationBridge.coversStructureFootprint(extent)) {
			if (enabled) preservedWholeBounds.add(extent);
			return;
		}
		if (extent != null && !enabled) {
			OceanCanvasStructureMetadataCleaner.queue(world, start, OceanCanvasStructureKind.BURIED_TREASURE);
			return;
		}
		net.minecraft.world.level.levelgen.structure.BoundingBox relocatedBounds =
				relocateBuriedTreasureIfNeeded(world, start);
		if (relocatedBounds != null) {
			newlyRelocatedBounds.add(relocatedBounds);
		}
	}

	/**
	 * Fixes up two environment-dependent block properties that {@code
	 * placeInWorld} writes verbatim from the captured template - see the
	 * call site's comment for the full story of what's wrong and why.
	 * Called once, right after a structure is confirmed placed, over its
	 * whole bounding box.
	 *
	 * <p><b>Waterlogging:</b> straightforward - for every position with a
	 * {@code WATERLOGGED} property, set it to whether real water is
	 * actually touching that position right now (checked against all 6
	 * neighbors, not just "is this y-level generally underwater" - a
	 * position enclosed by the ship's own hull on every side is a real
	 * air pocket, not a place that should fill with water just because
	 * it's deep). This only ever ADDS missing water where it's genuinely
	 * supposed to be; it can't flood a real enclosed air pocket, since an
	 * air pocket by definition has no water-touching neighbor to trigger
	 * on.</p>
	 *
	 * <p><b>Fence/wall connection bars:</b> harder to fix perfectly
	 * without knowing whether a specific dangling bar was baked into the
	 * ORIGINAL vanilla template on purpose (a deliberately "broken
	 * rigging" wreck aesthetic - plausible, not confirmed) or is
	 * genuinely stale from the old site's now-irrelevant surroundings.
	 * Re-setting each block to its OWN current state with {@code
	 * Block.UPDATE_ALL} is the standard, side-effect-free way to force
	 * vanilla to recompute shape-derived properties against the REAL,
	 * final neighbors, now that the whole structure is placed and every
	 * genuine in-structure neighbor actually exists (avoids the
	 * placement-order issue a mid-placement update could hit, where a
	 * fence's own neighbor piece hasn't been written yet). Deliberately
	 * skips re-setting chest/barrel positions - {@code state.is(sameBlock)}
	 * should make this a no-op for containers anyway (vanilla's own
	 * "spill contents" check is gated on the BLOCK actually changing, not
	 * just its properties), but given how much trouble the entity-spill
	 * bug already caused, this stays deliberately conservative and never
	 * touches a container block at all.</p>
	 */
	// v253.89: post-placement waterlogging/neighbor-shape repair and persisted
	// revalidation draining live in OceanCanvasStructureEnvironmentService. Keeping
	// those writes outside this class reduces mutation responsibilities without moving
	// structure placement or lighting invalidation out of the proven flattener.

	/**
	 * <p><b>Real gap this closes, found from an actual "part of the boat
	 * was missing, chest destroyed" report on a chunk that was NOT
	 * adjacent to the structure's owning chunk:</b> {@code
	 * ChunkAccess#getAllStarts()} - what both {@link #flattenChunk}'s 3x3
	 * scan and {@link #discoverAndProtectShipwreck} rely on - only ever
	 * returns real {@code StructureStart} data on the ONE chunk that
	 * actually owns/generated a given structure. Every OTHER chunk the
	 * structure's footprint touches only ever records a REFERENCE (a
	 * pointer back to the owning chunk's position) via {@code
	 * ChunkAccess#getAllReferences()} - never the piece data itself. The
	 * 3x3-neighborhood scan only finds a structure if its owning chunk
	 * happens to fall within that window - true for most shipwrecks, but
	 * a real vanilla shipwreck variant/rotation can be long/wide enough
	 * to reach further than 3 chunks from its own owning chunk, and nothing
	 * before this fix ever discovered - let alone protected - the part of
	 * such a structure sitting in a chunk outside that window.</p>
	 *
	 * <p>Deliberately does NOT force-load a referenced owner chunk that
	 * isn't ready yet, unlike {@link #neighborsReady}'s capped one-hop
	 * force-load: an owning chunk could be arbitrarily far away here, and
	 * force-loading an unbounded distance risks exactly the force-load
	 * cascade {@code neighborsReady}'s own doc describes and already had
	 * to be reverted once. Simply reports "not ready" instead, which
	 * {@link #onServerTick}'s drain loop already knows how to handle
	 * (re-queue, try again next tick) - a chunk waiting indefinitely for
	 * a shipwreck owner the player may never actually visit is a
	 * completely safe failure mode; carving through part of an
	 * undiscovered shipwreck is not.</p>
	 */
	private static boolean structureReferencesReady(ServerLevel world, LevelChunk chunk) {
		return OceanCanvasManagedStructureReferences.ownersReady(world, chunk);
	}


	private static boolean neighborsReady(ServerLevel world, ChunkPos pos) {
		long targetKey = pos.pack();
		boolean pregenOwned = pregenSession().PREGEN_TARGET_CHUNKS.contains(targetKey)
				|| pregenSession().PREGEN_QUEUED_CHUNKS.contains(targetKey);
		// v89: explicit Rewipe targets need the same retry semantics as Pregen.
		// The v88 final drain could reliably stall on a small fixed tail (for
		// example 28 chunks) because neighborsReady() treated Rewipe like normal
		// exploration: each missing support chunk was requested only once. C2ME
		// may unload/lose that support future before the target is flattened,
		// leaving pregenSession().FORCE_LOADED_CHUNK_KEYS to suppress every later retry. Pregen
		// solved this exact class of stall by retrying support loads while the
		// operation owns the target. Rewipe must do the same.
		boolean operationOwned = pregenOwned || pregenSession().FORCE_REPROCESS_CHUNKS.contains(targetKey);

		// v224: shared strip ownership is generation/residency only. A Pregen
		// target may enter the authoritative ticking/readiness gate only after it
		// has been explicitly promoted into the bounded target-centered carve lane.
		if (pregenOwned && !pregenSession().PREGEN_PROCESSING_LEASE_LEDGER.isCarveLaneTarget(targetKey)) {
			return false;
		}

		// Normal exploration: preserve the old one-hop anti-cascade rule.
		// Pregen: a target may itself have first been loaded as somebody
		// else's support chunk, so pregenSession().FORCE_LOADED_CHUNK_KEYS must NOT strip it
		// of the ability to maintain its own 3x3 support window.
		boolean allowOneShotForceLoad = !pregenSession().FORCE_LOADED_CHUNK_KEYS.contains(targetKey);
		boolean allReady = true;
		long now = monotonicMillis();

		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkPos neighbor = new ChunkPos(pos.x() + dx, pos.z() + dz);
				long neighborKey = neighbor.pack();
				// v198: was world.hasChunk(...) here, which only proves the
				// chunk's data is loaded (FULL status) - not that it has
				// reached Minecraft's stricter "ticking" tier, which is what
				// this class's own doc comment several lines above (the
				// "real, permanent cause of every waited forever" writeup)
				// describes fixing via isPositionTicking, and which
				// allChunksReadyForRelocation already correctly relies on
				// elsewhere in this same file. hasChunk had regressed back
				// in at some point without that doc catching up - restoring
				// the documented, stricter check here.
				if (world.getChunkSource().isPositionTicking(neighborKey)) {
					pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.remove(neighborKey);
					continue;
				}

				allReady = false;

				if (pregenOwned) {
					// v221: the shared radius-4 ownership strip is itself the load +
					// ticking-neighborhood primitive. Adding radius-0 support tickets here
					// duplicates FORCED-ticket churn without strengthening ticking level.
					// If propagation is delayed, simply wait; the bounded processing-lease
					// watchdog remains the exceptional fallback.
				} else if (operationOwned) {
					// Rewipe/other explicitly owned destructive work does not use Pregen's
					// shared ownership strip, so it retains the bounded support retry.
					long last = pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.get(neighborKey);
					if (last == OceanCanvasPrimitiveLongLongMap.ABSENT || now - last >= PREGEN_SUPPORT_RETRY_MS) {
						pregenSession().PREGEN_SUPPORT_LAST_REQUEST_MS.put(neighborKey, now);
						pregenSession().FORCE_LOADED_CHUNK_KEYS.add(neighborKey);
						requestPregenSupportLoad(world, neighbor.x(), neighbor.z());
					}
				} else if (allowOneShotForceLoad && pregenSession().FORCE_LOADED_CHUNK_KEYS.add(neighborKey)) {
					// Ordinary exploration remains strictly one-hop so a support
					// chunk can never recursively grow the loaded world.
					// v137: THIS is the exact call caught in a real thread dump
					// blocking the main thread for 27+ seconds - see
					// requestNonBlockingChunkLoad's doc comment for the full story.
					requestNonBlockingChunkLoad(world, neighbor.x(), neighbor.z());
				}
			}
		}
		return allReady;
	}

	/**
	 * <p><b>The real, permanent cause of every "waited forever, land
	 * never disappeared" report:</b> the raw, non-notifying chunk-write
	 * API used here was chosen specifically to avoid light/physics
	 * cascades (see the class doc), on the reasoning that it's safe
	 * because "the client hasn't received this chunk yet". That
	 * reasoning holds for chunks far from the player, which is why
	 * exploring by walking/flying worked reasonably well - but it's
	 * guaranteed FALSE for the spawn chunk specifically, since that's
	 * sent to the client almost immediately on login, typically before
	 * this flattener has run. The server-side block data was very
	 * likely already being corrected the whole time; the client was
	 * just never told, so it kept rendering the stale copy forever -
	 * explaining why no amount of waiting ever fixed it in any trial.</p>
	 *
	 * <p>First attempted fix: {@code ChunkMap#resendChunk(LevelChunk)}.
	 * That method doesn't exist under that name - browsing the real
	 * decompiled {@code ChunkMap} class turned up
	 * {@code resendBiomesForChunks} (biome data only, not blocks),
	 * {@code onChunkReadyToSend}, and a couple of others, but nothing
	 * that resends a whole chunk's block data on demand.</p>
	 *
	 * <p><b>Real fix:</b> {@link ServerLevel#sendBlockUpdated} is the
	 * standard, purpose-built vanilla API for exactly this situation -
	 * "block data changed via some other means, notify tracking clients
	 * without re-running placement behavior". Unlike {@code setBlock},
	 * it does NOT call {@code onPlace} or touch neighboring chunks at
	 * all, so it carries none of the risk that caused the original
	 * generation-time stall - it's a pure network notification. Called
	 * once per changed block, right where the raw write already happens
	 * in {@link #flattenChunk}, using the old and new states already on
	 * hand there.</p>
	 *
	 * <p><b>One more gap found once spawn itself was working: switching
	 * to Spectator and flying fast enough froze the game</b>, with a
	 * single log line right before it went silent - "Trying to schedule
	 * tick in not loaded position". Placing water schedules a fluid-tick
	 * internally (part of {@code LiquidBlock#onPlace}), and it turns out
	 * {@code ServerLevel#hasChunk} is a weaker guarantee than needed
	 * here - a chunk can have its data loaded without yet being in
	 * Minecraft's stricter "ticking" state that scheduling requires.
	 * Fast spectator flight crosses into that gap more easily than
	 * normal walking/flying did. Fixed by switching
	 * {@link #neighborsReady} from {@code hasChunk} to
	 * {@code ServerChunkCache#isPositionTicking(long)} (found via the
	 * real decompiled class), which specifically confirms a position is
	 * eligible for scheduling, not just that its data exists.</p>
	 *
	 * <p><b>Player-defined protected zones ({@link OceanCanvasPlayerZones},
	 * {@code /oceancanvas protect}):</b> {@link #flattenChunk} checks these
	 * FIRST, before the existing structure-protection check, and
	 * unconditionally (no material-based exception) - see that class's
	 * doc for exactly why the two checks need different rules. This one
	 * choke point is also what makes pregen/reset/canvas-expansion
	 * automatically respect protected zones with no extra code in any of
	 * those three features: they all funnel through this same method.</p>
	 */

	/**
	 * Cheap "does this chunk need any real work at all" pre-check - real
	 * gap noticed while widening how much of the world this method needs
	 * to consider for the soft edge transition: {@link #flattenChunk}
	 * previously ran its FULL body (including the 3x3-chunk-neighborhood
	 * natural-structure scan, real work) for literally every chunk that
	 * loads anywhere in the world, canvas or not - {@code isInsideCanvas}
	 * only ever gated individual COLUMNS deep inside the loop, never the
	 * method's expensive setup. That was already real, unnecessary cost
	 * for a player exploring far outside the canvas; adding the taper
	 * ring (which needs its own, wider "is this chunk even relevant"
	 * test) made it worth actually fixing rather than leaving as an
	 * accepted inefficiency. A generous, standard, cheap
	 * closest-point-in-a-box-to-a-point check - does this chunk's
	 * bounding box come within the (possibly taper-widened) canvas radius
	 * of the center - without checking all 256 columns individually. See
	 * {@link #flattenChunk}'s early-exit call site for how this is used.
	 */

	/**
	 * Recognizes chunks already carved by pre-protection Ocean Canvas builds.
	 *
	 * <p>We intentionally bias toward preservation: two matching sample columns
	 * are enough to adopt the chunk. A vanilla chunk naturally having the exact
	 * configured deep stone floor plus continuous water up to the configured sea
	 * level at two separated points is uncommon, while requiring a majority
	 * match would risk reprocessing a legitimate old canvas chunk after the
	 * player built on it. False-positive preservation is recoverable with an
	 * explicit reset; false-negative destruction of player entities is not.</p>
	 */
	private enum LegacySealCheck { CANVAS_CONFIRMED, OBVIOUS_STALE_TERRAIN, AMBIGUOUS }

	/**
	 * One-time compatibility check for processed seals written before v67. The
	 * classifier is intentionally conservative: it only auto-recovers a seal when
	 * many separated sample columns still contain high natural terrain and none
	 * show the expected Canvas floor/water profile. Ambiguous chunks are preserved
	 * because they may contain player construction.
	 */
	private static LegacySealCheck checkLegacyProcessedSeal(ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
		if (!isChunkRelevantToCanvas(chunk, config)) return LegacySealCheck.AMBIGUOUS;
		// v67 treated the mere presence of any structure start/reference as
		// ambiguous. Vanilla structure references span many neighboring chunks,
		// which turned large strips of untouched vanilla terrain into false
		// "player-modified" results. Sample the actual blocks instead; protected
		// sample columns are skipped below and flattenChunk already preserves the
		// real managed structure bounds.
		//
		// v134: every sampled x,z below is a LOCAL offset (2-14) into THIS chunk,
		// never a neighbor. Reading via world.getBlockState(pos) here was a real,
		// confirmed-by-thread-dump cause of the project's worst pregen freezes -
		// Level.getBlockState() routes through the chunk source, and if this exact
		// LevelChunk reference has gone stale (unloaded again by the time a
		// backlogged queue entry is finally processed), that turns into
		// ServerChunkCache.getChunkBlocking() - a genuinely synchronous, blocking
		// chunk load ON THE MAIN THREAD, observed taking 40-100+ seconds and
		// tripping ModernFix's tick watchdog. Since every sample here is
		// guaranteed inside the chunk we already hold a direct reference to,
		// reading straight off that chunk's own in-memory data can never block on
		// the chunk source at all.
		int minX=chunk.getPos().getMinBlockX(), minZ=chunk.getPos().getMinBlockZ();
		int waterTop=OceanCanvasConfig.WATER_SURFACE_Y, matches=0, highNatural=0, usableSamples=0;
		for (int localX=2; localX<=14; localX+=4) for (int localZ=2; localZ<=14; localZ+=4) {
			int x=minX+localX, z=minZ+localZ;
			if (config.canvasZone(x,z)!=OceanCanvasConfig.CanvasZone.INSIDE) continue;
			int floorY=config.oceanFloorY()+floorOffset(x,z,config.oceanFloorVariation());
			if (OceanCanvasPlayerZones.get(world).isProtected(x,waterTop,z)
					|| OceanCanvasProtectedData.get(world).isProtected(x,waterTop,z)) { continue; }
			usableSamples++;
			BlockState floor=chunk.getBlockState(new BlockPos(x,floorY,z));
			BlockState below=chunk.getBlockState(new BlockPos(x,floorY-1,z));
			BlockState surface=chunk.getBlockState(new BlockPos(x,waterTop,z));
			BlockState above=chunk.getBlockState(new BlockPos(x,waterTop+1,z));
			boolean floorWater=floor.is(Blocks.WATER)||floor.is(Blocks.SEAGRASS)||floor.is(Blocks.TALL_SEAGRASS)||floor.is(Blocks.KELP)||floor.is(Blocks.KELP_PLANT);
			if (floorWater && below.is(Blocks.STONE) && surface.is(Blocks.WATER) && above.isAir()) matches++;
			int highY=waterTop+8;
			BlockState high=chunk.getBlockState(new BlockPos(x,highY,z));
			if (!high.isAir() && !high.is(Blocks.WATER) && !high.is(Blocks.SEAGRASS) && !high.is(Blocks.TALL_SEAGRASS)
					&& !high.is(Blocks.KELP) && !high.is(Blocks.KELP_PLANT)) highNatural++;
		}
		if (matches>=4) return LegacySealCheck.CANVAS_CONFIRMED;
		// One protected sample must not poison the entire chunk. Require enough
		// unprotected evidence and a strong natural-terrain majority instead.
		// This recovers the chunk-aligned cliffs seen in v67 while still refusing
		// to guess when too little physical evidence is available.
		if (matches==0 && usableSamples>=8 && highNatural>=Math.max(6, (usableSamples*2+2)/3))
			return LegacySealCheck.OBVIOUS_STALE_TERRAIN;
		return LegacySealCheck.AMBIGUOUS;
	}

	private static boolean looksLikeLegacyProcessedCanvasChunk(ServerLevel world, LevelChunk chunk,
			OceanCanvasConfig config) {
		if (!isChunkRelevantToCanvas(chunk, config)) {
			return false;
		}

		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int variation = config.oceanFloorVariation();
		int matches = 0;

		// Four-by-four samples avoid structure/player-build dependence on any
		// single column while remaining negligible compared with a carve pass.
		//
		// v134: sample via chunk.getBlockState, not world.getBlockState - see
		// checkLegacyProcessedSeal's doc for the full story. This exact call
		// (Level.getBlockState -> ServerChunkCache.getChunkBlocking) was caught red-
		// handed in a real thread dump blocking the main thread for 40-100+ seconds
		// during a backlogged pregen run - every x,z sampled here is a local offset
		// into the chunk we already hold, so reading directly off it can never
		// trigger a chunk-source load.
		for (int localX = 2; localX <= 14; localX += 4) {
			for (int localZ = 2; localZ <= 14; localZ += 4) {
				int x = minX + localX;
				int z = minZ + localZ;
				if (config.canvasZone(x, z) == OceanCanvasConfig.CanvasZone.OUTSIDE) {
					continue;
				}
				int floorY = baseFloorY + floorOffset(x, z, variation);
				BlockState floor = chunk.getBlockState(new BlockPos(x, floorY, z));
				BlockState below = chunk.getBlockState(new BlockPos(x, floorY - 1, z));
				BlockState surface = chunk.getBlockState(new BlockPos(x, waterTop, z));
				BlockState above = chunk.getBlockState(new BlockPos(x, waterTop + 1, z));

				boolean floorIsWater = floor.is(Blocks.WATER)
						|| floor.is(Blocks.SEAGRASS) || floor.is(Blocks.TALL_SEAGRASS)
						|| floor.is(Blocks.KELP) || floor.is(Blocks.KELP_PLANT);
				if (floorIsWater && below.is(Blocks.STONE)
						&& surface.is(Blocks.WATER) && above.isAir()) {
					matches++;
					if (matches >= 2) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean isChunkRelevantToCanvas(LevelChunk chunk, OceanCanvasConfig config) {
		int outerRadius = config.taperEnabled() ? config.radius() + config.taperWidthBlocks() : config.radius();
		int chunkMinX = chunk.getPos().getMinBlockX();
		int chunkMinZ = chunk.getPos().getMinBlockZ();
		int chunkMaxX = chunkMinX + 15;
		int chunkMaxZ = chunkMinZ + 15;
		int nearestX = Math.max(chunkMinX, Math.min(config.centerX(), chunkMaxX));
		int nearestZ = Math.max(chunkMinZ, Math.min(config.centerZ(), chunkMaxZ));
		int nearestSquareDistance = Math.max(Math.abs(nearestX - config.centerX()), Math.abs(nearestZ - config.centerZ()));
		return nearestSquareDistance < outerRadius;
	}

	private static boolean flattenChunk(ServerLevel world, LevelChunk chunk) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
		OceanCanvasConfig config = OceanCanvasConfig.get();

		// Early exit (see isChunkRelevantToCanvas's doc for the full real
		// gap this closes) - skips the ENTIRE method (setup included)
		// when every column in this chunk is provably {@link
		// OceanCanvasConfig.CanvasZone#OUTSIDE}, computed once, cheaply,
		// from the chunk's bounding box, before anything else runs.
		// Region biome rules are applied BEFORE the canvas-relevance
		// early-out, because a region is allowed to sit outside the canvas
		// - marking out the biome of a bay just past the edge is a
		// perfectly reasonable thing to draw, and gating it on canvas
		// relevance would have made every such rule silently do nothing.
		// Cheap when unused: one "are there any at all" check.
		boolean regionBiomeChanged = OceanCanvasBiomeMasker.applyRegionBiomes(world, chunk);

		if (!isChunkRelevantToCanvas(chunk, config)) {
			if (regionBiomeChanged) {
				// A region biome rule is allowed outside the canvas. Do NOT enter the
				// canonical-canvas light finalizer for an outside chunk merely to ship a
				// biome palette. Persist and publish the already-loaded full chunk
				// directly; this updates tracked clients without widening the terrain/
				// lighting ownership boundary.
				publishBiomeOnlyChunkUpdate(world, chunk);
			}
			return true;
		}

		// Fixed constant, not world.getSeaLevel() - see the field's doc
		// comment in OceanCanvasConfig for why that dynamic value was
		// actually the root cause of the height-mismatch bugs, not a
		// fix for them.
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int floorVariation = config.oceanFloorVariation();
		int transitionThickness = config.oceanFloorTransitionThickness();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();

		BlockState water = Blocks.WATER.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState stone = Blocks.STONE.defaultBlockState();

		// v253.77 P0: cache world-owned policy once per chunk and suppress millions
		// of redundant per-block client publications while a destructive operation
		// owns the chunk. The lighting finalizer already publishes one authoritative
		// full chunk+light boundary after all writes settle. Ordinary exploration
		// repair keeps the immediate block update behavior.
		OceanCanvasPlayerZones playerZones = OceanCanvasPlayerZones.get(world);
		OceanCanvasPlayerZones.ProtectionLookup playerProtection = playerZones.protectionLookupForChunk(chunk.getPos());
		long mutationChunkKey = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
		boolean batchedOwnedMutation = pregenSession().PREGEN_TARGET_CHUNKS.contains(mutationChunkKey)
				|| pregenSession().FORCE_REPROCESS_CHUNKS.contains(mutationChunkKey)
				|| lightFinalizerSession().allowPhysicalRepair.contains(mutationChunkKey);

		// Natural vanilla structures this chunk knows about (real
		// shipwrecks, mineshafts, ocean ruins, monuments, ...) - see the
		// class doc's "structure preservation redone properly" note.
		//
		// ONLY SHIPWRECKS - filtered by actual structure type, not by
		// "does this look like structure material". Found via a real,
		// serious bug: an abandoned mineshaft's wooden supports aren't
		// "natural terrain material" either, so they were being
		// protected too - including the part of it that happened to
		// poke up into the water/air we're excavating, leaving a chunk
		// of mineshaft floating above the sealed floor. Per the user's
		// explicit spec: shipwrecks are the only structure meant to be
		// visible above the floor; anything else (mineshafts, etc.)
		// should just get carved away like ordinary terrain wherever it
		// intersects the excavated range, and stay completely untouched
		// wherever it remains below the seal (which it always will,
		// since we never carve below transitionBottom regardless).
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> naturalStructureBounds =
				new java.util.ArrayList<>();
		// Real fix, found from an actual "the stairs on the shipwreck
		// still don't get waterlogged" report even after
		// revalidatePlacedStructureEnvironment was added: that method
		// used to run immediately inside relocateShipwreckIfNeeded, right
		// after placeInWorld - but relocation happens during THIS
		// discovery block, which runs BEFORE the main per-column carve
		// loop below. At that exact moment, the real ambient water that's
		// SUPPOSED to surround the newly-pasted ship hasn't actually been
		// carved into existence yet (it's still whatever solid ground was
		// there before) - so checking "is a neighbor touching water"
		// found nothing, every time, regardless of the fix's own logic
		// being correct. Structures relocated during THIS call are
		// tracked here and revalidated at the very end of this method
		// instead, after the carve loop has actually turned the
		// surrounding area into real water.
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> newlyRelocatedBounds =
				new java.util.ArrayList<>();

		// Structures preserved WHOLE - ocean ruins and ocean monuments -
		// where the region's rule (or the world default) says to keep them.
		// See discoverAndPreserveWholeBox's doc for why these are carved
		// around unconditionally rather than material-filtered the way
		// naturalStructureBounds (shipwrecks) is.
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds =
				new java.util.ArrayList<>();

		// Pass 1: 3x3-neighborhood getAllStarts() scan - found via a real
		// shipwreck being fully destroyed despite protection.
		// ChunkAccess#getAllStarts() only returns structures that START
		// in that exact chunk; a shipwreck long enough to cross into a
		// neighboring chunk would have its overflow portion carved away
		// in that neighbor, since that chunk's own getAllStarts() has no
		// idea a structure starting elsewhere reaches into it. The
		// neighboring chunks are already known to be loaded/ticking here
		// (neighborsReady already confirmed that before this method was
		// ever called). Handles the common case, where a shipwreck's
		// owning chunk is nearby - see Pass 2 below for the real,
		// distance-independent fix for when it isn't.
		for (int ndx = -1; ndx <= 1; ndx++) {
			for (int ndz = -1; ndz <= 1; ndz++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
				int nx = chunk.getPos().x() + ndx, nz = chunk.getPos().z() + ndz;
				// v182: v163.6's hasChunk()-then-getChunk() pair was assumed
				// safe because hasChunk() had just returned true, but the two
				// calls are NOT atomic - a real thread dump caught the main
				// thread blocked 220+ seconds inside this exact getChunk()
				// call (the worst stall found in this project so far), with
				// the hasChunk guard directly above it having already passed.
				// C2ME can evict the chunk in the gap between the two calls
				// with no main-thread yield to stop it, so getChunk() falls
				// through to the same getChunkBlocking path v137/v163.6
				// thought they'd already closed. getChunkNow(x, z) (already
				// proven-working elsewhere in this project, see
				// OceanCanvasPhysicalHealthScanner#sample) is a single atomic
				// call - it returns the resident LevelChunk or null, with no
				// separate check-then-use window left to race.
				LevelChunk neighborChunk = world.getChunkSource().getChunkNow(nx, nz);
				if (neighborChunk == null) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) v182 flattenChunk aborted at {},{}: neighbor {},{} was not immediately available via getChunkNow. Re-queuing instead of blocking the main thread.",
							chunk.getPos().x(), chunk.getPos().z(), nx, nz);
					return false;
				}
				for (net.minecraft.world.level.levelgen.structure.StructureStart start
						: neighborChunk.getAllStarts().values()) {
					dispatchStructure(world, start, naturalStructureBounds, newlyRelocatedBounds, preservedWholeBounds);
				}
			}
		}

		// Pass 2: real fix for "part of the boat was missing, chest
		// destroyed" on a chunk that wasn't in Pass 1's 3x3 window.
		// v182: same getChunkNow fix as Pass 1 above - a separate
		// hasChunk()-then-getChunk() pair here is exactly as racy under
		// C2ME's background eviction, and Pass 1 and Pass 2 have always
		// mirrored each other's fix history (see v163.6's identical
		// treatment of both).
		for (java.util.Map.Entry<net.minecraft.world.level.levelgen.structure.Structure,
				it.unimi.dsi.fastutil.longs.LongSet> entry : chunk.getAllReferences().entrySet()) {
			if (!isManagedStructure(entry.getKey())) {
				continue;
			}
			it.unimi.dsi.fastutil.longs.LongIterator ownerIt = entry.getValue().iterator();
			while (ownerIt.hasNext()) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
				long packedOwnerPos = ownerIt.nextLong();
				// new ChunkPos(long) does NOT exist on this project's
				// ChunkPos - confirmed via a real compile already (see
				// OceanCanvasUndoManager's note on ChunkPos.containing).
				// ChunkPos.getX(long)/getZ(long) are separate static
				// helpers, not the record's constructor, and have been
				// stable, unrelated low-level vanilla utility methods
				// for a very long time - paired with the
				// already-confirmed-working (int, int) constructor.
				ChunkPos ownerPos = new ChunkPos(ChunkPos.getX(packedOwnerPos), ChunkPos.getZ(packedOwnerPos));
				LevelChunk ownerChunk = world.getChunkSource().getChunkNow(ownerPos.x(), ownerPos.z());
				if (ownerChunk == null) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) v182 flattenChunk aborted at {},{}: structure owner chunk {},{} was not immediately available via getChunkNow. Re-queuing instead of blocking the main thread.",
							chunk.getPos().x(), chunk.getPos().z(), ownerPos.x(), ownerPos.z());
					return false;
				}
				net.minecraft.world.level.levelgen.structure.StructureStart start =
						ownerChunk.getStartForStructure(entry.getKey());
				dispatchStructure(world, start, naturalStructureBounds, newlyRelocatedBounds, preservedWholeBounds);
			}
		}

		// For /oceancanvas stats (drafted this round) - only counts a
		// chunk as "flattened" if it actually changed at least one block,
		// so repeat visits to an already-flattened chunk (a player
		// walking back over the same area) don't inflate the counter.
		// Deliberately NOT a Set<ChunkPos> of every chunk ever flattened -
		// for a 20,000 x 20,000 canvas that's up to ~1.56M entries,
		// real persisted memory/disk cost for a feature that's only ever
		// meant to be an approximate progress indicator, not an exact
		// audit log.
		boolean anyChange = false;

		// v253.22: Drain lava across the entire owned chunk BEFORE any column is
		// replaced with canvas water.  The old column-at-a-time carve could put new
		// water beside still-live lava in the next column; sendBlockUpdated/checkBlock
		// then gave vanilla a chance to run fluid physics and permanently manufacture
		// stone/cobblestone/obsidian after Ocean Canvas had already passed that spot.
		// Raw replacement first removes the reactant, so later queued lava ticks see
		// water/air and become harmless.  This is deliberately chunk-wide and happens
		// before ANY water write, rather than trying to clean up guessed reaction
		// products afterward (which could delete legitimate player stone).
		if (predrainCanvasLava(world, chunk, config, minX, minZ, waterTop,
				baseFloorY, floorVariation, transitionThickness)) {
			anyChange = true;
		}
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
				int x = minX + dx;
				int z = minZ + dz;

				if (!operationColumnSelected(chunk, x, z)) continue;
				OceanCanvasConfig.CanvasZone zone = config.canvasZone(x, z);
				if (zone == OceanCanvasConfig.CanvasZone.OUTSIDE) {
					continue;
				}

				// Smooth, deterministic per-column offset from
				// baseFloorY - see the class doc's "organic floor"
				// note above for why a flat floor looked unnatural.
				// A function of world x,z (not chunk-local coords) so
				// it's continuous across chunk boundaries - no seams.
				int floorY = baseFloorY + floorOffset(x, z, floorVariation);

				// Dynamic, not a fixed margin - see the class doc's
				// PRIORITY FOLLOW-UP note for why a fixed "+4" margin
				// was leaving floating debris behind.
				int columnTop = findSafeColumnTop(chunk, x, z, waterTop);

				// The sealed stone buffer directly below the floor -
				// see the class doc's "sealed stone transition layer"
				// note for why this, not cave-void detection, is the
				// actual fix for caves being exposed at the boundary.
				int transitionBottom = floorY - transitionThickness;

				// Soft edge transition - see the class doc's "taper"
				// section for the full design and its
				// honestly-documented residual imperfection. These three
				// "effective" values default to exactly the INSIDE-zone
				// values above (floorY/transitionBottom/columnTop
				// unchanged), and are ONLY overridden inside the TAPER
				// branch below - meaning INSIDE-zone columns are carved
				// by byte-for-byte the same logic as every prior round,
				// zero behavioral change for the already-proven case.
				int effectiveFloorY = floorY;
				int effectiveTransitionBottom = transitionBottom;
				int effectiveColumnTop = columnTop;

				if (zone == OceanCanvasConfig.CanvasZone.TAPER) {
					// 0.0 right at the canvas edge (carve exactly as
					// INSIDE would, for a seamless inner boundary) to
					// just under 1.0 at the outermost taper chunk
					// (carve almost nothing) - see
					// OceanCanvasConfig#taperBlend's doc comment.
					double blend = config.taperBlend(x, z);

					// The natural, pre-carve terrain height at this
					// exact column - taper-zone chunks have never been
					// touched by this method before (they were always
					// OUTSIDE prior to the taper feature), so this
					// heightmap read reflects true vanilla generation,
					// the same confirmed-working API findSafeColumnTop
					// already uses to read a column's natural surface.
					int naturalSurfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

					// Never taper DOWNWARD past floorY - if natural
					// terrain here is already at or below the canvas
					// floor (e.g. a natural ocean/trench just outside
					// the edge), there's no "wall" to fix in the first
					// place, so the taper should have no effect at all
					// rather than accidentally carving MORE than the
					// interior would.
					int taperTargetY = Math.max(naturalSurfaceY, floorY);

					// The floor ramps up from floorY (blend 0) toward
					// taperTargetY (blend 1) - the actual fix for the
					// "visible wall": a mountain's base gradually rises
					// out of the water/floor over the taper width
					// instead of the floor stopping dead at a fixed
					// deep Y right next to untouched full-height terrain.
					effectiveFloorY = floorY + (int) Math.round(blend * (taperTargetY - floorY));

					// The sealed-stone-buffer thickness ALSO shrinks
					// toward zero as blend approaches 1 - not just the
					// floor position - so effectiveTransitionBottom and
					// effectiveColumnTop (below) converge toward the
					// SAME value near the outer edge of the ring,
					// collapsing the carved range toward nothing rather
					// than leaving a fixed-thickness band of touched
					// terrain no matter how far into the taper a column
					// is. Floored at 0, never negative.
					int effectiveTransitionThickness =
							Math.max(0, (int) Math.round(transitionThickness * (1.0 - blend)));
					effectiveTransitionBottom = effectiveFloorY - effectiveTransitionThickness;

					// The "clear to air" ceiling also ramps, from
					// columnTop (blend 0, matches INSIDE exactly) down
					// toward taperTargetY (blend 1, i.e. nothing above
					// the natural surface needs clearing since nothing
					// artificial was ever there).
					effectiveColumnTop = (int) Math.round(columnTop + blend * (taperTargetY - columnTop));

					// Honest residual, not hidden: because TAPER's blend
					// is mathematically always < 1.0 (the outermost ring
					// still classifies as TAPER, never OUTSIDE - see
					// OceanCanvasConfig#canvasZone), the carved range
					// never shrinks to LITERALLY zero at the very last
					// taper chunk before the OUTSIDE boundary - a
					// residual band bounded by rounding (at most a
					// couple of blocks) can still be touched right at
					// that seam. This is a real, deliberately-accepted
					// tradeoff, not a bug: it replaces what used to be a
					// full, unbounded (up to the entire floor depth)
					// vertical cliff with, at worst, a sub-transition-
					// thickness ledge - a large, genuine improvement,
					// just not a mathematically perfect one.
				}

				for (int y = effectiveTransitionBottom; y <= effectiveColumnTop; y++) {
					if ((y & 15) == 0 && net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
					BlockPos pos = new BlockPos(x, y, z);
					BlockState target;
					if (y > waterTop) {
						target = air;
					} else if (y >= effectiveFloorY) {
						target = water;
					} else {
						// Inside the transition layer (transitionBottom
						// <= y < floorY) - always solid stone,
						// regardless of what was there before,
						// guaranteeing no cave void can ever be exposed
						// right at the ocean floor boundary.
						target = stone;
					}
					BlockState current = chunk.getBlockState(pos);

					if (current == target) {
						continue; // already flattened - the common case on repeat loads
					}

					// Player-defined protected zones - checked FIRST and
					// unconditionally (no isNaturalTerrainMaterial escape
					// hatch), unlike the structure-protection check right
					// below. See OceanCanvasPlayerZones's class doc for
					// exactly why those two need different rules: a
					// player's build is very likely made of ordinary
					// terrain materials (stone, dirt, sand), so applying
					// the same material filter here would carve away most
					// of a protected build while leaving only its
					// non-terrain blocks standing.
					if (playerProtection.isProtected(x, y, z)) {
						continue;
					}

					// Structures preserved whole by a region rule (ocean ruins,
					// ocean monuments) - unconditional, not material-filtered,
					// same reasoning as the player-zone check just above. See
					// discoverAndPreserveWholeBox's doc for why that is safe
					// without a material allowlist for these kinds, unlike a
					// sprawling mineshaft.
					if (isInsideAny(preservedWholeBounds, x, y, z)) {
						continue;
					}

					if ((ProtectedRegions.isProtected(world, x, y, z) || isInsideAny(naturalStructureBounds, x, y, z))
							&& isShipMaterial(current)) {
						// A real structure (manually-placed spawn
						// shipwreck, or a naturally-generated one this
						// chunk knows about) - leave it exactly as is.
						// isShipMaterial is what actually distinguishes
						// "this is the ship" from "this is sand/sandstone/
						// ice/whatever else just happens to sit inside the
						// ship's bounding box" - see that method's doc for
						// why this checks an ALLOWLIST of real ship
						// materials now, not a denylist of terrain.
						continue;
					}

					// Undo recording (drafted this round, /oceancanvas
					// undo) - a cheap no-op unless a reset job is
					// actively recording for this exact world, checked
					// internally by the installed undo recorder. Must
					// happen here, right before the raw write below,
					// since `current` is exactly the pre-carve state an
					// undo needs to restore later. See that class's doc
					// for why this is scoped to reset only (pregen/
					// expand never start a recording session, so this
					// is a no-op for both).
					OceanCanvasUndoRecorderBridge.record(world, pos, current);

					// Raw chunk-level write - no light propagation, no
					// physics update cascade. It DOES still trigger
					// per-block placement behavior (LiquidBlock#onPlace
					// for water, which looks up neighboring chunks) -
					// that's fine here specifically because
					// neighborsReady() already confirmed those
					// neighbors are loaded before this runs, so the
					// lookup resolves instantly instead of blocking
					// (see the class doc's thread-dump-based finding
					// on why that combination, not avoiding onPlace
					// entirely, is what actually fixed the stall).
					// Raw chunk writes bypass the normal Level#setBlock block-entity
					// lifecycle. Generated vaults/brushable blocks proved that stale
					// block-entity NBT can otherwise survive after the block itself is
					// replaced, producing "block entity at ... is invalid" errors later.
					// v103: `current.hasBlockEntity()` only reflects the IN-MEMORY block
					// array, not the chunk's persisted `pendingBlockEntities` NBT. A
					// legacy world can have a position whose block array already reads
					// air/stone/water (correct) while its saved block-entity list still
					// references a chest/spawner from an even older flatten pass that
					// predates this guard - `current.hasBlockEntity()` is false there, so
					// the old gated call never fired, and vanilla's own setBlockState
					// then tries to lazily promote that stale pending entity and throws
					// "Invalid block entity ... got Block{minecraft:air}" (observed on
					// the v103 20k run, on chunks reached for the first time by the
					// restored neighbor-lease fix). removeBlockEntity is a safe no-op
					// when nothing is actually there, so call it unconditionally on
					// every changed position rather than gating on the current block's
					// own type.
					//
					// v132.6 proved that raw, never-yet-parsed block-entity NBT in
					// LevelChunk.pendingBlockEntities must also be removed before the raw
					// block state changes. v253.69.2 centralizes that lifecycle in
					// setBlockStateRawSafe(): it removes only an exact pending-NBT entry
					// (plus any live block entity) before replacement, avoiding the old
					// getBlockEntity() promotion lookup on every terrain block.
					setBlockStateRawSafe(world, chunk, pos, current, target);

					// Separately, explicitly notify any tracking client
					// of the change - see the doc comment above
					// flattenChunk for why this was the actual missing
					// piece behind every "never updates, even after
					// waiting" report. sendBlockUpdated is network-only:
					// it doesn't call onPlace or touch neighbors, so it
					// carries none of the risk that made setBlock unsafe
					// here in the first place.
					if (!batchedOwnedMutation) {
						world.sendBlockUpdated(pos, current, target, 3);
					}
					// v253.3: raw chunk writes deliberately bypass Level#setBlock, so
					// vanilla never receives the normal lighting invalidation.  Now that
					// destructive writes are restricted to explicitly-owned jobs whose
					// 3x3 neighborhood is ticking-ready, it is safe to enqueue the light
					// update here without reviving the old generation-time recursive-load
					// failure.  This removes the dark silhouettes left by the terrain that
					// used to occupy the column.
					world.getChunkSource().getLightEngine().checkBlock(pos);
					// One chunk-level dirty mark below is sufficient for raw writes. Marking
					// the same chunk dirty for every changed block adds pure hot-loop cost.
					anyChange = true;
				}

				// Real bug, not hypothetical - see
				// removeUnsupportedFallingBlocks's doc for the full
				// story. Must run after the main carve loop above (needs
				// each block's FINAL state for this column). Keeping this last also
				// makes the support cleanup independent of any future optional
				// ecology layer.
				if (removeUnsupportedFallingBlocks(world, chunk, x, z, transitionBottom, columnTop, waterTop)) {
					anyChange = true;
				}

				// Final canonicalization for the play-test gravel/sand piles:
				// above the generated floor, ordinary falling blocks are never
				// intended terrain, even if they currently happen to be supported.
				if (removeExposedFallingBlocks(world, chunk, x, z, effectiveFloorY, effectiveColumnTop, waterTop,
						naturalStructureBounds, preservedWholeBounds)) {
					anyChange = true;
				}
			}
		}

		// v253.89: post-placement environment repair is a separate structure service;
		// SurfaceFlattener retains lighting invalidation as an explicit callback.
		if (!OceanCanvasStructureEnvironmentService.finishRelocatedThisPass(
				world, newlyRelocatedBounds, OceanCanvasSurfaceFlattener::rearmLightingAfterStructureMutation)) return false;
		if (!OceanCanvasStructureEnvironmentService.drainPersistedForChunk(
				world, chunk.getPos(), OceanCanvasSurfaceFlattener::rearmLightingAfterStructureMutation)) return false;

		// v253.79: terrain preparation no longer deletes entities. Boats, mobs,
		// item frames, minecarts, drops and other entities are world content, not
		// terrain debris. Vanilla physics can resolve an entity whose supporting
		// terrain changed; Ocean Canvas must not silently discard it. This also
		// preserves entities created before a queued Pregen reaches their chunk.
		if (anyChange) {
			OceanCanvasProtectedData.get(world).incrementFlattenedChunks();
		}

		// Drafted, off-by-default future work - see
		// OceanCanvasBiomeMasker's class doc. Config-gated inside that
		// class itself (not just here) so every call site that might
		// ever call this stays correct by construction rather than
		// needing to remember the check.
		boolean biomeMaskChanged = OceanCanvasBiomeMasker.maskChunkIfEnabled(
				world, chunk, baseFloorY, floorVariation, waterTop);

		// v71: completion is now a physical assertion, not merely "the carve
		// function returned". The v70 runtime gate exposed visible water/lava
		// columns after a nominal 15876/15876 completion. Refuse to persist a
		// completion seal while any UNPROTECTED column violates the canonical
		// canvas profile. Pregen ownership remains outstanding and retries the
		// chunk on a later tick; ordinary exploration likewise leaves it
		// unsealed so a later load can repair it.
		boolean canonicalRepairRan = false;
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
		PhysicalProfileMismatch mismatch = firstPhysicalProfileMismatch(world, chunk, config);
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
		if (mismatch != null) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Physical completion verification failed at chunk {},{}: {}. Running deterministic canonical repair before completion.",
					chunk.getPos().x(), chunk.getPos().z(), mismatch.describe());

			// v72: detection alone is not enough. The v70 playtest showed that
			// ordinary vanilla islands as well as fluid walls could survive a
			// nominally successful carve. Re-running the same carve can repeat
			// the same omission forever, so a failed audit now invokes a much
			// simpler deterministic canonicalizer: for every unprotected INSIDE
			// column, stone below the floor transition, water through sea level,
			// and air above sea level. Explicit player/region/managed-structure
			// protection is still respected block-by-block.
			repairCanonicalCanvasProfile(world, chunk, config);
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
			canonicalRepairRan = true;
			mismatch = firstPhysicalProfileMismatch(world, chunk, config);
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
			if (mismatch != null) {
				OceanCanvas.LOGGER.error("(Ocean Canvas) Canonical repair still could not verify chunk {},{}: {}. Chunk remains incomplete and will be retried; Pregen cannot report 100% while this persists.",
						chunk.getPos().x(), chunk.getPos().z(), mismatch.describe());
				return true;
			}
		}

		// The physical profile is correct now, but raw block writes may still have
		// server-side light work in flight and tracking clients may still hold the
		// pre-carve light arrays. Schedule a full chunk+light synchronization rather
		// than relying on block-update packets to carry lighting (they do not).
		if (anyChange || canonicalRepairRan || biomeMaskChanged || regionBiomeChanged) {
			// v253.24: raw terrain writes invalidate the persisted light snapshot.
			// Keep the chunk explicitly NOT light-correct until the bounded final
			// convergence pass finishes. v253.23 set this flag true before the
			// asynchronous C2ME light work had settled, which could preserve small
			// stale light cells even though the large stale-light masks were fixed.
			// v253.25: never toggle LevelChunk#lightCorrect on an already-live FULL
			// chunk. C2ME owns that status lifecycle; forcing false/true from Ocean
			// Canvas produced the severe black section artifacts in v253.24. The live
			// incremental light graph is driven exclusively through checkBlock.
			// v253.46: a biome-only correction changes water tint even when the
			// block profile was already canonical. Persist it and route it through
			// the same authoritative full-chunk publication so an already-tracking
			// client receives the new biome palette instead of keeping the old tint.
			chunk.markUnsaved();
			scheduleLightSync(world, chunk.getPos(), true);
		}

		if (wholeChunkCompletionAllowed(chunk)) {
			OceanCanvasProtectedData.get(world).markChunkProcessedPhysicallyVerified(chunk.getPos());
			var terrainStateData = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world);
			var previousTerrainState = terrainStateData.get(chunk.getPos());
			terrainStateData.set(chunk.getPos(), net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS);
			boolean reconcileP1W3Evidence = previousTerrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.VANILLA
					|| previousTerrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED;
			net.oceancanvas.mod.project.OceanCanvasP1W3Service.recordCanvasChunk(world, chunk, reconcileP1W3Evidence);
		} else {
			// A chunk-level seal means all 256 columns are canonical. Edge chunks of
			// a block-radius request are intentionally partial, so never let that
			// coarse metadata make a later adjacent Pregen skip untouched columns.
			OceanCanvasProtectedData.get(world).clearChunkProcessed(chunk.getPos());
			net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).clear(chunk.getPos());
			OceanCanvas.LOGGER.debug("(Ocean Canvas) PARTIAL-EDGE-COMPLETION build={} chunk={},{} action=no-whole-chunk-seal",
					net.oceancanvas.mod.OceanCanvas.VERSION, chunk.getPos().x(), chunk.getPos().z());
		}

		// v253.25: do NOT notify Voxy/other compatibility consumers here. This
		// point is physically complete but lighting is intentionally still settling.
		// v253.24 published once here and again after authoritative lighting, causing
		// thousands of duplicate/stale Voxy ingests. The finalizer is now the single
		// authoritative CANVAS_WRITE publication boundary.
		return true;
	}


	/** True only for columns the currently-owned destructive operation is allowed
	 * to mutate. Radius requests are block-exact even though loading remains chunk-based. */
	private static boolean operationColumnSelected(LevelChunk chunk, int x, int z) {
		long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
		boolean freshOperation = pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)
				|| pregenSession().FORCE_REPROCESS_CHUNKS.contains(packed)
				|| lightFinalizerSession().allowPhysicalRepair.contains(packed);
		if (!freshOperation) return true;
		return OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(
				chunk.getPos().x(), chunk.getPos().z(), x, z);
	}

	private static boolean strictCanvasColumnSelected(LevelChunk chunk, OceanCanvasConfig config, int x, int z) {
		return config.canvasZone(x, z) == OceanCanvasConfig.CanvasZone.INSIDE
				&& operationColumnSelected(chunk, x, z);
	}

	private static boolean wholeChunkCompletionAllowed(LevelChunk chunk) {
		return OceanCanvasActiveTerrainOperationBridge.fullyCoversChunkBlocks(
				chunk.getPos().x(), chunk.getPos().z());
	}

	/**
	 * v71 physical-completion audit. This is intentionally cheaper than replaying
	 * the whole generation algorithm, but it checks the invariants that distinguish
	 * a genuinely blank Canvas column from the exact artifacts seen in playtests:
	 * no unprotected material/fluid may protrude above the configured water surface;
	 * every unprotected block from the organic floor through the water surface is
	 * water (or intentional ocean vegetation); and the configured transition seal is
	 * solid stone. Player zones and persisted Ocean Canvas structure protection are
	 * exclusions, never false failures.
	 *
	 * <p>Taper columns are intentionally skipped by this strict profile audit because
	 * their expected floor depends on the pre-carve natural surface, which is not
	 * reconstructible after carving without separate persisted source data. The bug
	 * this gate is closing occurred in ordinary INSIDE canvas chunks; treating taper
	 * as a separate health contract avoids inventing a destructive approximation at
	 * the world edge.</p>
	 */
	static final class PhysicalAuditState {
		final LevelChunk chunk;
		final java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds;
		final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int columnCursor;
		int phase;
		int yCursor;
		int x;
		int z;
		int floorY;
		int transitionBottom;
		int surfaceY;
		boolean columnActive;
		int fingerprintSampleCursor;
		long boundaryHash = 0xcbf29ce484222325L;
		PhysicalAuditState(ServerLevel world, LevelChunk chunk) {
			this.chunk = chunk;
			this.preservedWholeBounds = computePreservedWholeBoundsForAudit(world, chunk);
		}
	}

	private record PhysicalAuditAdvance(boolean complete, PhysicalProfileMismatch mismatch, long boundaryFingerprint) { }

	/**
	 * v253.125.34 block-granular cooperative physical profile proof. The .33 soak
	 * still measured 371ms physical-audit slices because the old cursor checked its
	 * deadline only between columns; one protected/complex column could walk dozens
	 * of Y cells after the 4ms budget had already expired. Persist both the column and
	 * intra-column phase/Y cursor. Every block read is now a yield boundary and no
	 * certification occurs until all 256 columns have completed the exact old proof.
	 */
	private static PhysicalAuditAdvance advancePhysicalProfileAudit(ServerLevel world, LevelChunk chunk,
			OceanCanvasConfig config, OceanCanvasPrimitiveLongObjectMap<PhysicalAuditState> stateMap, long packed,
			boolean captureBoundaryFingerprint) {
		PhysicalAuditState state = stateMap.get(packed);
		if (state == null || state.chunk != chunk) {
			state = new PhysicalAuditState(world, chunk);
			stateMap.put(packed, state);
		}
		long started = System.nanoTime();
		int processedBlocks = 0;
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int floorVariation = config.oceanFloorVariation();
		int transitionThickness = config.oceanFloorTransitionThickness();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		final int maxBlocks = 128;

		while (state.columnCursor < 256 && processedBlocks < maxBlocks) {
			if (processedBlocks > 0 && System.nanoTime() - started >= LIGHT_PROOF_SLICE_TIME_BUDGET_NS) break;
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) break;
			if (!state.columnActive) {
				int idx = state.columnCursor;
				int dx = idx >>> 4, dz = idx & 15;
				state.x = minX + dx;
				state.z = minZ + dz;
				if (!strictCanvasColumnSelected(chunk, config, state.x, state.z)) {
					state.columnCursor++;
					continue;
				}
				state.floorY = baseFloorY + floorOffset(state.x, state.z, floorVariation);
				state.transitionBottom = state.floorY - transitionThickness;
				state.surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, state.x, state.z);
				state.phase = state.surfaceY > waterTop ? 0 : 1;
				state.yCursor = state.phase == 0 ? state.surfaceY : state.transitionBottom;
				state.columnActive = true;
			}

			if (state.phase == 0) {
				if (state.yCursor <= waterTop) {
					state.phase = 1;
					state.yCursor = state.transitionBottom;
					continue;
				}
				int y = state.yCursor--;
				BlockState bs = chunk.getBlockState(state.cursor.set(state.x, y, state.z));
				processedBlocks++;
				if (!bs.isAir() && !isPhysicalAuditProtected(world, state.x, y, state.z, bs, state.preservedWholeBounds)) {
					stateMap.remove(packed);
					return new PhysicalAuditAdvance(true, new PhysicalProfileMismatch(state.x, y, state.z, "block/fluid above water surface: " + bs), 0L);
				}
				continue;
			}

			if (state.phase == 1) {
				if (state.yCursor >= state.floorY) {
					state.phase = 2;
					state.yCursor = state.floorY;
					continue;
				}
				int y = state.yCursor++;
				BlockState bs = chunk.getBlockState(state.cursor.set(state.x, y, state.z));
				processedBlocks++;
				if (!isPhysicalAuditProtected(world, state.x, y, state.z, bs, state.preservedWholeBounds) && !bs.is(Blocks.STONE)) {
					stateMap.remove(packed);
					return new PhysicalAuditAdvance(true, new PhysicalProfileMismatch(state.x, y, state.z, "transition seal is not stone: " + bs), 0L);
				}
				continue;
			}

			if (state.phase == 2 && state.yCursor > waterTop) {
				if (captureBoundaryFingerprint) {
					state.phase = 3;
					state.fingerprintSampleCursor = 0;
				} else {
					state.columnCursor++;
					state.columnActive = false;
				}
				continue;
			}
			if (state.phase == 3) {
				int sample = state.fingerprintSampleCursor;
				int y = switch (sample) {
					case 0 -> state.floorY - 1;
					case 1 -> state.floorY;
					case 2 -> state.floorY + 1;
					case 3 -> waterTop;
					case 4 -> waterTop + 1;
					default -> waterTop + 2;
				};
				BlockState bs = chunk.getBlockState(state.cursor.set(state.x, y, state.z));
				state.boundaryHash ^= (((long)state.x) << 32) ^ (state.z & 0xffffffffL) ^ ((long)y << 17) ^ bs.hashCode();
				state.boundaryHash *= 0x100000001b3L;
				processedBlocks++;
				state.fingerprintSampleCursor++;
				if (state.fingerprintSampleCursor >= 6) {
					state.columnCursor++;
					state.columnActive = false;
				}
				continue;
			}
			int y = state.yCursor++;
			BlockState bs = chunk.getBlockState(state.cursor.set(state.x, y, state.z));
			processedBlocks++;
			if (!isPhysicalAuditProtected(world, state.x, y, state.z, bs, state.preservedWholeBounds)
					&& !isCanonicalCanvasWaterState(bs)) {
				stateMap.remove(packed);
				return new PhysicalAuditAdvance(true, new PhysicalProfileMismatch(state.x, y, state.z, "water column contains non-canvas state: " + bs), 0L);
			}
		}
		long elapsed = System.nanoTime() - started;
		lightTelemetrySession().LIGHT_DIAG_PHYSICAL_AUDIT_SLICES.incrementAndGet();
		lightTelemetrySession().LIGHT_DIAG_MAX_PHYSICAL_AUDIT_SLICE_NANOS.accumulateAndGet(elapsed, Math::max);
		if (state.columnCursor < 256) {
			lightTelemetrySession().LIGHT_DIAG_PHYSICAL_AUDIT_YIELDS.incrementAndGet();
			return new PhysicalAuditAdvance(false, null, 0L);
		}
		stateMap.remove(packed);
		return new PhysicalAuditAdvance(true, null, captureBoundaryFingerprint ? state.boundaryHash : 0L);
	}

	private static PhysicalProfileMismatch firstPhysicalProfileMismatch(
			ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int floorVariation = config.oceanFloorVariation();
		int transitionThickness = config.oceanFloorTransitionThickness();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds =
				computePreservedWholeBoundsForAudit(world, chunk);
		// v253.125.25: this audit can read thousands of blocks on every fresh
		// certificate. Reuse one cursor so the healthy path produces no per-read
		// BlockPos garbage. The cursor never escapes this synchronous read loop.
		BlockPos.MutableBlockPos auditCursor = new BlockPos.MutableBlockPos();

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return null;
				int x = minX + dx;
				int z = minZ + dz;
				if (!strictCanvasColumnSelected(chunk, config, x, z)) continue;

				int floorY = baseFloorY + floorOffset(x, z, floorVariation);
				int transitionBottom = floorY - transitionThickness;

				// WORLD_SURFACE includes fluids. Any top above the canonical sea
				// surface is therefore a surviving wall/pillar/fluid column unless
				// that exact top block belongs to an explicit protected region.
				int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				if (surfaceY > waterTop) {
					for (int y = surfaceY; y > waterTop; y--) {
						BlockState state = chunk.getBlockState(auditCursor.set(x, y, z));
						if (state.isAir()) continue;
						if (!isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) {
							return new PhysicalProfileMismatch(x, y, z, "block/fluid above water surface: " + state);
						}
					}
				}

				for (int y = transitionBottom; y < floorY; y++) {
					BlockState state = chunk.getBlockState(auditCursor.set(x, y, z));
					if (isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) continue;
					if (!state.is(Blocks.STONE)) {
						return new PhysicalProfileMismatch(x, y, z, "transition seal is not stone: " + state);
					}
				}

				for (int y = floorY; y <= waterTop; y++) {
					BlockState state = chunk.getBlockState(auditCursor.set(x, y, z));
					if (isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) continue;
					if (!isCanonicalCanvasWaterState(state)) {
						return new PhysicalProfileMismatch(x, y, z, "water column contains non-canvas state: " + state);
					}
				}
			}
		}
		return null;
	}


	/**
	 * v72 deterministic last-mile repair used only after the ordinary flattener
	 * fails the physical-completion audit. It deliberately does not try to infer
	 * what old terrain "meant". Inside the explicit Canvas zone, an unprotected
	 * column has one canonical post-Pregen state and is rewritten to that state.
	 */
	private static void repairCanonicalCanvasProfile(
			ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int floorVariation = config.oceanFloorVariation();
		int transitionThickness = config.oceanFloorTransitionThickness();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		int maxY = world.getMaxY();
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds =
				computePreservedWholeBoundsForAudit(world, chunk);

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
				int x = minX + dx;
				int z = minZ + dz;
				if (!strictCanvasColumnSelected(chunk, config, x, z)) continue;

				int floorY = baseFloorY + floorOffset(x, z, floorVariation);
				int transitionBottom = floorY - transitionThickness;

				for (int y = transitionBottom; y < floorY; y++) {
					if ((y & 15) == 0 && net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = chunk.getBlockState(pos);
					if (isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) continue;
					if (!state.is(Blocks.STONE)) {
						BlockState target = Blocks.STONE.defaultBlockState();
						world.getBlockEntity(pos);
						world.removeBlockEntity(pos);
						setBlockStateRawSafe(world, chunk, pos, target);
						world.sendBlockUpdated(pos, state, target, 3);
						world.getChunkSource().getLightEngine().checkBlock(pos);
						chunk.markUnsaved();
					}
				}

				for (int y = floorY; y <= waterTop; y++) {
					if ((y & 15) == 0 && net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = chunk.getBlockState(pos);
					if (isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) continue;
					if (!isCanonicalCanvasWaterState(state)) {
						BlockState target = Blocks.WATER.defaultBlockState();
						world.getBlockEntity(pos);
						world.removeBlockEntity(pos);
						setBlockStateRawSafe(world, chunk, pos, target);
						world.sendBlockUpdated(pos, state, target, 3);
						world.getChunkSource().getLightEngine().checkBlock(pos);
						chunk.markUnsaved();
					}
				}

				for (int y = waterTop + 1; y < maxY; y++) {
					if ((y & 15) == 0 && net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = chunk.getBlockState(pos);
					if (state.isAir()) continue;
					if (isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) continue;
					BlockState target = Blocks.AIR.defaultBlockState();
					world.getBlockEntity(pos);
					world.removeBlockEntity(pos);
					setBlockStateRawSafe(world, chunk, pos, target);
					world.sendBlockUpdated(pos, state, target, 3);
					world.getChunkSource().getLightEngine().checkBlock(pos);
					chunk.markUnsaved();
				}
			}
		}
		
	}

	public record LightFinalizationDiagnostics(int pendingSync, int activeResidencyTickets) {
		public boolean drained() { return pendingSync == 0 && activeResidencyTickets == 0; }
	}

	/**
	 * v253.27: terrain completion is not lighting completion. This bounded query
	 * reports the job rectangle for progress/auditing. The final completion gate
	 * separately requires the global Ocean Canvas light queue/tickets to drain so
	 * restart/audit recovery work cannot outlive the operation that triggered it.
	 */
	public static LightFinalizationDiagnostics lightFinalizationDiagnostics(
			int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		// v253.125.31: the dominant caller is the full Canvas completion/progress
		// query. That query does not need to enumerate every key; map sizes are a
		// conservative completion debt count and active ticket count is exact.
		OceanCanvasConfig cfg = OceanCanvasConfig.get();
		int canvasMinChunkX = Math.floorDiv(cfg.centerX() - cfg.radius(), 16);
		int canvasMaxChunkX = Math.floorDiv(cfg.centerX() + cfg.radius() - 1, 16);
		int canvasMinChunkZ = Math.floorDiv(cfg.centerZ() - cfg.radius(), 16);
		int canvasMaxChunkZ = Math.floorDiv(cfg.centerZ() + cfg.radius() - 1, 16);
		boolean coversCanvas = minChunkX <= canvasMinChunkX && maxChunkX >= canvasMaxChunkX
				&& minChunkZ <= canvasMinChunkZ && maxChunkZ >= canvasMaxChunkZ;
		if (coversCanvas) {
			int pending = pendingLightSyncCount() + lightFinalizerSession().persistedAuditSession.pendingCount();
			return new LightFinalizationDiagnostics(pending, lightFinalizerSession().relightResidencyLedger.activeCount());
		}

		java.util.function.LongToIntFunction inBounds = packed -> {
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ ? 1 : 0;
		};
		int pending = lightFinalizerSession().pendingTicks.sumKeys(inBounds)
				+ lightRecoverySession().skyBackoffUntilTick.sumKeys(inBounds)
				+ lightRecoverySession().pressureParkUntilTick.sumKeys(inBounds)
				+ lightFinalizerSession().persistedAuditSession.countInBounds(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
		int tickets = lightFinalizerSession().relightResidencyLedger.countMatching(packed -> {
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ;
		});
		return new LightFinalizationDiagnostics(pending, tickets);
	}

	/**
	 * v253.69 explicit-cancel cleanup for asynchronous light finalization. A cancelled
	 * Pregen must not leave its JVM-only relight queue or FORCED residency tickets
	 * running after the command reports cancellation. Physical completion remains
	 * persisted, while the deliberately absent lighting certificate causes ordinary
	 * chunk-load/restart self-heal to re-arm any affected chunk later.
	 */
	public static int cancelPendingLightFinalization(ServerLevel world) {
		int pending = lightFinalizerSession().pendingTicks.size() + lightRecoverySession().skyBackoffUntilTick.size() + lightRecoverySession().pressureParkUntilTick.size() + lightFinalizerSession().persistedAuditSession.pendingCount();
		// Remove real TicketStorage ownership before clearing bookkeeping. The pool is
		// bounded, so explicit cancel can release all of it synchronously and still stay
		// cheap/deterministic.
		for (long packed : lightFinalizerSession().relightResidencyLedger.activeSnapshot()) {
			releaseLightRelightResidencyTicket(world, packed);
		}
		lightFinalizerSession().pendingTicks.clear();
		// v253.125.35: explicit cancel must clear every scheduler lane and reset the
		// cached durable-debt generation. Queue nodes are merely scheduler hints; the
		// authoritative debt maps above remain the correctness source of truth.
		lightFinalizerSession().pendingWorkOrder.clear();
		lightFinalizerSession().pendingWorkMembership.clear();
		lightFinalizerSession().visibleWorkOrder.clear();
		lightFinalizerSession().visibleWorkMembership.clear();
		lightFinalizerSession().terrainWorkOrder.clear();
		lightFinalizerSession().terrainWorkMembership.clear();
		lightFinalizerSession().debtMembershipGeneration.incrementAndGet();
		lightFinalizerSession().cachedPendingCountGeneration = Long.MIN_VALUE;
		lightFinalizerSession().cachedPendingCountExact = 0;
		lightFinalizerSession().physicalMutationGenerationTick.clear();
		lightFinalizerSession().boundaryMutationGenerationTick.clear();
		lightFinalizerSession().visibleLightPriority.clear();
		lightFinalizerSession().visibleLightPriorityDistanceSq.clear();
		lightFinalizerSession().visibleLightPriorityCursor.set(0L);
		lightFinalizerSession().rejoinAuditDeadlineTick.clear();
		lightFinalizerSession().rejoinAuditSeen.clear();
		lightFinalizerSession().rejoinAuditHealthy.clear();
		lightFinalizerSession().rejoinAuditBad.clear();
		lightFinalizerSession().rejoinAuditUnverified.clear();
		lightFinalizerSession().visibleSentinelCursor.clear();
		// v253.72.1: frontier proof/republish work is part of finalization too. Explicit
		// cancel must leave no delayed audit running behind the user's back. Future
		// ordinary chunk loads can safely re-arm the persisted-certificate proof.
		lightFinalizerSession().persistedAuditSession.clear();
		lightFinalizerSession().pendingPasses.clear();
		lightFinalizerSession().allowPhysicalRepair.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.clear();
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.clear();
		lightRecoverySession().verifyEscalations.clear();
		lightRecoverySession().skyQuarantine.clear();
		lightFinalizerSession().stagedBlockFingerprint.clear();
		lightFinalizerSession().terrainLastMutationTick.clear();
		lightFinalizerSession().terrainInstabilityStreak.clear();
		lightRecoverySession().hardSkyResetRadius1.clear();
		lightRecoverySession().hardSkyResetCounts.clear();
		lightRecoverySession().skyBackoffUntilTick.clear();
		lightRecoverySession().pressureParkUntilTick.clear();
		lightFinalizerSession().retryLedger.clear();
		lightTelemetrySession().LIGHT_DIAG_LAST_DETAIL_WARN_TICK.clear();
		lightTelemetrySession().LIGHT_DEEP_ZERO_PUBLIC_RECOVERY_UNAVAILABLE_LOGGED.set(false);
		lightTelemetrySession().lightDiagDetailBudgetTick = Long.MIN_VALUE;
		lightTelemetrySession().lightDiagDetailBudgetUsed = 0;
		lightTelemetrySession().lightDiagLastAggregateTick = Long.MIN_VALUE;
		lightTelemetrySession().LIGHT_DIAG_SUPPRESSED_DETAIL_WARNINGS.set(0L);
		lightRecoverySession().deepZeroScrubCounts.clear();
		lightRecoverySession().visibleDeepDenseRepairCounts.clear();
		lightRecoverySession().visibleDeepClusterRepairCounts.clear();
		lightRecoverySession().visibleDeepDenseSectionY.clear();
		lightRecoverySession().visibleDeepDenseCursor.clear();
		lightRecoverySession().visibleDeepDenseChecksAccumulated.clear();
		lightRecoverySession().visibleDeepClusterAttemptInFlight.clear();
		lightRecoverySession().visibleDeepClusterSectionY.clear();
		lightRecoverySession().visibleDeepClusterCursor.clear();
		lightRecoverySession().visibleDeepClusterChecksAccumulated.clear();
		lightRecoverySession().deepRepairHeapDeferralCounts.clear();
		lightRecoverySession().pathologyHotspotLevel.clear();
		lightRecoverySession().visibleDeepClusterNextAllowedTick.set(Long.MIN_VALUE);
		lightRecoverySession().deepZeroPublicRecoveryCounts.clear();
		lightRecoverySession().deepZeroPublicRecoveryRetryAfterTick.clear();
		lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAITS.clear();
		lightFinalizerSession().relightStartedNs.clear();
		lightTelemetrySession().LIGHT_DIAG_PRE_PRIME.clear();
		lightFinalizerSession().scanCursor.set(0L);
		lightFinalizerSession().lastProductiveTileKey.set(Long.MIN_VALUE);
		if (pending > 0) {
			OceanCanvas.LOGGER.info("(Ocean Canvas) v253.69 cancel discarded {} transient light-finalizer entr{} and released all bounded relight tickets; missing lighting certificates preserve deterministic self-heal on later load/restart.",
					pending, pending == 1 ? "y" : "ies");
		}
		return pending;
	}

	public static int pendingLightSyncCount() {
		// v253.125.35: small-debt exact union counting is cached by membership
		// generation. Countdown/pass changes are irrelevant to cardinality and no longer
		// trigger another three-map scan. Large debt retains the conservative .31 raw
		// upper bound; zero remains exact and therefore fail-closed completion-safe.
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		int active = session.pendingTicks.size();
		int backoff = lightRecoverySession().skyBackoffUntilTick.size();
		int parked = lightRecoverySession().pressureParkUntilTick.size();
		int raw = active + backoff + parked;
		if (raw == 0) {
			session.cachedPendingCountExact = 0;
			session.cachedPendingCountGeneration = session.debtMembershipGeneration.get();
			return 0;
		}
		if (raw > LIGHT_PENDING_EXACT_COUNT_THRESHOLD) {
			lightTelemetrySession().LIGHT_DIAG_FAST_PENDING_COUNT_FALLBACKS.incrementAndGet();
			return raw;
		}
		long debtGeneration = session.debtMembershipGeneration.get();
		if (session.cachedPendingCountGeneration == debtGeneration) return session.cachedPendingCountExact;
		int generation = ++session.pendingCountGeneration;
		if (generation == 0) {
			java.util.Arrays.fill(session.pendingCountStamps, 0);
			generation = session.pendingCountGeneration = 1;
		}
		final int countGeneration = generation;
		int unique = session.pendingTicks.sumKeys(packed -> pendingCountInsert(session, packed, countGeneration));
		unique += lightRecoverySession().skyBackoffUntilTick.sumKeys(packed -> pendingCountInsert(session, packed, countGeneration));
		unique += lightRecoverySession().pressureParkUntilTick.sumKeys(packed -> pendingCountInsert(session, packed, countGeneration));
		session.cachedPendingCountExact = unique;
		session.cachedPendingCountGeneration = debtGeneration;
		return unique;
	}

	private static int pendingCountInsert(OceanCanvasLightFinalizerSession session, long packed, int generation) {
		int mask = session.pendingCountKeys.length - 1;
		long mixed = packed ^ (packed >>> 33);
		mixed *= 0xff51afd7ed558ccdl;
		mixed ^= mixed >>> 33;
		int slot = ((int)mixed) & mask;
		while (session.pendingCountStamps[slot] == generation) {
			if (session.pendingCountKeys[slot] == packed) return 0;
			slot = (slot + 1) & mask;
		}
		session.pendingCountStamps[slot] = generation;
		session.pendingCountKeys[slot] = packed;
		return 1;
	}
	/** v253.72.9: active finalizer entries only; dormant persistent debt is excluded. */
	public static int activeLightSyncCount() { return lightFinalizerSession().pendingTicks.size(); }

	/**
	 * Chunks whose skylight field could not be proven after the full repair ladder.
	 * They are NOT certified, are still counted by {@link #pendingLightSyncCount()},
	 * and still block job completion. Quarantine changes scheduling share only.
	 */
	public static int quarantinedLightSyncCount() { return lightRecoverySession().skyQuarantine.size(); }
	/** Entries in timed persistent-SKY backoff remain strict completion blockers,
	 * but they are not productive active-light work and must not by themselves
	 * close terrain admission. */
	public static int persistentSkyBackoffCount() { return lightRecoverySession().skyBackoffUntilTick.size(); }
	/** Scheduler-only dormant work; unlike persistentSkyBackoffCount(), this does not imply a failed SKY proof. */
	public static int pressureParkedLightSyncCount() { return lightRecoverySession().pressureParkUntilTick.size(); }
	public static int pendingPersistedLightAuditCount() { return lightFinalizerSession().persistedAuditSession.pendingCount(); }
	public static int activeLightResidencyTicketCount() { return lightFinalizerSession().relightResidencyLedger.activeCount(); }
	/** v253.73.5 monotonic proof-of-drain counter used only by the restart recovery admission guard. */
	public static long lightFinalizationPublishCount() { return lightTelemetrySession().LIGHT_DIAG_FINAL_PUBLISHES.get(); }
	public static int lightFinalizationBackpressureHighWater() { return LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER; }
	public static int lightFinalizationBackpressureLowWater() { return LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER; }
	public static boolean lightFinalizationBackpressured() {
		return lightFinalizerSession().pendingTicks.size() >= LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER;
	}

	/** Summary returned by the post-release visual-integrity completion gate. */
	public record PostJobVisualIntegrityDiagnostics(
			int expected, int loaded, int unloaded, int skySampledChunks, int skyFieldBad,
			int heightBad, int physicalBad, int requeuedLighting, int biomePaletteRepairs) {
		/**
		 * Historical method name retained for the Pregen completion caller. Since
		 * v253.46 this is a visual-state gate, not only a light-field gate: a loaded
		 * chunk whose intended biome palette had to be repaired must be persisted
		 * and republished before the job can claim a stable clean tick.
		 */
		public boolean loadedLightingClean() {
			return skyFieldBad == 0 && requeuedLighting == 0 && biomePaletteRepairs == 0;
		}
	}

	/**
	 * v253.35 authoritative end-of-job gate. v253.33 produced the decisive failure
	 * signature: the staged light queue drained to zero, then the immediate post-job
	 * audit still reported skyFieldBad=2, yet Pregen announced success anyway. That
	 * means a one-shot healthy finalizer sample is not a stable completion proof.
	 *
	 * <p>This pass runs only after the normal light queue/tickets have drained, so it
	 * observes the same post-release state the player will actually see. Any loaded
	 * canonical chunk whose skylight field is bad is re-armed into the existing
	 * staged finalizer. The job must then drain again and pass several consecutive
	 * clean end gates before it may complete. This method does not flatten terrain or
	 * widen the job bounds; it only re-seeds lighting for a chunk whose canonical
	 * open-water light field is demonstrably inconsistent.</p>
	 */
	public static PostJobVisualIntegrityDiagnostics enforcePostJobVisualIntegrityGate(ServerLevel world,
			int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, String kind) {
		long expectedLong = (long) (maxChunkX - minChunkX + 1) * (long) (maxChunkZ - minChunkZ + 1);
		int expected = expectedLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, expectedLong);
		int loaded = 0, lightIncorrect = 0, heightBad = 0, physicalBad = 0, skyFieldBad = 0, skySampledChunks = 0, requeued = 0;
		int biomePaletteRepairs = 0;
		OceanCanvasConfig integrityConfig = OceanCanvasConfig.get();
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				loaded++;

				// v253.46: lighting can be mathematically perfect while the same water
				// renders as isolated tinted islands if a biome-only quart-palette write
				// was never persisted/published. Re-apply the deterministic intended
				// palette to every currently loaded target at the same post-ticket
				// boundary used by the light gate. Any actual difference is a real
				// visual regression: persist it and route it through the authoritative
				// full chunk + light publication before a clean completion tick counts.
				boolean regionBiomeRepaired = OceanCanvasBiomeMasker.applyRegionBiomes(world, chunk);
				boolean globalBiomeRepaired = OceanCanvasBiomeMasker.maskChunkIfEnabled(
						world, chunk, integrityConfig.oceanFloorY(), integrityConfig.oceanFloorVariation(),
						OceanCanvasConfig.WATER_SURFACE_Y);
				if (regionBiomeRepaired || globalBiomeRepaired) {
					biomePaletteRepairs++;
					chunk.markUnsaved();
					scheduleLightSync(world, chunk.getPos(), false);
					requeued++;
					OceanCanvas.LOGGER.warn(
							"(Ocean Canvas) POST-JOB-BIOME-GATE build={} kind={} chunk={},{} regionChanged={} maskChanged={} action=persist-and-republish-authoritative-biome-palette",
							net.oceancanvas.mod.OceanCanvas.VERSION, kind, cx, cz, regionBiomeRepaired, globalBiomeRepaired);
				}

				if (!chunk.isLightCorrect()) lightIncorrect++;
				SkyLightDiag sky = sampleCanonicalSurfaceSkyLight(world, chunk);
				if (sky.samples() > 0 || sky.deepSamples() > 0) skySampledChunks++;
				long packed = ChunkPos.pack(cx, cz);
				if (!sky.healthy()) {
					skyFieldBad++;
					int regression = lightRecoverySession().postAuditRegressions.incrementCapped(packed, Integer.MAX_VALUE);
					lightTelemetrySession().LIGHT_POST_AUDIT_REQUEUES.incrementAndGet();
					// On repeat post-release regressions, immediately run the strongest
					// bounded repair before re-entering the staged verifier. This is still
					// incremental LightEngine work; no blocks are replaced.
					if (regression >= 2) queueAnomalousColumnLightRepair(world, chunk, sky);
					scheduleLightSync(world, chunk.getPos(), false);
					requeued++;
					OceanCanvas.LOGGER.warn("(Ocean Canvas) POST-JOB-LIGHT-GATE build={} kind={} chunk={},{} regression={} surfaceSamples={} aboveSky={}..{} waterSky={}..{} deepSamples={} deepAnomalousLayers={} deepAnomalousColumns={} firstDeep={} firstDeepActual={} firstDeepRequiredMin={} firstDeepRequiredMax={} firstDeepDepth={} deepOverbrightLayers={} deepOverbrightColumns={} action=requeue-authoritative-light-finalizer",
						net.oceancanvas.mod.OceanCanvas.VERSION, kind, cx, cz, regression, sky.samples(), sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax(), sky.deepSamples(), sky.deepAnomalousLayers(), sky.deepAnomalousColumns(),
						sky.firstDeepAnomaly(), sky.firstDeepActual(), sky.firstDeepRequiredMin(), sky.firstDeepRequiredMax(), sky.firstDeepDepth(), sky.deepOverbrightLayers(), sky.deepOverbrightColumns());
				} else {
					lightRecoverySession().postAuditRegressions.remove(packed);
				}
				if (sampleHeightmapAgreement(chunk).mismatchedColumns() > 0) heightBad++;
				if (firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get()) != null) physicalBad++;
			}
		}
		int unloaded = Math.max(0, expected - loaded);
		double coveragePct = expected <= 0 ? 100.0 : (100.0 * loaded / expected);
		OceanCanvas.LOGGER.info("(Ocean Canvas) POST-JOB-VISUAL-INTEGRITY build={} kind={} expected={} loaded={} unloaded={} coveragePct={} engineLightIncorrect={} skySampledChunks={} skyFieldBad={} heightBad={} physicalBad={} requeuedLighting={} biomePaletteRepairs={} biomeSurfaceGuardCells={} biomeFloorGuardCells={} pendingLight={} activeRelightTickets={} postAuditRequeues={} lavaPredrainRemoved={} reactionProductSuspects={} fluidSettleRepairedBlocks={} fluidSettleRepairedChunks={}",
			net.oceancanvas.mod.OceanCanvas.VERSION, kind, expected, loaded, unloaded, String.format(java.util.Locale.ROOT, "%.1f", coveragePct),
			lightIncorrect, skySampledChunks, skyFieldBad, heightBad, physicalBad, requeued, biomePaletteRepairs,
			OceanCanvasBiomeMasker.surfaceGuardRepairedCells(), OceanCanvasBiomeMasker.floorGuardRepairedCells(), lightFinalizerSession().pendingTicks.size(), lightFinalizerSession().relightResidencyLedger.activeCount(), lightTelemetrySession().LIGHT_POST_AUDIT_REQUEUES.get(),
			lightTelemetrySession().FLUID_LAVA_PREDRAIN_BLOCKS.get(), lightTelemetrySession().FLUID_REACTION_PRODUCT_SUSPECTS.get(), lightTelemetrySession().FLUID_SETTLE_REPAIRED_BLOCKS.get(), lightTelemetrySession().FLUID_SETTLE_REPAIRED_CHUNKS.get());
		if (unloaded > 0) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) POST-JOB-VISUAL-INTEGRITY-COVERAGE build={} kind={} auditedLoaded={}/{} chunks; unloaded targets were not claimed healthy. Every authored chunk already passed its staged finalizer while resident; this end gate specifically proves that currently loaded/player-visible chunks did not regress after ticket release.",
				net.oceancanvas.mod.OceanCanvas.VERSION, kind, loaded, expected);
		}
		return new PostJobVisualIntegrityDiagnostics(expected, loaded, unloaded, skySampledChunks, skyFieldBad, heightBad, physicalBad, requeued, biomePaletteRepairs);
	}

	/**
	 * v253.69.2 unified raw terrain replacement. Raw LevelChunk writes are retained
	 * for performance/physics isolation, but every such write now executes the same
	 * block-entity lifecycle first. Pending NBT is removed directly from the exact
	 * position so we do not pay the old getBlockEntity() promotion cost for millions
	 * of ordinary water/air/floor blocks. Live block entities are still removed
	 * through the normal level path before the raw state replacement.
	 */
	private static void setBlockStateRawSafe(ServerLevel world, LevelChunk chunk, BlockPos pos, BlockState target) {
		setBlockStateRawSafe(world, chunk, pos, chunk.getBlockState(pos), target);
	}

	/**
	 * v253.77 P0 hot-path overload. The primary terrain loop already owns the old
	 * state, so do not perform another block lookup and, more importantly, do not
	 * enter Level's live block-entity removal path for the millions of ordinary
	 * terrain/water/air replacements that provably cannot own one. Persisted raw
	 * block-entity NBT is still removed unconditionally through the exact-position
	 * accessor. If that accessor ever stops working, the fallback deliberately
	 * returns to the conservative promote+remove behavior.
	 */
	private static void setBlockStateRawSafe(ServerLevel world, LevelChunk chunk, BlockPos pos, BlockState current, BlockState target) {
		boolean removeLive = current != null && current.hasBlockEntity();
		try {
			var pending = ((LevelChunkBlockEntityAccessor)(Object)chunk).oceancanvas$getPendingBlockEntities();
			if (pending.remove(pos) != null) lightTelemetrySession().RAW_PENDING_BLOCK_ENTITIES_REMOVED.incrementAndGet();
		} catch (Throwable accessorFailure) {
			// Defensive fallback if a future mapping changes the accessor. Promote while
			// the old block state is still present, preserving the v132.6 safety behavior.
			world.getBlockEntity(pos);
			removeLive = true;
		}
		if (removeLive) world.removeBlockEntity(pos);
		chunk.setBlockState(pos, target, 0);
	}

	private static void schedulePersistedLightAuditIfCertified(ServerLevel world, LevelChunk chunk, int delayTicks, boolean forceRepublish) {
		ChunkPos pos = chunk.getPos();
		long packed = ChunkPos.pack(pos.x(), pos.z());
		if (!forceRepublish && lightFinalizerSession().persistedAuditSession.wasAudited(packed)) return;
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		if (!protectedData.isChunkLightingVerified(pos)) return;
		if (net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos)
				!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) return;
		long now = world.getGameTime();
		long due = now + Math.max(1, delayTicks);
		// A frontier-boundary proof owns a stronger quiet deadline than ordinary
		// load/join audit latency. Never let a later CHUNK_LOAD/JOIN MIN-merge pull
		// that proof in front of the last nearby terrain mutation.
		long lastBoundaryMutation = lightFinalizerSession().terrainLastMutationTick.get(packed);
		if (lastBoundaryMutation != OceanCanvasPrimitiveLongLongMap.ABSENT) {
			due = Math.max(due, lastBoundaryMutation + LIGHT_TERRAIN_QUIET_TICKS);
		}
		lightFinalizerSession().persistedAuditSession.scheduleEarliest(packed, due);
	}

	/**
	 * v253.72.1 frontier regression guard. A later adjacent CANVAS carve can alter
	 * the threaded skylight graph (or merely leave the tracking client with the old
	 * section arrays) even when this already-verified chunk's own blocks never change.
	 *
	 * <p>Do not immediately invalidate the durable certificate and do not recursively
	 * enqueue a full relight. Instead, debounce a cheap actual-skylight proof until the
	 * same 40-tick terrain-quiet window used by fresh chunks has elapsed. Repeated
	 * neighboring mutations push the due time later (MAX, not MIN), so a row-major
	 * frontier produces one proof/republish after the last nearby mutation rather than
	 * three competing finalizer restarts. If the proof is bad, the existing
	 * non-destructive finalizer is armed; if healthy, the authoritative chunk+light
	 * packet is still republished so the client cannot retain a stale chunk-column
	 * light snapshot.</p>
	 */
	private static void scheduleVerifiedNeighborBoundaryAudit(ServerLevel world, ChunkPos pos) {
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		if (!protectedData.isChunkLightingVerified(pos)) return;
		if (net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos)
				!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) return;
		long packed = ChunkPos.pack(pos.x(), pos.z());
		long now = world.getGameTime();
		// Reuse the existing bounded terrain-quiet timestamp rather than creating a
		// second million-entry frontier map. Persisted audits only target currently
		// lighting-certified chunks, so this timestamp is unambiguously a boundary
		// convergence deadline and is discarded as soon as the due proof is claimed.
		lightFinalizerSession().terrainLastMutationTick.mergeMax(packed, now);
		long due = now + LIGHT_TERRAIN_QUIET_TICKS;
		lightFinalizerSession().persistedAuditSession.scheduleLatest(packed, due);
		// If the target unloads before the due tick, the ordinary CHUNK_LOAD path must
		// be allowed to schedule a fresh proof on its next residency window.
		lightFinalizerSession().persistedAuditSession.clearAudited(packed);
		lightTelemetrySession().LIGHT_DIAG_VERIFIED_NEIGHBOR_AUDITS.incrementAndGet();
	}

	private static void markVisibleLightPriority(long packed, long distanceChunksSq) {
		lightFinalizerSession().visibleLightPriority.add(packed);
		long rank = Math.max(0L, distanceChunksSq);
		lightFinalizerSession().visibleLightPriorityDistanceSq.mergeMin(packed, rank);
		// v253.125.35: priority is an event, not something the scheduler should
		// rediscover by scanning the generic queue. Migrate any already-pending
		// obligation directly into the visible lane.
		if (lightFinalizerSession().pendingTicks.containsKey(packed)) ensureLightWorkQueued(packed);
	}

	private static void beginJoinPersistedLightAudit(net.minecraft.server.level.ServerPlayer player) {
		ServerLevel world = player.level();
		if (world.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
		java.util.UUID id = player.getUUID();
		long now = world.getGameTime();
		lightFinalizerSession().rejoinAuditDeadlineTick.put(id, now + REJOIN_LIGHT_AUDIT_WINDOW_TICKS);
		lightFinalizerSession().rejoinAuditSeen.put(id, new OceanCanvasPrimitiveLongSet());
		lightFinalizerSession().rejoinAuditHealthy.put(id, 0);
		lightFinalizerSession().rejoinAuditBad.put(id, 0);
		lightFinalizerSession().rejoinAuditUnverified.put(id, 0);
		OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-REJOIN-AUDIT-WINDOW build={} player={} windowTicks={} action=wait-for-real-tracking-then-nearest-first-server-proof",
				net.oceancanvas.mod.OceanCanvas.VERSION, player.getGameProfile().name(), REJOIN_LIGHT_AUDIT_WINDOW_TICKS);
	}

	/**
	 * v253.125.10 strict no-relight fast path for an already resident, physically
	 * verified Canvas chunk whose CURRENT authoritative field is already correct.
	 *
	 * <p>This is not a metadata shortcut: it refuses active structure/terrain
	 * boundaries, requires current heightmap agreement, then runs the same complete
	 * canonical surface/deep/seam skylight oracle used by the finalizer. It also
	 * requires substantial eligible evidence so preserved structures cannot pass on
	 * an empty sample. A success publishes the exact live chunk+light state, persists
	 * the lighting certificate, advances the authoritative commit boundary and
	 * retires every retry/ticket identity. A failure changes no certificate and falls
	 * back to the existing staged finalizer.</p>
	 */
	private static boolean tryFastCertifyResidentCanvasLighting(
			ServerLevel world, LevelChunk chunk, boolean playerVisible,
			long visibleDistanceChunksSq, String trigger) {
		if (world == null || chunk == null) return false;
		ChunkPos pos = chunk.getPos();
		long packed = ChunkPos.pack(pos.x(), pos.z());
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		if (!protectedData.isChunkProcessedPhysicallyVerified(pos)) return false;
		if (net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos)
				!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) return false;
		// A PHYSICAL_AWARE recovery target may carry a stale physical seal; its full
		// profile proof/repair is mandatory before any lighting-only shortcut.
		if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS.contains(packed)
				|| pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)
				|| lightFinalizerSession().allowPhysicalRepair.contains(packed)) return false;
		if (protectedData.pendingOriginalProtectionTouchesChunkRing(pos, 1)) return false;
		if (adjacentPregenTerrainMayStillMutate(pos)) return false;
		long lastMutation = lightFinalizerSession().terrainLastMutationTick.get(packed);
		if (lastMutation != OceanCanvasPrimitiveLongLongMap.ABSENT
				&& world.getGameTime() - lastMutation < LIGHT_TERRAIN_QUIET_TICKS) return false;

		OceanCanvasHeightmapDiag height = sampleHeightmapAgreement(chunk);
		if (height.mismatchedColumns() > 0) {
			// Heightmap repair is metadata-only and deterministic. Re-prove immediately;
			// if it still disagrees, the staged path owns diagnosis.
			net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(
					chunk, java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class));
			height = sampleHeightmapAgreement(chunk);
			if (height.mismatchedColumns() > 0) return false;
		}

		// v253.125.14: a player-visible fast certificate must use the same
		// sparse absolute deep-overbright proof as the visible finalizer. The
		// background majority quorum is intentionally cheaper for overnight Pregen,
		// but it may accept a small positive-SKY island that is visibly wrong.
		// v253.125.19: visibility may change publication priority, never correctness.
		// Background and visible fast-certification use the identical strict oracle.
		SkyLightDiag sky = sampleCanonicalSurfaceSkyLight(world, chunk, true);
		if (!sky.healthy()
				|| sky.samples() < FAST_LIGHT_PROOF_MIN_SURFACE_SAMPLES
				|| sky.deepSamples() < FAST_LIGHT_PROOF_MIN_DEEP_SAMPLES) return false;

		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
		chunk.markUnsaved();
		lightTelemetrySession().LIGHT_DIAG_FINAL_PUBLISHES.incrementAndGet();
		recordProductiveLightTile(packed);
		lightTelemetrySession().LIGHT_DIAG_FAST_CERTIFIED.incrementAndGet();
		if (playerVisible) {
			markVisibleLightPriority(packed, visibleDistanceChunksSq);
			lightTelemetrySession().LIGHT_DIAG_VISIBLE_FAST_CERTIFIED.incrementAndGet();
		}
		pushChunkWithAuthoritativeLight(world, chunk);
		net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.publish(
				new net.oceancanvas.mod.compat.OceanCanvasTerrainChange(
						world, pos,
						playerVisible
								? net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.VISIBLE_LIGHT_REPAIR
								: net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.CANVAS_WRITE));
		protectedData.markChunkLightingVerified(pos);
		OceanCanvasActiveTerrainOperationBridge.authoritativeCommit(world, pos);
		lightFinalizerSession().persistedAuditSession.markAudited(packed);
		lightFinalizerSession().persistedAuditSession.removePending(packed);
		retireCompletedLightState(world, packed);
		if (playerVisible) {
			OceanCanvas.LOGGER.info(
					"(Ocean Canvas) LIGHT-VISIBLE-FAST-CERTIFY build={} chunk={},{} distanceChunksSq={} trigger={} surfaceSamples={} deepSamples={} surfaceSky={}..{} waterSky={}..{} action=authoritative-publish-without-redundant-relight",
					net.oceancanvas.mod.OceanCanvas.VERSION, pos.x(), pos.z(), Math.max(0L, visibleDistanceChunksSq),
					trigger, sky.samples(), sky.deepSamples(), sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax());
		} else {
			long n = lightTelemetrySession().LIGHT_DIAG_FAST_CERTIFIED.get();
			if (n <= 8L || (n & 255L) == 0L) {
				OceanCanvas.LOGGER.info(
						"(Ocean Canvas) LIGHT-FAST-CERTIFY build={} chunk={},{} trigger={} count={} surfaceSamples={} deepSamples={} action=strict-current-field-proof-skipped-redundant-finalizer",
						net.oceancanvas.mod.OceanCanvas.VERSION, pos.x(), pos.z(), trigger, n, sky.samples(), sky.deepSamples());
			}
		}
		return true;
	}

	/**
	 * v253.125.6 targeted spawn/rejoin audit. Fabric's JOIN event fires before the
	 * integrated server has a usable tracking set; the v253.125.4 log proved both
	 * joins scanned zero chunks. Re-scan for ten seconds, but only chunks that are
	 * already resident, physically verified Canvas chunks ACTUALLY tracked by this player.
	 * v253.125.7 deliberately does NOT pre-filter on the lighting certificate: the fixed
	 * repro at x~-749,z~399 remained visibly broken while the v253.125.6 audit reported
	 * only 32 certified chunks healthy. Uncertified visible chunks are now admitted to
	 * the same strict LIGHT_ONLY finalizer instead of being invisible to the audit. Healthy
	 * certified server light is republished immediately (which also activates the existing
	 * client renderer barrier). Bad server light enters the exact same strict light
	 * finalizer, tagged as player-visible priority so historical recovery cannot hide it.
	 * Nothing here force-loads chunks or changes the lighting oracle.
	 */
	private static void drainJoinPersistedLightAuditWindows(ServerLevel world) {
		if (lightFinalizerSession().rejoinAuditDeadlineTick.isEmpty()) return;
		long now = world.getGameTime();
		for (java.util.UUID id : new java.util.ArrayList<>(lightFinalizerSession().rejoinAuditDeadlineTick.keySet())) {
			Long deadline = lightFinalizerSession().rejoinAuditDeadlineTick.get(id);
			if (deadline == null) continue;
			net.minecraft.server.level.ServerPlayer player = world.getServer().getPlayerList().getPlayer(id);
			if (player == null || player.level() != world || now > deadline.longValue()) {
				OceanCanvasPrimitiveLongSet seenSet = lightFinalizerSession().rejoinAuditSeen.get(id);
				int seen = seenSet == null ? 0 : seenSet.size();
				int healthy = lightFinalizerSession().rejoinAuditHealthy.getOrDefault(id, 0);
				int bad = lightFinalizerSession().rejoinAuditBad.getOrDefault(id, 0);
				int unverified = lightFinalizerSession().rejoinAuditUnverified.getOrDefault(id, 0);
				long visibleRing1 = lightFinalizerSession().visibleLightPriorityDistanceSq.countValuesAtMost(2L);
				long visibleRadius3 = lightFinalizerSession().visibleLightPriorityDistanceSq.countValuesAtMost(9L);
				OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-REJOIN-AUDIT-COMPLETE build={} playerPresent={} audited={} healthyRepublished={} serverBad={} visibleUncertified={} visiblePriorityRemaining={} visibleRing1={} visibleRadius3={} action=bounded-window-complete-nearest-ranked",
						net.oceancanvas.mod.OceanCanvas.VERSION, player != null, seen, healthy, bad, unverified, lightFinalizerSession().visibleLightPriority.size(), visibleRing1, visibleRadius3);
				lightFinalizerSession().rejoinAuditDeadlineTick.remove(id);
				lightFinalizerSession().rejoinAuditSeen.remove(id);
				lightFinalizerSession().rejoinAuditHealthy.remove(id);
				lightFinalizerSession().rejoinAuditBad.remove(id);
				lightFinalizerSession().rejoinAuditUnverified.remove(id);
				continue;
			}

			ChunkPos center = player.chunkPosition();
			int radius = Math.min(REJOIN_LIGHT_AUDIT_RADIUS_CAP_CHUNKS,
					Math.max(2, world.getServer().getPlayerList().getViewDistance() + 1));
			OceanCanvasPrimitiveLongSet seen = lightFinalizerSession().rejoinAuditSeen
					.computeIfAbsent(id, ignored -> new OceanCanvasPrimitiveLongSet());
			java.util.ArrayList<LevelChunk> candidates = new java.util.ArrayList<>();
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					long packed = ChunkPos.pack(center.x() + dx, center.z() + dz);
					if (seen.contains(packed)) continue;
					LevelChunk chunk = world.getChunkSource().getChunkNow(center.x() + dx, center.z() + dz);
					if (chunk == null) continue;
					ChunkPos pos = chunk.getPos();
					if (!net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(world, pos).contains(player)) continue;
					if (net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos)
							!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) continue;
					OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
					// A light-only finalizer is safe only after the physical Canvas shape is certified.
					// Do not mistake an actively mutating Pregen chunk for a lighting-only defect.
					if (!protectedData.isChunkProcessedPhysicallyVerified(pos)) continue;
					candidates.add(chunk);
				}
			}
			candidates.sort(java.util.Comparator.comparingLong(chunk -> {
				long dx = (long)chunk.getPos().x() - center.x();
				long dz = (long)chunk.getPos().z() - center.z();
				return dx * dx + dz * dz;
			}));

			int budget = REJOIN_LIGHT_AUDIT_CHUNKS_PER_TICK;
			for (LevelChunk chunk : candidates) {
				if (budget-- <= 0) break;
				ChunkPos pos = chunk.getPos();
				long packed = ChunkPos.pack(pos.x(), pos.z());
				if (!seen.add(packed)) continue;
				OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
				if (!protectedData.isChunkLightingVerified(pos)) {
					long ddx = (long)pos.x() - center.x();
					long ddz = (long)pos.z() - center.z();
					long distanceChunksSq = ddx * ddx + ddz * ddz;
					// v253.125.10: being uncertified is not itself evidence that the light
					// field is bad. The 125.9 fixed repro queued 321 visible chunks into a
					// saturated finalizer even though subsequent client proofs were clean.
					// Prove the current resident field first. Healthy chunks publish now;
					// only demonstrably bad/unsafe chunks consume staged repair capacity.
					if (tryFastCertifyResidentCanvasLighting(
							world, chunk, true, distanceChunksSq, "REJOIN_UNCERTIFIED")) {
						lightFinalizerSession().rejoinAuditHealthy.merge(id, 1, Integer::sum);
						continue;
					}
					lightFinalizerSession().rejoinAuditUnverified.merge(id, 1, Integer::sum);
					markVisibleLightPriority(packed, distanceChunksSq);
					boolean alreadyPending = lightFinalizerSession().pendingTicks.containsKey(packed);
					if (!alreadyPending) scheduleLightSync(world, pos, false);
					if (distanceChunksSq <= 9L) {
						OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-SPAWN-ROOT-CAUSE build={} player={} chunk={},{} classification=VISIBLE_UNCERTIFIED_CANVAS alreadyPending={} distanceChunksSq={} action=strict-fast-proof-did-not-pass-visible-priority-finalizer",
								net.oceancanvas.mod.OceanCanvas.VERSION, player.getGameProfile().name(), pos.x(), pos.z(), alreadyPending, distanceChunksSq);
					}
					continue;
				}
				SkyLightDiag sky = sampleCanonicalSurfaceSkyLight(world, chunk);
				if (!sky.healthy()) {
					lightFinalizerSession().rejoinAuditBad.merge(id, 1, Integer::sum);
					long ddx = (long)pos.x() - center.x();
					long ddz = (long)pos.z() - center.z();
					markVisibleLightPriority(packed, ddx * ddx + ddz * ddz);
					// We already paid for the complete strict SKY proof above; do not sample the
					// same chunk a second time through requestTrackedClientLightReverification.
					if (!lightFinalizerSession().pendingTicks.containsKey(packed)) scheduleLightSync(world, pos, false);
					OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-SPAWN-ROOT-CAUSE build={} player={} chunk={},{} classification=SERVER_SKYLIGHT_BAD_AT_TRACKING surfaceSky={}..{} waterSky={}..{} deepAnomalousLayers={} deepAnomalousColumns={} action=visible-priority-strict-finalizer",
							net.oceancanvas.mod.OceanCanvas.VERSION, player.getGameProfile().name(), pos.x(), pos.z(),
							sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax(), sky.deepAnomalousLayers(), sky.deepAnomalousColumns());
					continue;
				}

				lightFinalizerSession().persistedAuditSession.markAudited(packed);
				lightTelemetrySession().LIGHT_DIAG_RESTART_AUDIT_HEALTHY.incrementAndGet();
				pushChunkWithAuthoritativeLight(world, chunk);
				net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.publish(
						new net.oceancanvas.mod.compat.OceanCanvasTerrainChange(
								world, pos, net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.VISIBLE_LIGHT_REPAIR));
				lightTelemetrySession().LIGHT_DIAG_REJOIN_REPUBLISHES.incrementAndGet();
				lightFinalizerSession().rejoinAuditHealthy.merge(id, 1, Integer::sum);
			}
		}
	}



	private record VisibleWaterVoidRepair(int repairedBlocks, int repairedColumns,
			BlockPos firstPosition, BlockState firstOldState) { }

	/**
	 * v253.125.54 player-visible physical-water sentinel.
	 *
	 * <p>The .53 spawn revisit proved that a persisted PHYSICAL seal cannot be treated
	 * as eternal truth: old builds left rectangular AIR/flowing-water voids in chunks
	 * that later re-entered only through the LIGHT_ONLY path.  The lighting sentinel
	 * therefore saw/treated the chunk as physically verified and could repair SKY while
	 * the visible ocean itself was still physically wrong.</p>
	 *
	 * <p>This is intentionally <strong>not</strong> the destructive canonicalizer. It
	 * never removes or replaces a solid block.  It only restores missing AIR cells or
	 * normalizes flowing plain WATER to source WATER, and only in a strict Canvas column
	 * whose sea-surface cell itself proves that the column is currently broken. Player
	 * zones and managed/preserved structure cells remain absolute exclusions.  Repairs
	 * are block-budgeted so a badly damaged chunk cannot monopolize one server tick.</p>
	 */
	private static VisibleWaterVoidRepair repairVisibleCanonicalWaterVoids(
			ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int floorVariation = config.oceanFloorVariation();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		BlockState sourceWater = Blocks.WATER.defaultBlockState();
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds =
				computePreservedWholeBoundsForAudit(world, chunk);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int repaired = 0, columns = 0;
		BlockPos first = null;
		BlockState firstOld = null;

		for (int dx = 0; dx < 16 && repaired < VISIBLE_WATER_VOID_REPAIR_MAX_BLOCKS_PER_CHUNK_PASS; dx++) {
			for (int dz = 0; dz < 16 && repaired < VISIBLE_WATER_VOID_REPAIR_MAX_BLOCKS_PER_CHUNK_PASS; dz++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) {
					return new VisibleWaterVoidRepair(repaired, columns, first, firstOld);
				}
				int x = minX + dx, z = minZ + dz;
				if (!strictCanvasColumnSelected(chunk, config, x, z)) continue;
				BlockState surface = chunk.getBlockState(cursor.set(x, waterTop, z));
				if (isPhysicalAuditProtected(world, x, waterTop, z, surface, preservedWholeBounds)) continue;
				boolean brokenSurface = surface.isAir()
						|| (surface.is(Blocks.WATER) && !surface.getFluidState().isSource());
				if (!brokenSurface) continue;

				int floorY = baseFloorY + floorOffset(x, z, floorVariation);
				boolean repairedColumn = false;
				// Bottom-up on purpose: if the per-pass block budget is exhausted, the
				// still-broken surface remains a durable trigger for the next sentinel lap.
				for (int y = floorY; y <= waterTop
						&& repaired < VISIBLE_WATER_VOID_REPAIR_MAX_BLOCKS_PER_CHUNK_PASS; y++) {
					BlockPos pos = cursor.set(x, y, z).immutable();
					BlockState state = chunk.getBlockState(pos);
					if (isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) continue;
					boolean repairableVoid = state.isAir()
							|| (state.is(Blocks.WATER) && !state.getFluidState().isSource());
					if (!repairableVoid) continue;
					if (first == null) { first = pos; firstOld = state; }
					setBlockStateRawSafe(world, chunk, pos, sourceWater);
					world.sendBlockUpdated(pos, state, sourceWater, 3);
					world.getChunkSource().getLightEngine().checkBlock(pos);
					repaired++;
					repairedColumn = true;
				}
				if (repairedColumn) columns++;
			}
		}
		if (repaired > 0) chunk.markUnsaved();
		return new VisibleWaterVoidRepair(repaired, columns, first, firstOld);
	}

	/**
	 * v253.125.18 continuous player-visible lighting sentinel.
	 *
	 * <p>The v253.125.17 runtime reproduced the residual ocean-floor defect after the
	 * bounded rejoin audit had finished. The server itself diagnosed chunks around the
	 * second location as deep-overbright, but the visible-repair telemetry remained at
	 * zero because neither the client sparse probe nor the expired join window promoted
	 * those chunks. This sentinel closes that admission gap without broadening the
	 * repair oracle: it only inspects chunks that are already resident, physically
	 * verified Canvas, and actually tracked by the player. A strict visible server proof
	 * must fail before the existing non-destructive finalizer is armed.</p>
	 */
	private static void drainContinuousVisibleLightSentinel(ServerLevel world) {
		if (world == null || world.players().isEmpty()) return;
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		int configuredRadius = Math.min(VISIBLE_LIGHT_SENTINEL_RADIUS_CAP_CHUNKS,
				Math.max(2, world.getServer().getPlayerList().getViewDistance() + 1));
		int side = configuredRadius * 2 + 1;
		int total = side * side;
		java.util.HashSet<java.util.UUID> livePlayers = new java.util.HashSet<>();

		for (net.minecraft.server.level.ServerPlayer player : world.players()) {
			if (player == null || player.level() != world) continue;
			java.util.UUID id = player.getUUID();
			livePlayers.add(id);
			ChunkPos center = player.chunkPosition();
			long cursor = lightFinalizerSession().visibleSentinelCursor.getOrDefault(id, 0L);
			int proofs = 0;
			int visited = 0;
			while (proofs < VISIBLE_LIGHT_SENTINEL_PROOFS_PER_TICK && visited < total) {
				int index = Math.floorMod((int)(cursor % total), total);
				cursor++;
				visited++;
				int dx = index % side - configuredRadius;
				int dz = index / side - configuredRadius;
				int cx = center.x() + dx, cz = center.z() + dz;
				LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				ChunkPos pos = chunk.getPos();
				if (!net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(world, pos).contains(player)) continue;
				if (net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos)
						!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) continue;
				if (!protectedData.isChunkProcessedPhysicallyVerified(pos)) continue;
				long packed = ChunkPos.pack(pos.x(), pos.z());
				long ddx = (long)pos.x() - center.x();
				long ddz = (long)pos.z() - center.z();
				long distanceChunksSq = ddx * ddx + ddz * ddz;

				// v253.125.54: do the narrow visible water-integrity repair even if this
				// chunk already has lighting debt.  A persisted physical seal from an older
				// build must not hide an obvious AIR/flowing-water hole at sea level.
				VisibleWaterVoidRepair waterRepair = repairVisibleCanonicalWaterVoids(world, chunk, OceanCanvasConfig.get());
				if (waterRepair.repairedBlocks() > 0) {
					protectedData.markChunkLightingDirty(pos);
					lightFinalizerSession().persistedAuditSession.clearAudited(packed);
					markVisibleLightPriority(packed, distanceChunksSq);
					net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(
							chunk, java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class));
					// Real block mutation, but deliberately do not grant the broad destructive
					// physical-repair permission: this path is add-only water restoration.
					scheduleLightSync(world, pos, false, true);
					net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.publish(
							new net.oceancanvas.mod.compat.OceanCanvasTerrainChange(
									world, pos, net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.CANVAS_WRITE));
					OceanCanvas.LOGGER.warn("(Ocean Canvas) VISIBLE-WATER-VOID-REPAIR build={} player={} chunk={},{} distanceChunksSq={} repairedBlocks={} repairedColumns={} first={} oldState={} action=restore-only-air-or-flowing-water-then-strict-light-reproof",
							net.oceancanvas.mod.OceanCanvas.VERSION, player.getGameProfile().name(), pos.x(), pos.z(),
							distanceChunksSq, waterRepair.repairedBlocks(), waterRepair.repairedColumns(),
							waterRepair.firstPosition(), waterRepair.firstOldState());
					proofs++;
					continue;
				}

				if (lightFinalizerSession().visibleLightPriority.contains(packed)) continue;
				boolean existingDebt = lightFinalizerSession().pendingTicks.containsKey(packed)
						|| lightRecoverySession().skyBackoffUntilTick.containsKey(packed)
						|| lightRecoverySession().pressureParkUntilTick.containsKey(packed);
				if (existingDebt) {
					// Do not pay for a duplicate strict proof here. Promotion is enough; the
					// existing debt will use the strict visible oracle on its next service.
					markVisibleLightPriority(packed, distanceChunksSq);
					continue;
				}
				SkyLightDiag sky = sampleCanonicalSurfaceSkyLight(world, chunk, true);
				proofs++;
				if (sky.healthy()) continue;
				markVisibleLightPriority(packed, distanceChunksSq);
				boolean alreadyPending = false;
				scheduleLightSync(world, pos, false);
				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-VISIBLE-SENTINEL-BAD build={} player={} chunk={},{} distanceChunksSq={} alreadyPending={} surfaceSky={}..{} waterSky={}..{} deepAnomalousLayers={} deepOverbrightColumns={} firstDeep={} actual={} depth={} action=promote-resident-tracked-chunk-to-visible-strict-finalizer",
						net.oceancanvas.mod.OceanCanvas.VERSION, player.getGameProfile().name(), pos.x(), pos.z(),
						distanceChunksSq, alreadyPending, sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax(),
						sky.deepAnomalousLayers(), sky.deepOverbrightColumns(), sky.firstDeepAnomaly(), sky.firstDeepActual(), sky.firstDeepDepth());
			}
			lightFinalizerSession().visibleSentinelCursor.put(id, cursor % Math.max(1, total));
		}
		lightFinalizerSession().visibleSentinelCursor.keySet().removeIf(id -> !livePlayers.contains(id));
	}

	/** Return the nearest currently tracking player's squared chunk distance, or MAX. */
	private static long nearestTrackingPlayerDistanceSq(ServerLevel world, ChunkPos pos) {
		long best = Long.MAX_VALUE;
		for (net.minecraft.server.level.ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(world, pos)) {
			if (player == null || player.level() != world) continue;
			ChunkPos pc = player.chunkPosition();
			long dx = (long)pos.x() - pc.x();
			long dz = (long)pos.z() - pc.z();
			best = Math.min(best, dx * dx + dz * dz);
		}
		return best;
	}

	private static void drainPersistedLightAudits(ServerLevel world) {
		if (lightFinalizerSession().persistedAuditSession.pendingEmpty()) return;
		// v253.125.24: persisted CERTIFICATE audits are correctness debt, but they
		// are lower priority than chunks that are already known to be uncertified.
		// The .23 soak re-proved/re-published 7,822 healthy persisted chunks while
		// thousands of real active/pressure-parked obligations were waiting; only one
		// persisted audit found a repair. That duplicate strict proof + packet work
		// consumed controller/heap budget and amplified chunk-load contention.
		// Visible correctness is still covered independently by the bounded rejoin
		// window + continuous sentinel above. Keep every persisted audit pending and
		// therefore completion-blocking, but do not spend on it until all known
		// uncertified active/backoff/pressure debt has drained. If an audit later finds
		// a bad field, schedule exactly one repair and yield again on the next tick.
		if (!lightFinalizerSession().pendingTicks.isEmpty()
				|| !lightRecoverySession().skyBackoffUntilTick.isEmpty()
				|| !lightRecoverySession().pressureParkUntilTick.isEmpty()) {
			lightTelemetrySession().LIGHT_DIAG_PERSISTED_AUDIT_PRIORITY_DEFERRALS.incrementAndGet();
			return;
		}
		// v253.73.14: load/rejoin audits are high-value near-field repair, but a
		// historical certified-world scan must not become another unbounded inlet.
		// Arm new nondestructive finalizers only below low-water; healthy proofs may
		// continue once admitted, and failed proofs remain queued/due rather than lost.
		int activeAtStart = lightFinalizerSession().pendingTicks.size();
		if (activeAtStart >= LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER) return;
		long now = world.getGameTime();
		int budget = Math.min(PERSISTED_LIGHT_AUDITS_PER_TICK,
				Math.max(1, LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER - activeAtStart));
		int claimLimit = budget;
		for (int claimed = 0; claimed < claimLimit; claimed++) {
			if (budget <= 0 || lightFinalizerSession().pendingTicks.size() >= LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER) break;
			OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry =
					lightFinalizerSession().persistedAuditSession.pollDue(now);
			if (entry == null) break;
			long packed = entry.packed();
			long due = entry.dueTick();
			// Any frontier quiet deadline has now elapsed. Drop the timestamp even if
			// the chunk unloaded; CHUNK_LOAD will schedule a fresh persisted proof.
			lightFinalizerSession().terrainLastMutationTick.remove(packed);
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			LevelChunk live = world.getChunkSource().getChunkNow(cx, cz);
			if (live == null) continue; // next CHUNK_LOAD will schedule it again
			ChunkPos pos = live.getPos();
			OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
			if (!protectedData.isChunkLightingVerified(pos)) continue;
			if (net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos)
					!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) continue;
			budget--;

			boolean playerVisibleLightTarget = lightFinalizerSession().visibleLightPriority.contains(packed);
			// v253.125.19: global certificate invariant. The same strict sparse
			// overbright proof applies even if no player has ever visited this chunk.
			SkyLightDiag sky = sampleCanonicalSurfaceSkyLight(world, live, true);
			if (!sky.healthy()) {
				long auditRepairs = lightTelemetrySession().LIGHT_DIAG_RESTART_AUDIT_REPAIRS.incrementAndGet();
				// v253.73.14: the supplied run found 995 unique historical certified
				// chunks with stale saved SKY while exploring. Preserve exact evidence for
				// the first cohort and periodic milestones without turning log I/O into part
				// of the repair workload. The cumulative summary remains authoritative.
				boolean milestone = auditRepairs <= 16L || (auditRepairs & 255L) == 0L;
				if (milestone) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-RESTART-AUDIT build={} chunk={},{} classification=PERSISTED_SKYLIGHT_MISMATCH aboveSky={}..{} waterSky={}..{} aboveNot15={} deepAnomalousLayers={} deepAnomalousColumns={} repairCount={} action=invalidate-certificate-and-run-nondestructive-finalizer",
						net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax(), sky.aboveNot15(), sky.deepAnomalousLayers(), sky.deepAnomalousColumns(), auditRepairs);
				} else {
					OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-RESTART-AUDIT build={} chunk={},{} classification=PERSISTED_SKYLIGHT_MISMATCH repairCount={} action=bounded-historical-repair",
						net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, auditRepairs);
				}
				scheduleLightSync(world, pos, false);
				// The newly scheduled uncertified repair now owns priority. Stop this
				// audit batch immediately; the top-of-method gate will resume certified
				// audits only after that real repair debt has retired.
				break;
			}

			lightFinalizerSession().persistedAuditSession.markAudited(packed);
			lightTelemetrySession().LIGHT_DIAG_RESTART_AUDIT_HEALTHY.incrementAndGet();
			pushChunkWithAuthoritativeLight(world, live);
			net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.publish(
					new net.oceancanvas.mod.compat.OceanCanvasTerrainChange(
							world, pos, net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.CANVAS_WRITE));
			lightTelemetrySession().LIGHT_DIAG_REJOIN_REPUBLISHES.incrementAndGet();
		}
	}

	private static void scheduleLightSync(ServerLevel world, ChunkPos pos, boolean terrainMutation) {
		// Historical call sites use one boolean because freshly authored terrain both
		// grants physical-repair permission and proves a real block mutation occurred.
		// Recovery code must use the four-argument overload below: "may repair" and
		// "did mutate" are different facts.
		scheduleLightSync(world, pos, terrainMutation, terrainMutation);
	}

	/**
	 * v253.125.10 separates repair PERMISSION from actual terrain MUTATION.
	 *
	 * <p>The v253.125.9 runtime handed thousands of PHYSICAL_AWARE recovery chunks to
	 * this method using {@code allowPhysicalRepair=true}. The old implementation
	 * interpreted that permission as proof that blocks had just changed: it reset the
	 * skylight recovery epoch, stamped a fresh terrain-mutation time, and re-armed up
	 * to eight canonical neighbours. That manufactured lighting fanout and repeatedly
	 * destroyed persistent-fault progress even when the recovered chunk was already
	 * physically canonical. Only a block/fluid write may set {@code terrainMutation}.
	 * A recovery target may still retain physical-repair permission without claiming
	 * that a mutation happened.</p>
	 */
	private static void scheduleLightSync(ServerLevel world, ChunkPos pos,
			boolean allowPhysicalRepair, boolean terrainMutation) {
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		protectedData.markChunkLightingDirty(pos);
		long packed = ChunkPos.pack(pos.x(), pos.z());
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		session.persistedAuditSession.clearAudited(packed);
		long nowTick = world.getGameTime();
		if (terrainMutation) {
			long previousGeneration = session.physicalMutationGenerationTick.put(packed, nowTick);
			if (previousGeneration != OceanCanvasPrimitiveLongLongMap.ABSENT && previousGeneration == nowTick) {
				// Several block/fluid callbacks in one server tick describe one final
				// physical epoch. Extending the quiet window is sufficient; resetting
				// proof/recovery and fan-out repeatedly cannot improve the end state.
				session.terrainLastMutationTick.put(packed, nowTick);
				if (allowPhysicalRepair) session.allowPhysicalRepair.add(packed);
				int current = session.pendingTicks.get(packed);
				if (current == OceanCanvasPrimitiveLongIntMap.ABSENT) armLightSyncEntry(pos, allowPhysicalRepair);
				else {
					session.pendingTicks.put(packed, Math.max(current, LIGHT_SYNC_INITIAL_DELAY_TICKS));
					ensureLightWorkQueued(packed);
					lightTelemetrySession().LIGHT_DIAG_DIRTY_COALESCED.incrementAndGet();
				}
				return;
			}
			resetSkyRecoveryForPhysicalMutation(packed);
		}
		armLightSyncEntry(pos, allowPhysicalRepair);
		if (terrainMutation) {
			session.terrainLastMutationTick.put(packed, nowTick);
			// Rolling/local convergence is justified only by a real adjacent block
			// mutation. Permission alone must never manufacture neighbour debt.
			rearmCanonicalNeighborLighting(world, pos);
		}
	}

	/**
	 * v253.125.30 client-visible repair admission. The .29 runtime proved that even
	 * a four-chunk networking drain can exceed its 3ms wall budget when one request
	 * performs a complete synchronous SKY proof. A client mismatch is already enough
	 * evidence to require revalidation, so admit the chunk to the existing visible,
	 * cooperative, fail-closed finalizer and return immediately. Healthy authoritative
	 * fields are re-proved and republished; bad fields follow normal repair escalation.
	 * No physical-repair permission is granted here.
	 */
	public static boolean requestTrackedClientLightReverification(ServerLevel world, LevelChunk chunk) {
		if (world == null || chunk == null) return false;
		ChunkPos pos = chunk.getPos();
		long packed = ChunkPos.pack(pos.x(), pos.z());
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		if (net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos)
				!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) return false;
		markVisibleLightPriority(packed, 0L);
		// Active/unverified physical work owns its own eventual authoritative publish.
		// Do not answer a visible mismatch by immediately resending a known-transient
		// chunk snapshot, and do not manufacture a light-only proof before physical seal.
		if (!protectedData.isChunkProcessedPhysicallyVerified(pos)) return true;
		boolean alreadyPending = lightFinalizerSession().pendingTicks.containsKey(packed)
				|| lightRecoverySession().skyBackoffUntilTick.containsKey(packed)
				|| lightRecoverySession().pressureParkUntilTick.containsKey(packed);
		if (!alreadyPending) scheduleLightSync(world, pos, false);
		OceanCanvas.LOGGER.debug("(Ocean Canvas) CLIENT-LIGHT-SERVER-REPAIR build={} chunk={},{} alreadyPending={} action=visible-priority-cooperative-reproof-and-authoritative-republish",
				net.oceancanvas.mod.OceanCanvas.VERSION, pos.x(), pos.z(), alreadyPending);
		return true;
	}

	private static void rearmCanonicalNeighborLighting(ServerLevel world, ChunkPos pos) {
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		var terrainStates = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				ChunkPos neighborPos = new ChunkPos(pos.x() + dx, pos.z() + dz);
				if (!protectedData.isChunkProcessedPhysicallyVerified(neighborPos)) continue;
				if (terrainStates.get(neighborPos)
						!= net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) continue;

				// v253.72.1: the 20k runtime disproved the v253.61.7 "verified forever"
				// assumption. The log recorded a previously healthy frontier chunk falling to
				// aboveSky=5..15 while millions of later-neighbor invalidations were skipped,
				// matching the chunk-column slabs visible in the renderer. Do not resurrect
				// the old O(neighbor^2) recursive relight storm: verified neighbors keep their
				// certificate and receive one debounced proof + authoritative republish. Only
				// an actually bad proof enters the existing light-only finalizer.
				if (protectedData.isChunkLightingVerified(neighborPos)) {
					scheduleVerifiedNeighborBoundaryAudit(world, neighborPos);
					continue;
				}
				protectedData.markChunkLightingDirty(neighborPos);
				rearmPendingLightSyncForBoundaryMutation(world, neighborPos);
			}
		}
	}

	/**
	 * Structure placement/relocation is different from ordinary Canvas carving: it
	 * can ADD opaque blocks after a neighbor was already lighting-certified. Re-arm
	 * the complete structure footprint plus a one-chunk propagation ring, regardless
	 * of the previous lighting certificate, but keep the pass light-only so it cannot
	 * canonicalize legitimate structure blocks.
	 */
	private static void rearmLightingAfterStructureMutation(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.BoundingBox bounds) {
		int minChunkX = Math.floorDiv(bounds.minX(), 16) - 1;
		int maxChunkX = Math.floorDiv(bounds.maxX(), 16) + 1;
		int minChunkZ = Math.floorDiv(bounds.minZ(), 16) - 1;
		int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16) + 1;
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		var terrainStates = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world);
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				ChunkPos pos = new ChunkPos(cx, cz);
				if (!protectedData.isChunkProcessedPhysicallyVerified(pos)) continue;
				if (terrainStates.get(pos) != net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) continue;
				long packed = ChunkPos.pack(cx, cz);
				protectedData.markChunkLightingDirty(pos);
				lightFinalizerSession().persistedAuditSession.clearAudited(packed);
				resetSkyRecoveryForPhysicalMutation(packed);
				armLightSyncEntry(pos, false);
				lightFinalizerSession().terrainLastMutationTick.put(packed, world.getGameTime());
			}
		}
	}

	/**
	 * Coalesce a real adjacent terrain mutation into a neighbor that has not yet
	 * completed lighting. Stage 0 has not seeded its final sweep yet, so restarting
	 * it is pure duplicate work; just extend the short quiet window. If the chunk is
	 * already settling/verifying, restart that chunk only. Crucially, this method
	 * never cascades into the neighbor's neighbors.
	 */
	private static void rearmPendingLightSyncForBoundaryMutation(ServerLevel world, ChunkPos pos) {
		long packed = ChunkPos.pack(pos.x(), pos.z());
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		long nowTick = world.getGameTime();
		long previousGeneration = session.boundaryMutationGenerationTick.put(packed, nowTick);
		int current = session.pendingTicks.get(packed);
		if (previousGeneration != OceanCanvasPrimitiveLongLongMap.ABSENT && previousGeneration == nowTick && current != OceanCanvasPrimitiveLongIntMap.ABSENT) {
			// Multiple adjacent chunk retirements in the same tick can report the
			// same final boundary epoch. One re-arm proves that epoch; duplicates
			// only extend the debounce and must not restart staged proof again.
			session.pendingTicks.put(packed, Math.max(current, LIGHT_SYNC_VERIFY_RETRY_TICKS));
			ensureLightWorkQueued(packed);
			lightTelemetrySession().LIGHT_DIAG_DIRTY_COALESCED.incrementAndGet();
			return;
		}
		if (current != OceanCanvasPrimitiveLongIntMap.ABSENT) ensureLightWorkQueued(packed);
		if (current == OceanCanvasPrimitiveLongIntMap.ABSENT) {
			// v253.73.10: this neighbor has no live finalizer epoch, so create one.
			// armLightSyncEntry() performs the normal fresh-epoch reset.
			armLightSyncEntry(pos, false);
			return;
		}
		lightTelemetrySession().LIGHT_DIAG_BOUNDARY_RECOVERY_EPOCH_PRESERVED.incrementAndGet();
		int pass = lightFinalizerSession().pendingPasses.getOrDefault(packed, 0);
		if (pass <= 0) {
			// Boundary churn while the neighbor is still in its quiet/initial stage
			// only extends the debounce. Do NOT reset the deep-SKY recovery epoch:
			// the neighbor's own blocks did not physically mutate.
			lightFinalizerSession().pendingTicks.put(packed, Math.max(current, LIGHT_SYNC_VERIFY_RETRY_TICKS));
			lightTelemetrySession().LIGHT_DIAG_DIRTY_COALESCED.incrementAndGet();
			return;
		}
		// v253.73.10 forever-world fix: an adjacent terrain carve invalidates this
		// neighbor's in-flight stage/fingerprint, but it is not a physical mutation of
		// the neighbor itself. v253.73.9 called resetSkyRecoveryForPhysicalMutation()
		// here and also erased lightRecoverySession().verifyEscalations, so a moving frontier could
		// repeatedly forgive the same historical deep-SKY failure before it reached the
		// finite persistent-backoff ladder. Preserve scrub/public-recovery/hard-reset
		// counts and verification escalation across boundary-only rearms. This lets old
		// recovery debt converge or become dormant instead of pinning active pressure.
		lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
		lightFinalizerSession().pendingPasses.put(packed, 0);
		lightFinalizerSession().stagedBlockFingerprint.remove(packed);
		clearIncrementalDeepRepairState(packed);
		lightRecoverySession().hardSkyResetRadius1.remove(packed);
		lightTelemetrySession().LIGHT_DIAG_DIRTY_REARMS.incrementAndGet();
	}

	private static void ensureLightWorkQueued(long packed) {
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		boolean visible = session.visibleLightPriority.contains(packed);
		boolean terrain = !visible && session.allowPhysicalRepair.contains(packed);
		if (visible) {
			session.terrainWorkMembership.remove(packed);
			session.pendingWorkMembership.remove(packed);
			session.visibleWorkOrder.offerIfMembershipAdded(session.visibleWorkMembership, packed);
			return;
		}
		session.visibleWorkMembership.remove(packed);
		if (terrain) {
			session.pendingWorkMembership.remove(packed);
			session.terrainWorkOrder.offerIfMembershipAdded(session.terrainWorkMembership, packed);
			return;
		}
		session.terrainWorkMembership.remove(packed);
		session.pendingWorkOrder.offerIfMembershipAdded(session.pendingWorkMembership, packed);
	}

	private static void compactSchedulerLaneIfBloated(
			OceanCanvasPrimitiveLongQueue queue, OceanCanvasPrimitiveLongSet membership) {
		int queued = queue.size();
		int live = membership.size();
		if (queued == 0) return;
		if (live == 0) {
			queue.clear();
			lightFinalizerSession().schedulerStaleNodesDropped.addAndGet(queued);
			lightFinalizerSession().schedulerLaneCompactions.incrementAndGet();
			return;
		}
		if (queued <= live * LIGHT_SCHEDULER_LANE_COMPACT_RATIO + LIGHT_SCHEDULER_LANE_COMPACT_SLACK) return;
		int dropped = queue.rebuildFromMembership(membership);
		if (dropped > 0) {
			lightFinalizerSession().schedulerStaleNodesDropped.addAndGet(dropped);
			lightFinalizerSession().schedulerLaneCompactions.incrementAndGet();
		}
	}

	private static void removeLightWorkLaneMembership(long packed) {
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		session.visibleWorkMembership.remove(packed);
		session.terrainWorkMembership.remove(packed);
		session.pendingWorkMembership.remove(packed);
	}

	private static void markLightDebtMembershipMutation() {
		lightFinalizerSession().debtMembershipGeneration.incrementAndGet();
	}

	private static void armLightSyncEntry(ChunkPos pos, boolean allowPhysicalRepair) {
		long packed = ChunkPos.pack(pos.x(), pos.z());
		if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) {
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.add(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(packed);
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
		} else if (pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed)) {
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.add(packed);
		}
		// v253.125.5: a non-physical re-arm may accelerate dormant work because the
		// surrounding light context changed or the tracking client observed a fault, but
		// it must NOT erase strict-failure identity. Boundary churn previously reset the
		// scrub/public-recovery/escalation/backoff streak every time a dormant entry was
		// re-armed, preventing genuinely persistent faults from ever reaching quarantine.
		// A real block mutation already calls resetSkyRecoveryForPhysicalMutation() first.
		lightRecoverySession().skyBackoffUntilTick.remove(packed);
		lightRecoverySession().pressureParkUntilTick.remove(packed);
		// Seed the liveness stamp on first arm so an escape can never fire before the
		// finalizer has had any work at all.
		lightFinalizerSession().lastRetirementNs.compareAndSet(0L, System.nanoTime());
		if (allowPhysicalRepair) lightFinalizerSession().allowPhysicalRepair.add(packed);
		int previousDelay = lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_INITIAL_DELAY_TICKS);
		ensureLightWorkQueued(packed);
		if (previousDelay == OceanCanvasPrimitiveLongIntMap.ABSENT) {
			markLightDebtMembershipMutation();
			lightTelemetrySession().LIGHT_DIAG_DIRTY_ADDS.incrementAndGet();
		} else lightTelemetrySession().LIGHT_DIAG_DIRTY_REARMS.incrementAndGet();
		lightFinalizerSession().pendingPasses.put(packed, 0);
		lightFinalizerSession().stagedBlockFingerprint.remove(packed);
		lightRecoverySession().hardSkyResetRadius1.remove(packed);
	}

	private static void resetSkyRecoveryForPhysicalMutation(long packed) {
		// A real block change is the ONLY thing that releases quarantine. A generic
		// re-arm must not, or a chunk on a busy boundary would re-enter the hot path
		// forever - which is the churn quarantine exists to stop.
		if (lightRecoverySession().skyQuarantine.remove(packed)) lightTelemetrySession().LIGHT_DIAG_QUARANTINE_RELEASES.incrementAndGet();
		lightRecoverySession().deepZeroScrubCounts.remove(packed);
		// Visible dense/cluster budgets are per physical epoch too. A genuine block
		// change creates a new light graph and must restore their bounded attempts.
		lightRecoverySession().visibleDeepDenseRepairCounts.remove(packed);
		lightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);
		clearIncrementalDeepRepairState(packed);
		lightRecoverySession().pathologyHotspotLevel.remove(packed);
		lightRecoverySession().deepZeroPublicRecoveryCounts.remove(packed);
		lightRecoverySession().deepZeroPublicRecoveryRetryAfterTick.remove(packed);
		lightRecoverySession().verifyEscalations.remove(packed);
		lightRecoverySession().hardSkyResetRadius1.remove(packed);
		lightRecoverySession().hardSkyResetCounts.remove(packed);
		lightRecoverySession().skyBackoffUntilTick.remove(packed);
		lightRecoverySession().pressureParkUntilTick.remove(packed);
		lightFinalizerSession().retryLedger.clearBackoffStreak(packed);
		lightTelemetrySession().LIGHT_DIAG_LAST_DETAIL_WARN_TICK.remove(packed);
	}

	/** v253.72.9: compute a bounded, deterministic retry delay for a persistent
	 * light fault. Repeated failures back off exponentially while chunk-key jitter
	 * spreads a large cohort across real server ticks. */
	private static int nextPersistentSkyBackoffTicks(long packed) {
		int streak = lightFinalizerSession().retryLedger.incrementBackoffStreak(packed, 16);
		int shift = Math.min(LIGHT_PERSISTENT_SKY_BACKOFF_MAX_SHIFT, Math.max(0, streak - 1));
		int base = Math.min(LIGHT_PERSISTENT_SKY_BACKOFF_MAX_TICKS, LIGHT_PERSISTENT_SKY_BACKOFF_TICKS << shift);
		long mixed = packed ^ (packed >>> 33) ^ (0x9E3779B97F4A7C15L * (long)streak);
		int jitter = Math.floorMod((int)(mixed ^ (mixed >>> 32)), LIGHT_PERSISTENT_SKY_BACKOFF_JITTER_TICKS);
		return Math.min(LIGHT_PERSISTENT_SKY_BACKOFF_MAX_TICKS, base + jitter);
	}

	/** Move a persistent failure out of the active finalizer map. Completion still
	 * sees it through lightRecoverySession().skyBackoffUntilTick, but it owns no residency ticket
	 * and consumes no per-tick verifier budget until its retry becomes due. */
	private static int deferPersistentSkyRepair(ServerLevel world, long packed) {
		int delay = nextPersistentSkyBackoffTicks(packed);
		// v253.73.19: a fault that has now been deferred this many times in a row has
		// run the full scrub / public-recovery / source-reseed / hard-reset ladder to
		// exhaustion more than once. Move it to the quarantine cadence. It is still
		// uncertified debt and still blocks completion - it just stops paying for a
		// full strict proof every few minutes at the expense of chunks that can pass.
		int streak = lightFinalizerSession().retryLedger.backoffStreak(packed);
		if (streak >= LIGHT_SKY_QUARANTINE_DEFER_STREAK) {
			delay = LIGHT_SKY_QUARANTINE_RETRY_TICKS;
			if (lightRecoverySession().skyQuarantine.add(packed)) {
				long n = lightTelemetrySession().LIGHT_DIAG_QUARANTINED.incrementAndGet();
				if (n <= 8L || (n & 127L) == 0L) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-SKY-QUARANTINE build={} chunk={},{} streak={} escalation={} hardResets={} quarantined={} action=uncertified-debt-moved-to-slow-lane; chunk is NOT certified and still blocks completion",
							net.oceancanvas.mod.OceanCanvas.VERSION, ChunkPos.getX(packed), ChunkPos.getZ(packed), streak,
							lightRecoverySession().verifyEscalations.getOrDefault(packed, 0),
							lightRecoverySession().hardSkyResetCounts.getOrDefault(packed, 0), lightRecoverySession().skyQuarantine.size());
				}
				logQuarantineLightProfile(world, packed);
			}
		}
		long due = world.getGameTime() + (long)delay;
		long previous = lightRecoverySession().skyBackoffUntilTick.put(packed, due);
		lightFinalizerSession().pendingTicks.remove(packed);
		if (pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed)) {
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
		}
		if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) {
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed);
		}
		releaseLightRelightResidencyTicket(world, packed);
		if (previous == OceanCanvasPrimitiveLongLongMap.ABSENT) {
			if (lightRecoverySession().skyQuarantine.contains(packed)) lightFinalizerSession().retryLedger.offerQuarantine(packed, due);
			else lightFinalizerSession().retryLedger.offerOrdinary(packed, due);
		}
		lightTelemetrySession().LIGHT_DIAG_PERSISTENT_DEFERRED.incrementAndGet();
		return delay;
	}


	/** Immutable read-only snapshot of one chunk's live source table and a fresh
	 * recomputation from the chunk's current block states. The recomputed table is
	 * never installed into the chunk; it exists only to answer whether the live
	 * metadata agrees with what vanilla would derive right now. */
	private record SkySourceTables(LevelChunk chunk, ChunkSkyLightSources live, ChunkSkyLightSources recomputed) {}

	private record SkySourceEvidence(boolean loaded, int liveLowestSourceY, int recomputedLowestSourceY) {
		boolean mismatch() { return loaded && liveLowestSourceY != recomputedLowestSourceY; }
	}

	private static SkySourceTables probeSkySourceTables(
			ServerLevel world,
			int blockX,
			int blockZ,
			Long2ObjectOpenHashMap<SkySourceTables> cache) {
		int cx = blockX >> 4, cz = blockZ >> 4;
		long chunkKey = ChunkPos.pack(cx, cz);
		if (cache.containsKey(chunkKey)) return cache.get(chunkKey);
		LevelChunk c = world.getChunkSource().getChunkNow(cx, cz);
		if (c == null) {
			cache.put(chunkKey, null);
			return null;
		}
		ChunkSkyLightSources recomputed = new ChunkSkyLightSources(c);
		recomputed.fillFrom(c);
		SkySourceTables tables = new SkySourceTables(c, c.getSkyLightSources(), recomputed);
		cache.put(chunkKey, tables);
		return tables;
	}

	private static SkySourceEvidence probeSkySourceEvidence(
			ServerLevel world,
			int blockX,
			int blockZ,
			Long2ObjectOpenHashMap<SkySourceTables> cache) {
		SkySourceTables tables = probeSkySourceTables(world, blockX, blockZ, cache);
		if (tables == null) return new SkySourceEvidence(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
		int localX = Math.floorMod(blockX, 16), localZ = Math.floorMod(blockZ, 16);
		return new SkySourceEvidence(true,
				tables.live().getLowestSourceY(localX, localZ),
				tables.recomputed().getLowestSourceY(localX, localZ));
	}

	/**
	 * v253.73.20 pathological-chunk probe.
	 *
	 * <p>v253.73.19 classified the light profile and then inferred that a deep 15
	 * implied a stale ChunkSkyLightSources table. That was not strong enough, and
	 * its explanatory comment was factually unsafe: Ocean Canvas' current writer
	 * calls {@code chunk.setBlockState(...)} on a {@link LevelChunk}, so we must not
	 * assume the source table was bypassed merely because the helper is named
	 * "raw".</p>
	 *
	 * <p>This probe now compares the exact live {@link ChunkSkyLightSources} entry
	 * against a fresh, read-only {@link ChunkSkyLightSources#fillFrom} recomputation
	 * from the current blocks. The temporary table is never installed. This gives a
	 * direct fork:</p>
	 *
	 * <ul>
	 * <li>{@code STALE_SOURCE_TABLE}: live and recomputed source metadata disagree.</li>
	 * <li>{@code NEIGHBOR_STALE_SOURCE_TABLE}: the centre agrees but a brighter
	 *     same-depth neighbour carries stale source metadata.</li>
	 * <li>{@code RECOMPUTED_SOURCE_TABLE_OPEN}: live and recomputed agree, and the
	 *     freshly-derived table itself says the sampled depth is still sky-source
	 *     reachable. That points at the attenuation/block-state assumption, not stale
	 *     metadata.</li>
	 * <li>{@code PROPAGATION_OR_STORAGE_STALE}: source metadata agrees with current
	 *     blocks but the actual field does not, so investigate queued decrease work
	 *     or stored SKY data.</li>
	 * <li>{@code FIELD_CANONICAL_VALIDATOR_FAULT}: the complete sampled water profile
	 *     already equals the validator's canonical 14,13,...,0 field.</li>
	 * </ul>
	 *
	 * <p>The old shape labels remain as {@code profileShape}; they describe what the
	 * field looks like, not what caused it. Read-only with respect to the world: only
	 * temporary diagnostic objects are populated.</p>
	 */
	private static void logQuarantineLightProfile(ServerLevel world, long packed) {
		if (lightTelemetrySession().LIGHT_QUARANTINE_PROBES.get() >= LIGHT_QUARANTINE_MAX_PROBES) return;
		int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
		LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) return;
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int probed = 0;
		Long2ObjectOpenHashMap<SkySourceTables> sourceTableCache = new Long2ObjectOpenHashMap<>();
		for (int lx = 2; lx < 16 && probed < LIGHT_QUARANTINE_PROBE_COLUMNS; lx += 5) {
			for (int lz = 2; lz < 16 && probed < LIGHT_QUARANTINE_PROBE_COLUMNS; lz += 5) {
				int x = chunk.getPos().getMinBlockX() + lx, z = chunk.getPos().getMinBlockZ() + lz;
				int floorY = waterTop;
				while (floorY > world.getMinY() + 1
						&& chunk.getBlockState(new BlockPos(x, floorY - 1, z)).is(Blocks.WATER)) floorY--;
				int depthBlocks = waterTop - floorY;
				if (depthBlocks < 16) continue;
				if (!hasDirectCanonicalWaterShaft(world, chunk, x, z, floorY, waterTop)) continue;

				StringBuilder profile = new StringBuilder();
				int previous = -1, firstPlateauY = Integer.MIN_VALUE, plateauValue = -1;
				int stillFifteenDepth = -1;
				int firstProfileMismatchY = Integer.MIN_VALUE, firstOverbrightY = Integer.MIN_VALUE;
				int evidenceSky = -1;
				boolean canonicalProfile = true;
				for (int y = waterTop; y >= floorY; y--) {
					int sky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, new BlockPos(x, y, z));
					int depth = waterTop - y;
					int expected = maximumPlainWaterSkyAtDepth(depth);
					if (profile.length() > 0) profile.append(',');
					profile.append(y).append(':').append(sky);
					if (sky >= 15) stillFifteenDepth = depth;
					if (sky != expected) {
						canonicalProfile = false;
						if (firstProfileMismatchY == Integer.MIN_VALUE) firstProfileMismatchY = y;
					}
					if (sky > expected && firstOverbrightY == Integer.MIN_VALUE) firstOverbrightY = y;
					if (previous >= 0 && sky == previous && sky > 0 && firstPlateauY == Integer.MIN_VALUE) {
						firstPlateauY = y;
						plateauValue = sky;
					}
					previous = sky;
				}
				int floorSky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, new BlockPos(x, floorY, z));
				int canonicalFloorSky = maximumPlainWaterSkyAtDepth(waterTop - floorY);
				int evidenceY = firstOverbrightY != Integer.MIN_VALUE ? firstOverbrightY
						: firstProfileMismatchY != Integer.MIN_VALUE ? firstProfileMismatchY
						: firstPlateauY != Integer.MIN_VALUE ? firstPlateauY : floorY;
				evidenceSky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, new BlockPos(x, evidenceY, z));

				SkySourceEvidence centerSource = probeSkySourceEvidence(world, x, z, sourceTableCache);
				boolean sourceTableMismatch = centerSource.mismatch();
				boolean recomputedSourceOpenAtEvidence = centerSource.loaded()
						&& centerSource.recomputedLowestSourceY() <= evidenceY;

				int brightestNeighbour = -1;
				String brightestSide = "none";
				boolean brighterNeighbourSourceMismatch = false;
				StringBuilder neighborEvidence = new StringBuilder();
				int[][] sides = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
				String[] names = new String[]{"+x","-x","+z","-z"};
				for (int i = 0; i < sides.length; i++) {
					int nx = x + sides[i][0], nz = z + sides[i][1];
					int nSky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY,
							new BlockPos(nx, evidenceY, nz));
					SkySourceEvidence nSource = probeSkySourceEvidence(world, nx, nz, sourceTableCache);
					if (nSky > brightestNeighbour) { brightestNeighbour = nSky; brightestSide = names[i]; }
					if (nSky > evidenceSky && nSource.mismatch()) brighterNeighbourSourceMismatch = true;
					if (neighborEvidence.length() > 0) neighborEvidence.append(';');
					neighborEvidence.append(names[i]).append(":sky=").append(nSky)
							.append(",liveSourceY=").append(nSource.loaded() ? Integer.toString(nSource.liveLowestSourceY()) : "unloaded")
							.append(",recomputedSourceY=").append(nSource.loaded() ? Integer.toString(nSource.recomputedLowestSourceY()) : "unloaded")
							.append(",mismatch=").append(nSource.mismatch());
				}

				String profileShape;
				if (canonicalProfile) profileShape = "CANONICAL";
				else if (stillFifteenDepth >= 4) profileShape = "SOURCE_TABLE_OPEN_COLUMN";
				else if (brightestNeighbour > evidenceSky) profileShape = "LATERAL_INFLOW";
				else if (firstPlateauY != Integer.MIN_VALUE) profileShape = "DECAY_PLATEAU";
				else profileShape = "UNCLASSIFIED";

				String classification;
				if (sourceTableMismatch) classification = "STALE_SOURCE_TABLE";
				else if (brighterNeighbourSourceMismatch) classification = "NEIGHBOR_STALE_SOURCE_TABLE";
				else if (canonicalProfile && floorSky == canonicalFloorSky) classification = "FIELD_CANONICAL_VALIDATOR_FAULT";
				else if (recomputedSourceOpenAtEvidence) classification = "RECOMPUTED_SOURCE_TABLE_OPEN";
				else classification = "PROPAGATION_OR_STORAGE_STALE";

				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-QUARANTINE-PROBE build={} chunk={},{} column={},{} waterTop={} floorY={} waterDepth={} evidenceY={} evidenceSky={} floorSky={} canonicalFloorSky={} canonicalProfile={} liveLowestSourceY={} recomputedLowestSourceY={} sourceTableMismatch={} recomputedSourceOpenAtEvidence={} sky15DownToDepth={} firstProfileMismatchY={} firstOverbrightY={} firstPlateauY={} plateauValue={} brightestNeighbourAtEvidence={}({}) classification={} profileShape={} neighborEvidence=[{}] profile=[{}]",
						net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, x, z, waterTop, floorY, depthBlocks,
						evidenceY, evidenceSky, floorSky, canonicalFloorSky, canonicalProfile,
						centerSource.loaded() ? Integer.toString(centerSource.liveLowestSourceY()) : "unloaded",
						centerSource.loaded() ? Integer.toString(centerSource.recomputedLowestSourceY()) : "unloaded",
						sourceTableMismatch, recomputedSourceOpenAtEvidence, stillFifteenDepth,
						firstProfileMismatchY, firstOverbrightY, firstPlateauY, plateauValue,
						brightestNeighbour, brightestSide, classification, profileShape, neighborEvidence, profile);
				probed++;
			}
		}
		if (probed > 0) lightTelemetrySession().LIGHT_QUARANTINE_PROBES.incrementAndGet();
	}

	/**
	 * v253.125.10 drain healthy resident LIGHT_ONLY pressure debt without waking it
	 * back into the saturated active finalizer. This is deliberately limited to
	 * already-loaded chunks and a tiny per-tick proof budget; it never force-loads,
	 * never grants physical repair, and uses the same strict current-field proof as
	 * the visible rejoin shortcut.
	 */
	private static int fastCertifyResidentPressureParked(ServerLevel world, int maxProofs) {
		if (world == null || maxProofs <= 0 || lightRecoverySession().pressureParkUntilTick.isEmpty()) return 0;
		long now = world.getGameTime();
		int attempted = 0, completed = 0;
		// v253.125.35: due-time heap means not-yet-due parks are invisible to this
		// tick instead of being poll/reoffered through a 64-node rotating scan.
		while (attempted < maxProofs) {
			OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry = lightFinalizerSession().retryLedger.pollDuePressurePark(now);
			if (entry == null) break;
			long packed = entry.packed();
			long due = lightRecoverySession().pressureParkUntilTick.get(packed);
			if (due == OceanCanvasPrimitiveLongLongMap.ABSENT || due != entry.dueTick()) continue; // stale heap node
			boolean physical = pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed);
			boolean trackedLightOnly = !physical && pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed);
			boolean genericLightOnly = !physical && !trackedLightOnly
					&& !lightFinalizerSession().allowPhysicalRepair.contains(packed)
					&& !pregenSession().PREGEN_TARGET_CHUNKS.contains(packed);
			boolean lightOnly = trackedLightOnly || genericLightOnly;
			if (!lightOnly) continue;
			if (genericLightOnly
					&& net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets() > 0) {
				long postponed = now + 40L;
				if (lightRecoverySession().pressureParkUntilTick.replace(packed, due, postponed))
					lightFinalizerSession().retryLedger.offerPressurePark(packed, postponed);
				continue;
			}
			LevelChunk live = world.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed));
			if (live == null) {
				long postponed = now + 20L;
				if (lightRecoverySession().pressureParkUntilTick.replace(packed, due, postponed))
					lightFinalizerSession().retryLedger.offerPressurePark(packed, postponed);
				continue;
			}
			attempted++;
			if (tryFastCertifyResidentCanvasLighting(
					world, live, false, Long.MAX_VALUE, "PRESSURE_PARK_LIGHT_ONLY")) {
				lightTelemetrySession().LIGHT_DIAG_PRESSURE_FAST_CERTIFIED.incrementAndGet();
				completed++;
				continue;
			}
			// The strict proof did not pass or the local boundary was unsafe. Keep the
			// node dormant but do not burn scheduler cycles again immediately.
			long postponed = now + 20L;
			if (lightRecoverySession().pressureParkUntilTick.replace(packed, due, postponed))
				lightFinalizerSession().retryLedger.offerPressurePark(packed, postponed);
		}
		return completed;
	}

	/**
	 * v253.125.5 reactivates scheduler-only pressure parks strictly inside the same
	 * LIGHT_ONLY/PHYSICAL_AWARE windows that caused them to be parked. This prevents
	 * the old wake->repark loop: pressure parks are not scanned by the persistent-fault
	 * lane and therefore cannot consume its four-per-tick budget or advance quarantine
	 * identity. Stale queue nodes are harmless and discarded when their map entry is
	 * absent.
	 */
	public static int wakePressureParkedRecoveryForPressure(
			ServerLevel world,
			int lightOnlyTargetActive,
			int physicalTargetActive,
			int maxLightOnlyWake,
			int maxPhysicalWake) {
		if (world == null || lightRecoverySession().pressureParkUntilTick.isEmpty()) return 0;
		int fastCompleted = fastCertifyResidentPressureParked(world, FAST_PRESSURE_PROOFS_PER_TICK);
		if (lightRecoverySession().pressureParkUntilTick.isEmpty()) return fastCompleted;
		if (maxLightOnlyWake <= 0 && maxPhysicalWake <= 0) return fastCompleted;
		int globalHeadroom = Math.max(0,
				LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER - lightFinalizerSession().pendingTicks.size());
		if (globalHeadroom <= 0) return fastCompleted;
		int lightAvailable = Math.max(0, lightOnlyTargetActive - activeLightOnlyRecoveryWorkCount());
		int physicalAvailable = Math.max(0, physicalTargetActive - activePhysicalRecoveryWorkCount());
		int lightBudget = Math.min(Math.max(0, maxLightOnlyWake), lightAvailable);
		int physicalBudget = Math.min(Math.max(0, maxPhysicalWake), physicalAvailable);
		if (lightBudget <= 0 && physicalBudget <= 0) return fastCompleted;

		long now = world.getGameTime();
		int woken = 0, lightWoken = 0, physicalWoken = 0;
		while (woken < globalHeadroom && (lightWoken < lightBudget || physicalWoken < physicalBudget)) {
			OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry = lightFinalizerSession().retryLedger.pollDuePressurePark(now);
			if (entry == null) break;
			long packed = entry.packed();
			long due = lightRecoverySession().pressureParkUntilTick.get(packed);
			if (due == OceanCanvasPrimitiveLongLongMap.ABSENT || due != entry.dueTick()) continue;

			boolean physical = pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed);
			boolean trackedLightOnly = !physical && pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed);
			boolean genericLightOnly = !physical && !trackedLightOnly
					&& !lightFinalizerSession().allowPhysicalRepair.contains(packed)
					&& !pregenSession().PREGEN_TARGET_CHUNKS.contains(packed);
			boolean lightOnly = trackedLightOnly || genericLightOnly;
			if (!physical && !lightOnly) {
				if (lightRecoverySession().pressureParkUntilTick.remove(packed, due)) markLightDebtMembershipMutation();
				continue;
			}
			if (genericLightOnly
					&& net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets() > 0) {
				long postponed = now + 40L;
				if (lightRecoverySession().pressureParkUntilTick.replace(packed, due, postponed))
					lightFinalizerSession().retryLedger.offerPressurePark(packed, postponed);
				continue;
			}
			if ((physical && physicalWoken >= physicalBudget) || (lightOnly && lightWoken >= lightBudget)) {
				long postponed = now + 5L;
				if (lightRecoverySession().pressureParkUntilTick.replace(packed, due, postponed))
					lightFinalizerSession().retryLedger.offerPressurePark(packed, postponed);
				continue;
			}
			if (!lightRecoverySession().pressureParkUntilTick.remove(packed, due)) continue;
			activateDormantLightRepair(world, packed, false);
			if (physical) physicalWoken++; else lightWoken++;
			woken++;
		}
		if (woken > 0) lightTelemetrySession().LIGHT_DIAG_FOREVER_WORLD_PRESSURE_WAKES.addAndGet(woken);
		return fastCompleted + woken;
	}

	/** Shared activation mechanics for dormant work. Persistent-fault telemetry is
	 * incremented only for a real strict-fault backoff wake, never for pressure parks. */
	private static void activateDormantLightRepair(ServerLevel world, long packed, boolean persistentFaultWake) {
		lightFinalizerSession().pendingTicks.put(packed, 1);
		ensureLightWorkQueued(packed);
		if (pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) {
			pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.add(packed);
		} else if (pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed)) {
			pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.add(packed);
		}
		lightFinalizerSession().relightStartedNs.put(packed, System.nanoTime());
		if (persistentFaultWake) lightTelemetrySession().LIGHT_DIAG_PERSISTENT_WOKEN.incrementAndGet();
	}

	/** Activate one dormant persistent-light entry after its due-time map entry has
	 * been atomically claimed. Shared by the ordinary and quarantine queues so the
	 * two lanes differ only in admission policy, not correctness state. */
	private static void activatePersistentSkyRepair(ServerLevel world, long packed) {
		activateDormantLightRepair(world, packed, true);
	}

	/**
	 * Wake dormant persistent repairs without allowing quarantine population to
	 * affect ordinary retry service.
	 *
	 * <p>v253.73.20 fixes a v253.73.19 mechanics bug: quarantine was tagged but
	 * still stored in the ordinary queue, so a quarantine wake consumed the same
	 * `woken` budget and a large due quarantine cohort could consume the bounded
	 * scan before ordinary debt was reached. The queues are now physically separate.
	 * Ordinary retries retain their 4/tick lane; quarantine gets at most one wake
	 * per 100 ticks and may never consume an ordinary wake slot.</p>
	 */
	private static void wakeDuePersistentSkyRepairs(ServerLevel world) {
		if (lightFinalizerSession().retryLedger.queuesEmpty()) return;

		int active = lightFinalizerSession().pendingTicks.size();
		int lowWater = LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER;
		int wakeHeadroom = Math.max(0, lowWater - active);
		int ordinaryWakeBudget = Math.min(LIGHT_PERSISTENT_WAKE_BUDGET_PER_TICK, wakeHeadroom);
		if (ordinaryWakeBudget <= 0) {
			long lastRetirement = lightFinalizerSession().lastRetirementNs.get();
			if (lastRetirement != 0L && System.nanoTime() - lastRetirement >= LIGHT_FINALIZER_STALL_ESCAPE_NS) {
				if (lightTelemetrySession().LIGHT_DIAG_FINALIZER_STALL_ESCAPES.getAndIncrement() % 200L == 0L) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-FINALIZER STALL-ESCAPE build={} active={} lowWater={} persistentQueued={} quarantineQueued={} tickets={}/{} stalledSeconds={} action=admit-one-ordinary-dormant-retry-despite-pressure-reserve",
							net.oceancanvas.mod.OceanCanvas.VERSION, active, lowWater,
							lightFinalizerSession().retryLedger.ordinarySize(), lightFinalizerSession().retryLedger.quarantineSize(),
							lightFinalizerSession().relightResidencyLedger.activeCount(), LIGHT_RELIGHT_RESIDENCY_TICKET_MAX,
							(System.nanoTime() - lastRetirement) / 1_000_000_000L);
				}
				ordinaryWakeBudget = 1;
			} else {
				lightTelemetrySession().LIGHT_DIAG_PERSISTENT_WAKE_PRESSURE_HOLDS.incrementAndGet();
				return;
			}
		}

		long now = world.getGameTime();
		int ordinaryWoken = 0;
		while (ordinaryWoken < ordinaryWakeBudget) {
			OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry = lightFinalizerSession().retryLedger.pollDueOrdinary(now);
			if (entry == null) break;
			long packed = entry.packed();
			long due = lightRecoverySession().skyBackoffUntilTick.get(packed);
			if (due == OceanCanvasPrimitiveLongLongMap.ABSENT || due != entry.dueTick()) continue;
			if (lightRecoverySession().skyQuarantine.contains(packed)) {
				lightFinalizerSession().retryLedger.offerQuarantine(packed, due);
				continue;
			}
			if (!lightRecoverySession().skyBackoffUntilTick.remove(packed, due)) continue;
			activatePersistentSkyRepair(world, packed);
			ordinaryWoken++;
		}

		// Quarantine remains an independent one-per-100-tick lane. The due heap
		// makes the usual not-yet-due case O(1) instead of queue rotation.
		if ((now % (long)LIGHT_SKY_QUARANTINE_WAKE_INTERVAL_TICKS) != 0L) return;
		if (lightFinalizerSession().pendingTicks.size() >= LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER) return;
		while (true) {
			OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry = lightFinalizerSession().retryLedger.pollDueQuarantine(now);
			if (entry == null) break;
			long packed = entry.packed();
			long due = lightRecoverySession().skyBackoffUntilTick.get(packed);
			if (due == OceanCanvasPrimitiveLongLongMap.ABSENT || due != entry.dueTick()) continue;
			if (!lightRecoverySession().skyQuarantine.contains(packed)) {
				lightFinalizerSession().retryLedger.offerOrdinary(packed, due);
				continue;
			}
			if (!lightRecoverySession().skyBackoffUntilTick.remove(packed, due)) continue;
			activatePersistentSkyRepair(world, packed);
			lightTelemetrySession().LIGHT_DIAG_QUARANTINE_WAKES.incrementAndGet();
			break;
		}
	}

	/**
	 * v253.125.24 neighbor-load diagnostic rate limit. The .23 runtime recorded
	 * 3,601 starved-ticket events and emitted almost all of them as WARN, often in
	 * same-tick bursts. Preserve the exact cumulative starvation counter for health
	 * decisions, but emit only the first cohort and 256-event milestones. This keeps
	 * log I/O observational instead of letting it contend with C2ME chunk loading.
	 */
	private static boolean recordNeighborStarvedAndShouldWarn() {
		long ordinal = lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_STARVED.incrementAndGet();
		if (ordinal <= 16L || (ordinal & 255L) == 0L) return true;
		lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAIT_WARNINGS_SUPPRESSED.incrementAndGet();
		return false;
	}

	private static boolean shouldLogDetailedLightWarning(ServerLevel world, long packed) {
		long tick = world.getGameTime();
		Long last = lightTelemetrySession().LIGHT_DIAG_LAST_DETAIL_WARN_TICK.get(packed);
		if (last != null && tick - last.longValue() < LIGHT_DIAG_DETAIL_MIN_INTERVAL_TICKS) {
			lightTelemetrySession().LIGHT_DIAG_SUPPRESSED_DETAIL_WARNINGS.incrementAndGet();
			return false;
		}
		if (lightTelemetrySession().lightDiagDetailBudgetTick != tick) {
			lightTelemetrySession().lightDiagDetailBudgetTick = tick;
			lightTelemetrySession().lightDiagDetailBudgetUsed = 0;
		}
		if (lightTelemetrySession().lightDiagDetailBudgetUsed >= LIGHT_DIAG_DETAIL_WARNINGS_PER_TICK) {
			lightTelemetrySession().LIGHT_DIAG_SUPPRESSED_DETAIL_WARNINGS.incrementAndGet();
			return false;
		}
		lightTelemetrySession().lightDiagDetailBudgetUsed++;
		lightTelemetrySession().LIGHT_DIAG_LAST_DETAIL_WARN_TICK.put(packed, tick);
		return true;
	}

	private static void logCooperativeLightTelemetry(String trigger) {
		int partialPhysicalAudits = lightFinalizerSession().preLightPhysicalAuditState.size() + lightFinalizerSession().prePublishPhysicalAuditState.size();
		int partialProofs = lightFinalizerSession().strictSkyProofState.size() + lightFinalizerSession().completedStrictSkyProof.size();
		int partialFingerprints = lightFinalizerSession().stage0FingerprintState.size() + lightFinalizerSession().stage1FingerprintState.size()
				+ lightFinalizerSession().postProofFingerprintState.size() + lightFinalizerSession().hardResetFingerprintState.size();
		int partialSweeps = lightFinalizerSession().lightSweepColumnCursor.size();
		OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-COOPERATIVE build={} trigger={} physicalAuditSlices={} physicalAuditYields={} strictProofSlices={} strictProofYields={} fingerprintSlices={} fingerprintYields={} sweepSlices={} sweepYields={} heavyHeapDeferrals={} heavyHeapEscapes={} heavyEscapeGlobalThrottles={} maxPhysicalAuditSliceMicros={} maxStrictProofSliceMicros={} maxFingerprintSliceMicros={} maxSweepSliceMicros={} partialPhysicalAudits={} partialProofs={} partialFingerprints={} partialSweeps={} lateShutdownChunkLoadsIgnored={} action=bounded-cooperative-proof-state",
				net.oceancanvas.mod.OceanCanvas.VERSION, trigger,
				lightTelemetrySession().LIGHT_DIAG_PHYSICAL_AUDIT_SLICES.get(), lightTelemetrySession().LIGHT_DIAG_PHYSICAL_AUDIT_YIELDS.get(),
				lightTelemetrySession().LIGHT_DIAG_STRICT_PROOF_SLICES.get(), lightTelemetrySession().LIGHT_DIAG_STRICT_PROOF_YIELDS.get(),
				lightTelemetrySession().LIGHT_DIAG_FINGERPRINT_SLICES.get(), lightTelemetrySession().LIGHT_DIAG_FINGERPRINT_YIELDS.get(),
				lightTelemetrySession().LIGHT_DIAG_LIGHT_SWEEP_SLICES.get(), lightTelemetrySession().LIGHT_DIAG_LIGHT_SWEEP_YIELDS.get(),
				lightTelemetrySession().LIGHT_DIAG_HEAVY_PHASE_HEAP_DEFERRALS.get(), lightTelemetrySession().LIGHT_DIAG_HEAVY_PHASE_HEAP_ESCAPES.get(),
				lightTelemetrySession().LIGHT_DIAG_HEAVY_ESCAPE_GLOBAL_THROTTLES.get(),
				lightTelemetrySession().LIGHT_DIAG_MAX_PHYSICAL_AUDIT_SLICE_NANOS.get() / 1_000L,
				lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_PROOF_SLICE_NANOS.get() / 1_000L,
				lightTelemetrySession().LIGHT_DIAG_MAX_FINGERPRINT_SLICE_NANOS.get() / 1_000L,
				lightTelemetrySession().LIGHT_DIAG_MAX_LIGHT_SWEEP_SLICE_NANOS.get() / 1_000L,
				partialPhysicalAudits, partialProofs, partialFingerprints, partialSweeps, lightTelemetrySession().LIGHT_DIAG_LATE_SHUTDOWN_CHUNK_LOADS_IGNORED.get());
		long anchorTile = lightFinalizerSession().lastProductiveTileKey.get();
		OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-SQUARE-THROUGHPUT build={} trigger={} tileSizeChunks=8 anchorTile={},{} localityHits={} naturallyResidentWork={} currentTerrainWork={} pathologyFairShareDeferrals={} residencyCap={} residencyCapHolds={} residencyCapTransitions={} residencyBudgetSamples={} residencyHeapEmaPct={} residencyEmergencyDownshifts={} schedulerSampled={} schedulerQueueRepairs={} schedulerMaxAdminMicros={} schedulerLaneCompactions={} schedulerStaleNodesDropped={} fastPendingCountFallbacks={} persistedAuditHeapCompactions={} persistedAuditStaleNodesDropped={} historicalResidencyDeferrals={} warmHistoricalTickets={} workBudgetNormal={} workBudgetBacklog={} wallBudgetMicros={} action=event-lanes-with-stale-compaction-and-bounded-raw-polls",
				net.oceancanvas.mod.OceanCanvas.VERSION, trigger,
				anchorTile == Long.MIN_VALUE ? Integer.MIN_VALUE : ChunkPos.getX(anchorTile),
				anchorTile == Long.MIN_VALUE ? Integer.MIN_VALUE : ChunkPos.getZ(anchorTile),
				lightTelemetrySession().LIGHT_DIAG_TILE_LOCALITY_HITS.get(),
				lightTelemetrySession().LIGHT_DIAG_TILE_RESIDENT_FIRST.get(),
				lightTelemetrySession().LIGHT_DIAG_CURRENT_TERRAIN_FIRST.get(),
				lightTelemetrySession().LIGHT_DIAG_PATHOLOGY_FAIR_SHARE_DEFERRALS.get(),
				lightTelemetrySession().lightDiagEffectiveResidencyCap,
				lightTelemetrySession().LIGHT_DIAG_RESIDENCY_CAP_HOLDS.get(),
				lightTelemetrySession().LIGHT_DIAG_RESIDENCY_CAP_TRANSITIONS.get(),
				lightTelemetrySession().LIGHT_DIAG_RESIDENCY_BUDGET_SAMPLES.get(),
				String.format(java.util.Locale.ROOT, "%.1f", Math.max(0.0D, lightTelemetrySession().lightDiagResidencyHeapEma) * 100.0D),
				lightTelemetrySession().LIGHT_DIAG_RESIDENCY_EMERGENCY_DOWNSHIFTS.get(),
				lightTelemetrySession().LIGHT_DIAG_SCHEDULER_SAMPLED.get(),
				lightTelemetrySession().LIGHT_DIAG_SCHEDULER_QUEUE_REPAIRS.get(),
				lightTelemetrySession().LIGHT_DIAG_SCHEDULER_MAX_ADMIN_NANOS.get() / 1_000L,
				lightFinalizerSession().schedulerLaneCompactions.get(),
				lightFinalizerSession().schedulerStaleNodesDropped.get(),
				lightTelemetrySession().LIGHT_DIAG_FAST_PENDING_COUNT_FALLBACKS.get(),
				lightFinalizerSession().persistedAuditSession.heapCompactions(),
				lightFinalizerSession().persistedAuditSession.staleDeadlineNodesDiscarded(),
				lightFinalizerSession().historicalResidencyInstallDeferrals.get(),
				lightFinalizerSession().historicalWarmResidencyUntilTick.size(),
				LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_NORMAL, LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_BACKLOG,
				LIGHT_FINALIZER_TICK_TIME_BUDGET_NS / 1_000L);
	}

	private static void maybeLogLightDiagnosticAggregate(ServerLevel world) {
		long tick = world.getGameTime();
		if (lightTelemetrySession().lightDiagLastAggregateTick != Long.MIN_VALUE
				&& tick - lightTelemetrySession().lightDiagLastAggregateTick < LIGHT_DIAG_AGGREGATE_INTERVAL_TICKS) return;
		long suppressed = lightTelemetrySession().LIGHT_DIAG_SUPPRESSED_DETAIL_WARNINGS.getAndSet(0L);
		if (suppressed <= 0L && lightRecoverySession().skyBackoffUntilTick.isEmpty()
				&& lightRecoverySession().pressureParkUntilTick.isEmpty()) return;
		lightTelemetrySession().lightDiagLastAggregateTick = tick;
		// v253.125.22: the .21 liveness stall could not be distinguished from a
		// healthy saturated cohort because the aggregate exposed only ticket count.
		// Report staged holders, oldest ticket age, and rotations so a future 64/64
		// plateau proves whether staged-ticket liveness recovery is actually firing.
		int stagedTicketHolders = 0;
		long oldestRelightTicketMs = 0L;
		long aggregateNowNs = System.nanoTime();
		for (long ticketPacked : lightFinalizerSession().relightResidencyLedger.activeSnapshot()) {
			if (lightFinalizerSession().pendingPasses.getOrDefault(ticketPacked, 0) > 0) stagedTicketHolders++;
			long installedNs = lightFinalizerSession().relightResidencyLedger.installedAtNanos(ticketPacked);
			if (installedNs != OceanCanvasLightRelightResidencyLedger.ABSENT_NANOS) oldestRelightTicketMs = Math.max(oldestRelightTicketMs, Math.max(0L, aggregateNowNs - installedNs) / 1_000_000L);
		}
		OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-DIAG AGGREGATE build={} active={} persistentFaults={} pressureParked={} ordinaryRetryQueued={} quarantineRetryQueued={} pressureRetryQueued={} tickets={} stagedTickets={} oldestTicketMs={} ticketRotations={} suppressedDetailWarnings={} neighborWaitWarnSuppressed={} persistedAuditPriorityDeferrals={} persistentDeferred={} persistentWoken={} persistentWakePressureHolds={} quarantined={} quarantineWakes={} quarantineReleases={} foreverWorldPressureParks={} foreverWorldPressureWakes={} workBudgetDeferrals={} fastCertified={} visibleFastCertified={} pressureFastCertified={} visibleTicketPreempts={} crashRecoveryHandoffs={} publicRecoveries={} publicRecoveryFailures={} boundaryEpochPreserved={} deepRepairSlices={} deepRepairYields={} deepRepairHeapDeferrals={} deepRepairMaxSliceMicros={} adaptiveQuietEscalations={} maxQuietTicks={} pathologyHotspots={} slowPhaseEvents={} maxSkyProofMicros={} maxPhysicalAuditMicros={} maxFingerprintMicros={} maxSweepMicros={} fluidReactionSuspects={} fluidReactionSuspectChunks={} action=bounded-repair-scheduler-lanes-separated",
				net.oceancanvas.mod.OceanCanvas.VERSION, lightFinalizerSession().pendingTicks.size(), lightRecoverySession().skyBackoffUntilTick.size(),
				lightRecoverySession().pressureParkUntilTick.size(), lightFinalizerSession().retryLedger.ordinarySize(), lightFinalizerSession().retryLedger.quarantineSize(),
				lightFinalizerSession().retryLedger.pressureParkSize(), lightFinalizerSession().relightResidencyLedger.activeCount(), stagedTicketHolders, oldestRelightTicketMs, lightFinalizerSession().relightResidencyLedger.rotationCount(), suppressed,
				lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAIT_WARNINGS_SUPPRESSED.get(), lightTelemetrySession().LIGHT_DIAG_PERSISTED_AUDIT_PRIORITY_DEFERRALS.get(), lightTelemetrySession().LIGHT_DIAG_PERSISTENT_DEFERRED.get(),
				lightTelemetrySession().LIGHT_DIAG_PERSISTENT_WOKEN.get(), lightTelemetrySession().LIGHT_DIAG_PERSISTENT_WAKE_PRESSURE_HOLDS.get(),
				lightRecoverySession().skyQuarantine.size(), lightTelemetrySession().LIGHT_DIAG_QUARANTINE_WAKES.get(), lightTelemetrySession().LIGHT_DIAG_QUARANTINE_RELEASES.get(),
				lightTelemetrySession().LIGHT_DIAG_FOREVER_WORLD_PRESSURE_PARKS.get(), lightTelemetrySession().LIGHT_DIAG_FOREVER_WORLD_PRESSURE_WAKES.get(), lightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.get(),
				lightTelemetrySession().LIGHT_DIAG_FAST_CERTIFIED.get(), lightTelemetrySession().LIGHT_DIAG_VISIBLE_FAST_CERTIFIED.get(),
				lightTelemetrySession().LIGHT_DIAG_PRESSURE_FAST_CERTIFIED.get(), lightTelemetrySession().LIGHT_DIAG_VISIBLE_TICKET_PREEMPTS.get(),
				lightTelemetrySession().LIGHT_DIAG_CRASH_RECOVERY_HANDOFFS.get(),
				lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERIES.get(), lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_FAILURES.get(),
				lightTelemetrySession().LIGHT_DIAG_BOUNDARY_RECOVERY_EPOCH_PRESERVED.get(),
				lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_SLICES.get(), lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_SLICE_YIELDS.get(),
				lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_HEAP_DEFERRALS.get(), lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_MAX_SLICE_NANOS.get() / 1_000L,
				lightTelemetrySession().LIGHT_DIAG_ADAPTIVE_QUIET_ESCALATIONS.get(), lightTelemetrySession().LIGHT_DIAG_MAX_ADAPTIVE_QUIET_TICKS.get(),
				lightTelemetrySession().LIGHT_DIAG_PATHOLOGY_HOTSPOTS.get(), lightTelemetrySession().LIGHT_DIAG_SLOW_PHASE_EVENTS.get(),
				lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_SKY_PROOF_NANOS.get() / 1_000L, lightTelemetrySession().LIGHT_DIAG_MAX_PHYSICAL_AUDIT_NANOS.get() / 1_000L,
				lightTelemetrySession().LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS.get() / 1_000L, lightTelemetrySession().LIGHT_DIAG_MAX_LIGHT_SWEEP_NANOS.get() / 1_000L,
				lightTelemetrySession().FLUID_REACTION_PRODUCT_SUSPECTS.get(), lightTelemetrySession().FLUID_REACTION_PRODUCT_SUSPECT_CHUNKS.size());
		logCooperativeLightTelemetry("AGGREGATE");
	}

	/**
	 * Resends authoritative block data AND the current light section arrays for
	 * chunks mutated through raw ChunkAccess writes. sendBlockUpdated deliberately
	 * avoids neighbor physics and is appropriate for the carve itself, but it does
	 * not replace the client's cached sky/block-light payload. The restore engine
	 * already uses this exact 26.2 packet shape successfully, so reuse the same
	 * proven path here instead of guessing at a renderer-side refresh API.
	 */
	/** v253.61.11 physical-quiescence barrier, corrected in v253.69.1. Do not begin
	 * the durable lighting proof while an adjacent chunk is actually admitted/in-flight
	 * or is explicitly queued for deferred/replay terrain mutation. Untouched chunks
	 * merely ahead of the row-major cursor are NOT blockers: making them blockers
	 * deadlocked with lighting high-water backpressure (lighting waited for future
	 * terrain while future terrain admission waited for lighting). Later normal CANVAS
	 * carving is safe under the verified-neighbor stability/rearm rules. */
	private static boolean adjacentPregenTerrainMayStillMutate(ChunkPos center) {
		// v253.125.23: only ADMITTED/IN-FLIGHT terrain ownership is a hard lighting
		// quiescence barrier. The .22 runtime proved that treating a deferred/replay
		// coordinate which is merely WAITING for admission as a present mutator creates
		// a circular wait at the 512 light high-water mark: future recovery waits for
		// lighting headroom, while resident stage-0 lighting waits for that future
		// recovery to mutate. Every replay/deferred coordinate must pass
		// requestPregenTargetLoad()->markPregenTarget() before it can enter the terrain
		// mutation pipeline, so PREGEN_TARGET_CHUNKS is the authoritative boundary.
		// When future work is finally admitted, normal dirty/rearm rules invalidate any
		// neighboring certificate whose boundary can change.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				long packed = ChunkPos.pack(center.x() + dx, center.z() + dz);
				if (pregenSession().PREGEN_TARGET_CHUNKS.contains(packed)) return true;
			}
		}
		return false;
	}

	/** v253.125.25 chunk-local physical quiet requirement. Healthy chunks retain the
	 * proven 40-tick gate; only chunks that actually mutate after certification pay
	 * a longer settle cost. */
	private static int terrainQuietTicksFor(long packed) {
		int streak = lightFinalizerSession().terrainInstabilityStreak.getOrDefault(packed, 0);
		if (streak <= 0) return LIGHT_TERRAIN_QUIET_TICKS;
		int shift = Math.min(3, streak);
		return Math.min(LIGHT_TERRAIN_QUIET_MAX_TICKS, LIGHT_TERRAIN_QUIET_TICKS << shift);
	}

	/** Record a proven late block mutation and return the new required quiet window. */
	private static int notePhysicalInstability(ServerLevel world, long packed) {
		int streak = lightFinalizerSession().terrainInstabilityStreak.incrementCapped(packed, 8);
		lightFinalizerSession().terrainLastMutationTick.put(packed, world.getGameTime());
		int quietTicks = terrainQuietTicksFor(packed);
		lightTelemetrySession().LIGHT_DIAG_ADAPTIVE_QUIET_ESCALATIONS.incrementAndGet();
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_INSTABILITY_STREAK, streak);
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_ADAPTIVE_QUIET_TICKS, quietTicks);
		return quietTicks;
	}

	private static void updateAtomicMax(java.util.concurrent.atomic.AtomicLong target, long candidate) {
		long current = target.get();
		while (candidate > current && !target.compareAndSet(current, candidate)) current = target.get();
	}

	/** Release scarce transient residency while a stage-0 entry is deliberately
	 * waiting for scheduled physics. The correctness obligation remains armed. */
	private static void releaseQuietWaitResidencyIfOwned(ServerLevel world, long packed, int pass) {
		if (pass <= 0 && lightFinalizerSession().relightResidencyLedger.contains(packed)) {
			releaseLightRelightResidencyTicket(world, packed);
			lightTelemetrySession().LIGHT_DIAG_ADAPTIVE_QUIET_TICKET_RELEASES.incrementAndGet();
		}
	}

	private static double currentHeapUseFraction(ServerLevel world) {
		// v253.125.31: every proof/ticket candidate used to call Runtime memory
		// sampling independently. Cache one observation per server tick; this is both
		// cheaper and ensures all admission decisions in a tick use one coherent view.
		OceanCanvasLightTelemetrySession telemetry = lightTelemetrySession();
		long tick = world == null ? Long.MIN_VALUE : world.getGameTime();
		if (tick != Long.MIN_VALUE && telemetry.lightDiagHeapSampleTick == tick) return telemetry.lightDiagHeapSampleFraction;
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory();
		double value = max <= 0L ? 0.0D : (double)(rt.totalMemory() - rt.freeMemory()) / (double)max;
		telemetry.lightDiagHeapSampleTick = tick;
		telemetry.lightDiagHeapSampleFraction = value;
		return value;
	}

	/**
	 * v253.125.33 runtime-pressure circuit breaker. The 125.31 soak captured two
	 * 8s+ stalls in JourneyMap's loaded-chunk polygon rebuild and a third in
	 * advancement predicate evaluation while Ocean Canvas was simultaneously at
	 * 90-99% heap / high I/O pressure. Those are not Ocean Canvas deadlocks, but
	 * continuing expensive proof during the recovery window makes them longer.
	 * Keep correctness debt armed and simply stop entering non-trivial light work
	 * for a bounded cooldown after a severe previous-tick/heap signal.
	 */
	private static boolean runtimePressureHoldsLightFinalizer(ServerLevel world) {
		if (world == null) return false;
		long nowTick = world.getGameTime();
		double heap = currentHeapUseFraction(world);
		double workMs = net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry.workMs();
		double intervalMs = net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry.intervalMs();
		long requestedHold = Long.MIN_VALUE;
		String reason = "clear";
		if (heap >= 0.90D || workMs >= 250.0D || intervalMs >= 500.0D) {
			requestedHold = nowTick + 100L;
			reason = heap >= 0.90D ? "heap-critical" : (workMs >= 250.0D ? "tick-work-critical" : "tick-interval-critical");
		} else if (heap >= 0.85D || workMs >= 100.0D || intervalMs >= 150.0D) {
			requestedHold = nowTick + 40L;
			reason = heap >= 0.85D ? "heap-hard" : (workMs >= 100.0D ? "tick-work-hard" : "tick-interval-hard");
		} else if (heap >= 0.80D || workMs >= 70.0D || intervalMs >= 100.0D) {
			requestedHold = nowTick + 10L;
			reason = heap >= 0.80D ? "heap-elevated" : (workMs >= 70.0D ? "tick-work-elevated" : "tick-interval-elevated");
		}
		java.util.concurrent.atomic.AtomicLong holdUntil = lightFinalizerSession().runtimePressureHoldUntilTick;
		if (requestedHold != Long.MIN_VALUE) {
			long current = holdUntil.get();
			while (requestedHold > current && !holdUntil.compareAndSet(current, requestedHold)) current = holdUntil.get();
		}
		long until = holdUntil.get();
		if (until == Long.MIN_VALUE || nowTick >= until) {
			lightFinalizerSession().runtimePressureNextEscapeTick.set(Long.MIN_VALUE);
			return false;
		}
		// Persistent high heap must not become a new correctness deadlock. Hold the
		// expensive lane normally, but allow one ordinary bounded finalizer tick every
		// five seconds. The outer finalizer still has its unchanged 8ms wall budget and
		// heavy sub-phases have their own slower pressure-aware escape below.
		java.util.concurrent.atomic.AtomicLong escapeGate = lightFinalizerSession().runtimePressureNextEscapeTick;
		long nextEscape = escapeGate.get();
		if (nextEscape == Long.MIN_VALUE) {
			escapeGate.compareAndSet(Long.MIN_VALUE, nowTick + 100L);
		} else if (nowTick >= nextEscape && escapeGate.compareAndSet(nextEscape, nowTick + 100L)) {
			return false;
		}
		lightFinalizerSession().runtimePressureHolds.incrementAndGet();
		java.util.concurrent.atomic.AtomicLong lastLog = lightFinalizerSession().runtimePressureLastLogTick;
		long last = lastLog.get();
		if ((last == Long.MIN_VALUE || nowTick - last >= 600L) && lastLog.compareAndSet(last, nowTick)) {
			OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-RUNTIME-PRESSURE-HOLD build={} reason={} heapPct={} previousTickWorkMs={} previousTickIntervalMs={} holdRemainingTicks={} pending={} activeTickets={} action=defer-expensive-light-proof-without-dropping-correctness-debt",
				net.oceancanvas.mod.OceanCanvas.VERSION, reason, Math.round(heap * 100.0D),
				Math.round(workMs), Math.round(intervalMs), Math.max(0L, until - nowTick),
				lightFinalizerSession().pendingTicks.size(), lightFinalizerSession().relightResidencyLedger.activeCount());
		}
		return true;
	}

	/**
	 * v253.125.30 sampled/EMA heap-aware admission cap. The .29 runtime proved that
	 * consulting raw heap on every individual ticket attempt creates GC-sawtooth cap
	 * thrash (thousands of transitions and millions of rejected attempts). Sample at
	 * most every ten server ticks, require sustained pressure/recovery for ordinary
	 * tier changes, and reserve an immediate raw-heap escape only for >=97% emergency.
	 * Existing productive tickets are still never revoked solely for heap pressure.
	 */
	private static int effectiveLightRelightResidencyTicketCap(ServerLevel world) {
		OceanCanvasLightTelemetrySession telemetry = lightTelemetrySession();
		long tick = world == null ? Long.MIN_VALUE : world.getGameTime();
		if (tick != Long.MIN_VALUE && telemetry.lightDiagResidencyLastSampleTick != Long.MIN_VALUE
				&& tick - telemetry.lightDiagResidencyLastSampleTick < 10L) {
			return telemetry.lightDiagEffectiveResidencyCap;
		}
		telemetry.lightDiagResidencyLastSampleTick = tick;
		double rawHeap = currentHeapUseFraction(world);
		double previousEma = telemetry.lightDiagResidencyHeapEma;
		double heapEma = previousEma < 0.0D ? rawHeap : previousEma * 0.75D + rawHeap * 0.25D;
		telemetry.lightDiagResidencyHeapEma = heapEma;
		telemetry.LIGHT_DIAG_RESIDENCY_BUDGET_SAMPLES.incrementAndGet();

		int previous = Math.min(telemetry.lightDiagEffectiveResidencyCap, LIGHT_RELIGHT_RESIDENCY_TICKET_MAX);
		if (telemetry.lightDiagEffectiveResidencyCap != previous) {
			// v253.125.52 fail-safe for version transitions or future constant changes:
			// a stale session seed may never exceed the real install ceiling.
			telemetry.lightDiagEffectiveResidencyCap = previous;
		}
		int next = previous;
		boolean emergency = rawHeap >= 0.90D && previous > LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_CRITICAL;
		if (emergency) {
			next = LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_CRITICAL;
			telemetry.lightDiagResidencyHighSamples = 0;
			telemetry.lightDiagResidencyLowSamples = 0;
			telemetry.LIGHT_DIAG_RESIDENCY_EMERGENCY_DOWNSHIFTS.incrementAndGet();
		} else {
			double highThreshold = previous >= LIGHT_RELIGHT_RESIDENCY_TICKET_MAX ? 0.72D
					: previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM ? 0.80D
					: previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT ? 0.86D : 2.0D;
			double lowThreshold = previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM ? 0.66D
					: previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT ? 0.72D
					: previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_CRITICAL ? 0.78D : -1.0D;
			if (heapEma >= highThreshold) {
				telemetry.lightDiagResidencyHighSamples++;
				telemetry.lightDiagResidencyLowSamples = 0;
			} else if (lowThreshold >= 0.0D && heapEma <= lowThreshold) {
				telemetry.lightDiagResidencyLowSamples++;
				telemetry.lightDiagResidencyHighSamples = 0;
			} else {
				telemetry.lightDiagResidencyHighSamples = 0;
				telemetry.lightDiagResidencyLowSamples = 0;
			}
			if (telemetry.lightDiagResidencyHighSamples >= 3) {
				if (previous >= LIGHT_RELIGHT_RESIDENCY_TICKET_MAX) next = LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM;
				else if (previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM) next = LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT;
				else if (previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT) next = LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_CRITICAL;
			} else if (telemetry.lightDiagResidencyLowSamples >= 16) {
				long sinceTransition = telemetry.lightDiagResidencyLastTransitionTick == Long.MIN_VALUE
						? Long.MAX_VALUE : Math.max(0L, tick - telemetry.lightDiagResidencyLastTransitionTick);
				boolean recoveryHoldElapsed = tick == Long.MIN_VALUE || sinceTransition >= LIGHT_RESIDENCY_RECOVERY_HOLD_TICKS;
				if (recoveryHoldElapsed) {
					if (previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_CRITICAL && rawHeap < 0.78D) next = LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT;
					else if (previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT && rawHeap < 0.72D) next = LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM;
					else if (previous == LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM && rawHeap < 0.66D) next = LIGHT_RELIGHT_RESIDENCY_TICKET_MAX;
				}
			}
		}
		if (next != previous) {
			telemetry.lightDiagEffectiveResidencyCap = next;
			telemetry.lightDiagResidencyLastTransitionTick = tick;
			telemetry.lightDiagResidencyHighSamples = 0;
			telemetry.lightDiagResidencyLowSamples = 0;
			long transition = telemetry.LIGHT_DIAG_RESIDENCY_CAP_TRANSITIONS.incrementAndGet();
			OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-RESIDENCY-BUDGET build={} rawHeapPct={} heapEmaPct={} previousCap={} effectiveCap={} activeTickets={} samples={} transitions={} emergencyDownshifts={} action=sampled-sustained-hysteresis-without-revoking-productive-proof",
				net.oceancanvas.mod.OceanCanvas.VERSION,
				String.format(java.util.Locale.ROOT, "%.1f", rawHeap * 100.0D),
				String.format(java.util.Locale.ROOT, "%.1f", heapEma * 100.0D), previous, next,
				lightFinalizerSession().relightResidencyLedger.activeCount(),
				telemetry.LIGHT_DIAG_RESIDENCY_BUDGET_SAMPLES.get(), transition,
				telemetry.LIGHT_DIAG_RESIDENCY_EMERGENCY_DOWNSHIFTS.get());
		}
		return telemetry.lightDiagEffectiveResidencyCap;
	}

	/** Heavy repair yields briefly above 95% heap, but every sixth request is allowed
	 * through so memory pressure can never turn into a new correctness deadlock. */
	private static boolean claimGlobalHeavyEscape(ServerLevel world) {
		// v253.125.33: the .31 soak repeatedly reached 96-99% heap; forcing one
		// heavy proof every two ticks in that state competes with GC and extends
		// stalls. Slow the global liveness escape to 2s/5s under hard/critical
		// pressure instead of disabling it, so persistent high heap cannot become
		// a new correctness deadlock.
		long now = world == null ? 0L : world.getGameTime();
		double heap = world == null ? 0.0D : currentHeapUseFraction(world);
		double workMs = net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry.workMs();
		double intervalMs = net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry.intervalMs();
		long escapeInterval = (heap >= 0.96D || workMs >= 250.0D || intervalMs >= 500.0D) ? 100L
				: (heap >= 0.94D || workMs >= 100.0D || intervalMs >= 150.0D) ? 40L
				: LIGHT_HEAVY_GLOBAL_ESCAPE_INTERVAL_TICKS;
		java.util.concurrent.atomic.AtomicLong gate = lightFinalizerSession().heavyPhaseNextGlobalEscapeTick;
		while (true) {
			long allowed = gate.get();
			if (allowed != Long.MIN_VALUE && now < allowed) {
				lightTelemetrySession().LIGHT_DIAG_HEAVY_ESCAPE_GLOBAL_THROTTLES.incrementAndGet();
				return false;
			}
			long next = now + escapeInterval;
			if (gate.compareAndSet(allowed, next)) return true;
		}
	}

	/** Heap-paused deep repair with one globally paced liveness escape. */
	private static boolean deferDeepRepairForHeap(ServerLevel world, long packed) {
		if (currentHeapUseFraction(world) < LIGHT_DEEP_REPAIR_HEAP_PAUSE_FRACTION) {
			lightRecoverySession().deepRepairHeapDeferralCounts.remove(packed);
			return false;
		}
		int count = lightRecoverySession().deepRepairHeapDeferralCounts.incrementCapped(packed, 1024);
		lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_HEAP_DEFERRALS.incrementAndGet();
		if (count <= LIGHT_DEEP_REPAIR_MAX_CONSECUTIVE_HEAP_DEFERRALS) return true;
		if (!claimGlobalHeavyEscape(world)) return true;
		lightRecoverySession().deepRepairHeapDeferralCounts.put(packed, 0);
		lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_HEAP_ESCAPE_SLICES.incrementAndGet();
		return false;
	}

	/** v253.125.31: expensive proof-phase liveness escapes are globally paced so
	 * thousands of per-chunk streaks cannot all burst through during one high-heap
	 * interval. Correctness debt remains armed while a chunk waits. */
	private static boolean deferHeavyFinalizerPhaseForHeap(ServerLevel world, long packed) {
		if (currentHeapUseFraction(world) < LIGHT_HEAVY_PHASE_HEAP_PAUSE_FRACTION) {
			lightFinalizerSession().heavyPhaseHeapDeferralStreak.remove(packed);
			return false;
		}
		int count = lightFinalizerSession().heavyPhaseHeapDeferralStreak.incrementCapped(packed, 1024);
		lightTelemetrySession().LIGHT_DIAG_HEAVY_PHASE_HEAP_DEFERRALS.incrementAndGet();
		if (count <= LIGHT_HEAVY_PHASE_MAX_CONSECUTIVE_HEAP_DEFERRALS) return true;
		if (!claimGlobalHeavyEscape(world)) return true;
		lightFinalizerSession().heavyPhaseHeapDeferralStreak.put(packed, 0);
		lightTelemetrySession().LIGHT_DIAG_HEAVY_PHASE_HEAP_ESCAPES.incrementAndGet();
		return false;
	}

	private static void recordDeepRepairSlice(long checks, long elapsedNanos, boolean yielded) {
		lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_SLICES.incrementAndGet();
		if (yielded) lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_SLICE_YIELDS.incrementAndGet();
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_MAX_SLICE_CHECKS, checks);
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_MAX_SLICE_NANOS, elapsedNanos);
	}

	private static long recordLightPhaseDuration(ServerLevel world, long packed, String phase,
			java.util.concurrent.atomic.AtomicLong maxTarget, long startedNanos) {
		long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
		updateAtomicMax(maxTarget, elapsed);
		if (elapsed >= LIGHT_PHASE_SLOW_WARN_NS) {
			long event = lightTelemetrySession().LIGHT_DIAG_SLOW_PHASE_EVENTS.incrementAndGet();
			if (event <= 16L || (event & 63L) == 0L) {
				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-PHASE-SLOW build={} chunk={},{} phase={} elapsedMicros={} heapPermille={} pendingLight={} activeTickets={} event={} action=retain-fail-closed-state-and-attribute-hot-path",
					net.oceancanvas.mod.OceanCanvas.VERSION, ChunkPos.getX(packed), ChunkPos.getZ(packed), phase,
					elapsed / 1_000L, Math.round(currentHeapUseFraction(world) * 1_000.0D),
					lightFinalizerSession().pendingTicks.size(), lightFinalizerSession().relightResidencyLedger.activeCount(), event);
			}
		}
		return elapsed;
	}

	private static void clearCooperativeFinalizerState(long packed) {
		lightFinalizerSession().preLightPhysicalAuditState.remove(packed);
		lightFinalizerSession().prePublishPhysicalAuditState.remove(packed);
		lightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);
		lightFinalizerSession().prePublishPhysicalAuditComplete.remove(packed);
		lightFinalizerSession().lightSweepState.remove(packed);
		lightFinalizerSession().lightSweepColumnCursor.remove(packed);
		lightFinalizerSession().lightSweepChecksAccumulated.remove(packed);
		lightFinalizerSession().lightSweepSectionStatusDone.remove(packed);
		lightFinalizerSession().strictSkyProofState.remove(packed);
		lightFinalizerSession().completedStrictSkyProof.remove(packed);
		lightFinalizerSession().stage0FingerprintState.remove(packed);
		lightFinalizerSession().stage1FingerprintState.remove(packed);
		lightFinalizerSession().postProofFingerprintState.remove(packed);
		lightFinalizerSession().hardResetFingerprintState.remove(packed);
		lightFinalizerSession().hardResetFingerprintPending.remove(packed);
		lightFinalizerSession().stage1FingerprintVerified.remove(packed);
		lightFinalizerSession().heavyPhaseHeapDeferralStreak.remove(packed);
	}

	/** Drop transient cursor state whenever the light graph's physical epoch changes. */
	private static void clearIncrementalDeepRepairState(long packed) {
		clearCooperativeFinalizerState(packed);
		lightRecoverySession().visibleDeepDenseSectionY.remove(packed);
		lightRecoverySession().visibleDeepDenseCursor.remove(packed);
		lightRecoverySession().visibleDeepDenseChecksAccumulated.remove(packed);
		lightRecoverySession().visibleDeepClusterAttemptInFlight.remove(packed);
		lightRecoverySession().visibleDeepClusterSectionY.remove(packed);
		lightRecoverySession().visibleDeepClusterCursor.remove(packed);
		lightRecoverySession().visibleDeepClusterChecksAccumulated.remove(packed);
		lightRecoverySession().deepRepairHeapDeferralCounts.remove(packed);
	}

	private static boolean hasIncrementalClusterRepair(long packed) {
		return lightRecoverySession().visibleDeepClusterAttemptInFlight.containsKey(packed);
	}

	private static void maybeRecordPathologyHotspot(long packed, int escalation, boolean visible, SkyLightDiag sky) {
		if (escalation < LIGHT_PATHOLOGY_HOTSPOT_ESCALATION) return;
		int milestone = escalation >= 64 ? 64 : escalation >= 32 ? 32 : 16;
		int prior = lightRecoverySession().pathologyHotspotLevel.getOrDefault(packed, 0);
		if (prior >= milestone) return;
		lightRecoverySession().pathologyHotspotLevel.put(packed, milestone);
		long n = lightTelemetrySession().LIGHT_DIAG_PATHOLOGY_HOTSPOTS.incrementAndGet();
		int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
		OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-PATHOLOGY-HOTSPOT build={} chunk={},{} milestone={} visible={} deepLayers={} deepColumns={} overbrightLayers={} overbrightColumns={} denseAttempts={} clusterAttempts={} hardResets={} instabilityStreak={} hotspotCount={} action=retain-fail-closed-debt-and-capture-bounded-repair-evidence",
			net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, milestone, visible,
			sky.deepAnomalousLayers(), sky.deepAnomalousColumns(), sky.deepOverbrightLayers(), sky.deepOverbrightColumns(),
			lightRecoverySession().visibleDeepDenseRepairCounts.getOrDefault(packed, 0),
			lightRecoverySession().visibleDeepClusterRepairCounts.getOrDefault(packed, 0),
			lightRecoverySession().hardSkyResetCounts.getOrDefault(packed, 0),
			lightFinalizerSession().terrainInstabilityStreak.getOrDefault(packed, 0), n);
	}

	private static void sortVisiblePrimitive(long[] packed, long[] distance, int size) {
		for (int i = 1; i < size; i++) {
			long p = packed[i], d = distance[i];
			int j = i - 1;
			while (j >= 0 && (distance[j] > d || (distance[j] == d && packed[j] > p))) {
				packed[j + 1] = packed[j];
				distance[j + 1] = distance[j];
				j--;
			}
			packed[j + 1] = p;
			distance[j + 1] = d;
		}
	}

	private static long lightLocalityTileKey(long packed) {
		int tileX = ChunkPos.getX(packed) >> LIGHT_LOCALITY_TILE_SHIFT;
		int tileZ = ChunkPos.getZ(packed) >> LIGHT_LOCALITY_TILE_SHIFT;
		return ChunkPos.pack(tileX, tileZ);
	}

	private static void recordProductiveLightTile(long packed) {
		lightFinalizerSession().lastProductiveTileKey.set(lightLocalityTileKey(packed));
	}

	/** v253.125.33 successful visible-repair telemetry is sampled instead of WARN-flooded. */
	private static void logVisibleRepairPublish(ServerLevel world, int cx, int cz, long visibleRank, SkyLightDiag sky) {
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		long tick = world.getGameTime();
		java.util.concurrent.atomic.AtomicLong startRef = session.visiblePublishLogWindowStartTick;
		long start = startRef.get();
		if (start == Long.MIN_VALUE) { startRef.compareAndSet(Long.MIN_VALUE, tick); start = startRef.get(); }
		if (tick - start >= 100L && startRef.compareAndSet(start, tick)) {
			long suppressed = session.visiblePublishSuppressedLogs.getAndSet(0L);
			session.visiblePublishDetailedLogs.set(0L);
			if (suppressed > 0L) {
				OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-VISIBLE-REPAIR-PUBLISH-SUMMARY build={} suppressedDetails={} windowTicks={} finalPublishes={} action=aggregate-success-telemetry-without-server-log-storm",
						net.oceancanvas.mod.OceanCanvas.VERSION, suppressed, Math.max(0L, tick - start),
						lightTelemetrySession().LIGHT_DIAG_FINAL_PUBLISHES.get());
			}
		}
		long detail = session.visiblePublishDetailedLogs.incrementAndGet();
		if (detail <= 8L) {
			OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-VISIBLE-REPAIR-PUBLISH build={} chunk={},{} distanceChunksSq={} surfaceSky={}..{} waterSky={}..{} deepAnomalousLayers={} action=authoritative-publish-before-client-render-barrier",
					net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, visibleRank, sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax(), sky.deepAnomalousLayers());
		} else {
			long suppressed = session.visiblePublishSuppressedLogs.incrementAndGet();
			if ((suppressed & 63L) == 0L) {
				OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-VISIBLE-REPAIR-PUBLISH-SUMMARY build={} suppressedDetails={} windowTicks={} finalPublishes={} action=aggregate-success-telemetry-without-server-log-storm",
						net.oceancanvas.mod.OceanCanvas.VERSION, suppressed, Math.max(0L, tick - startRef.get()),
						lightTelemetrySession().LIGHT_DIAG_FINAL_PUBLISHES.get());
			}
		}
	}

	private static void drainPendingLightSync(ServerLevel world) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
		// .34 warm residency is transient optimization state, not correctness debt.
		// Expire/shear it BEFORE the runtime-pressure return so a high-heap hold can
		// never pin completed neighborhoods and make its own memory pressure worse.
		expireHistoricalWarmResidencyTickets(world);
		// v253.125.33: do not enter scheduler administration, retry wakes or any
		// proof/repair phase while the integrated server is in a severe recovery
		// window. Debt remains pending and resumes automatically after cooldown.
		if (runtimePressureHoldsLightFinalizer(world)) return;
		// v253.72.9: dormant persistent faults wake through their own bounded queue;
		// they are not kept in the per-tick active countdown map.
		wakeDuePersistentSkyRepairs(world);
		maybeLogLightDiagnosticAggregate(world);
		// v253.125.20: recovery-specific pressure windows cannot bound persisted/global
		// audit debt. Collapse excess non-visible generic LIGHT_ONLY work into the same
		// scheduler-only pressure park before building/sorting the active work window.
		if (lightFinalizerSession().pendingTicks.size() > LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER) {
			parkExcessGenericLightOnlyForPressure(world, LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER,
					LIGHT_GENERIC_PRESSURE_PARK_PER_TICK);
		}
		if (lightFinalizerSession().pendingTicks.isEmpty()) return;
		// v253.49: no global terrain barrier. Light work drains while Pregen keeps
		// feeding. v253.61.7 coalesces real adjacent mutations only into unfinished
		// entries; completed verified neighbors stay complete. Pregen admission keeps
		// a bounded flow-control gate as a safety net against any remaining backlog.
		// v253.24: the previous finalizer imposed four staggered waits and two
		// 1024-seed full-column sweeps on every dirty chunk. The runtime log proved
		// that this was the scaling bottleneck: most "RELIGHT_SLOW" entries were
		// almost exactly the two seconds that Ocean Canvas itself forced between
		// the final sweeps. Raw carve writes already call LightEngine#checkBlock for
		// every changed block, so finalization only needs a quiet-period boundary
		// reinforcement and an authoritative publish, not a second complete relight.
		// v253.61.6: FAIR rotating scan. The 61.4 20k runtime reached the 2,048
		// high-water mark with all 32 relight tickets held, then finalized nothing for
		// minutes. The old code rebuilt the whole key snapshot but always examined only
		// indexes 0..255. If ticket holders lived later in ConcurrentHashMap iteration
		// order, the first window could not acquire a slot and the holders were never
		// revisited to finish/release theirs: a deterministic head-of-line deadlock.
		// Rotate through the snapshot so every pending entry is serviced. Under actual
		// backpressure, scan more cheap state entries while keeping expensive light
		// sweeps at the same bounded 16/tick budget.
		long schedulerAdminStartedNanos = System.nanoTime();
		int pendingAtStart = lightFinalizerSession().pendingTicks.size();
		rotateStaleLightRelightResidencyTickets(world, pendingAtStart);
		int scanLimit = pendingAtStart >= 1024 ? LIGHT_SCHEDULER_SAMPLE_BACKLOG : LIGHT_SCHEDULER_SAMPLE_NORMAL;

		// v253.125.35: event-driven scheduler lanes. Priority changes enqueue/migrate
		// chunks when they happen, so the hot path no longer scans the visible set or
		// globally rank-sorts a rotating generic cohort just to rediscover state already
		// known by the mutation/rejoin code. Visible -> fresh physical -> ordinary is the
		// same semantic priority as before. Inside the physical/ordinary lanes, naturally
		// resident or current-locality work is emitted first from a bounded primitive
		// cohort; the fallback cohort remains fair because every live node is rotated.
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		compactSchedulerLaneIfBloated(session.visibleWorkOrder, session.visibleWorkMembership);
		compactSchedulerLaneIfBloated(session.terrainWorkOrder, session.terrainWorkMembership);
		compactSchedulerLaneIfBloated(session.pendingWorkOrder, session.pendingWorkMembership);
		if (session.visibleWorkOrder.isEmpty() && session.terrainWorkOrder.isEmpty()
				&& session.pendingWorkOrder.isEmpty() && !session.pendingTicks.isEmpty()) {
			int repaired = session.pendingTicks.copyFirstKeys(session.schedulerCandidatePacked, scanLimit);
			for (int i = 0; i < repaired; i++) ensureLightWorkQueued(session.schedulerCandidatePacked[i]);
			if (repaired > 0) lightTelemetrySession().LIGHT_DIAG_SCHEDULER_QUEUE_REPAIRS.addAndGet(repaired);
		}

		long[] workWindow = session.schedulerWorkWindow;
		long[] visiblePacked = session.schedulerVisiblePacked;
		long[] visibleDistance = session.schedulerVisibleDistanceSq;
		long[] candidatePacked = session.schedulerCandidatePacked;
		int workCount = 0, sampled = 0, visibleCount = 0;

		int visibleScanLimit = Math.min(VISIBLE_PRIORITY_SCAN_LIMIT, scanLimit);
		int visiblePollAttempts = 0;
		int visiblePollCap = Math.max(LIGHT_SCHEDULER_STALE_POLL_FLOOR,
				visibleScanLimit * LIGHT_SCHEDULER_STALE_POLL_MULTIPLIER);
		while (sampled < visibleScanLimit && visiblePollAttempts++ < visiblePollCap) {
			long packed = session.visibleWorkOrder.poll();
			if (packed == OceanCanvasPrimitiveLongQueue.EMPTY) break;
			if (!session.visibleWorkMembership.contains(packed) || !session.pendingTicks.containsKey(packed)) {
				session.visibleWorkMembership.remove(packed);
				continue;
			}
			if (!session.visibleLightPriority.contains(packed)) {
				session.visibleWorkMembership.remove(packed);
				ensureLightWorkQueued(packed);
				continue;
			}
			session.visibleWorkOrder.offer(packed);
			sampled++;
			visiblePacked[visibleCount] = packed;
			visibleDistance[visibleCount] = session.visibleLightPriorityDistanceSq.getOrDefault(packed, Long.MAX_VALUE);
			visibleCount++;
			if (visibleCount >= visiblePacked.length) break;
		}
		sortVisiblePrimitive(visiblePacked, visibleDistance, visibleCount);
		for (int i = 0; i < visibleCount && workCount < scanLimit; i++) workWindow[workCount++] = visiblePacked[i];

		long localityAnchorTile = session.lastProductiveTileKey.get();
		boolean hasNormalPendingInWindow = visibleCount > 0;
		int fallbackCount = 0;
		int reserveForOrdinary = session.pendingWorkMembership.isEmpty() ? 0 : Math.max(1, scanLimit / 4);
		int terrainScanBudget = Math.max(0, scanLimit - workCount - reserveForOrdinary);
		int terrainScanned = 0;
		int terrainPollAttempts = 0;
		int terrainPollCap = Math.max(LIGHT_SCHEDULER_STALE_POLL_FLOOR,
				Math.max(1, terrainScanBudget) * LIGHT_SCHEDULER_STALE_POLL_MULTIPLIER);
		while (terrainScanned < terrainScanBudget && sampled < scanLimit
				&& terrainPollAttempts++ < terrainPollCap) {
			long packed = session.terrainWorkOrder.poll();
			if (packed == OceanCanvasPrimitiveLongQueue.EMPTY) break;
			if (!session.terrainWorkMembership.contains(packed) || !session.pendingTicks.containsKey(packed)) {
				session.terrainWorkMembership.remove(packed);
				continue;
			}
			if (session.visibleLightPriority.contains(packed) || !session.allowPhysicalRepair.contains(packed)) {
				session.terrainWorkMembership.remove(packed);
				ensureLightWorkQueued(packed);
				continue;
			}
			session.terrainWorkOrder.offer(packed);
			terrainScanned++; sampled++;
			int pass = session.pendingPasses.getOrDefault(packed, 0);
			if (pass < LIGHT_PATHOLOGY_BACKGROUND_PASS) hasNormalPendingInWindow = true;
			boolean centerLoaded = session.loadedChunkHints.contains(packed);
			boolean sameTile = localityAnchorTile != Long.MIN_VALUE && lightLocalityTileKey(packed) == localityAnchorTile;
			if ((centerLoaded || sameTile) && workCount < scanLimit) workWindow[workCount++] = packed;
			else if (fallbackCount < candidatePacked.length) candidatePacked[fallbackCount++] = packed;
		}
		for (int i = 0; i < fallbackCount && workCount < scanLimit; i++) workWindow[workCount++] = candidatePacked[i];

		fallbackCount = 0;
		int ordinaryScanned = 0;
		int ordinaryPollAttempts = 0;
		int ordinaryPollCap = Math.max(LIGHT_SCHEDULER_STALE_POLL_FLOOR,
				Math.max(1, scanLimit - sampled) * LIGHT_SCHEDULER_STALE_POLL_MULTIPLIER);
		while (sampled < scanLimit && ordinaryPollAttempts++ < ordinaryPollCap) {
			long packed = session.pendingWorkOrder.poll();
			if (packed == OceanCanvasPrimitiveLongQueue.EMPTY) break;
			if (!session.pendingWorkMembership.contains(packed) || !session.pendingTicks.containsKey(packed)) {
				session.pendingWorkMembership.remove(packed);
				continue;
			}
			if (session.visibleLightPriority.contains(packed) || session.allowPhysicalRepair.contains(packed)) {
				session.pendingWorkMembership.remove(packed);
				ensureLightWorkQueued(packed);
				continue;
			}
			session.pendingWorkOrder.offer(packed);
			ordinaryScanned++; sampled++;
			int pass = session.pendingPasses.getOrDefault(packed, 0);
			if (pass < LIGHT_PATHOLOGY_BACKGROUND_PASS) hasNormalPendingInWindow = true;
			boolean centerLoaded = session.loadedChunkHints.contains(packed);
			boolean sameTile = localityAnchorTile != Long.MIN_VALUE && lightLocalityTileKey(packed) == localityAnchorTile;
			if ((centerLoaded || sameTile) && workCount < scanLimit) workWindow[workCount++] = packed;
			else if (fallbackCount < candidatePacked.length) candidatePacked[fallbackCount++] = packed;
		}
		for (int i = 0; i < fallbackCount && workCount < scanLimit; i++) workWindow[workCount++] = candidatePacked[i];
		lightTelemetrySession().LIGHT_DIAG_SCHEDULER_SAMPLED.addAndGet(sampled);
		int scanCount = workCount;
		long schedulerAdminElapsed = Math.max(0L, System.nanoTime() - schedulerAdminStartedNanos);
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_SCHEDULER_MAX_ADMIN_NANOS, schedulerAdminElapsed);

		int activeWorkBudget = pendingAtStart >= LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER
				? LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_BACKLOG
				: LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_NORMAL;
		int pathologyWorkBudget = hasNormalPendingInWindow
				? Math.max(1, activeWorkBudget / LIGHT_PATHOLOGY_FAIR_SHARE_DIVISOR)
				: activeWorkBudget;
		int pathologyWorkUsed = 0;
		int sweepBudget = pendingAtStart >= 1024 ? 32 : 16;
		// Start the wall-time budget only after queue administration. This budget now
		// measures actual block/light work rather than HashMap/ArrayList construction.
		long finalizerTickStartedNanos = System.nanoTime();
		for (int scan = 0; scan < scanCount; scan++) {
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
			long packed = workWindow[scan];
			int current = lightFinalizerSession().pendingTicks.get(packed);
			if (current == OceanCanvasPrimitiveLongIntMap.ABSENT) continue;
			int remaining = current - 1;
			if (remaining > 0) {
				lightFinalizerSession().pendingTicks.put(packed, remaining);
				continue;
			}

			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			LevelChunk live = world.getChunkSource().getChunkNow(cx, cz);
			if (live == null) {
				if (!ensureLightRelightResidencyTicket(world, packed, cx, cz)) {
					lightFinalizerSession().pendingTicks.put(packed, 2);
				} else {
					int waits = lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAITS.incrementCapped(packed, Integer.MAX_VALUE);
					if (waits == 20 && recordNeighborStarvedAndShouldWarn()) {
						OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-DIAG chunk {},{} classification=CENTER_LOADING_UNDER_TICKET waits={} potentialCause=final light entry is waiting for its bounded residency ticket to reload the chunk.", cx, cz, waits);
					}
					lightFinalizerSession().pendingTicks.put(packed, 1);
				}
				continue;
			}

			int pass = lightFinalizerSession().pendingPasses.getOrDefault(packed, 0);

			// The sweep needs the 3x3 neighborhood resident, but do not install a
			// forced ticket when it is already naturally resident under Pregen/player
			// ownership. This removes almost all of the v253.23 ticket churn on the
			// normal fast path; a bounded ticket is only a recovery mechanism.
			if (!isLightNeighborhoodLoaded(world, cx, cz)) {
				if (!ensureLightRelightResidencyTicket(world, packed, cx, cz)) {
					lightFinalizerSession().pendingTicks.put(packed, 2);
					continue;
				}
				int waits = lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAITS.incrementCapped(packed, Integer.MAX_VALUE);
				if (waits == 20 && recordNeighborStarvedAndShouldWarn()) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-DIAG chunk {},{} classification=NEIGHBOR_LOADING_UNDER_TICKET waits={} potentialCause=bounded radius-1 light residency ticket is installed but the 3x3 neighborhood has not reached loaded state yet.", cx, cz, waits);
				}
				lightFinalizerSession().pendingTicks.put(packed, 1);
				continue;
			}

			// v253.69.2: a natural shipwreck can be discovered/protected before its
			// footprint+buffer reaches ticking-ready and relocation can run. Certifying
			// lighting in that interval caused the verifier to fight blocks that were
			// known to change later (91 reseeds / 13 hard resets in the long soak). Hold
			// only the small local footprint/ring; terrain admission remains independent.
			if (OceanCanvasProtectedData.get(world).pendingOriginalProtectionTouchesChunkRing(live.getPos(), 1)) {
				lightTelemetrySession().LIGHT_DIAG_STRUCTURE_BARRIER_TICKS.incrementAndGet();
				// Same liveness rule as terrain/quiet barriers: stage-0 proof cannot
				// progress while structure geometry is known to be mutable, so do not
				// pin one of the 64 residency slots while waiting.
				if (pass <= 0 && lightFinalizerSession().relightResidencyLedger.contains(packed)) {
					releaseLightRelightResidencyTicket(world, packed);
					lightTelemetrySession().LIGHT_DIAG_STRUCTURE_BARRIER_TICKET_RELEASES.incrementAndGet();
				}
				lightFinalizerSession().pendingTicks.put(packed, 1);
				continue;
			}

			// Freshly authored chunks cannot receive a durable light certificate while an
			// adjacent target may still carve/fall/flow into this boundary later. Because
			// terrain ownership for this chunk has already retired, this is acyclic: the
			// neighboring terrain jobs can continue and release the barrier independently.
			if (lightFinalizerSession().allowPhysicalRepair.contains(packed) && adjacentPregenTerrainMayStillMutate(live.getPos())) {
				lightTelemetrySession().LIGHT_DIAG_TERRAIN_BARRIER_TICKS.incrementAndGet();
				// v253.125.23: a pre-stage entry blocked on real adjacent terrain has no
				// useful light work to perform yet. Do not let it monopolize one of the 64
				// scarce relight residency tickets while waiting. Releasing only residency
				// is fail-closed: the pending proof stays armed and will reacquire a ticket
				// if the chunk/neighborhood is no longer naturally resident when the
				// admitted terrain owner retires.
				if (pass <= 0 && lightFinalizerSession().relightResidencyLedger.contains(packed)) {
					releaseLightRelightResidencyTicket(world, packed);
					lightTelemetrySession().LIGHT_DIAG_TERRAIN_BARRIER_TICKET_RELEASES.incrementAndGet();
				}
				lightFinalizerSession().pendingTicks.put(packed, 1);
				continue;
			}

			// Own-chunk scheduled physics can outlive the raw carve by a few ticks even
			// after every adjacent target has retired. Require a real game-time quiet
			// window before stage 0; this catches delayed gravel/water ticks before they
			// can become POST_CERT_PHYSICAL_MUTATION.
			long lastTerrainMutation = lightFinalizerSession().terrainLastMutationTick.get(packed);
			int requiredTerrainQuietTicks = terrainQuietTicksFor(packed);
			if (lightFinalizerSession().allowPhysicalRepair.contains(packed) && lastTerrainMutation != OceanCanvasPrimitiveLongLongMap.ABSENT
					&& world.getGameTime() - lastTerrainMutation < requiredTerrainQuietTicks) {
				lightTelemetrySession().LIGHT_DIAG_TERRAIN_BARRIER_TICKS.incrementAndGet();
				releaseQuietWaitResidencyIfOwned(world, packed, pass);
				lightFinalizerSession().pendingTicks.put(packed, 1);
				continue;
			}

			// v253.125.28: isolate pathological retry cohorts from the ordinary square
			// throughput lane. They remain strict completion debt and visible repairs
			// bypass this cap, but while ordinary work exists they may consume at most
			// one quarter of the bounded expensive-work slots for this tick. This avoids
			// a few escalation-heavy chunks monopolizing the 20k overnight pipeline.
			boolean playerVisiblePriority = lightFinalizerSession().visibleLightPriority.contains(packed);
			boolean pathologyWork = pass >= LIGHT_PATHOLOGY_BACKGROUND_PASS && !playerVisiblePriority;
			if (pathologyWork && pathologyWorkUsed >= pathologyWorkBudget) {
				int deferTicks = 2 + Math.floorMod((int)(packed ^ (packed >>> 32)), 5);
				lightFinalizerSession().pendingTicks.put(packed, deferTicks);
				lightTelemetrySession().LIGHT_DIAG_PATHOLOGY_FAIR_SHARE_DEFERRALS.incrementAndGet();
				continue;
			}

			// v253.72.9: all code below this point performs non-trivial block/light
			// inspection or mutation. Bound it by both operation count and wall time.
			// Cheap residency/barrier bookkeeping above remains fair even when the
			// expensive budget is exhausted. A 1-4 tick deterministic deferral avoids
			// rebuilding a synchronized due cohort on the next tick.
			if (activeWorkBudget <= 0 || System.nanoTime() - finalizerTickStartedNanos >= LIGHT_FINALIZER_TICK_TIME_BUDGET_NS) {
				int deferTicks = 1 + Math.floorMod((int)(packed ^ (packed >>> 32)), 4);
				lightFinalizerSession().pendingTicks.put(packed, deferTicks);
				lightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.incrementAndGet();
				continue;
			}
			activeWorkBudget--;
			if (pathologyWork) pathologyWorkUsed++;
			if (lightFinalizerSession().allowPhysicalRepair.contains(packed)) {
				lightTelemetrySession().LIGHT_DIAG_CURRENT_TERRAIN_FIRST.incrementAndGet();
			}
			long productiveTile = lightFinalizerSession().lastProductiveTileKey.get();
			if (productiveTile != Long.MIN_VALUE && lightLocalityTileKey(packed) == productiveTile) {
				lightTelemetrySession().LIGHT_DIAG_TILE_LOCALITY_HITS.incrementAndGet();
			}
			if (!lightFinalizerSession().relightResidencyLedger.contains(packed)) {
				lightTelemetrySession().LIGHT_DIAG_TILE_RESIDENT_FIRST.incrementAndGet();
			}

			// v253.36 hard-reset stage 2. Stage 1 was queued only after repeated
			// verified skylight failure and cleared SKY storage asynchronously. Give
			// the light executor real tick boundaries, then rebuild source tables from
			// current blocks, re-enable the column and explicitly propagate sources.
			int hardResetRadius1Flag = lightRecoverySession().hardSkyResetRadius1.remove(packed);
			if (hardResetRadius1Flag != OceanCanvasPrimitiveLongIntMap.ABSENT) {
				long hardResetStarted = System.nanoTime();
				finishHardSkyStorageReset(world, live, hardResetRadius1Flag == 1);
				recordLightPhaseDuration(world, packed, "HARD_RESET_FINISH", lightTelemetrySession().LIGHT_DIAG_MAX_HARD_RESET_NANOS, hardResetStarted);
				lightFinalizerSession().hardResetFingerprintPending.add(packed);
				lightFinalizerSession().pendingTicks.put(packed, 1);
				continue;
			}
			if (lightFinalizerSession().hardResetFingerprintPending.contains(packed)) {
				if (!lightFinalizerSession().hardResetFingerprintState.containsKey(packed) && deferHeavyFinalizerPhaseForHeap(world, packed)) {
					lightFinalizerSession().pendingTicks.put(packed, LIGHT_HEAVY_PHASE_HEAP_PAUSE_TICKS);
					continue;
				}
				long hardResetFingerprintStarted = System.nanoTime();
				FingerprintAdvance hardResetFp = advanceBoundaryFingerprint(world, live, lightFinalizerSession().hardResetFingerprintState, packed);
				recordLightPhaseDuration(world, packed, "BOUNDARY_FINGERPRINT_AFTER_HARD_RESET_SLICE", lightTelemetrySession().LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS, hardResetFingerprintStarted);
				if (!hardResetFp.complete()) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }
				lightFinalizerSession().hardResetFingerprintPending.remove(packed);
				lightFinalizerSession().stagedBlockFingerprint.put(packed, hardResetFp.hash());
				lightFinalizerSession().pendingPasses.put(packed, 1);
				lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_FINAL_SETTLE_TICKS);
				continue;
			}

			// Stage 1: only freshly-authored terrain may repair physical state. A restart,
			// late chunk load, or adjacent-boundary re-arm is deliberately LIGHT-ONLY;
			// it must not canonicalize blocks that may now contain player/gameplay work.
			boolean allowPhysicalRepair = lightFinalizerSession().allowPhysicalRepair.contains(packed);
			// v253.125.25: stage 1 already performs the exhaustive pre-publish physical
			// audit below. Running this same whole-column audit again before the stage
			// branch doubled one of the most expensive read-only scans in the hot path.
			// Keep the pre-light audit only at stage 0; stage 1 remains fail-closed via
			// fingerprint + fluid guard + exhaustive latePhysical before publication.
			boolean runPreLightPhysicalAudit = allowPhysicalRepair && pass <= 0
					&& !lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed);
			PhysicalProfileMismatch physicalBeforeLight = null;
			if (runPreLightPhysicalAudit) {
				if (deferHeavyFinalizerPhaseForHeap(world, packed)) {
					lightFinalizerSession().pendingTicks.put(packed, LIGHT_HEAVY_PHASE_HEAP_PAUSE_TICKS);
					continue;
				}
				long physicalAuditStarted = System.nanoTime();
				PhysicalAuditAdvance audit = advancePhysicalProfileAudit(world, live, OceanCanvasConfig.get(),
						lightFinalizerSession().preLightPhysicalAuditState, packed, true);
				recordLightPhaseDuration(world, packed, "PHYSICAL_AUDIT_PRE_LIGHT_SLICE", lightTelemetrySession().LIGHT_DIAG_MAX_PHYSICAL_AUDIT_NANOS, physicalAuditStarted);
				if (!audit.complete()) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }
				physicalBeforeLight = audit.mismatch();
				if (physicalBeforeLight == null) {
					lightFinalizerSession().preLightPhysicalAuditComplete.add(packed);
					// v253.125.52: physical correctness is proven for this server lifetime.
					// Keep repair permission/durable identity until light publication, but
					// release the PHYSICAL admission-window slot now.
					markPhysicalRecoveryTerrainSafe(packed);
					// .35 fuses the stage-0 block fingerprint into the exhaustive physical
					// traversal. Same six samples/column, same FNV order; only the duplicate
					// second pass is removed.
					lightFinalizerSession().stagedBlockFingerprint.put(packed, audit.boundaryFingerprint());
				}
			}
			if (physicalBeforeLight != null) {
				rearmPhysicalRecoveryTerrainUnsafe(packed);
				lightTelemetrySession().LIGHT_DIAG_PROFILE_REPAIRS_AFTER_CERT.incrementAndGet();
				int quietTicks = notePhysicalInstability(world, packed);
				int instabilityStreak = lightFinalizerSession().terrainInstabilityStreak.getOrDefault(packed, 0);
				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-ROOT-CAUSE build={} chunk={},{} classification=POST_CERT_PHYSICAL_MUTATION stage={} first={} reason={} instabilityStreak={} requiredQuietTicks={} action=repair-profile-and-restart-stage-0",
					net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, pass,
					new BlockPos(physicalBeforeLight.x(), physicalBeforeLight.y(), physicalBeforeLight.z()), physicalBeforeLight.reason(), instabilityStreak, quietTicks);
				repairCanonicalCanvasProfile(world, live, OceanCanvasConfig.get());
				resetSkyRecoveryForPhysicalMutation(packed);
				// A real post-cert block repair can invalidate adjacent boundary lighting.
				// Re-arm those neighbors LIGHT-ONLY so repair cannot cascade into player work.
				rearmCanonicalNeighborLighting(world, live.getPos());
				net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(
					live, java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class));
				live.markUnsaved();
				lightFinalizerSession().stagedBlockFingerprint.remove(packed);
				lightFinalizerSession().pendingPasses.put(packed, 0);
				lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
				continue;
			}

			if (pass == 0) {
				if (sweepBudget-- <= 0) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }
				// Heightmap priming remains mandatory even when the fresh-terrain
				// physical audit already supplied the identical stage-0 fingerprint.
				if (!lightFinalizerSession().relightStartedNs.containsKey(packed)) {
					if (deferHeavyFinalizerPhaseForHeap(world, packed)) { lightFinalizerSession().pendingTicks.put(packed, LIGHT_HEAVY_PHASE_HEAP_PAUSE_TICKS); continue; }
					lightFinalizerSession().relightStartedNs.put(packed, System.nanoTime());
					lightTelemetrySession().LIGHT_DIAG_PRE_PRIME.put(packed, sampleHeightmapAgreement(live));
					net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(live, java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class));
					live.markUnsaved();
				}
				if (!lightFinalizerSession().stagedBlockFingerprint.containsKey(packed)) {
					long fingerprintStarted = System.nanoTime();
					FingerprintAdvance fp = advanceBoundaryFingerprint(world, live, lightFinalizerSession().stage0FingerprintState, packed);
					recordLightPhaseDuration(world, packed, "BOUNDARY_FINGERPRINT_STAGE0_SLICE", lightTelemetrySession().LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS, fingerprintStarted);
					if (!fp.complete()) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }
					long fingerprint = fp.hash();
					lightFinalizerSession().stagedBlockFingerprint.put(packed, fingerprint);
					live.markUnsaved();
					OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-STAGE build={} chunk={},{} stage=PHYSICAL_CERTIFIED_HEIGHTMAP_PRIMED fingerprint={} pendingSync={} activeTickets={} neighborhoodLoaded=true", net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, Long.toUnsignedString(fingerprint), lightFinalizerSession().pendingTicks.size(), lightFinalizerSession().relightResidencyLedger.activeCount());
				}
				if (!advanceLiveChunkLightSweep(world, live, true, packed)) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }
				lightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);
				lightFinalizerSession().pendingPasses.put(packed, 1);
				lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_FINAL_SETTLE_TICKS);
				continue;
			}

			// Stage 3: prove nobody changed the canonical boundary while lighting was
			// settling. This is intentionally independent of the physical audit above:
			// a write can replace one valid Canvas state with another valid-looking state
			// yet still invalidate the light graph. If the fingerprint moves, restart.
			long stagedFingerprint = lightFinalizerSession().stagedBlockFingerprint.get(packed);
			long currentFingerprint = stagedFingerprint == OceanCanvasPrimitiveLongLongMap.ABSENT ? Long.MIN_VALUE : stagedFingerprint;
			if (!lightFinalizerSession().stage1FingerprintVerified.contains(packed)) {
				if (!lightFinalizerSession().stage1FingerprintState.containsKey(packed) && deferHeavyFinalizerPhaseForHeap(world, packed)) { lightFinalizerSession().pendingTicks.put(packed, LIGHT_HEAVY_PHASE_HEAP_PAUSE_TICKS); continue; }
				long verifyFingerprintStarted = System.nanoTime();
				FingerprintAdvance verifyFp = advanceBoundaryFingerprint(world, live, lightFinalizerSession().stage1FingerprintState, packed);
				recordLightPhaseDuration(world, packed, "BOUNDARY_FINGERPRINT_VERIFY_SLICE", lightTelemetrySession().LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS, verifyFingerprintStarted);
				if (!verifyFp.complete()) { lightFinalizerSession().pendingTicks.put(packed,1); continue; }
				currentFingerprint = verifyFp.hash();
			}
			if (stagedFingerprint == OceanCanvasPrimitiveLongLongMap.ABSENT || stagedFingerprint != currentFingerprint) {
				rearmPhysicalRecoveryTerrainUnsafe(packed);
				lightTelemetrySession().LIGHT_DIAG_POST_STAGE_MUTATIONS.incrementAndGet();
				int quietTicks = notePhysicalInstability(world, packed);
				int instabilityStreak = lightFinalizerSession().terrainInstabilityStreak.getOrDefault(packed, 0);
				resetSkyRecoveryForPhysicalMutation(packed);
				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-ROOT-CAUSE build={} chunk={},{} classification=BLOCKS_CHANGED_DURING_LIGHT_SETTLE beforeFingerprint={} afterFingerprint={} instabilityStreak={} requiredQuietTicks={} action=restart-from-heightmap-and-light-stage",
					net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz,
					stagedFingerprint == OceanCanvasPrimitiveLongLongMap.ABSENT ? "missing" : Long.toUnsignedString(stagedFingerprint), Long.toUnsignedString(currentFingerprint), instabilityStreak, quietTicks);
				lightFinalizerSession().stagedBlockFingerprint.remove(packed);
				lightFinalizerSession().pendingPasses.put(packed, 0);
				lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
				continue;
			}
			lightFinalizerSession().stage1FingerprintVerified.add(packed);

			// A late fluid tick can manufacture stone/cobblestone after the original
			// carve. This repair is a TERRAIN mutation and therefore belongs to the same
			// freshly-authored-only safety boundary as physical-profile repair above.
			// Restart/late-load/neighbor reconciliation must remain strictly light-only.
			if (allowPhysicalRepair) {
				FluidSettleRepair repair = repairUnexpectedFluidReactionProducts(world, live, OceanCanvasConfig.get());
				if (repair.repairedBlocks() > 0) {
					rearmPhysicalRecoveryTerrainUnsafe(packed);
					int quietTicks = notePhysicalInstability(world, packed);
					resetSkyRecoveryForPhysicalMutation(packed);
					lightTelemetrySession().FLUID_SETTLE_REPAIRED_BLOCKS.addAndGet(repair.repairedBlocks());
					lightTelemetrySession().FLUID_SETTLE_REPAIRED_CHUNKS.incrementAndGet();
					OceanCanvas.LOGGER.warn("(Ocean Canvas) FLUID-SETTLE-TARGETED-REPAIR build={} chunk={},{} repairedBlocks={} first={} oldState={} instabilityStreak={} requiredQuietTicks={} cumulativeBlocks={} cumulativeChunks={}",
							net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, repair.repairedBlocks(), repair.firstPosition(), repair.firstOldState(),
							lightFinalizerSession().terrainInstabilityStreak.getOrDefault(packed, 0), quietTicks,
							lightTelemetrySession().FLUID_SETTLE_REPAIRED_BLOCKS.get(), lightTelemetrySession().FLUID_SETTLE_REPAIRED_CHUNKS.get());
					live.markUnsaved();
					rearmCanonicalNeighborLighting(world, live.getPos());
					lightFinalizerSession().stagedBlockFingerprint.remove(packed);
					lightFinalizerSession().pendingPasses.put(packed, 0);
					lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
					continue;
				}
			}

			// v253.72.4: biome lookup guards are visual metadata, not block-terrain
			// mutation. Re-assert them for BOTH physical-aware and LIGHT_ONLY recovery
			// entries so already-committed chunks from older builds receive the new
			// below-floor guard during ordinary restart/rejoin recovery. This does not
			// weaken the v253.72.2 rule that LIGHT_ONLY replay cannot change blocks.
			OceanCanvasConfig finalConfig = OceanCanvasConfig.get();
			boolean biomeReasserted = OceanCanvasBiomeMasker.maskChunkIfEnabled(
					world, live, finalConfig.oceanFloorY(), finalConfig.oceanFloorVariation(), OceanCanvasConfig.WATER_SURFACE_Y);
			if (biomeReasserted && !allowPhysicalRepair) {
				live.markUnsaved();
				OceanCanvas.LOGGER.debug("(Ocean Canvas) BIOME-LOOKUP-GUARD-REASSERT build={} chunk={},{} mode=LIGHT_ONLY floorGuardCells={} surfaceGuardCells={} action=publish-with-authoritative-chunk",
					net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, OceanCanvasBiomeMasker.floorGuardRepairedCells(), OceanCanvasBiomeMasker.surfaceGuardRepairedCells());
			}

			// v253.71: run the final exhaustive PHYSICAL audit only on entries that
			// are explicitly allowed to repair blocks. Ice/waterfall repair semantics
			// remain exactly as before.
			if (allowPhysicalRepair && biomeReasserted) {
				resetSkyRecoveryForPhysicalMutation(packed); rearmCanonicalNeighborLighting(world, live.getPos());
				net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(live, java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class)); live.markUnsaved();
				lightFinalizerSession().stagedBlockFingerprint.remove(packed); lightFinalizerSession().pendingPasses.put(packed,0); lightFinalizerSession().pendingTicks.put(packed,LIGHT_SYNC_VERIFY_RETRY_TICKS); continue;
			}
			if (allowPhysicalRepair && !lightFinalizerSession().prePublishPhysicalAuditComplete.contains(packed)) {
				if (deferHeavyFinalizerPhaseForHeap(world, packed)) { lightFinalizerSession().pendingTicks.put(packed, LIGHT_HEAVY_PHASE_HEAP_PAUSE_TICKS); continue; }
				long latePhysicalAuditStarted = System.nanoTime();
				PhysicalAuditAdvance lateAudit = advancePhysicalProfileAudit(world, live, finalConfig, lightFinalizerSession().prePublishPhysicalAuditState, packed, false);
				recordLightPhaseDuration(world, packed, "PHYSICAL_AUDIT_PRE_PUBLISH_SLICE", lightTelemetrySession().LIGHT_DIAG_MAX_PHYSICAL_AUDIT_NANOS, latePhysicalAuditStarted);
				if (!lateAudit.complete()) { lightFinalizerSession().pendingTicks.put(packed,1); continue; }
				PhysicalProfileMismatch latePhysical = lateAudit.mismatch();
				if (latePhysical != null) {
					lightTelemetrySession().LIGHT_DIAG_PROFILE_REPAIRS_AFTER_CERT.incrementAndGet();
					int quietTicks = notePhysicalInstability(world, packed);
					int instabilityStreak = lightFinalizerSession().terrainInstabilityStreak.getOrDefault(packed, 0);
					OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-ROOT-CAUSE build={} chunk={},{} classification=PRE_PUBLISH_PHYSICAL_MUTATION stage={} first={} reason={} instabilityStreak={} requiredQuietTicks={} action=repair-and-restart-before-authoritative-send",
						net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, pass,
						new BlockPos(latePhysical.x(), latePhysical.y(), latePhysical.z()), latePhysical.reason(), instabilityStreak, quietTicks);
					repairCanonicalCanvasProfile(world, live, finalConfig);
					resetSkyRecoveryForPhysicalMutation(packed);
					rearmCanonicalNeighborLighting(world, live.getPos());
					net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(live, java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class));
					live.markUnsaved();
					lightFinalizerSession().stagedBlockFingerprint.remove(packed);
					lightFinalizerSession().pendingPasses.put(packed, 0);
					lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
					continue;
				}
				lightFinalizerSession().prePublishPhysicalAuditComplete.add(packed);
			}

			long skyBackoffUntil = lightRecoverySession().skyBackoffUntilTick.get(packed);
			if (skyBackoffUntil != OceanCanvasPrimitiveLongLongMap.ABSENT) {
				long remainingBackoff = skyBackoffUntil - world.getGameTime();
				if (remainingBackoff > 0L) {
					lightFinalizerSession().pendingTicks.put(packed, (int)Math.min(Integer.MAX_VALUE, remainingBackoff));
					continue;
				}
				lightRecoverySession().skyBackoffUntilTick.remove(packed, skyBackoffUntil);
			}

			// v253.25: validate the ACTUAL skylight field before publishing. The
			// lightCorrect boolean proved insufficient in v253.24: it was true while
			// large black/light-blue artifacts were visible. Open-air samples above a
			// canonical water surface must be sky=15 and water-surface samples must be
			// spatially uniform. Escalate only anomalous columns, never every chunk.
			// v253.125.14: visible targets use the stricter sparse deep-overbright
			// oracle; background Pregen retains the proven majority quorum.
			boolean playerVisibleLightTarget = lightFinalizerSession().visibleLightPriority.contains(packed);
			// v253.125.18: visibility is a live fact, not only a historical rejoin/client
			// request tag. If an already-pending background finalizer chunk is now actually
			// tracked by a player, upgrade it in place before sampling so the strict sparse
			// deep-overbright oracle and visible repair ladder can run immediately.
			if (!playerVisibleLightTarget) {
				long trackedDistanceSq = nearestTrackingPlayerDistanceSq(world, live.getPos());
				if (trackedDistanceSq != Long.MAX_VALUE) {
					markVisibleLightPriority(packed, trackedDistanceSq);
					playerVisibleLightTarget = true;
				}
			}
			// v253.125.26: global certificate invariant is now cooperative. The
			// proof accumulator and lazy shaft cache persist across ticks; no result is
			// accepted until every surface/deep/floor/seam phase has completed.
			SkyLightDiag sky = lightFinalizerSession().completedStrictSkyProof.get(packed);
			if (sky == null) {
				if (!lightFinalizerSession().strictSkyProofState.containsKey(packed) && deferHeavyFinalizerPhaseForHeap(world, packed)) {
					lightFinalizerSession().pendingTicks.put(packed, LIGHT_HEAVY_PHASE_HEAP_PAUSE_TICKS);
					continue;
				}
				long strictSkyProofStarted = System.nanoTime();
				StrictSkyProofAdvance proofAdvance = advanceStrictSkyProof(world, live, packed);
				recordLightPhaseDuration(world, packed, "STRICT_SKY_PROOF_SLICE", lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_SKY_PROOF_NANOS, strictSkyProofStarted);
				if (!proofAdvance.complete()) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }
				sky = proofAdvance.diag();
				lightFinalizerSession().completedStrictSkyProof.put(packed, sky);
			}
			// Proof now spans real tick boundaries. Re-check the block-only boundary at
			// the end, cooperatively, so late gravel/fluid physics cannot certify stale SKY.
			long postProofFingerprintStarted = System.nanoTime();
			FingerprintAdvance postProofFp = advanceBoundaryFingerprint(world, live, lightFinalizerSession().postProofFingerprintState, packed);
			recordLightPhaseDuration(world, packed, "BOUNDARY_FINGERPRINT_POST_PROOF_SLICE", lightTelemetrySession().LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS, postProofFingerprintStarted);
			if (!postProofFp.complete()) { lightFinalizerSession().pendingTicks.put(packed,1); continue; }
			long postProofFingerprint = postProofFp.hash();
			lightFinalizerSession().completedStrictSkyProof.remove(packed);
			long proofExpectedFingerprint = lightFinalizerSession().stagedBlockFingerprint.get(packed);
			if (proofExpectedFingerprint == OceanCanvasPrimitiveLongLongMap.ABSENT || proofExpectedFingerprint != postProofFingerprint) {
				lightTelemetrySession().LIGHT_DIAG_POST_STAGE_MUTATIONS.incrementAndGet();
				int quietTicks = notePhysicalInstability(world, packed);
				resetSkyRecoveryForPhysicalMutation(packed);
				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-ROOT-CAUSE build={} chunk={},{} classification=BLOCKS_CHANGED_DURING_COOPERATIVE_SKY_PROOF beforeFingerprint={} afterFingerprint={} requiredQuietTicks={} action=restart-stage-0-before-certificate", net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, proofExpectedFingerprint == OceanCanvasPrimitiveLongLongMap.ABSENT ? "missing" : Long.toUnsignedString(proofExpectedFingerprint), Long.toUnsignedString(postProofFingerprint), quietTicks);
				lightFinalizerSession().stagedBlockFingerprint.remove(packed);
				lightFinalizerSession().pendingPasses.put(packed, 0);
				lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
				continue;
			}
			lightTelemetrySession().LIGHT_DIAG_SKY_SAMPLES.addAndGet(sky.samples());
			lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_SAMPLES.addAndGet(sky.deepSamples());
			lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_ANOMALOUS_LAYERS.addAndGet(sky.deepAnomalousLayers());
			lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_ANOMALOUS_COLUMNS.addAndGet(sky.deepAnomalousColumns());
			lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_OVERBRIGHT_LAYERS.addAndGet(sky.deepOverbrightLayers());
			lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_OVERBRIGHT_COLUMNS.addAndGet(sky.deepOverbrightColumns());

			// v253.73.15: deep-water correctness is no longer an absolute zero-tail
			// oracle. The 73.14 runtime + screenshot showed that whole-section zero SKY
			// replacement can manufacture a hard 16x16 black seam. Surface/deep-dark
			// proof remains strict, while deep-water bright/dark islands are detected by
			// cross-chunk seam continuity and repaired only through public light-engine
			// source/section re-prime operations.
			int hardResetsThisEpoch = lightRecoverySession().hardSkyResetCounts.getOrDefault(packed, 0);

			skyRecovery:
			if (!sky.healthy()) {
				// v253.125.25: a partially submitted deep-zero repair is valid only while
				// the same geometry-gated failure class remains active. If strict proof
				// changes class, discard its cursor before any different repair path runs.
				if (!sky.onlyDeepZeroTailOverbright()) clearIncrementalDeepRepairState(packed);
				int failedProof = lightRecoverySession().verifyEscalations.incrementCapped(packed, 64);
				if (failedProof <= LIGHT_SYNC_PASSIVE_VERIFY_ATTEMPTS) {
					// Do not mutate the light graph yet. A threaded light task that is merely
					// late should be allowed to converge on its own; if it remains wrong after
					// two additional quiet windows, the existing repair ladder takes over.
					lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
					continue;
				}
				lightTelemetrySession().LIGHT_DIAG_SKY_ANOMALOUS.incrementAndGet();
				int escalation = failedProof - LIGHT_SYNC_PASSIVE_VERIFY_ATTEMPTS;
				lightTelemetrySession().LIGHT_DIAG_SKY_ESCALATIONS.incrementAndGet();
				maybeRecordPathologyHotspot(packed, escalation, playerVisibleLightTarget, sky);
				boolean detailedLightWarning = shouldLogDetailedLightWarning(world, packed);
				if (detailedLightWarning) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-DIAG chunk {},{} classification=SKYLIGHT_FIELD_INCONSISTENT escalation={} surfaceSamples={} aboveSky={}..{} waterSky={}..{} aboveNot15={} deepSamples={} deepAnomalousLayers={} deepAnomalousColumns={} deepOverbrightLayers={} deepOverbrightColumns={} anomalousColumns={} firstAnomaly={} firstDeepAnomaly={} firstDeepActual={} firstDeepRequiredMin={} firstDeepRequiredMax={} firstDeepDepth={} potentialCause=actual server skylight violates the canonical plain-water field; detailed repeats are rate-limited and aggregated",
						cx, cz, escalation, sky.samples(), sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax(), sky.aboveNot15(),
						sky.deepSamples(), sky.deepAnomalousLayers(), sky.deepAnomalousColumns(), sky.deepOverbrightLayers(), sky.deepOverbrightColumns(), sky.anomalousColumns(), sky.firstAnomaly(), sky.firstDeepAnomaly(),
						sky.firstDeepActual(), sky.firstDeepRequiredMin(), sky.firstDeepRequiredMax(), sky.firstDeepDepth());
				}

				// v253.125.20: correctness remains global, but background deep repair is
				// phase-separated from outstanding terrain. The .19 runtime proved that an
				// unbounded cohort of global 23k-41k checkBlock waves can stall terrain for
				// minutes and blow the server tick budget. Keep the dirty certificate as
				// dormant debt and resume it automatically once terrain ownership drains.
				if (sky.onlyDeepZeroTailOverbright() && !playerVisibleLightTarget
						&& parkGlobalBackgroundRepairForTerrainLiveness(world, packed)) {
					clearIncrementalDeepRepairState(packed);
					continue;
				}

				// v253.125.19 globalized deep-water repair (originated in v253.125.14 visible recovery). The 125.13 runtime
				// proved sparse public re-primes were insufficient: >114k sampled deep
				// overbright columns survived while the surface stayed canonical. A geometry-proven
				// overbright-only failure gets at most two dense public checkBlock passes, one
				// fully deterministic plain-water section per pass. The path is globally bounded by the existing finalizer work/time budgets; no player visit is required.
				if (sky.onlyDeepZeroTailOverbright()) {
					int denseAttempts = lightRecoverySession().visibleDeepDenseRepairCounts.getOrDefault(packed, 0);
					if (denseAttempts < LIGHT_MAX_VISIBLE_DEEP_DENSE_REPAIRS_PER_PHYSICAL_EPOCH) {
						VisibleDeepDenseRelightResult dense = queueVisibleDeepDenseRelight(world, live, sky);
						if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
						if (dense.inProgress()) {
							lightFinalizerSession().pendingTicks.put(packed, Math.max(1, dense.retryTicks()));
							continue;
						}
						if (dense.sections() > 0) {
							lightRecoverySession().visibleDeepDenseRepairCounts.put(packed, denseAttempts + 1);
							lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_REPAIRS.incrementAndGet();
							lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_SECTIONS.addAndGet(dense.sections());
							OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-VISIBLE-DEEP-DENSE-REPAIR build={} chunk={},{} attempt={} sections={} checks={} firstDeep={} actual={} depth={} scope={} action=public-checkBlock-all-cells-in-safe-section-then-strict-reverify delivery=resumable-sliced",
									net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, denseAttempts + 1, dense.sections(), dense.checks(),
									sky.firstDeepAnomaly(), sky.firstDeepActual(), sky.firstDeepDepth(),
									playerVisibleLightTarget ? "VISIBLE" : "GLOBAL_CERTIFICATE");
							lightFinalizerSession().pendingTicks.put(packed, LIGHT_VISIBLE_DEEP_DENSE_REPAIR_SETTLE_TICKS);
							continue;
						}
						lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_INCONCLUSIVE.incrementAndGet();
					}
				}

				// v253.125.19 globalized cross-chunk escalation (originated in v253.125.16). v253.125.15
				// finally hit the exact bad y=30/depth-32 sections, but after the second
				// wave the client showed repaired SKY 0 immediately beside SKY 7-9 in
				// neighboring chunks. That is a connected stale-light graph, not a
				// center-section problem. Re-check the same vertical section across a
				// bounded resident neighborhood, then propagate sources for every member.
				if (sky.onlyDeepZeroTailOverbright()
						&& lightRecoverySession().visibleDeepDenseRepairCounts.getOrDefault(packed, 0)
								>= LIGHT_MAX_VISIBLE_DEEP_DENSE_REPAIRS_PER_PHYSICAL_EPOCH) {
					int clusterAttempts = lightRecoverySession().visibleDeepClusterRepairCounts.getOrDefault(packed, 0);
					if (clusterAttempts < LIGHT_MAX_VISIBLE_DEEP_CLUSTER_REPAIRS_PER_PHYSICAL_EPOCH) {
						if (!hasIncrementalClusterRepair(packed)) {
							int throttleDelay = claimVisibleDeepClusterRepairBudget(world);
							if (throttleDelay > 0) {
								lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_THROTTLED.incrementAndGet();
								lightFinalizerSession().pendingTicks.put(packed, throttleDelay);
								continue;
							}
						}
						VisibleDeepClusterRelightResult cluster = queueVisibleDeepClusterRelight(world, live, sky, clusterAttempts);
						if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
						if (cluster.inProgress()) {
							lightFinalizerSession().pendingTicks.put(packed, Math.max(1, cluster.retryTicks()));
							continue;
						}
						if (cluster.missingChunks() > 0) {
							lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_INCONCLUSIVE.incrementAndGet();
							// A visible target can straddle the residency edge. Never synchronously
							// load/generate support chunks and never submit a partial cluster. Fall
							// through to the existing fail-closed recovery ladder instead of pinning
							// completion forever waiting for a player to load support chunks.
							OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-VISIBLE-DEEP-CLUSTER-DEFER build={} chunk={},{} attempt={} missingResident={} mode={} action=skip-partial-cluster-and-fall-through-to-existing-fail-closed-recovery",
									net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, clusterAttempts + 1, cluster.missingChunks(), cluster.mode());
						} else if (cluster.chunkSections() > 0) {
							lightRecoverySession().visibleDeepClusterRepairCounts.put(packed, clusterAttempts + 1);
							lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_REPAIRS.incrementAndGet();
							lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_CHUNK_SECTIONS.addAndGet(cluster.chunkSections());
							OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-VISIBLE-DEEP-CLUSTER-REPAIR build={} chunk={},{} attempt={} mode={} chunkSections={} checks={} firstDeep={} actual={} depth={} scope={} action=reseed-resident-connected-light-graph-and-public-checkBlock-before-strict-reverify delivery=resumable-sliced",
									net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, clusterAttempts + 1, cluster.mode(),
									cluster.chunkSections(), cluster.checks(), sky.firstDeepAnomaly(), sky.firstDeepActual(), sky.firstDeepDepth(),
									playerVisibleLightTarget ? "VISIBLE" : "GLOBAL_CERTIFICATE");
							int clusterSettle = clusterAttempts >= 2
									? LIGHT_VISIBLE_DEEP_CLUSTER_COLUMN_SETTLE_TICKS
									: LIGHT_VISIBLE_DEEP_CLUSTER_REPAIR_SETTLE_TICKS;
							lightFinalizerSession().pendingTicks.put(packed, clusterSettle);
							continue;
						} else {
							lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_INCONCLUSIVE.incrementAndGet();
						}
					}
				}

				// Legacy narrow-repair branch retained for state compatibility. Under the
				// v253.73.15 seam oracle onlyDeepZeroTailOverbright() is not produced, but
				// if older persisted state reaches this path the compatibility shim below
				// performs only public graph re-prime operations; it never replaces SKY
				// DataLayers.
				if (sky.onlyDeepZeroTailOverbright()) {
					int scrubCount = lightRecoverySession().deepZeroScrubCounts.getOrDefault(packed, 0);
					if (scrubCount < LIGHT_MAX_DEEP_ZERO_SCRUBS_PER_PHYSICAL_EPOCH) {
						boolean radius1 = scrubCount > 0;
						int scrubbedSections = scrubDeterministicDeepZeroSkyStorage(world, live, radius1);
						if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
						if (scrubbedSections < 0) {
							lightFinalizerSession().pendingTicks.put(packed, 1);
							lightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.incrementAndGet();
							continue;
						}
						lightRecoverySession().deepZeroScrubCounts.put(packed, scrubCount + 1);
						if (scrubbedSections > 0) {
							lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUBS.incrementAndGet();
							lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUB_SECTIONS.addAndGet(scrubbedSections);
							OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-DEEP-ZERO-SCRUB build={} chunk={},{} scrub={} radius1={} sections={} firstDeep={} actual={} requiredMax=0 action=reprime-safe-deep-water-sections-and-reverify; no-block-biome-or-light-storage-replacement",
									net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, scrubCount + 1, radius1, scrubbedSections,
									sky.firstDeepAnomaly(), sky.firstDeepActual());
							lightFinalizerSession().pendingTicks.put(packed, LIGHT_DEEP_ZERO_SCRUB_SETTLE_TICKS);
							continue;
						}
						lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUB_INCONCLUSIVE.incrementAndGet();
						OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-DEEP-ZERO-SCRUB build={} chunk={},{} scrub={} radius1={} sections=0 classification=GEOMETRY_NOT_SAFE_FOR_SECTION_ZERO action=fall-through-to-general-light-repair",
								net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, scrubCount + 1, radius1);
					}

					// Once the two narrow re-primes fail, use the stronger public-API
					// recovery: reseed sources and re-prime the live graph. Never mutate or
					// replace ThreadedLevelLightEngine SKY storage.
					int publicRecoveryCount = lightRecoverySession().deepZeroPublicRecoveryCounts.getOrDefault(packed, 0);
					if (scrubCount >= LIGHT_MAX_DEEP_ZERO_SCRUBS_PER_PHYSICAL_EPOCH
							&& publicRecoveryCount < LIGHT_MAX_DEEP_ZERO_PUBLIC_RECOVERIES_PER_PHYSICAL_EPOCH) {
						boolean radius1 = publicRecoveryCount > 0;
						int repairedSections = queueDeterministicDeepZeroSkyRecovery(world, live, radius1);
						if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
						if (repairedSections < 0) {
							lightFinalizerSession().pendingTicks.put(packed, 1);
							lightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.incrementAndGet();
							continue;
						}
						lightRecoverySession().deepZeroPublicRecoveryCounts.put(packed, publicRecoveryCount + 1);
						if (repairedSections > 0) {
							lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERIES.incrementAndGet();
							lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_SECTIONS.addAndGet(repairedSections);
							// v253.73.5: queueSectionData/source propagation is consumed by the
							// threaded light executor asynchronously. v253.73.4 immediately ran the
							// full strict oracle again in this same server tick, which cannot prove the
							// queued work has executed and consumed a second expensive verifier pass for
							// no correctness benefit. Wait the existing settle window; the ordinary
							// Stage-3 oracle remains the only path to AUTHORITATIVE_SEND.
							OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-DEEP-ZERO-PUBLIC-RECOVERY build={} chunk={},{} repair={} radius1={} sections={} action=queued-public-api-recovery-await-threaded-settle-before-strict-proof",
									net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, publicRecoveryCount + 1, radius1, repairedSections);
							lightFinalizerSession().pendingTicks.put(packed, LIGHT_DEEP_ZERO_PUBLIC_RECOVERY_SETTLE_TICKS);
							continue;
						}
						lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_FAILURES.incrementAndGet();
						OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-DEEP-ZERO-PUBLIC-RECOVERY build={} chunk={},{} repair={} radius1={} sections=0 classification=PUBLIC_RECOVERY_INCONCLUSIVE action=fall-through-to-general-light-repair",
								net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, publicRecoveryCount + 1, radius1);
					}
				}

				// v253.36: the 20:51 runtime showed two chunks surviving more than
				// 140 identical full-column checkBlock sweeps. Repeating the same
				// incremental operation is not recovery. First re-derive ChunkSkyLightSources
				// from the current blocks and explicitly seed the threaded engine. Every
				// eighth failed proof, stage a source/status reseed across real executor/tick
				// boundaries without ever clearing live SKY storage. The second escalation
				// widens the safe source refresh to radius 1 so boundary source tables cannot
				// pin the center chunk in a stale state.
				if (hardResetsThisEpoch >= LIGHT_MAX_HARD_SKY_RESETS_PER_PHYSICAL_EPOCH) {
					// v253.72.9: persistent failures become DORMANT correctness debt. They
					// keep completion blocked but leave the active per-tick finalizer queue,
					// release their residency ticket, and wake through a fair 4-chunk/tick
					// retry lane after an exponential+jittered delay. This eliminates both
					// the 1024-entry countdown scan and the synchronized retry storm.
					if (sky.onlyDeepZeroTailOverbright()) {
						long retryAfter = lightRecoverySession().deepZeroPublicRecoveryRetryAfterTick.getOrDefault(packed, 0L);
						if (world.getGameTime() >= retryAfter) {
							int repairedSections = queueDeterministicDeepZeroSkyRecovery(world, live, true);
							if (repairedSections < 0) {
								lightFinalizerSession().pendingTicks.put(packed, 1);
								lightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.incrementAndGet();
								continue;
							}
							if (repairedSections > 0) {
								lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERIES.incrementAndGet();
								lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_SECTIONS.addAndGet(repairedSections);
								// v253.73.5: as above, never spend a strict verifier pass before
								// the threaded executor has had the configured settle window. Keep the
								// persistent retry deadline armed, then prove on the normal later pass.
								OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-DEEP-ZERO-PUBLIC-RECOVERY-RETRY build={} chunk={},{} sections={} action=queued-public-api-recovery-await-threaded-settle-before-strict-proof",
										net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, repairedSections);
								lightRecoverySession().deepZeroPublicRecoveryRetryAfterTick.put(packed, world.getGameTime() + LIGHT_PERSISTENT_SKY_BACKOFF_TICKS);
								lightFinalizerSession().pendingTicks.put(packed, LIGHT_DEEP_ZERO_PUBLIC_RECOVERY_SETTLE_TICKS);
								continue;
							}
							lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_FAILURES.incrementAndGet();
						}
					}
					int backoffTicks = deferPersistentSkyRepair(world, packed);
					lightRecoverySession().deepZeroPublicRecoveryRetryAfterTick.put(packed, world.getGameTime() + (long)backoffTicks);
					lightTelemetrySession().LIGHT_DIAG_PERSISTENT_SKY_BACKOFFS.incrementAndGet();
					OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-DIAG chunk {},{} classification=PERSISTENT_SKYLIGHT_BACKOFF escalation={} hardResets={} backoffTicks={} action=dormant-strict-debt-release-ticket-and-fair-retry",
							cx, cz, escalation, hardResetsThisEpoch, backoffTicks);
					continue;
				}

				if (escalation >= LIGHT_HARD_SKY_RESET_ESCALATION
						&& ((escalation - LIGHT_HARD_SKY_RESET_ESCALATION) % LIGHT_HARD_SKY_RESET_INTERVAL) == 0) {
					boolean radius1 = hardResetsThisEpoch > 0;
					startHardSkyStorageReset(world, live, radius1);
					lightRecoverySession().hardSkyResetCounts.put(packed, hardResetsThisEpoch + 1);
					lightRecoverySession().hardSkyResetRadius1.put(packed, radius1 ? 1 : 0);
					lightFinalizerSession().pendingTicks.put(packed, LIGHT_HARD_SKY_RESET_CLEAR_TICKS);
					continue;
				}
				if (escalation >= LIGHT_SOURCE_RESEED_ESCALATION) {
					reseedSkyLightSources(world, live);
				}
				queueAnomalousColumnLightRepair(world, live, sky);
				lightFinalizerSession().pendingTicks.put(packed, LIGHT_SYNC_VERIFY_RETRY_TICKS);
				continue;
			}

			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
			live.markUnsaved();
			lightTelemetrySession().LIGHT_DIAG_FINAL_PUBLISHES.incrementAndGet();
			recordProductiveLightTile(packed);
			OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-STAGE build={} chunk={},{} stage=AUTHORITATIVE_SEND pendingSync={} activeTickets={} engineLightCorrect={} skySamples={} aboveSky={}..{} waterSky={}..{} deepSamples={} deepAnomalousLayers={} deepAnomalousColumns={}",
				net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, lightFinalizerSession().pendingTicks.size(), lightFinalizerSession().relightResidencyLedger.activeCount(), live.isLightCorrect(), sky.samples(), sky.aboveMin(), sky.aboveMax(), sky.waterMin(), sky.waterMax(), sky.deepSamples(), sky.deepAnomalousLayers(), sky.deepAnomalousColumns());
			runPostRelightDiagnostics(world, live, packed);
			pushChunkWithAuthoritativeLight(world, live);
			boolean playerVisibleRepair = lightFinalizerSession().visibleLightPriority.contains(packed);
			if (playerVisibleRepair) {
				long visibleRank = lightFinalizerSession().visibleLightPriorityDistanceSq.getOrDefault(packed, Long.MAX_VALUE);
				logVisibleRepairPublish(world, cx, cz, visibleRank, sky);
			}
			net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.publish(
					new net.oceancanvas.mod.compat.OceanCanvasTerrainChange(
							world, live.getPos(),
							playerVisibleRepair
									? net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.VISIBLE_LIGHT_REPAIR
									: net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.CANVAS_WRITE));
			// The persisted certificate is written only after the same verified
			// authoritative publication boundary the player/renderer consumes.
			OceanCanvasProtectedData.get(world).markChunkLightingVerified(live.getPos());
			// v253.72: this exact boundary is the durable Pregen commit signal. The
			// manager advances only a contiguous committed prefix, so a crash can
			// safely rewind to it without confusing submission with completion.
			OceanCanvasActiveTerrainOperationBridge.authoritativeCommit(world, live.getPos());
			lightFinalizerSession().persistedAuditSession.markAudited(packed);
			lightFinalizerSession().persistedAuditSession.removePending(packed);
			retireCompletedLightState(world, packed);
		}
	}

	/**
	 * v253.125.10 single retirement path for strict lighting completion.
	 *
	 * <p>Fast proof, ordinary finalization, and pressure-park proof all converge
	 * through this cleanup so stale retry nodes, visible-priority ranks, tickets,
	 * recovery identity and escalation state cannot survive a successful
	 * authoritative publication.</p>
	 */
	private static void retireCompletedLightState(ServerLevel world, long packed) {
		boolean historicalLightOnly = !lightFinalizerSession().allowPhysicalRepair.contains(packed)
				&& !lightFinalizerSession().visibleLightPriority.contains(packed);
		// A genuine retirement is the only event allowed to refresh this stamp.
		lightFinalizerSession().lastRetirementNs.set(System.nanoTime());
		if (lightRecoverySession().skyQuarantine.remove(packed)) {
			lightTelemetrySession().LIGHT_DIAG_QUARANTINE_RELEASES.incrementAndGet();
		}
		boolean removedDebt = lightFinalizerSession().pendingTicks.remove(packed) != OceanCanvasPrimitiveLongIntMap.ABSENT;
		removeLightWorkLaneMembership(packed);
		lightFinalizerSession().visibleLightPriority.remove(packed);
		lightFinalizerSession().visibleLightPriorityDistanceSq.remove(packed);
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed);
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.remove(packed);
		pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.remove(packed);
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
		pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(packed);
		lightFinalizerSession().pendingPasses.remove(packed);
		lightFinalizerSession().allowPhysicalRepair.remove(packed);
		lightRecoverySession().verifyEscalations.remove(packed);
		lightFinalizerSession().stagedBlockFingerprint.remove(packed);
		lightRecoverySession().hardSkyResetRadius1.remove(packed);
		lightRecoverySession().hardSkyResetCounts.remove(packed);
		lightRecoverySession().deepZeroScrubCounts.remove(packed);
		lightRecoverySession().visibleDeepDenseRepairCounts.remove(packed);
		lightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);
		clearIncrementalDeepRepairState(packed);
		lightRecoverySession().pathologyHotspotLevel.remove(packed);
		lightRecoverySession().deepZeroPublicRecoveryCounts.remove(packed);
		lightRecoverySession().deepZeroPublicRecoveryRetryAfterTick.remove(packed);
		removedDebt |= lightRecoverySession().skyBackoffUntilTick.remove(packed) != OceanCanvasPrimitiveLongLongMap.ABSENT;
		removedDebt |= lightRecoverySession().pressureParkUntilTick.remove(packed) != OceanCanvasPrimitiveLongLongMap.ABSENT;
		if (removedDebt) markLightDebtMembershipMutation();
		// Deadline heaps may retain stale scheduling nodes after a fast certificate,
		// but the authoritative due-time maps above have already been cleared. The
		// bounded wake paths discard those stale primitive nodes without any queue scan.
		lightFinalizerSession().retryLedger.clearBackoffStreak(packed);
		lightTelemetrySession().LIGHT_DIAG_LAST_DETAIL_WARN_TICK.remove(packed);
		lightFinalizerSession().terrainLastMutationTick.remove(packed);
		lightFinalizerSession().physicalMutationGenerationTick.remove(packed);
		lightFinalizerSession().boundaryMutationGenerationTick.remove(packed);
		lightFinalizerSession().terrainInstabilityStreak.remove(packed);
		// v253.125.35: warm residency is conditional on an actually pending adjacent
		// historical obligation. A fixed 40-tick linger without a consumer only delays
		// unload and recreates the residency tail .34 is intended to eliminate.
		if (historicalLightOnly && lightFinalizerSession().relightResidencyLedger.contains(packed)
				&& hasNearbyHistoricalLightDebtForWarmReuse(packed)) {
			lightFinalizerSession().historicalWarmResidencyUntilTick.put(
					packed, world.getGameTime() + LIGHT_HISTORICAL_WARM_RESIDENCY_TICKS);
		} else {
			releaseLightRelightResidencyTicket(world, packed);
		}
		lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAITS.remove(packed);
		lightFinalizerSession().relightStartedNs.remove(packed);
		lightTelemetrySession().LIGHT_DIAG_PRE_PRIME.remove(packed);
	}

	private static boolean hasNearbyHistoricalLightDebtForWarmReuse(long packed) {
		int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			if (dx == 0 && dz == 0) continue;
			long neighbor = ChunkPos.pack(cx + dx, cz + dz);
			if (!session.pendingTicks.containsKey(neighbor)) continue;
			if (session.visibleLightPriority.contains(neighbor) || session.allowPhysicalRepair.contains(neighbor)) continue;
			return true;
		}
		return false;
	}

	/**
	 * C2ME-safe relight path for a live FULL chunk. Minecraft's checkBlock API is
	 * specifically the incremental path used after block changes and C2ME wraps it
	 * with its own lighting executor/ticket discipline. We intentionally check both
	 * sides of the two opacity transitions Ocean Canvas creates in every column:
	 * air<->water at sea level and water<->floor at the configured floor. The first
	 * sweep also refreshes section readiness so old terrain section-state cannot
	 * suppress skylight propagation.
	 */
	/**
	 * v253.125.26 cooperative initial light sweep. The .25 runtime measured an
	 * atomic sweep at >300ms. Persist the column cursor and submit at most a small
	 * public checkBlock cohort per tick; stage 1 cannot begin until all columns ran.
	 */
	static final class LightSweepState {
		final LevelChunk chunk;
		final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int sectionCursor;
		int columnCursor;
		int sampleStage;
		long checks;
		boolean sectionStatusComplete;
		LightSweepState(LevelChunk chunk, boolean includeSectionStatus) {
			this.chunk = chunk;
			this.sectionStatusComplete = !includeSectionStatus;
		}
	}

	/**
	 * v253.125.34 operation-granular initial light sweep. The .33 soak still saw a
	 * 210ms maximum because section-status updates and a whole multi-check column
	 * were atomic relative to the 2.5ms deadline. Persist section/column/sample
	 * cursors and queue one public light-engine operation at a time. A mutable
	 * BlockPos also removes the previous per-check allocation churn.
	 */
	private static boolean advanceLiveChunkLightSweep(ServerLevel world, LevelChunk chunk, boolean includeSectionStatus, long packed) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;
		long started = System.nanoTime();
		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
		ChunkPos cp = chunk.getPos();
		int baseX = cp.getMinBlockX(), baseZ = cp.getMinBlockZ();
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int baseFloorY = config.oceanFloorY();
		int variation = config.oceanFloorVariation();
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		LightSweepState state = session.lightSweepState.get(packed);
		if (state == null || state.chunk != chunk) {
			state = new LightSweepState(chunk, includeSectionStatus);
			session.lightSweepState.put(packed, state);
		}
		int operations = 0;

		while (operations < LIGHT_SWEEP_MAX_CHECKS_PER_SLICE) {
			if (operations > 0 && System.nanoTime() - started >= LIGHT_SWEEP_SLICE_TIME_BUDGET_NS) break;
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) break;
			if (!state.sectionStatusComplete) {
				if (state.sectionCursor >= chunk.getSectionsCount()) {
					state.sectionStatusComplete = true;
					continue;
				}
				int sectionIndex = state.sectionCursor++;
				int sectionY = world.getSectionYFromSectionIndex(sectionIndex);
				lightEngine.updateSectionStatus(net.minecraft.core.SectionPos.of(cp, sectionY), chunk.getSection(sectionIndex).hasOnlyAir());
				operations++;
				continue;
			}
			if (state.columnCursor >= 256) break;
			int idx = state.columnCursor;
			int lx = idx >>> 4, lz = idx & 15;
			int x = baseX + lx, z = baseZ + lz;
			int floorY = baseFloorY + floorOffset(x, z, variation);
			boolean deepColumn = (lx & 3) == 2 && (lz & 3) == 2;
			int stage = state.sampleStage;
			int y;
			boolean valid = true;
			if (stage == 0) y = waterTop + 1;
			else if (stage == 1) y = waterTop;
			else if (!deepColumn) { state.columnCursor++; state.sampleStage = 0; continue; }
			else if (stage == 2) y = floorY + 1;
			else if (stage == 3) y = floorY;
			else if (stage < 4 + DEEP_SKY_ZERO_TAIL_DEPTHS.length) {
				y = waterTop - DEEP_SKY_ZERO_TAIL_DEPTHS[stage - 4];
				valid = y > floorY && y > world.getMinY();
			} else if (stage < 7 + DEEP_SKY_ZERO_TAIL_DEPTHS.length) {
				int dy = stage - (3 + DEEP_SKY_ZERO_TAIL_DEPTHS.length);
				y = floorY + dy;
				valid = y < waterTop && y > world.getMinY();
			} else {
				state.columnCursor++;
				state.sampleStage = 0;
				continue;
			}
			state.sampleStage++;
			if (!valid) continue;
			lightEngine.checkBlock(new BlockPos(x, y, z));
			state.checks++;
			operations++;
		}

		long elapsed = Math.max(0L, System.nanoTime() - started);
		lightTelemetrySession().LIGHT_DIAG_LIGHT_SWEEP_SLICES.incrementAndGet();
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_LIGHT_SWEEP_SLICE_NANOS, elapsed);
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_LIGHT_SWEEP_NANOS, elapsed);
		if (!state.sectionStatusComplete || state.columnCursor < 256) {
			lightTelemetrySession().LIGHT_DIAG_LIGHT_SWEEP_YIELDS.incrementAndGet();
			return false;
		}
		session.lightSweepState.remove(packed);
		session.lightSweepColumnCursor.remove(packed);
		session.lightSweepChecksAccumulated.remove(packed);
		session.lightSweepSectionStatusDone.remove(packed);
		lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEPS.incrementAndGet();
		lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEP_BLOCK_CHECKS.addAndGet(state.checks);
		return true;
	}

	private static void queueLiveChunkLightSweep(ServerLevel world, LevelChunk chunk, boolean includeSectionStatus) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
		long sweepStarted = System.nanoTime();
		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
		ChunkPos cp = chunk.getPos();
		int baseX = cp.getMinBlockX(), baseZ = cp.getMinBlockZ();
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int baseFloorY = config.oceanFloorY();
		int variation = config.oceanFloorVariation();
		long checks = 0L;

		if (includeSectionStatus) {
			for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
				int sectionY = world.getSectionYFromSectionIndex(sectionIndex);
				net.minecraft.core.SectionPos sectionPos = net.minecraft.core.SectionPos.of(cp, sectionY);
				// Ocean Canvas water/floor sections are not empty; sections above the
				// waterline should be empty after a correct carve. Derive readiness from
				// actual section contents rather than old terrain metadata.
				boolean empty = chunk.getSection(sectionIndex).hasOnlyAir();
				lightEngine.updateSectionStatus(sectionPos, empty);
			}
		}

		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				// v253.25: surface skylight is the visible failure. Reinforce every
				// column at air<->water (512 checks/chunk), while keeping the floor
				// transition sparse because the carve already checked every changed
				// floor block. With 20-tick debounce this runs about once per dirty
				// chunk rather than 2.8x as in v253.24, so total work is lower despite
				// complete surface coverage.
				int x = baseX + lx, z = baseZ + lz;
				int floorY = baseFloorY + floorOffset(x, z, variation);
				lightEngine.checkBlock(new BlockPos(x, waterTop + 1, z)); checks++;
				lightEngine.checkBlock(new BlockPos(x, waterTop, z)); checks++;
				if ((lx & 3) == 2 && (lz & 3) == 2) {
					lightEngine.checkBlock(new BlockPos(x, floorY + 1, z)); checks++;
					lightEngine.checkBlock(new BlockPos(x, floorY, z)); checks++;
					// v253.73.16: the sweep used to touch ONLY the two opacity transitions,
					// so for a 37-block water column roughly 35 blocks were never nudged.
					// A column whose interior kept a pre-carve open-air skylight profile
					// therefore had no cheap path back to the canonical field and could sit
					// overbright until escalation. Nudge the exact depths the deep verifier
					// asserts, on the same 1-in-16 column stride the floor check uses, so
					// the incremental path gets a chance before source reseed / hard reset.
					for (int depth : DEEP_SKY_ZERO_TAIL_DEPTHS) {
						int y = waterTop - depth;
						if (y <= floorY || y <= world.getMinY()) continue;
						lightEngine.checkBlock(new BlockPos(x, y, z)); checks++;
					}
					// v253.125.11: fixed depths can stop several blocks above a variable
					// sea floor. Nudge the exact floor-adjacent cells that drive the top
					// face lighting visible in the user's remaining bottom-ocean artifacts.
					for (int dy = 1; dy <= 3; dy++) {
						int y = floorY + dy;
						if (y >= waterTop || y <= world.getMinY()) continue;
						lightEngine.checkBlock(new BlockPos(x, y, z)); checks++;
					}
				}
			}
		}
		lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEPS.incrementAndGet();
		lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEP_BLOCK_CHECKS.addAndGet(checks);
		recordLightPhaseDuration(world, ChunkPos.pack(cp.x(), cp.z()), "LIGHT_SWEEP", lightTelemetrySession().LIGHT_DIAG_MAX_LIGHT_SWEEP_NANOS, sweepStarted);
	}

	private static void expireHistoricalWarmResidencyTickets(ServerLevel world) {
		long tick = world.getGameTime();
		boolean shedForPressure = currentHeapUseFraction(world) >= LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION;
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		int limit = shedForPressure ? 16 : 4;
		long threshold = shedForPressure ? Long.MAX_VALUE : tick;
		int released = session.historicalWarmResidencyUntilTick.drainKeysAtOrBelow(
				threshold, session.historicalWarmReleaseScratch, limit);
		for (int i = 0; i < released; i++) {
			releaseLightRelightResidencyTicket(world, session.historicalWarmReleaseScratch[i]);
		}
	}

	private static void shedExcessLightRelightResidencyTickets(ServerLevel world, int effectiveCap) {
		OceanCanvasLightFinalizerSession session = lightFinalizerSession();
		int active = session.relightResidencyLedger.activeCount();
		if (active <= effectiveCap) return;

		// v253.125.53: a lowered admission cap is not enough if dozens of old forced
		// tickets are allowed to survive indefinitely above it. The .52 runtime reached
		// effectiveCap=8 while 44 tickets remained resident and heap climbed above 91%.
		// Shed only non-visible historical tickets; physical-authoring and player-visible
		// work keep priority. Releasing residency never clears pending/certificate debt.
		int toRelease = Math.min(8, active - effectiveCap);
		int released = 0;
		long now = System.nanoTime();
		while (released < toRelease && session.relightResidencyLedger.activeCount() > effectiveCap) {
			long selected = Long.MIN_VALUE;
			long selectedInstalled = Long.MAX_VALUE;
			for (long candidate : session.relightResidencyLedger.activeSnapshot()) {
				if (session.visibleLightPriority.contains(candidate)) continue;
				if (session.allowPhysicalRepair.contains(candidate)) continue;
				long installed = session.relightResidencyLedger.installedAtNanos(candidate);
				if (installed == OceanCanvasLightRelightResidencyLedger.ABSENT_NANOS) continue;
				if (installed < selectedInstalled) { selected = candidate; selectedInstalled = installed; }
			}
			if (selected == Long.MIN_VALUE) break;
			releaseLightRelightResidencyTicket(world, selected);
			if (session.pendingTicks.containsKey(selected)) session.pendingTicks.put(selected, 1);
			released++;
		}
		if (released > 0) {
			OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-RESIDENCY-SHED build={} effectiveCap={} before={} after={} released={} oldestCandidateAgeMs={} action=release-excess-historical-forced-residency-without-dropping-light-debt",
				net.oceancanvas.mod.OceanCanvas.VERSION, effectiveCap, active, session.relightResidencyLedger.activeCount(), released,
				selectedAgeMillisForResidencyLog(session, now));
		}
	}

	private static long selectedAgeMillisForResidencyLog(OceanCanvasLightFinalizerSession session, long now) {
		long oldest = Long.MAX_VALUE;
		for (long candidate : session.relightResidencyLedger.activeSnapshot()) {
			long installed = session.relightResidencyLedger.installedAtNanos(candidate);
			if (installed != OceanCanvasLightRelightResidencyLedger.ABSENT_NANOS && installed < oldest) oldest = installed;
		}
		return oldest == Long.MAX_VALUE ? 0L : Math.max(0L, (now - oldest) / 1_000_000L);
	}

	private static void rotateStaleLightRelightResidencyTickets(ServerLevel world, int pendingCount) {
		int effectiveCap = effectiveLightRelightResidencyTicketCap(world);
		shedExcessLightRelightResidencyTickets(world, effectiveCap);
		// v253.73.17. This used to also require pendingCount >= HIGH_WATER, on the
		// theory that a "healthy small queue" needs no rotation. The v253.73.16 runtime
		// disproved that: pending settled at 487 with all 64 residency tickets held by
		// cold pre-stage loads whose 3x3 neighborhood never reached loaded state (68
		// distinct chunks logged NEIGHBOR_LOADING_UNDER_TICKET). 487 < 512, so this
		// method returned immediately on every tick, ZERO rotations ever fired, the 423
		// ticketless entries could never acquire a slot, and the finalizer retired
		// nothing for the remaining 162 seconds of the session. That is precisely the
		// deterministic head-of-line deadlock v253.61.6 fixed, reintroduced through the
		// recovery path's own admission gate.
		//
		// Whether a ticket is STALE has nothing to do with how deep the queue is. A
		// ticket held 60s on a chunk that still is not resident is stale at pending=10
		// and at pending=1000. Churn is already prevented by three stronger guards
		// below: the ticket set must be at its cap (nothing to reclaim otherwise), the
		// hold must exceed LIGHT_RELIGHT_RESIDENCY_TICKET_MAX_HOLD_NS, and a ticket is
		// never rotated once stage 0 has seeded or the neighborhood is resident.
		if (lightFinalizerSession().relightResidencyLedger.activeCount() < effectiveCap) return;
		long now = System.nanoTime();
		int rotated = 0;
		for (long packed : lightFinalizerSession().relightResidencyLedger.activeSnapshot()) {
			if (rotated >= LIGHT_RELIGHT_STALE_ROTATE_PER_TICK) break;
			// A ticket whose work vanished is an immediate orphan. Otherwise require a
			// generous 15s hold before rotating it; ordinary finalization is ~seconds.
			long installed = lightFinalizerSession().relightResidencyLedger.installedAtNanos(packed);
			if (!lightFinalizerSession().pendingTicks.containsKey(packed)) {
				long warmUntil = lightFinalizerSession().historicalWarmResidencyUntilTick.get(packed);
				if (warmUntil != OceanCanvasPrimitiveLongLongMap.ABSENT && world.getGameTime() < warmUntil) continue;
				lightFinalizerSession().historicalWarmResidencyUntilTick.remove(packed);
				releaseLightRelightResidencyTicket(world, packed);
				rotated++;
				continue;
			}
			// Snapshot/release can race with diagnostics. A missing timestamp now means
			// the ticket is no longer owned; never recreate ledger membership here.
			if (installed == OceanCanvasLightRelightResidencyLedger.ABSENT_NANOS) continue;
			long ticketAgeNs = now - installed;
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			boolean neighborhoodResident = world.getChunkSource().getChunkNow(cx, cz) != null
					&& isLightNeighborhoodLoaded(world, cx, cz);
			if (neighborhoodResident) continue;
			int pass = lightFinalizerSession().pendingPasses.getOrDefault(packed, 0);
			if (pass <= 0) {
				if (ticketAgeNs < LIGHT_RELIGHT_RESIDENCY_TICKET_MAX_HOLD_NS) continue;
				releaseLightRelightResidencyTicket(world, packed);
				lightFinalizerSession().relightResidencyLedger.recordRotation();
				rotated++;
				lightFinalizerSession().pendingTicks.put(packed, 1);
				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-DIAG chunk {},{} classification=RELIGHT_TICKET_ROTATED pending={} activeTickets={} reason=60s-cold-prestage-load-recovery",
						cx, cz, pendingCount, lightFinalizerSession().relightResidencyLedger.activeCount());
				continue;
			}

			// v253.125.22: a staged ticket may also become permanently cold after the
			// frontier moves on. The old unconditional pass>0 guard made that ticket
			// immortal, so 64 such holders could strand every ticketless finalizer entry
			// while active pressure sat at 506 (<512). After a deliberately longer 90s
			// cold hold, discard ONLY the transient staged proof and residency. Recovery
			// escalation/certification debt is preserved and stage 0 must run again.
			if (ticketAgeNs < LIGHT_RELIGHT_STAGED_TICKET_MAX_HOLD_NS) continue;
			releaseLightRelightResidencyTicket(world, packed);
			lightFinalizerSession().relightResidencyLedger.recordRotation();
			rotated++;
			lightFinalizerSession().stagedBlockFingerprint.remove(packed);
			lightFinalizerSession().pendingPasses.put(packed, 0);
			lightFinalizerSession().pendingTicks.put(packed, 1);
			lightFinalizerSession().relightStartedNs.remove(packed);
			OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-DIAG chunk {},{} classification=RELIGHT_STAGED_TICKET_RESET previousPass={} pending={} activeTickets={} reason=90s-cold-staged-residency-liveness-recovery",
					cx, cz, pass, pendingCount, lightFinalizerSession().relightResidencyLedger.activeCount());
		}
	}

	private static boolean ensureLightRelightResidencyTicket(ServerLevel world, long packed, int cx, int cz) {
		try {
			OceanCanvasLightFinalizerSession session = lightFinalizerSession();
			if (session.relightResidencyLedger.contains(packed)) {
				session.historicalWarmResidencyUntilTick.remove(packed);
				return true;
			}
			int effectiveCap = effectiveLightRelightResidencyTicketCap(world);
			boolean visible = session.visibleLightPriority.contains(packed);
			boolean freshPhysical = session.allowPhysicalRepair.contains(packed);
			boolean historicalLightOnly = !visible && !freshPhysical;
			if (historicalLightOnly) {
				// The .33 soak finished with 191,347 overworld chunks waiting to unload.
				// Do not create more historical forced residency while the heap is already
				// in the pressure band. The obligation stays pending and naturally resident
				// chunks can still certify without a ticket.
				if (currentHeapUseFraction(world) >= LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION) {
					session.historicalResidencyInstallDeferrals.incrementAndGet();
					return false;
				}
				if (session.relightResidencyLedger.activeCount() >= effectiveCap) return false;
				long interval = net.oceancanvas.mod.pregen.PregenManager.activePregenTerrainAdmissionOpen()
						? LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_TERRAIN_TICKS
						: (net.oceancanvas.mod.pregen.PregenManager.isRunning()
								? LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS
								: LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_IDLE_TICKS);
				long nowTick = world.getGameTime();
				java.util.concurrent.atomic.AtomicLong gate = session.nextHistoricalResidencyInstallTick;
				long next = gate.get();
				if (next != Long.MIN_VALUE && nowTick < next) {
					session.historicalResidencyInstallDeferrals.incrementAndGet();
					return false;
				}
				long replacement = nowTick + interval;
				if (!gate.compareAndSet(next, replacement)) {
					session.historicalResidencyInstallDeferrals.incrementAndGet();
					return false;
				}
			}
			OceanCanvasLightRelightResidencyLedger.InstallResult result = session.relightResidencyLedger.install(
					packed, System.nanoTime(), effectiveCap,
					p -> net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(
							world, new ChunkPos(ChunkPos.getX(p), ChunkPos.getZ(p)), LIGHT_RELIGHT_RESIDENCY_TICKET_RADIUS));
			if (result != OceanCanvasLightRelightResidencyLedger.InstallResult.CAP_REJECTED) return true;
			OceanCanvasLightTelemetrySession telemetry = lightTelemetrySession();
			long holdTick = world.getGameTime();
			if (telemetry.lightDiagResidencyLastHoldTick != holdTick) {
				telemetry.lightDiagResidencyLastHoldTick = holdTick;
				telemetry.LIGHT_DIAG_RESIDENCY_CAP_HOLDS.incrementAndGet();
			}

			// v253.125.10: 125.9 spent most of the soak at 64/64 relight tickets and
			// logged hundreds of NEIGHBOR_LOADING waits while player-visible priority
			// still existed. Do not increase the cap. A visible repair may instead evict
			// ONE cold, non-visible, pre-stage historical ticket; productive resident or
			// already-staged work is never preempted.
			if (lightFinalizerSession().visibleLightPriority.contains(packed)
					&& preemptColdNonVisibleRelightTicketForVisible(world, packed)) {
				result = lightFinalizerSession().relightResidencyLedger.install(
						packed, System.nanoTime(), effectiveCap,
						p -> net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(
								world, new ChunkPos(ChunkPos.getX(p), ChunkPos.getZ(p)), LIGHT_RELIGHT_RESIDENCY_TICKET_RADIUS));
				return result != OceanCanvasLightRelightResidencyLedger.InstallResult.CAP_REJECTED;
			}
			return false;
		} catch (Throwable t) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not install relight residency ticket at {},{}: {}", cx, cz, t.toString());
			return false;
		}
	}

	private static boolean preemptColdNonVisibleRelightTicketForVisible(ServerLevel world, long requestedPacked) {
		// The residency pool is deliberately tiny. Select the oldest ELIGIBLE cold
		// historical ticket in one primitive pass: the old boxed sort ordered every
		// holder even though this method can release at most one.
		long selected = Long.MIN_VALUE;
		long selectedInstalled = Long.MAX_VALUE;
		for (long candidate : lightFinalizerSession().relightResidencyLedger.activeSnapshot()) {
			if (candidate == requestedPacked) continue;
			if (!lightFinalizerSession().pendingTicks.containsKey(candidate)) continue;
			if (lightFinalizerSession().visibleLightPriority.contains(candidate)) continue;
			if (lightFinalizerSession().pendingPasses.getOrDefault(candidate, 0) > 0) continue;
			int ccx = ChunkPos.getX(candidate), ccz = ChunkPos.getZ(candidate);
			if (world.getChunkSource().getChunkNow(ccx, ccz) != null
					&& isLightNeighborhoodLoaded(world, ccx, ccz)) continue;
			long installed = lightFinalizerSession().relightResidencyLedger.installedAtNanos(candidate);
			if (installed != OceanCanvasLightRelightResidencyLedger.ABSENT_NANOS && installed < selectedInstalled) {
				selected = candidate;
				selectedInstalled = installed;
			}
		}
		if (selected == Long.MIN_VALUE) return false;
		int ccx = ChunkPos.getX(selected), ccz = ChunkPos.getZ(selected);
		releaseLightRelightResidencyTicket(world, selected);
		lightFinalizerSession().pendingTicks.put(selected, 2);
		long n = lightTelemetrySession().LIGHT_DIAG_VISIBLE_TICKET_PREEMPTS.incrementAndGet();
		if (n <= 8L || (n & 63L) == 0L) {
			OceanCanvas.LOGGER.info(
					"(Ocean Canvas) LIGHT-VISIBLE-TICKET-PREEMPT build={} requested={},{} released={},{} count={} action=bounded-cold-historical-ticket-yield",
					net.oceancanvas.mod.OceanCanvas.VERSION,
					ChunkPos.getX(requestedPacked), ChunkPos.getZ(requestedPacked),
					ccx, ccz, n);
		}
		return true;
	}

	private static void releaseLightRelightResidencyTicket(ServerLevel world, long packed) {
		int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
		lightFinalizerSession().historicalWarmResidencyUntilTick.remove(packed);
		try {
			lightFinalizerSession().relightResidencyLedger.release(packed,
					p -> net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.removeForcedTicket(
							world, new ChunkPos(ChunkPos.getX(p), ChunkPos.getZ(p)), LIGHT_RELIGHT_RESIDENCY_TICKET_RADIUS));
		} catch (Throwable t) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not release v253.13 relight residency ticket at {},{}: {}",
					cx, cz, t.toString());
		}
	}

	private static boolean isLightNeighborhoodLoaded(ServerLevel world, int cx, int cz) {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (world.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) return false;
			}
		}
		return true;
	}

	/**
	 * v253.27 block-only fingerprint around the two canonical opacity boundaries.
	 * Lighting itself cannot alter this value. A changed fingerprint after the
	 * settle window therefore proves a real late block mutation rather than a bad
	 * light cache. This intentionally samples every column but only five Y values,
	 * keeping it much cheaper than another full-height physical audit.
	 */
	static final class FingerprintState {
		final LevelChunk chunk;
		final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int columnCursor;
		int sampleCursor;
		long hash = 0xcbf29ce484222325L;
		FingerprintState(LevelChunk chunk) { this.chunk = chunk; }
	}

	private record FingerprintAdvance(boolean complete, long hash) { }

	/** v253.125.34 sample-granular block-only boundary fingerprint. */
	private static FingerprintAdvance advanceBoundaryFingerprint(ServerLevel world, LevelChunk chunk,
			OceanCanvasPrimitiveLongObjectMap<FingerprintState> stateMap, long packed) {
		FingerprintState state = stateMap.get(packed);
		if (state == null || state.chunk != chunk) { state = new FingerprintState(chunk); stateMap.put(packed, state); }
		long started = System.nanoTime();
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y, baseFloorY = config.oceanFloorY(), variation = config.oceanFloorVariation();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		int processedSamples = 0;
		final int maxSamples = 128;
		while (state.columnCursor < 256 && processedSamples < maxSamples) {
			if (processedSamples > 0 && System.nanoTime() - started >= LIGHT_PROOF_SLICE_TIME_BUDGET_NS) break;
			int idx = state.columnCursor, lx = idx >>> 4, lz = idx & 15;
			int x = baseX + lx, z = baseZ + lz;
			if (!strictCanvasColumnSelected(chunk, config, x, z)) {
				state.columnCursor++;
				state.sampleCursor = 0;
				continue;
			}
			int floorY = baseFloorY + floorOffset(x, z, variation);
			int sample = state.sampleCursor;
			int y = switch (sample) { case 0 -> floorY - 1; case 1 -> floorY; case 2 -> floorY + 1; case 3 -> waterTop; case 4 -> waterTop + 1; default -> waterTop + 2; };
			BlockState bs = chunk.getBlockState(state.cursor.set(x, y, z));
			state.hash ^= (((long)x) << 32) ^ (z & 0xffffffffL) ^ ((long)y << 17) ^ bs.hashCode();
			state.hash *= 0x100000001b3L;
			processedSamples++;
			state.sampleCursor++;
			if (state.sampleCursor >= 6) {
				state.sampleCursor = 0;
				state.columnCursor++;
			}
		}
		long elapsed = Math.max(0L, System.nanoTime() - started);
		lightTelemetrySession().LIGHT_DIAG_FINGERPRINT_SLICES.incrementAndGet();
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_FINGERPRINT_SLICE_NANOS, elapsed);
		updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS, elapsed);
		if (state.columnCursor < 256) { lightTelemetrySession().LIGHT_DIAG_FINGERPRINT_YIELDS.incrementAndGet(); return new FingerprintAdvance(false, 0L); }
		long hash = state.hash; stateMap.remove(packed); return new FingerprintAdvance(true, hash);
	}

	private static long canonicalBoundaryFingerprint(ServerLevel world, LevelChunk chunk) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int variation = config.oceanFloorVariation();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		long hash = 0xcbf29ce484222325L;
		BlockPos.MutableBlockPos fingerprintCursor = new BlockPos.MutableBlockPos();
		for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
			// v253.72.7.1 compile hotfix: this helper returns a long. Do not inject a
			// boolean early-return into this bounded read-only fingerprint loop; outer
			// finalizer checkpoints already preempt before/after the diagnostic helper.
			int x = baseX + lx, z = baseZ + lz;
			if (!strictCanvasColumnSelected(chunk, config, x, z)) continue;
			int floorY = baseFloorY + floorOffset(x, z, variation);
			// Avoid allocating a six-element int[] for every one of 256 columns.
			for (int sample = 0; sample < 6; sample++) {
				int y = switch (sample) {
					case 0 -> floorY - 1;
					case 1 -> floorY;
					case 2 -> floorY + 1;
					case 3 -> waterTop;
					case 4 -> waterTop + 1;
					default -> waterTop + 2;
				};
				BlockState state = chunk.getBlockState(fingerprintCursor.set(x, y, z));
				hash ^= (((long)x) << 32) ^ (z & 0xffffffffL) ^ ((long)y << 17) ^ state.hashCode();
				hash *= 0x100000001b3L;
			}
		}
		return hash;
	}

	private static final int[] DEEP_SKY_SAMPLE_DEPTHS = new int[]{2, 4, 6, 8, 10, 12};
	// v253.72.4: the previous proof stopped exactly where its lower bound became 0.
	// That made stale POSITIVE skylight in the renderer-visible lower ocean invisible
	// to both server and client telemetry. Sample the zero-tail deep into the water.
	private static final int[] DEEP_SKY_ZERO_TAIL_DEPTHS = new int[]{16, 24, 32, 48, 64};
	/**
	 * v253.73.16 overbright tolerance. v253.73.14 repaired an overbright deep field
	 * by zeroing whole SKY sections, which produced a hard black square, and
	 * v253.73.15 responded by deleting the ceiling check entirely rather than the
	 * repair. That left the deep column asserted only by a LOCAL seam invariant
	 * (|delta| &lt;= 1 across a chunk border), which a smoothly overbright field
	 * satisfies perfectly - so a fully lit sea floor 37 blocks down now passes
	 * silently. One level of slack absorbs a genuine lateral edge case; a majority
	 * quorum absorbs the rest. The repair path is the safe v253.73.15 one (source
	 * re-prime + checkBlock + propagate + strict re-verify), never storage zeroing.
	 */
	private static final int DEEP_SKY_OVERBRIGHT_TOLERANCE = 1;

	static record SkyLightDiag(int samples, int aboveMin, int aboveMax, int waterMin, int waterMax,
			int aboveNot15, int anomalousColumns, BlockPos firstAnomaly, java.util.List<BlockPos> anomalousPositions,
			int deepSamples, int deepAnomalousLayers, int deepAnomalousColumns, int deepOverbrightLayers, int deepOverbrightColumns, BlockPos firstDeepAnomaly,
			int firstDeepActual, int firstDeepRequiredMin, int firstDeepRequiredMax, int firstDeepDepth) {
		boolean healthy() {
			return (samples == 0 && deepSamples == 0) || (aboveNot15 == 0 && anomalousColumns == 0
					&& deepAnomalousLayers == 0 && deepAnomalousColumns == 0);
		}

		/** v253.72.6: isolate the overbright-only zero-tail class so it can receive
		 * the deterministic section-storage scrub without weakening any surface or
		 * deep-underbright invariant. anomalousColumns is a TOTAL, so derive the
		 * non-deep remainder before classifying the repair path. */
		boolean onlyDeepZeroTailOverbright() {
			int surfaceAnomalousColumns = Math.max(0, anomalousColumns - deepAnomalousColumns);
			return samples > 0
					&& aboveNot15 == 0 && surfaceAnomalousColumns == 0
					&& aboveMin == 15 && aboveMax == 15 && waterMin == 14 && waterMax == 14
					&& deepOverbrightLayers > 0
					&& deepAnomalousLayers == deepOverbrightLayers
					&& deepAnomalousColumns == deepOverbrightColumns;
		}
	}

	/**
	 * v253.125.10 lazy per-column plain-water shaft cache for one strict proof.
	 * The 125.9 soak spent tens to hundreds of milliseconds in the flattener while
	 * the verifier repeatedly walked the same 16-64 block shafts for underbright,
	 * overbright and seam checks. Each participating column is now walked at most
	 * once per proof; protection at the actual sampled Y remains checked per sample,
	 * so proof semantics are unchanged.
	 */
	static final class StrictSkyProofState {
		final LevelChunk chunk;
		final OceanCanvasConfig config;
		final OceanCanvasPlayerZones zones;
		final PlainWaterShaftCache shaftCache;
		final Long2ObjectOpenHashMap<PlainWaterShaftCache> neighborShaftCaches = new Long2ObjectOpenHashMap<>();
		final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		final java.util.ArrayList<BlockPos> bad = new java.util.ArrayList<>();
		int phase;
		int cursorIndex;
		// v253.125.33: phases 1-4 are resumable *inside* a depth layer. The .31
		// runtime recorded a 557ms strict-proof slice because the old 4ms deadline
		// was checked only after a whole 64-sample layer completed. Keep fixed,
		// reusable layer/side scratch so every individual sample is a preemption
		// boundary without changing the proof quorum or sample geometry.
		int layerSamples, layerBadCount, sideSamples, sideBadCount;
		final BlockPos[] layerBadPos = new BlockPos[64];
		final int[] layerBadActual = new int[64];
		final int[] layerBadAux = new int[64];
		final BlockPos[] sideBadPos = new BlockPos[8];
		final int[] sideBadActual = new int[8];
		final int[] sideBadNeighbor = new int[8];
		int minAbove=16, maxAbove=-1, minWater=16, maxWater=-1;
		int samples, aboveNot15, anomalous;
		BlockPos first;
		int deepSamples, deepAnomalousLayers, deepAnomalousColumns, deepOverbrightLayers, deepOverbrightColumns;
		BlockPos firstDeep;
		int firstDeepActual=-1, firstDeepRequiredMin=-1, firstDeepRequiredMax=-1, firstDeepDepth=-1;
		StrictSkyProofState(ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
			this.chunk=chunk;
			this.config=config;
			this.zones=OceanCanvasPlayerZones.get(world);
			this.shaftCache=new PlainWaterShaftCache(world, chunk, config);
		}
	}

	private record StrictSkyProofAdvance(boolean complete, SkyLightDiag diag) { }

	/**
	 * v253.125.26 resumable authoritative SKY proof. Every invariant from the
	 * monolithic proof is retained; only the scheduling changes. The same lazy shaft
	 * cache survives across slices, and no SkyLightDiag is returned until all proof
	 * phases finish.
	 */
	private static StrictSkyProofAdvance advanceStrictSkyProof(ServerLevel world, LevelChunk chunk, long packed) {
		StrictSkyProofState st = lightFinalizerSession().strictSkyProofState.get(packed);
		if (st == null || st.chunk != chunk) {
			st = new StrictSkyProofState(world, chunk, OceanCanvasConfig.get());
			lightFinalizerSession().strictSkyProofState.put(packed, st);
		}
		long started = System.nanoTime();
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		boolean yielded = false;
		proofLoop:
		while (true) {
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) { yielded=true; break; }
			if (st.phase == 0) {
				int processed=0;
				while (st.cursorIndex < 256 && processed < 64) {
					if (processed > 0 && System.nanoTime()-started >= LIGHT_PROOF_SLICE_TIME_BUDGET_NS) { yielded=true; break proofLoop; }
					int idx=st.cursorIndex++, lx=idx>>>4, lz=idx&15; processed++;
					int x=baseX+lx,z=baseZ+lz;
					if (!strictCanvasColumnSelected(chunk, st.config, x, z)) continue;
					BlockState ws=chunk.getBlockState(st.cursor.set(x,waterTop,z));
					BlockState as=chunk.getBlockState(st.cursor.set(x,waterTop+1,z));
					if (!ws.is(Blocks.WATER)||!as.isAir()) continue;
					if (st.zones.isProtected(x,waterTop,z)||st.zones.isProtected(x,waterTop+1,z)) continue;
					int above=world.getBrightness(net.minecraft.world.level.LightLayer.SKY,st.cursor.set(x,waterTop+1,z));
					int water=world.getBrightness(net.minecraft.world.level.LightLayer.SKY,st.cursor.set(x,waterTop,z));
					st.minAbove=Math.min(st.minAbove,above); st.maxAbove=Math.max(st.maxAbove,above);
					st.minWater=Math.min(st.minWater,water); st.maxWater=Math.max(st.maxWater,water); st.samples++;
					boolean aboveBad=above!=15, waterBad=water!=Math.max(0,above-1);
					if (aboveBad) st.aboveNot15++;
					if (aboveBad||waterBad) { st.anomalous++; BlockPos bp=new BlockPos(x,aboveBad?waterTop+1:waterTop,z); if(st.first==null)st.first=bp; st.bad.add(bp); }
				}
				if (st.cursorIndex < 256) { yielded=true; break; }
				st.phase=1; st.cursorIndex=0;
			}
			if (st.phase == 1) {
				final int samplesPerDepth = 64; // odd 1..15 x odd 1..15
				final int totalSamples = DEEP_SKY_SAMPLE_DEPTHS.length * samplesPerDepth;
				if (st.cursorIndex >= totalSamples) { st.phase=2; st.cursorIndex=0; continue; }
				int flat = st.cursorIndex;
				int depthIndex = flat / samplesPerDepth, sampleIndex = flat % samplesPerDepth;
				int depth = DEEP_SKY_SAMPLE_DEPTHS[depthIndex], y = waterTop - depth;
				if (sampleIndex == 0) { st.layerSamples = 0; st.layerBadCount = 0; }
				if (y > world.getMinY()) {
					int lx = 1 + ((sampleIndex >>> 3) << 1), lz = 1 + ((sampleIndex & 7) << 1);
					int x = baseX + lx, z = baseZ + lz;
					int shaftStatus = st.shaftCache.hasShaftStep(x, z, y, waterTop);
					if (shaftStatus < 0) { yielded = true; break; }
					if (shaftStatus > 0) {
						int sky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, st.cursor.set(x,y,z));
						st.layerSamples++;
						int requiredMin = minimumPlainWaterSkyAtDepth(depth);
						if (sky < requiredMin && st.layerBadCount < st.layerBadPos.length) {
							int bi = st.layerBadCount++;
							st.layerBadPos[bi] = new BlockPos(x,y,z);
							st.layerBadActual[bi] = sky;
						}
					}
				}
				st.cursorIndex++;
				if (sampleIndex == samplesPerDepth - 1) {
					st.deepSamples += st.layerSamples;
					if (st.layerSamples >= 8 && st.layerBadCount > 0) {
						int requiredMin = minimumPlainWaterSkyAtDepth(depth);
						st.deepAnomalousLayers++;
						st.deepAnomalousColumns += st.layerBadCount;
						for (int i=0;i<st.layerBadCount;i++) {
							BlockPos bp=st.layerBadPos[i];
							if(st.firstDeep==null){st.firstDeep=bp;st.firstDeepActual=st.layerBadActual[i];st.firstDeepRequiredMin=requiredMin;st.firstDeepDepth=depth;}
							if(st.first==null)st.first=bp; st.bad.add(bp);
						}
					}
				}
				if (System.nanoTime()-started >= LIGHT_PROOF_SLICE_TIME_BUDGET_NS) { yielded=true; break; }
				continue;
			}
			if (st.phase == 2) {
				final int samplesPerDepth = 16; // 2,6,10,14 x 2,6,10,14
				final int totalSamples = DEEP_SKY_ZERO_TAIL_DEPTHS.length * samplesPerDepth;
				if (st.cursorIndex >= totalSamples) { st.phase=3; st.cursorIndex=0; continue; }
				int flat=st.cursorIndex;
				int depthIndex=flat/samplesPerDepth, sampleIndex=flat%samplesPerDepth;
				int depth=DEEP_SKY_ZERO_TAIL_DEPTHS[depthIndex], y=waterTop-depth;
				if(sampleIndex==0){st.layerSamples=0;st.layerBadCount=0;}
				if(y>world.getMinY()){
					int lx=2+((sampleIndex>>>2)<<2),lz=2+((sampleIndex&3)<<2),x=baseX+lx,z=baseZ+lz;
					int shaftStatus=st.shaftCache.hasShaftStep(x,z,y,waterTop);
					if(shaftStatus<0){yielded=true;break;}
					if(shaftStatus>0&&hasSurroundedCanonicalZeroTailWater(chunk,st.shaftCache,x,z,y,waterTop)){
						int sky=world.getBrightness(net.minecraft.world.level.LightLayer.SKY,st.cursor.set(x,y,z));
						st.layerSamples++;
						int requiredMax=maximumPlainWaterSkyAtDepth(depth),ceiling=requiredMax+DEEP_SKY_OVERBRIGHT_TOLERANCE;
						if(sky>ceiling&&st.layerBadCount<st.layerBadPos.length){int bi=st.layerBadCount++;st.layerBadPos[bi]=new BlockPos(x,y,z);st.layerBadActual[bi]=sky;}
					}
				}
				st.cursorIndex++;
				if(sampleIndex==samplesPerDepth-1){
					st.deepSamples+=st.layerSamples;
					if(st.layerSamples>=1&&st.layerBadCount>0){int requiredMax=maximumPlainWaterSkyAtDepth(depth);st.deepAnomalousLayers++;st.deepOverbrightLayers++;st.deepAnomalousColumns+=st.layerBadCount;st.deepOverbrightColumns+=st.layerBadCount;for(int i=0;i<st.layerBadCount;i++){BlockPos bp=st.layerBadPos[i];if(st.firstDeep==null){st.firstDeep=bp;st.firstDeepActual=st.layerBadActual[i];st.firstDeepRequiredMin=0;st.firstDeepRequiredMax=requiredMax;st.firstDeepDepth=depth;}if(st.first==null)st.first=bp;st.bad.add(bp);}}
				}
				if(System.nanoTime()-started>=LIGHT_PROOF_SLICE_TIME_BUDGET_NS){yielded=true;break;} continue;
			}
			if (st.phase == 3) {
				final int totalSamples=16;
				if(st.cursorIndex>=totalSamples){st.deepSamples+=st.layerSamples;if(st.layerBadCount>0){st.deepAnomalousLayers++;st.deepOverbrightLayers++;st.deepAnomalousColumns+=st.layerBadCount;st.deepOverbrightColumns+=st.layerBadCount;for(int i=0;i<st.layerBadCount;i++){BlockPos bp=st.layerBadPos[i];if(st.firstDeep==null){st.firstDeep=bp;st.firstDeepActual=st.layerBadActual[i];st.firstDeepRequiredMin=0;st.firstDeepRequiredMax=0;st.firstDeepDepth=st.layerBadAux[i];}if(st.first==null)st.first=bp;st.bad.add(bp);}}st.phase=4;st.cursorIndex=0;continue;}
				int sampleIndex=st.cursorIndex;
				if(sampleIndex==0){st.layerSamples=0;st.layerBadCount=0;}
				int lx=2+((sampleIndex>>>2)<<2),lz=2+((sampleIndex&3)<<2),x=baseX+lx,z=baseZ+lz;
				if(!st.shaftCache.ensureInitializedStep(x,z,waterTop)){yielded=true;break;}
				int floorY=st.shaftCache.openFloorY(x,z,waterTop);
				if(floorY!=Integer.MIN_VALUE){int sampleY=floorY+1,depth=waterTop-sampleY;if(depth>=16&&sampleY>world.getMinY()&&!st.zones.isProtected(x,floorY,z)&&!st.zones.isProtected(x,sampleY,z)&&hasSurroundedFloorWater(chunk,st.shaftCache,x,z,sampleY,waterTop)){int sky=world.getBrightness(net.minecraft.world.level.LightLayer.SKY,st.cursor.set(x,sampleY,z));st.layerSamples++;if(sky>DEEP_SKY_OVERBRIGHT_TOLERANCE&&st.layerBadCount<st.layerBadPos.length){int bi=st.layerBadCount++;st.layerBadPos[bi]=new BlockPos(x,sampleY,z);st.layerBadActual[bi]=sky;st.layerBadAux[bi]=depth;}}}
				st.cursorIndex++;
				if(System.nanoTime()-started>=LIGHT_PROOF_SLICE_TIME_BUDGET_NS){yielded=true;break;} continue;
			}
			if (st.phase == 4) {
				final int samplesPerSide=7, sidesPerDepth=4, samplesPerDepth=samplesPerSide*sidesPerDepth;
				final int totalSamples=DEEP_SKY_ZERO_TAIL_DEPTHS.length*samplesPerDepth;
				if(st.cursorIndex>=totalSamples){st.phase=5;continue;}
				int flat=st.cursorIndex,depthIndex=flat/samplesPerDepth,withinDepth=flat%samplesPerDepth,side=withinDepth/samplesPerSide,offsetIndex=withinDepth%samplesPerSide;
				int depth=DEEP_SKY_ZERO_TAIL_DEPTHS[depthIndex],y=waterTop-depth;
				if(withinDepth==0){st.layerSamples=0;st.layerBadCount=0;}
				if(offsetIndex==0){st.sideSamples=0;st.sideBadCount=0;}
				if(y>world.getMinY()){
					int dx=side==0?1:side==1?-1:0,dz=side==2?1:side==3?-1:0;
					LevelChunk neighbor=world.getChunkSource().getChunkNow(chunk.getPos().x()+dx,chunk.getPos().z()+dz);
					if(neighbor!=null){
						int offset=1+(offsetIndex<<1),ax,az,bx,bz;
						if(dx>0){ax=baseX+15;az=baseZ+offset;bx=ax+1;bz=az;}
						else if(dx<0){ax=baseX;az=baseZ+offset;bx=ax-1;bz=az;}
						else if(dz>0){ax=baseX+offset;az=baseZ+15;bx=ax;bz=az+1;}
						else{ax=baseX+offset;az=baseZ;bx=ax;bz=az-1;}
						int centerStatus=st.shaftCache.hasShaftStep(ax,az,y,waterTop);
						if(centerStatus<0){yielded=true;break;}
						if(centerStatus>0){
							long np=ChunkPos.pack(neighbor.getPos().x(),neighbor.getPos().z());
							PlainWaterShaftCache nc=st.neighborShaftCaches.get(np);
							if(nc==null){nc=new PlainWaterShaftCache(world,neighbor,st.config);st.neighborShaftCaches.put(np,nc);}
							int neighborStatus=nc.hasShaftStep(bx,bz,y,waterTop);
							if(neighborStatus<0){yielded=true;break;}
							if(neighborStatus>0){
								int a=world.getBrightness(net.minecraft.world.level.LightLayer.SKY,st.cursor.set(ax,y,az)),b=world.getBrightness(net.minecraft.world.level.LightLayer.SKY,st.cursor.set(bx,y,bz));
								st.sideSamples++;st.layerSamples++;
								if(Math.abs(a-b)>1&&st.sideBadCount<st.sideBadPos.length){int bi=st.sideBadCount++;st.sideBadPos[bi]=new BlockPos(ax,y,az);st.sideBadActual[bi]=a;st.sideBadNeighbor[bi]=b;}
							}
						}
					}
				}
				st.cursorIndex++;
				if(offsetIndex==samplesPerSide-1){int quorum=Math.max(4,(st.sideSamples+1)/2);if(st.sideSamples>=4&&st.sideBadCount>=quorum){for(int i=0;i<st.sideBadCount&&st.layerBadCount<st.layerBadPos.length;i++){int bi=st.layerBadCount++;st.layerBadPos[bi]=st.sideBadPos[i];st.layerBadActual[bi]=st.sideBadActual[i];st.layerBadAux[bi]=st.sideBadNeighbor[i];}}}
				if(withinDepth==samplesPerDepth-1){st.deepSamples+=st.layerSamples;if(st.layerBadCount>0){st.deepAnomalousLayers++;st.deepAnomalousColumns+=st.layerBadCount;for(int i=0;i<st.layerBadCount;i++){BlockPos bp=st.layerBadPos[i];int a=st.layerBadActual[i],b=st.layerBadAux[i];if(st.firstDeep==null){st.firstDeep=bp;st.firstDeepActual=a;st.firstDeepRequiredMin=Math.max(0,b-1);st.firstDeepRequiredMax=Math.min(15,b+1);st.firstDeepDepth=depth;}if(st.first==null)st.first=bp;st.bad.add(bp);}}}
				if(System.nanoTime()-started>=LIGHT_PROOF_SLICE_TIME_BUDGET_NS){yielded=true;break;} continue;
			}
			if (st.phase == 5) {
				if(st.samples>0&&st.minWater!=st.maxWater){for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){BlockPos bp=new BlockPos(baseX+lx,waterTop,baseZ+lz);if(!st.bad.contains(bp))st.bad.add(bp);}st.anomalous+=256;if(st.first==null&&!st.bad.isEmpty())st.first=st.bad.get(0);}
				st.anomalous+=st.deepAnomalousColumns;
				SkyLightDiag result = st.samples==0
						? new SkyLightDiag(0,-1,-1,-1,-1,0,st.deepAnomalousColumns,st.first,java.util.List.copyOf(st.bad),st.deepSamples,st.deepAnomalousLayers,st.deepAnomalousColumns,st.deepOverbrightLayers,st.deepOverbrightColumns,st.firstDeep,st.firstDeepActual,st.firstDeepRequiredMin,st.firstDeepRequiredMax,st.firstDeepDepth)
						: new SkyLightDiag(st.samples,st.minAbove,st.maxAbove,st.minWater,st.maxWater,st.aboveNot15,st.anomalous,st.first,java.util.List.copyOf(st.bad),st.deepSamples,st.deepAnomalousLayers,st.deepAnomalousColumns,st.deepOverbrightLayers,st.deepOverbrightColumns,st.firstDeep,st.firstDeepActual,st.firstDeepRequiredMin,st.firstDeepRequiredMax,st.firstDeepDepth);
				lightFinalizerSession().strictSkyProofState.remove(packed);
				long elapsed=Math.max(0L,System.nanoTime()-started);lightTelemetrySession().LIGHT_DIAG_STRICT_PROOF_SLICES.incrementAndGet();updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_PROOF_SLICE_NANOS,elapsed);updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_SKY_PROOF_NANOS,elapsed);
				return new StrictSkyProofAdvance(true,result);
			}
		}
		long elapsed=Math.max(0L,System.nanoTime()-started);lightTelemetrySession().LIGHT_DIAG_STRICT_PROOF_SLICES.incrementAndGet();updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_PROOF_SLICE_NANOS,elapsed);updateAtomicMax(lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_SKY_PROOF_NANOS,elapsed);if(yielded)lightTelemetrySession().LIGHT_DIAG_STRICT_PROOF_YIELDS.incrementAndGet();
		return new StrictSkyProofAdvance(false,null);
	}

	private static final class PlainWaterShaftCache {
		private final ServerLevel world;
		private final LevelChunk chunk;
		private final OceanCanvasConfig config;
		private final OceanCanvasPlayerZones zones;
		private final boolean[] initialized = new boolean[256];
		private final boolean[] initializationStarted = new boolean[256];
		private final int[] initializationScanY = new int[256];
		private final int[] initializationDepth = new int[256];
		private final int[] maxPlainDepth = new int[256];
		private final int[] openFloorY = new int[256];
		private final boolean[] surfaceEligible = new boolean[256];
		// v253.125.21: strict proofs touch thousands of block coordinates per
		// candidate. Reuse one mutable cursor for cache population so the healthy
		// path does not allocate a BlockPos for every water block in every shaft.
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		PlainWaterShaftCache(ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
			this.world = world;
			this.chunk = chunk;
			this.config = config;
			this.zones = OceanCanvasPlayerZones.get(world);
			java.util.Arrays.fill(this.openFloorY, Integer.MIN_VALUE);
		}

		/**
		 * v253.125.34 bounded shaft-cache population for the cooperative strict proof.
		 * .33 made the proof sample-granular, but the first sample for a column could
		 * still scan up to 96 water blocks atomically inside hasShaft(). Populate at
		 * most eight vertical cells per call; callers keep the same sample cursor until
		 * this returns true, so incomplete cache construction can never be mistaken for
		 * a negative proof result.
		 */
		boolean ensureInitializedStep(int x, int z, int waterTop) {
			int lx = x - chunk.getPos().getMinBlockX();
			int lz = z - chunk.getPos().getMinBlockZ();
			if (lx < 0 || lx > 15 || lz < 0 || lz > 15) return true;
			int idx = (lx << 4) | lz;
			if (initialized[idx]) return true;
			if (!initializationStarted[idx]) {
				initializationStarted[idx] = true;
				if (!strictCanvasColumnSelected(chunk, config, x, z)
						|| zones.isProtected(x, waterTop, z) || zones.isProtected(x, waterTop + 1, z)
						|| !chunk.getBlockState(cursor.set(x, waterTop + 1, z)).isAir()
						|| !chunk.getBlockState(cursor.set(x, waterTop, z)).is(Blocks.WATER)) {
					initialized[idx] = true;
					return true;
				}
				surfaceEligible[idx] = true;
				initializationScanY[idx] = waterTop - 1;
				initializationDepth[idx] = 0;
			}
			int minY = Math.max(world.getMinY() + 1, waterTop - 96);
			int scanned = 0;
			while (initializationScanY[idx] >= minY && scanned < 8) {
				int y = initializationScanY[idx]--;
				BlockState state = chunk.getBlockState(cursor.set(x, y, z));
				scanned++;
				if (!state.is(Blocks.WATER)) {
					if (!state.isAir() && state.getFluidState().isEmpty()) openFloorY[idx] = y;
					maxPlainDepth[idx] = initializationDepth[idx];
					initialized[idx] = true;
					return true;
				}
				initializationDepth[idx]++;
			}
			if (initializationScanY[idx] < minY) {
				maxPlainDepth[idx] = initializationDepth[idx];
				initialized[idx] = true;
				return true;
			}
			return false;
		}

		int hasShaftStep(int x, int z, int sampleY, int waterTop) {
			int lx = x - chunk.getPos().getMinBlockX();
			int lz = z - chunk.getPos().getMinBlockZ();
			if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || sampleY > waterTop) return 0;
			if (!ensureInitializedStep(x, z, waterTop)) return -1;
			int idx = (lx << 4) | lz;
			if (!surfaceEligible[idx] || zones.isProtected(x, sampleY, z)) return 0;
			return waterTop - sampleY <= maxPlainDepth[idx] ? 1 : 0;
		}

		int openFloorY(int x, int z, int waterTop) {
			int lx = x - chunk.getPos().getMinBlockX();
			int lz = z - chunk.getPos().getMinBlockZ();
			if (lx < 0 || lx > 15 || lz < 0 || lz > 15) return Integer.MIN_VALUE;
			int idx = (lx << 4) | lz;
			if (!initialized[idx]) initialize(idx, x, z, waterTop);
			return openFloorY[idx];
		}

		boolean hasShaft(int x, int z, int sampleY, int waterTop) {
			int lx = x - chunk.getPos().getMinBlockX();
			int lz = z - chunk.getPos().getMinBlockZ();
			if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || sampleY > waterTop) return false;
			int idx = (lx << 4) | lz;
			if (!initialized[idx]) initialize(idx, x, z, waterTop);
			if (!surfaceEligible[idx]) return false;
			if (zones.isProtected(x, sampleY, z)) return false;
			return waterTop - sampleY <= maxPlainDepth[idx];
		}

		private void initialize(int idx, int x, int z, int waterTop) {
			while (!initialized[idx]) ensureInitializedStep(x, z, waterTop);
		}
	}

	/** v253.125.11 floor-visible deep-water guard. A floor-adjacent zero-tail
	 * sample participates only inside an uninterrupted local 3x3 water neighborhood
	 * at the floor and mid-column. This is deliberately detection-only: recovery
	 * remains the public light-engine re-prime path, never direct SKY storage edits. */
	private static boolean hasSurroundedFloorWater(LevelChunk chunk, PlainWaterShaftCache cache,
			int x, int z, int sampleY, int waterTop) {
		if (!cache.hasShaft(x, z, sampleY, waterTop)) return false;
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		int lx = x - baseX, lz = z - baseZ;
		if (lx <= 0 || lx >= 15 || lz <= 0 || lz >= 15) return false;
		int midY = sampleY + Math.max(1, (waterTop - sampleY) / 2);
		// v253.125.32: reuse the shaft cache cursor; this predicate runs thousands of
		// times during strict proof and does not need one new MutableBlockPos per call.
		BlockPos.MutableBlockPos cursor = cache.cursor;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			if (!chunk.getBlockState(cursor.set(x + dx, sampleY, z + dz)).is(Blocks.WATER)) return false;
			if (!chunk.getBlockState(cursor.set(x + dx, midY, z + dz)).is(Blocks.WATER)) return false;
		}
		return true;
	}

	/**
	 * v253.37 deterministic deep-water proof.
	 *
	 * <p>The v253.35 500-radius runtime left exactly two chunks pinned at the north
	 * edge of the authored 64x64 square: 11,31 and 12,31. Their surface was already
	 * correct (15 air / 14 surface water), but the old deep verifier compared the
	 * center chunk to a mode sampled from loaded neighbors. At an operation edge,
	 * those neighbors can be support/unmodified chunks outside the authored region,
	 * so an unrelated neighbor was allowed to define "correct" lighting. The server
	 * shaft predicate also accidentally accepted kelp/seagrass even though the
	 * v253.34 design explicitly said vegetation must opt out.</p>
	 *
	 * <p>For an uninterrupted column of plain WATER blocks, there is no need for a
	 * neighbor oracle. The already-certified surface pair gives a guaranteed direct
	 * vertical path: surface water is 14 and each additional plain-water block costs
	 * at most one level, so a sample may never be darker than max(0, 14-depth). A
	 * side-lit shoreline/cave can legitimately make the stored value brighter than
	 * that direct-path floor, so equality would create a new operation-edge false
	 * positive. The verifier therefore fails only values BELOW the deterministic
	 * floor. That targets the actual post-clearing failure class (stale obstruction
	 * leaves skylight too dark) without letting Pregen boundaries, vegetation,
	 * structures or neighbor load order manufacture a failure.</p>
	 */
	private static SkyLightDiag sampleCanonicalSurfaceSkyLight(ServerLevel world, LevelChunk chunk) {
		// v253.125.19: a durable lighting certificate must not depend on player
		// visibility. Use the geometry-gated sparse-overbright oracle for every
		// authoritative proof so an unvisited chunk cannot certify a stale positive-SKY
		// island that would later illuminate the ocean floor.
		return sampleCanonicalSurfaceSkyLight(world, chunk, true);
	}

	/**
	 * v253.125.19 global certificate proof mode. The geometry-gated sparse
	 * overbright oracle is now authoritative for every certificate candidate; the
	 * boolean is retained only for source compatibility with older call sites. Any
	 * sampled overbright cell surrounded by canonical plain water is actionable,
	 * regardless of whether a player has ever visited the chunk.
	 */
	private static SkyLightDiag sampleCanonicalSurfaceSkyLight(ServerLevel world, LevelChunk chunk, boolean strictSparseOverbright) {
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int minAbove = 16, maxAbove = -1, minWater = 16, maxWater = -1;
		int samples = 0, aboveNot15 = 0, anomalous = 0;
		BlockPos first = null;
		java.util.List<BlockPos> bad = new java.util.ArrayList<>();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		OceanCanvasConfig skyConfig = OceanCanvasConfig.get();
		OceanCanvasPlayerZones skyZones = OceanCanvasPlayerZones.get(world);
		PlainWaterShaftCache shaftCache = new PlainWaterShaftCache(world, chunk, skyConfig);
		Long2ObjectOpenHashMap<PlainWaterShaftCache> neighborShaftCaches = new Long2ObjectOpenHashMap<>();
		// v253.125.21: one mutable read cursor for the entire strict proof. Immutable
		// BlockPos instances are created only when evidence must escape this method.
		BlockPos.MutableBlockPos skyCursor = new BlockPos.MutableBlockPos();
		for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
			// v253.72.7.1 compile hotfix: SkyLightDiag is non-void. Keep this
			// 256-column sampling pass bounded and atomic, and let the surrounding
			// shutdown checkpoints own cooperative preemption.
			int x = baseX + lx, z = baseZ + lz;
			if (!strictCanvasColumnSelected(chunk, skyConfig, x, z)) continue;
			BlockState ws = chunk.getBlockState(skyCursor.set(x, waterTop, z));
			BlockState as = chunk.getBlockState(skyCursor.set(x, waterTop + 1, z));
			if (!ws.is(Blocks.WATER) || !as.isAir()) continue;
			if (skyZones.isProtected(x, waterTop, z) || skyZones.isProtected(x, waterTop + 1, z)) continue;
			int above = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyCursor.set(x, waterTop + 1, z));
			int water = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyCursor.set(x, waterTop, z));
			minAbove = Math.min(minAbove, above); maxAbove = Math.max(maxAbove, above);
			minWater = Math.min(minWater, water); maxWater = Math.max(maxWater, water);
			samples++;
			boolean aboveBad = above != 15;
			boolean waterBad = water != Math.max(0, above - 1);
			if (aboveBad) aboveNot15++;
			if (aboveBad || waterBad) {
				anomalous++;
				BlockPos anomalyPos = new BlockPos(x, aboveBad ? waterTop + 1 : waterTop, z);
				if (first == null) first = anomalyPos;
				bad.add(anomalyPos);
			}
		}

		int deepSamples = 0, deepAnomalousLayers = 0, deepAnomalousColumns = 0;
		int deepOverbrightLayers = 0, deepOverbrightColumns = 0;
		BlockPos firstDeep = null;
		int firstDeepActual = -1, firstDeepRequiredMin = -1, firstDeepRequiredMax = -1, firstDeepDepth = -1;
		for (int depth : DEEP_SKY_SAMPLE_DEPTHS) {
			int y = waterTop - depth;
			if (y <= world.getMinY()) continue;
			int requiredMin = minimumPlainWaterSkyAtDepth(depth);
			int layerSamples = 0;
			java.util.List<BlockPos> layerBadPositions = new java.util.ArrayList<>();
			java.util.List<Integer> layerBadActual = new java.util.ArrayList<>();
			for (int lx = 1; lx < 16; lx += 2) for (int lz = 1; lz < 16; lz += 2) {
				int x = baseX + lx, z = baseZ + lz;
				if (!shaftCache.hasShaft(x, z, y, waterTop)) continue;
				int sky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyCursor.set(x, y, z));
				layerSamples++;
				if (sky < requiredMin) {
					BlockPos p = new BlockPos(x, y, z);
					layerBadPositions.add(p);
					layerBadActual.add(Integer.valueOf(sky));
				}
			}
			deepSamples += layerSamples;
			// Kelp/seagrass/structures intentionally remove a shaft from the proof.
			// Values ABOVE requiredMin are valid: lateral skylight can make a shoreline
			// column brighter than its direct vertical path, especially at operation edges.
			// Require a small quorum before a layer can veto completion so one or two
			// exposed water columns next to preserved geometry cannot manufacture a
			// chunk-wide repair loop.
			if (layerSamples < 8 || layerBadPositions.isEmpty()) continue;
			deepAnomalousLayers++;
			deepAnomalousColumns += layerBadPositions.size();
			for (int i = 0; i < layerBadPositions.size(); i++) {
				BlockPos p = layerBadPositions.get(i);
				if (firstDeep == null) {
					firstDeep = p;
					firstDeepActual = layerBadActual.get(i).intValue();
					firstDeepRequiredMin = requiredMin;
					firstDeepDepth = depth;
				}
				if (first == null) first = p;
				bad.add(p);
			}
		}


		// v253.73.16 deep overbright ceiling. Restores the invariant v253.73.15
		// dropped, without restoring the repair that made it dangerous. Only columns
		// that pass hasDirectCanonicalWaterShaft participate (plain water, air above,
		// no player-protected block, no preserved geometry), and a layer must have a
		// MAJORITY of its sampled columns overbright before it can veto completion -
		// a handful of bright columns beside a wreck or a canvas edge is legitimate,
		// a whole lit sea floor is not.
		for (int depth : DEEP_SKY_ZERO_TAIL_DEPTHS) {
			int y = waterTop - depth;
			if (y <= world.getMinY()) continue;
			int requiredMax = maximumPlainWaterSkyAtDepth(depth);
			int ceiling = requiredMax + DEEP_SKY_OVERBRIGHT_TOLERANCE;
			int layerSamples = 0;
			java.util.List<BlockPos> layerBadPositions = new java.util.ArrayList<>();
			java.util.List<Integer> layerBadActual = new java.util.ArrayList<>();
			// v253.73.17: a 4-block stride (16 samples/layer, 80 shaft scans/chunk)
			// instead of 2 (64/layer, 320/chunk). hasDirectCanonicalWaterShaft walks the
			// column, and this runs inside drainPendingLightSync, where the v253.73.16
			// runtime recorded oc-flattener=FAIL(523ms). The observed failures were whole
			// layers (64 of 64 columns overbright), so a quarter of the samples still
			// clears the majority quorum decisively.
			for (int lx = 2; lx < 16; lx += 4) for (int lz = 2; lz < 16; lz += 4) {
				int x = baseX + lx, z = baseZ + lz;
				if (!shaftCache.hasShaft(x, z, y, waterTop)) continue;
				// A visible sparse failure needs stronger geometry evidence than the
				// background majority gate. Requiring a surrounded 3x3 plain-water
				// neighborhood removes shoreline/structure side-light as a false positive.
				if (strictSparseOverbright && !hasSurroundedCanonicalZeroTailWater(chunk, shaftCache, x, z, y, waterTop)) continue;
				int sky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyCursor.set(x, y, z));
				layerSamples++;
				if (sky > ceiling) {
					BlockPos p = new BlockPos(x, y, z);
					layerBadPositions.add(p);
					layerBadActual.add(Integer.valueOf(sky));
				}
			}
			deepSamples += layerSamples;
			int overbrightQuorum = strictSparseOverbright ? 1 : Math.max(6, (layerSamples + 1) / 2);
			int minimumSamples = strictSparseOverbright ? 1 : 6;
			if (layerSamples < minimumSamples || layerBadPositions.size() < overbrightQuorum) continue;
			deepAnomalousLayers++;
			deepOverbrightLayers++;
			deepAnomalousColumns += layerBadPositions.size();
			deepOverbrightColumns += layerBadPositions.size();
			for (int i = 0; i < layerBadPositions.size(); i++) {
				BlockPos p = layerBadPositions.get(i);
				if (firstDeep == null) {
					firstDeep = p;
					firstDeepActual = layerBadActual.get(i).intValue();
					firstDeepRequiredMin = 0;
					firstDeepRequiredMax = requiredMax;
					firstDeepDepth = depth;
				}
				if (first == null) first = p;
				bad.add(p);
			}
		}

		// v253.125.11 floor-adjacent absolute zero-tail proof. The fixed-depth
		// probes (16/24/32/48/64) can miss a stale positive-SKY island confined to
		// the last few water blocks above a variable y=20..30 sea floor. That is
		// exactly the residual visual class left after v253.125.10 fixed the two
		// surface artifacts. Sample the ACTUAL first dry floor under a direct water
		// shaft. At >=16 blocks below sea level, canonical SKY is zero; keep one
		// level of tolerance and require a surrounded 3x3 floor/mid-water neighborhood
		// so structures, vegetation and cave-side lighting cannot manufacture debt.
		java.util.List<BlockPos> floorBandBad = new java.util.ArrayList<>();
		java.util.List<Integer> floorBandActual = new java.util.ArrayList<>();
		java.util.List<Integer> floorBandDepth = new java.util.ArrayList<>();
		int floorBandSamples = 0;
		for (int lx = 2; lx < 16; lx += 4) for (int lz = 2; lz < 16; lz += 4) {
			int x = baseX + lx, z = baseZ + lz;
			int floorY = shaftCache.openFloorY(x, z, waterTop);
			if (floorY == Integer.MIN_VALUE) continue;
			int sampleY = floorY + 1;
			int depth = waterTop - sampleY;
			if (depth < 16 || sampleY <= world.getMinY()) continue;
			if (skyZones.isProtected(x, floorY, z) || skyZones.isProtected(x, sampleY, z)) continue;
			if (!hasSurroundedFloorWater(chunk, shaftCache, x, z, sampleY, waterTop)) continue;
			int sky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyCursor.set(x, sampleY, z));
			floorBandSamples++;
			if (sky > DEEP_SKY_OVERBRIGHT_TOLERANCE) {
				floorBandBad.add(new BlockPos(x, sampleY, z));
				floorBandActual.add(Integer.valueOf(sky));
				floorBandDepth.add(Integer.valueOf(depth));
			}
		}
		deepSamples += floorBandSamples;
		if (!floorBandBad.isEmpty()) {
			deepAnomalousLayers++;
			deepOverbrightLayers++;
			deepAnomalousColumns += floorBandBad.size();
			deepOverbrightColumns += floorBandBad.size();
			for (int i = 0; i < floorBandBad.size(); i++) {
				BlockPos p = floorBandBad.get(i);
				if (firstDeep == null) {
					firstDeep = p;
					firstDeepActual = floorBandActual.get(i).intValue();
					firstDeepRequiredMin = 0;
					firstDeepRequiredMax = 0;
					firstDeepDepth = floorBandDepth.get(i).intValue();
				}
				if (first == null) first = p;
				bad.add(p);
			}
		}

		// v253.73.15 deep chunk-seam proof. v253.73.14 showed that forcing
		// whole deep sections to all-zero SKY can create the exact hard 16x16 black
		// square visible to the player. Deep-water correctness is therefore expressed
		// as a local propagation invariant instead of an absolute-value oracle:
		// adjacent plain-water blocks across a loaded chunk boundary may differ by at
		// most one SKY level. This catches both dark zero-islands and pale overbright
		// islands while preserving legitimate smoothly varying vanilla light.
		for (int depth : DEEP_SKY_ZERO_TAIL_DEPTHS) {
			int y = waterTop - depth;
			if (y <= world.getMinY()) continue;
			int layerSamples = 0;
			java.util.List<BlockPos> layerBadPositions = new java.util.ArrayList<>();
			java.util.List<Integer> layerBadActual = new java.util.ArrayList<>();
			java.util.List<Integer> layerBadNeighbor = new java.util.ArrayList<>();
			final int[][] sides = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};

			for (int[] side : sides) {
				int dx = side[0], dz = side[1];
				LevelChunk neighbor = world.getChunkSource().getChunkNow(chunk.getPos().x() + dx, chunk.getPos().z() + dz);
				if (neighbor == null) continue;
				int sideSamples = 0;
				java.util.List<BlockPos> sideBadPositions = new java.util.ArrayList<>();
				java.util.List<Integer> sideBadActual = new java.util.ArrayList<>();
				java.util.List<Integer> sideBadNeighbor = new java.util.ArrayList<>();

				for (int offset = 1; offset <= 13; offset += 2) {
					int ax, az, bx, bz;
					if (dx > 0) {
						ax = baseX + 15; az = baseZ + offset; bx = ax + 1; bz = az;
					} else if (dx < 0) {
						ax = baseX; az = baseZ + offset; bx = ax - 1; bz = az;
					} else if (dz > 0) {
						ax = baseX + offset; az = baseZ + 15; bx = ax; bz = az + 1;
					} else {
						ax = baseX + offset; az = baseZ; bx = ax; bz = az - 1;
					}

					if (!shaftCache.hasShaft(ax, az, y, waterTop)) continue;
					long neighborPacked = ChunkPos.pack(neighbor.getPos().x(), neighbor.getPos().z());
					PlainWaterShaftCache neighborCache = neighborShaftCaches.computeIfAbsent(
							neighborPacked, ignored -> new PlainWaterShaftCache(world, neighbor, skyConfig));
					if (!neighborCache.hasShaft(bx, bz, y, waterTop)) continue;
					int aSky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyCursor.set(ax, y, az));
					int bSky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyCursor.set(bx, y, bz));
					sideSamples++;
					if (Math.abs(aSky - bSky) > 1) {
						BlockPos aPos = new BlockPos(ax, y, az);
						sideBadPositions.add(aPos);
						sideBadActual.add(Integer.valueOf(aSky));
						sideBadNeighbor.add(Integer.valueOf(bSky));
					}
				}
				layerSamples += sideSamples;
				int seamQuorum = Math.max(4, (sideSamples + 1) / 2);
				if (sideSamples >= 4 && sideBadPositions.size() >= seamQuorum) {
					layerBadPositions.addAll(sideBadPositions);
					layerBadActual.addAll(sideBadActual);
					layerBadNeighbor.addAll(sideBadNeighbor);
				}
			}

			deepSamples += layerSamples;
			if (layerBadPositions.isEmpty()) continue;
			deepAnomalousLayers++;
			deepAnomalousColumns += layerBadPositions.size();
			for (int i = 0; i < layerBadPositions.size(); i++) {
				BlockPos p = layerBadPositions.get(i);
				int actual = layerBadActual.get(i).intValue();
				int neighbor = layerBadNeighbor.get(i).intValue();
				if (firstDeep == null) {
					firstDeep = p;
					firstDeepActual = actual;
					firstDeepRequiredMin = Math.max(0, neighbor - 1);
					firstDeepRequiredMax = Math.min(15, neighbor + 1);
					firstDeepDepth = depth;
				}
				if (first == null) first = p;
				bad.add(p);
			}
		}

		if (samples == 0) {
			return new SkyLightDiag(0, -1, -1, -1, -1, 0, deepAnomalousColumns, first,
					java.util.List.copyOf(bad), deepSamples, deepAnomalousLayers, deepAnomalousColumns, deepOverbrightLayers, deepOverbrightColumns, firstDeep,
					firstDeepActual, firstDeepRequiredMin, firstDeepRequiredMax, firstDeepDepth);
		}
		// A non-uniform surface-water skylight field is anomalous even if all air
		// samples are 15. The escalated repair is intentionally chunk-wide, so a
		// conservative full-surface marker is sufficient here.
		if (minWater != maxWater) {
			for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
				BlockPos p = new BlockPos(baseX + lx, waterTop, baseZ + lz);
				if (!bad.contains(p)) bad.add(p);
			}
			anomalous += 256;
			if (first == null && !bad.isEmpty()) first = bad.get(0);
		}
		anomalous += deepAnomalousColumns;
		return new SkyLightDiag(samples, minAbove, maxAbove, minWater, maxWater, aboveNot15, anomalous, first,
				java.util.List.copyOf(bad), deepSamples, deepAnomalousLayers, deepAnomalousColumns, deepOverbrightLayers, deepOverbrightColumns, firstDeep,
				firstDeepActual, firstDeepRequiredMin, firstDeepRequiredMax, firstDeepDepth);
	}

	private static int minimumPlainWaterSkyAtDepth(int depthBelowSurfaceWater) {
		return Math.max(0, 14 - Math.max(0, depthBelowSurfaceWater));
	}

	/**
	 * v253.73.16. Skylight cannot INCREASE going down an uninterrupted plain-water
	 * column: water attenuates at least one level per block, and in open canvas
	 * ocean every lateral neighbour at the same depth is bounded by the same rule,
	 * so no lateral path can exceed it either. The canonical surface water block is
	 * 14, therefore depth d below it is at most 14 - d, i.e. exactly zero from depth
	 * 14 down. This is the same number {@link #minimumPlainWaterSkyAtDepth} returns
	 * - the plain-water field is a single value, not a range - but it is deliberately
	 * a separate method because the two bounds are asserted under different
	 * conditions and v253.73.15 removed only the ceiling.
	 */
	private static int maximumPlainWaterSkyAtDepth(int depthBelowSurfaceWater) {
		return Math.max(0, 14 - Math.max(0, depthBelowSurfaceWater));
	}

	private static boolean hasDirectCanonicalWaterShaft(ServerLevel world, LevelChunk chunk, int x, int z, int sampleY, int waterTop) {
		if (!strictCanvasColumnSelected(chunk, OceanCanvasConfig.get(), x, z)) return false;
		// Match the surface verifier: player-protected columns are not Ocean Canvas
		// canonical-light evidence and must never be used to trigger a repair.
		if (OceanCanvasPlayerZones.get(world).isProtected(x, sampleY, z)
				|| OceanCanvasPlayerZones.get(world).isProtected(x, waterTop, z)
				|| OceanCanvasPlayerZones.get(world).isProtected(x, waterTop + 1, z)) return false;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		if (!chunk.getBlockState(cursor.set(x, waterTop + 1, z)).isAir()) return false;
		// v253.37: "direct" means plain water, literally. The old predicate called
		// isCanonicalCanvasWaterState(), which also accepts kelp/seagrass and made
		// their legitimate opacity part of the skylight correctness oracle.
		for (int y = sampleY; y <= waterTop; y++) {
			if (!chunk.getBlockState(cursor.set(x, y, z)).is(Blocks.WATER)) return false;
		}
		return true;
	}


	/** v253.72.4 conservative deep-zero oracle: direct shaft plus a local 3x3
	 * plain-water neighborhood at the sampled depth and mid-depth. This filters
	 * shipwreck/monument/air-pocket side-light before zero-tail values can veto. */
	private static boolean hasSurroundedCanonicalZeroTailWater(ServerLevel world, LevelChunk chunk, int x, int z, int sampleY, int waterTop) {
		if (!hasDirectCanonicalWaterShaft(world, chunk, x, z, sampleY, waterTop)) return false;
		int midY = sampleY + Math.max(1, (waterTop - sampleY) / 2);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			int nx = x + dx, nz = z + dz;
			if (!chunk.getBlockState(cursor.set(nx, sampleY, nz)).is(Blocks.WATER)) return false;
			if (!chunk.getBlockState(cursor.set(nx, midY, nz)).is(Blocks.WATER)) return false;
		}
		return true;
	}

	/** v253.125.21 proof-local overload. The caller already proved the direct
	 * shaft through PlainWaterShaftCache, so do not walk the same 16-64 water
	 * blocks a second time merely to establish the surrounding geometry guard. */
	private static boolean hasSurroundedCanonicalZeroTailWater(LevelChunk chunk, PlainWaterShaftCache cache,
			int x, int z, int sampleY, int waterTop) {
		if (!cache.hasShaft(x, z, sampleY, waterTop)) return false;
		int midY = sampleY + Math.max(1, (waterTop - sampleY) / 2);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			int nx = x + dx, nz = z + dz;
			if (!chunk.getBlockState(cursor.set(nx, sampleY, nz)).is(Blocks.WATER)) return false;
			if (!chunk.getBlockState(cursor.set(nx, midY, nz)).is(Blocks.WATER)) return false;
		}
		return true;
	}

	/**
	 * v253.36 source-table escalation. checkBlock() is a local graph nudge; it
	 * cannot repair a stale ChunkSkyLightSources table that keeps re-seeding the
	 * same bad values. Rebuild source tables from current blocks for the resident
	 * 3x3 context, then explicitly propagate the center chunk's sources. This is
	 * the same source-seeding primitive used by vanilla lighting, without entering
	 * the chunk-status lightChunk() path that conflicts with C2ME on live FULL
	 * chunks.
	 */
	private static void reseedSkyLightSources(ServerLevel world, LevelChunk center) {
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			LevelChunk c = world.getChunkSource().getChunkNow(center.getPos().x() + dx, center.getPos().z() + dz);
			if (c != null) c.initializeLightSources();
		}
		world.getChunkSource().getLightEngine().propagateLightSources(center.getPos());
		long n = lightTelemetrySession().LIGHT_DIAG_SOURCE_RESEEDS.incrementAndGet();
		if (n <= 16 || (n & 63L) == 0L) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-SOURCE-RESEED build={} chunk={},{} reseeds={} action=rebuild-chunk-sky-source-tables-and-propagate",
				net.oceancanvas.mod.OceanCanvas.VERSION, center.getPos().x(), center.getPos().z(), n);
		}
	}

	/**
	 * v253.73.15 compatibility shim for the former deep-zero scrub.
	 *
	 * <p>Do NOT queue an all-zero SKY DataLayer. The v253.73.14 runtime produced
	 * chunk-aligned black squares after 120 such section replacements. Re-prime
	 * only through supported vanilla/C2ME light-engine operations and let the
	 * later strict surface/deep-seam oracle decide whether the graph converged.</p>
	 */
	private static int scrubDeterministicDeepZeroSkyStorage(ServerLevel world, LevelChunk center, boolean radius1) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return 0;
		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
		int radius = radius1 ? 1 : 0;
		int touchedSections = 0;
		long repairDeadlineNanos = System.nanoTime() + LIGHT_DEEP_ZERO_REPAIR_TIME_BUDGET_NS;

		for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return touchedSections;
			LevelChunk c = world.getChunkSource().getChunkNow(center.getPos().x() + dx, center.getPos().z() + dz);
			if (c == null) continue;
			c.initializeLightSources();
			ChunkPos cp = c.getPos();

			for (int sectionIndex = 0; sectionIndex < c.getSectionsCount(); sectionIndex++) {
				if (System.nanoTime() >= repairDeadlineNanos) return touchedSections > 0 ? touchedSections : -1;
				int sectionY = world.getSectionYFromSectionIndex(sectionIndex);
				int minY = sectionY << 4;
				int maxY = minY + 15;
				int safety = deterministicDeepPlainWaterSectionSafety(world, c, minY, maxY, repairDeadlineNanos);
				if (safety < 0) return touchedSections > 0 ? touchedSections : -1;
				if (safety == 0) continue;
				lightEngine.updateSectionStatus(net.minecraft.core.SectionPos.of(cp, sectionY), false);
				for (int lx : new int[]{2, 6, 10, 14}) for (int lz : new int[]{2, 6, 10, 14}) {
					lightEngine.checkBlock(new BlockPos(cp.getMinBlockX() + lx, minY, cp.getMinBlockZ() + lz));
					lightEngine.checkBlock(new BlockPos(cp.getMinBlockX() + lx, maxY, cp.getMinBlockZ() + lz));
				}
				touchedSections++;
			}
			lightEngine.setLightEnabled(cp, true);
			lightEngine.propagateLightSources(cp);
			c.markUnsaved();
		}
		return touchedSections;
	}

	/**
	 * v253.73.4 threaded-light-safe deep-zero recovery. The v253.72.8/73.3
	 * implementation reflected into ThreadedLevelLightEngine storage and mutated
	 * updating/visible maps from the server thread. The 73.3 runtime then proved
	 * that live SKY storage manipulation is not safe with the 26.2/C2ME light
	 * executor: its light worker terminated in LayerLightSectionStorage while a
	 * section DataLayer was absent.
	 *
	 * This recovery uses only supported queued/threaded light-engine operations:
	 * rebuild source tables/propagation, re-prime safe live sections, and nudge
	 * representative deep-water cells without replacing SKY storage. The normal
	 * strict verifier remains the only certification authority.
	 */
	private static int queueDeterministicDeepZeroSkyRecovery(ServerLevel world, LevelChunk center, boolean radius1) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return 0;
		try {
			reseedSkyLightSources(world, center);
			return scrubDeterministicDeepZeroSkyStorage(world, center, radius1);
		} catch (Throwable t) {
			if (lightTelemetrySession().LIGHT_DEEP_ZERO_PUBLIC_RECOVERY_UNAVAILABLE_LOGGED.compareAndSet(false, true)) {
				OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-DEEP-ZERO-PUBLIC-RECOVERY unavailable build={} cause={} action=fall-back-to-bounded-persistent-light-debt; no-internal-light-storage-mutation",
						net.oceancanvas.mod.OceanCanvas.VERSION, t.toString());
			} else {
				lightTelemetrySession().LIGHT_DIAG_SUPPRESSED_DETAIL_WARNINGS.incrementAndGet();
			}
			return 0;
		}
	}

	private static boolean isDeterministicDeepPlainWaterNeighborhood(ServerLevel world, int centerChunkX, int centerChunkZ, int minY, int maxY) {
		return deterministicDeepPlainWaterNeighborhoodSafety(world, centerChunkX, centerChunkZ, minY, maxY, Long.MAX_VALUE) > 0;
	}

	/** 1=safe, 0=unsafe, -1=time/shutdown budget exhausted. */
	private static int deterministicDeepPlainWaterNeighborhoodSafety(ServerLevel world, int centerChunkX, int centerChunkZ, int minY, int maxY, long deadlineNanos) {
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			if (System.nanoTime() >= deadlineNanos) return -1;
			LevelChunk neighbor = world.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
			if (neighbor == null) return 0;
			int safety = deterministicDeepPlainWaterSectionSafety(world, neighbor, minY, maxY, deadlineNanos);
			if (safety <= 0) return safety;
		}
		return 1;
	}

	private static boolean isDeterministicDeepPlainWaterSection(ServerLevel world, LevelChunk chunk, int minY, int maxY) {
		return deterministicDeepPlainWaterSectionSafety(world, chunk, minY, maxY, Long.MAX_VALUE) > 0;
	}

	/** 1=safe, 0=unsafe, -1=time/shutdown budget exhausted. */
	private static int deterministicDeepPlainWaterSectionSafety(ServerLevel world, LevelChunk chunk, int minY, int maxY, long deadlineNanos) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return -1;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		OceanCanvasPlayerZones playerZones = OceanCanvasPlayerZones.get(world);
		OceanCanvasPlayerZones.ProtectionLookup playerProtection = playerZones.protectionLookupForChunk(chunk.getPos());
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
			// Budget check once per column makes the 4096-cell proof cooperatively
			// preemptible without putting System.nanoTime() in the innermost block loop.
			if (System.nanoTime() >= deadlineNanos) return -1;
			int x = baseX + lx, z = baseZ + lz;
			if (!strictCanvasColumnSelected(chunk, config, x, z)) return 0;
			for (int y = minY; y <= maxY; y++) {
				if ((y & 15) == 0 && net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return -1;
				if (playerProtection.isProtected(x, y, z)) return 0;
				cursor.set(x, y, z);
				if (!isCanonicalCanvasWaterState(chunk.getBlockState(cursor))) return 0;
			}
		}
		return 1;
	}

	/**
	 * v253.73.4 threaded-light-safe escalation stage 1. Older builds disabled
	 * SKY and queued null section data while the 26.2 threaded light executor
	 * could still be propagating. The 73.3 runtime captured a fatal null DataLayer
	 * read on that executor. Never remove live SKY storage here. Re-prime source
	 * tables and section status with public APIs, keep light enabled, and give the
	 * executor a real settle window before stage 2 queues deterministic non-null
	 * deep-zero section data.
	 */
	private static void startHardSkyStorageReset(ServerLevel world, LevelChunk center, boolean radius1) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
		int radius = radius1 ? 1 : 0;
		int touched = 0;
		for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
			LevelChunk c = world.getChunkSource().getChunkNow(center.getPos().x() + dx, center.getPos().z() + dz);
			if (c == null) continue;
			c.initializeLightSources();
			ChunkPos cp = c.getPos();
			for (int sectionIndex = 0; sectionIndex < c.getSectionsCount(); sectionIndex++) {
				int sectionY = world.getSectionYFromSectionIndex(sectionIndex);
				lightEngine.updateSectionStatus(net.minecraft.core.SectionPos.of(cp, sectionY), c.getSection(sectionIndex).hasOnlyAir());
			}
			lightEngine.setLightEnabled(cp, true);
			lightEngine.propagateLightSources(cp);
			c.markUnsaved();
			touched++;
		}
		long n = lightTelemetrySession().LIGHT_DIAG_HARD_SKY_RESETS.incrementAndGet();
		OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-SAFE-RESEED build={} chunk={},{} reset={} radius1={} touchedChunks={} stage=prime-without-storage-clear action=wait-before-non-null-deep-zero-repair",
			net.oceancanvas.mod.OceanCanvas.VERSION, center.getPos().x(), center.getPos().z(), n, radius1, touched);
	}

	/** v253.73.15 safe escalation stage 2: refresh section readiness and source
	 * propagation, then re-prime the live graph without replacing SKY storage. */
	private static void finishHardSkyStorageReset(ServerLevel world, LevelChunk center, boolean radius1) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
		int radius = radius1 ? 1 : 0;
		int touched = 0;
		for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
			LevelChunk c = world.getChunkSource().getChunkNow(center.getPos().x() + dx, center.getPos().z() + dz);
			if (c == null) continue;
			c.initializeLightSources();
			ChunkPos cp = c.getPos();
			for (int sectionIndex = 0; sectionIndex < c.getSectionsCount(); sectionIndex++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
				int sectionY = world.getSectionYFromSectionIndex(sectionIndex);
				lightEngine.updateSectionStatus(net.minecraft.core.SectionPos.of(cp, sectionY), c.getSection(sectionIndex).hasOnlyAir());
			}
			lightEngine.setLightEnabled(cp, true);
			lightEngine.propagateLightSources(cp);
			c.markUnsaved();
			touched++;
		}
		queueLiveChunkLightSweep(world, center, true);
		// v253.73.15: never replace a deep section with an all-zero DataLayer.
		// Re-prime safe water sections through public light-engine operations only.
		int reprimeSections = scrubDeterministicDeepZeroSkyStorage(world, center, radius1);
		if (reprimeSections > 0) {
			lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUBS.incrementAndGet();
			lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUB_SECTIONS.addAndGet(reprimeSections);
		}
		OceanCanvas.LOGGER.debug("(Ocean Canvas) LIGHT-SAFE-RESEED build={} chunk={},{} radius1={} touchedChunks={} reprimeSections={} stage=public-graph-reprime-complete action=verify-after-quiet-period",
			net.oceancanvas.mod.OceanCanvas.VERSION, center.getPos().x(), center.getPos().z(), radius1, touched, reprimeSections);
	}

	/**
	 * v253.125.15 client-targeted visible deep decrease propagation. Never writes
	 * light storage and never edits blocks/biomes.
	 *
	 * <p>The 125.14 runtime isolated two persistent chunks whose exact depth-32
	 * samples were server-confirmed overbright for the whole session, yet dense
	 * repair never ran on either chunk. The first implementation required the
	 * <em>entire</em> implicated 16x16x16 section to be canonical water and
	 * unprotected. One unrelated preserved block anywhere in the section therefore
	 * vetoed repair even though the failing sample had already passed the strict
	 * surrounded plain-water oracle.</p>
	 *
	 * <p>Use the local proof that made the sample actionable as the safety gate, then
	 * ask the public threaded light engine to re-check every actual block state in
	 * that exact section. Re-checking a structure/protected block is safe:
	 * {@code checkBlock} recomputes lighting from the existing state; it does not
	 * mutate that state. A one-block vertical boundary plane is included so stale
	 * decrease propagation cannot stop at the section edge. Source tables are rebuilt
	 * first from current blocks. Strict later verification remains the only success
	 * certificate.</p>
	 */
	private record VisibleDeepDenseRelightResult(int sections, long checks, boolean inProgress, int retryTicks) {}

	/**
	 * v253.125.25 resumable dense repair. The old implementation submitted 4,608
	 * checkBlock calls in one server tick. This cursor submits at most 512 calls or
	 * 2.5ms of caller-side work, then resumes deterministically next tick. Strict
	 * verification still runs only after the whole wave has been submitted.
	 */
	private static VisibleDeepDenseRelightResult queueVisibleDeepDenseRelight(ServerLevel world, LevelChunk center, SkyLightDiag diag) {
		long packed = ChunkPos.pack(center.getPos().x(), center.getPos().z());
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer()))
			return new VisibleDeepDenseRelightResult(0, 0L, true, 1);
		if (diag == null || !diag.onlyDeepZeroTailOverbright()) {
			lightRecoverySession().visibleDeepDenseSectionY.remove(packed);
			lightRecoverySession().visibleDeepDenseCursor.remove(packed);
			lightRecoverySession().visibleDeepDenseChecksAccumulated.remove(packed);
			return new VisibleDeepDenseRelightResult(0, 0L, false, 0);
		}

		int sectionY = lightRecoverySession().visibleDeepDenseSectionY.get(packed);
		if (sectionY == OceanCanvasPrimitiveLongIntMap.ABSENT) {
			int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
			for (BlockPos bad : diag.anomalousPositions()) {
				if (bad == null || bad.getY() > waterTop - 16 || !belongsToChunk(center.getPos(), bad)) continue;
				if (!hasSurroundedCanonicalZeroTailWater(world, center, bad.getX(), bad.getZ(), bad.getY(), waterTop)
						&& !isLocallyProvenFloorBandWater(world, center, bad, waterTop)) continue;
				sectionY = net.minecraft.core.SectionPos.blockToSectionCoord(bad.getY());
				break;
			}
			if (sectionY == OceanCanvasPrimitiveLongIntMap.ABSENT) return new VisibleDeepDenseRelightResult(0, 0L, false, 0);
			lightRecoverySession().visibleDeepDenseSectionY.put(packed, sectionY);
			lightRecoverySession().visibleDeepDenseCursor.put(packed, 0);
			lightRecoverySession().visibleDeepDenseChecksAccumulated.put(packed, 0L);
		}

		if (deferDeepRepairForHeap(world, packed)) {
			return new VisibleDeepDenseRelightResult(0,
					lightRecoverySession().visibleDeepDenseChecksAccumulated.getOrDefault(packed, 0L), true,
					LIGHT_DEEP_REPAIR_HEAP_PAUSE_TICKS);
		}

		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
		ChunkPos cp = center.getPos();
		int minY = sectionY << 4;
		int maxY = minY + 15;
		int sectionIndex = world.getSectionIndex(minY);
		if (sectionIndex < 0 || sectionIndex >= center.getSectionsCount()) {
			lightRecoverySession().visibleDeepDenseSectionY.remove(packed);
			lightRecoverySession().visibleDeepDenseCursor.remove(packed);
			lightRecoverySession().visibleDeepDenseChecksAccumulated.remove(packed);
			return new VisibleDeepDenseRelightResult(0, 0L, false, 0);
		}

		int cursor = lightRecoverySession().visibleDeepDenseCursor.getOrDefault(packed, 0);
		if (cursor == 0) {
			reseedSkyLightSources(world, center);
			center.initializeLightSources();
			lightEngine.updateSectionStatus(net.minecraft.core.SectionPos.of(cp, sectionY),
					center.getSection(sectionIndex).hasOnlyAir());
		}

		final int sectionCells = 16 * 16 * 16;
		final int boundaryCells = 2 * 16 * 16;
		final int totalTasks = sectionCells + boundaryCells;
		long started = System.nanoTime();
		long checksThisSlice = 0L;
		while (cursor < totalTasks && checksThisSlice < LIGHT_DEEP_REPAIR_SLICE_CHECK_BUDGET) {
			if ((checksThisSlice & 63L) == 0L) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) break;
				if (checksThisSlice > 0L && System.nanoTime() - started >= LIGHT_DEEP_REPAIR_SLICE_TIME_BUDGET_NS) break;
			}
			int x, y, z;
			if (cursor < sectionCells) {
				int cell = cursor;
				int yOff = cell >>> 8;
				int plane = cell & 255;
				int lx = plane >>> 4;
				int lz = plane & 15;
				x = cp.getMinBlockX() + lx;
				y = minY + yOff;
				z = cp.getMinBlockZ() + lz;
			} else {
				int boundary = cursor - sectionCells;
				int planeIndex = boundary >>> 8;
				int cell = boundary & 255;
				int lx = cell >>> 4;
				int lz = cell & 15;
				x = cp.getMinBlockX() + lx;
				y = planeIndex == 0 ? minY - 1 : maxY + 1;
				z = cp.getMinBlockZ() + lz;
				if (y <= world.getMinY() || y >= world.getMaxY()) {
					cursor++;
					continue;
				}
			}
			lightEngine.checkBlock(new BlockPos(x, y, z));
			checksThisSlice++;
			cursor++;
		}

		long elapsed = System.nanoTime() - started;
		long accumulated = lightRecoverySession().visibleDeepDenseChecksAccumulated.getOrDefault(packed, 0L) + checksThisSlice;
		lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_CHECKS.addAndGet(checksThisSlice);
		lightRecoverySession().deepRepairHeapDeferralCounts.remove(packed);
		boolean yielded = cursor < totalTasks;
		recordDeepRepairSlice(checksThisSlice, elapsed, yielded);
		if (yielded) {
			lightRecoverySession().visibleDeepDenseCursor.put(packed, cursor);
			lightRecoverySession().visibleDeepDenseChecksAccumulated.put(packed, accumulated);
			return new VisibleDeepDenseRelightResult(0, accumulated, true, 1);
		}

		lightEngine.setLightEnabled(cp, true);
		lightEngine.propagateLightSources(cp);
		center.markUnsaved();
		lightRecoverySession().visibleDeepDenseSectionY.remove(packed);
		lightRecoverySession().visibleDeepDenseCursor.remove(packed);
		lightRecoverySession().visibleDeepDenseChecksAccumulated.remove(packed);
		return new VisibleDeepDenseRelightResult(1, accumulated, false, 0);
	}

	private record VisibleDeepClusterRelightResult(int chunkSections, long checks, int missingChunks,
			String mode, boolean inProgress, int retryTicks) {}

	/**
	 * v253.125.16 admission gate for expensive cross-chunk light repair. Returns
	 * zero when this caller owns the world-wide slot, otherwise the remaining tick
	 * delay. v253.125.25 claims this slot only when a new resumable job starts; slices
	 * of an already-admitted job do not wait another 40 ticks each.
	 */
	private static int claimVisibleDeepClusterRepairBudget(ServerLevel world) {
		long now = world.getGameTime();
		for (;;) {
			long next = lightRecoverySession().visibleDeepClusterNextAllowedTick.get();
			if (now < next) return (int)Math.max(1L, Math.min((long)Integer.MAX_VALUE, next - now));
			long replacement = now + LIGHT_VISIBLE_DEEP_CLUSTER_GLOBAL_SPACING_TICKS;
			if (lightRecoverySession().visibleDeepClusterNextAllowedTick.compareAndSet(next, replacement)) return 0;
		}
	}

	private static void clearIncrementalClusterRepairState(long packed) {
		lightRecoverySession().visibleDeepClusterAttemptInFlight.remove(packed);
		lightRecoverySession().visibleDeepClusterSectionY.remove(packed);
		lightRecoverySession().visibleDeepClusterCursor.remove(packed);
		lightRecoverySession().visibleDeepClusterChecksAccumulated.remove(packed);
		lightRecoverySession().deepRepairHeapDeferralCounts.remove(packed);
	}

	/**
	 * v253.125.25 resumable connected deep repair. It preserves the v253.125.16-.17
	 * geometry/source semantics, but linearizes every checkBlock operation into a
	 * deterministic cursor. A radius-1/vertical repair therefore cannot monopolize
	 * one server tick or allocate tens of thousands of BlockPos objects at once.
	 */
	private static VisibleDeepClusterRelightResult queueVisibleDeepClusterRelight(
			ServerLevel world, LevelChunk center, SkyLightDiag diag, int clusterAttempt) {
		long packed = ChunkPos.pack(center.getPos().x(), center.getPos().z());
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer()))
			return new VisibleDeepClusterRelightResult(0, 0L, 0, "PREEMPTED", true, 1);
		if (diag == null || !diag.onlyDeepZeroTailOverbright()) {
			clearIncrementalClusterRepairState(packed);
			return new VisibleDeepClusterRelightResult(0, 0L, 0, "INAPPLICABLE", false, 0);
		}

		boolean fullRadius1 = clusterAttempt >= 1;
		boolean fullWaterColumn = clusterAttempt >= 2;
		String mode = fullWaterColumn ? "VERTICAL_3X3_SOURCE_R2"
				: (fullRadius1 ? "RADIUS1_3X3_SOURCE_R2" : "CARDINAL5_SOURCE_R1");
		int inFlightAttempt = lightRecoverySession().visibleDeepClusterAttemptInFlight.get(packed);
		if (inFlightAttempt != OceanCanvasPrimitiveLongIntMap.ABSENT && inFlightAttempt != clusterAttempt) {
			clearIncrementalClusterRepairState(packed);
			inFlightAttempt = OceanCanvasPrimitiveLongIntMap.ABSENT;
		}

		int sectionY = lightRecoverySession().visibleDeepClusterSectionY.get(packed);
		BlockPos anchorBad = diag.firstDeepAnomaly();
		if (anchorBad == null && !diag.anomalousPositions().isEmpty()) anchorBad = diag.anomalousPositions().get(0);
		if (anchorBad == null) return new VisibleDeepClusterRelightResult(0, 0L, 0, mode, false, 0);
		if (sectionY == OceanCanvasPrimitiveLongIntMap.ABSENT) {
			sectionY = net.minecraft.core.SectionPos.blockToSectionCoord(anchorBad.getY());
			lightRecoverySession().visibleDeepClusterAttemptInFlight.put(packed, clusterAttempt);
			lightRecoverySession().visibleDeepClusterSectionY.put(packed, sectionY);
			lightRecoverySession().visibleDeepClusterCursor.put(packed, 0L);
			lightRecoverySession().visibleDeepClusterChecksAccumulated.put(packed, 0L);
		}

		if (deferDeepRepairForHeap(world, packed)) {
			return new VisibleDeepClusterRelightResult(0,
					lightRecoverySession().visibleDeepClusterChecksAccumulated.getOrDefault(packed, 0L), 0,
					mode, true, LIGHT_DEEP_REPAIR_HEAP_PAUSE_TICKS);
		}

		final int[][] cardinal5 = new int[][]{{0,0},{-1,0},{1,0},{0,-1},{0,1}};
		java.util.ArrayList<int[]> offsets = new java.util.ArrayList<>();
		if (fullRadius1) {
			for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) offsets.add(new int[]{dx,dz});
		} else {
			java.util.Collections.addAll(offsets, cardinal5);
		}
		java.util.ArrayList<LevelChunk> targets = new java.util.ArrayList<>(offsets.size());
		int missing = 0;
		for (int[] off : offsets) {
			LevelChunk c = world.getChunkSource().getChunkNow(center.getPos().x() + off[0], center.getPos().z() + off[1]);
			if (c == null) missing++; else targets.add(c);
		}

		int sourceContextRadius = fullRadius1 ? 2 : 1;
		java.util.ArrayList<LevelChunk> sourceContext = new java.util.ArrayList<>(fullRadius1 ? 25 : 9);
		int missingSourceContext = 0;
		for (int dz = -sourceContextRadius; dz <= sourceContextRadius; dz++) {
			for (int dx = -sourceContextRadius; dx <= sourceContextRadius; dx++) {
				LevelChunk c = world.getChunkSource().getChunkNow(center.getPos().x() + dx, center.getPos().z() + dz);
				if (c == null) missingSourceContext++; else sourceContext.add(c);
			}
		}
		if (missing > 0 || missingSourceContext > 0) {
			long cursor = lightRecoverySession().visibleDeepClusterCursor.getOrDefault(packed, 0L);
			if (cursor > 0L) lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_CONTEXT_ABORTS.incrementAndGet();
			clearIncrementalClusterRepairState(packed);
			return new VisibleDeepClusterRelightResult(0, 0L, Math.max(missing, missingSourceContext), mode, false, 0);
		}

		int minRepairSectionY;
		int maxRepairSectionY;
		if (fullWaterColumn) {
			OceanCanvasConfig config = OceanCanvasConfig.get();
			int floorProbeY = config.oceanFloorY() - Math.max(1, config.oceanFloorVariation()) - 1;
			minRepairSectionY = net.minecraft.core.SectionPos.blockToSectionCoord(Math.max(world.getMinY() + 1, floorProbeY));
			maxRepairSectionY = net.minecraft.core.SectionPos.blockToSectionCoord(OceanCanvasConfig.WATER_SURFACE_Y);
		} else {
			minRepairSectionY = sectionY;
			maxRepairSectionY = sectionY;
		}
		int sectionCount = Math.max(1, maxRepairSectionY - minRepairSectionY + 1);
		long cursor = lightRecoverySession().visibleDeepClusterCursor.getOrDefault(packed, 0L);
		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();

		if (cursor == 0L) {
			Long2ObjectOpenHashMap<SkySourceTables> sourceProbeBeforeCache = new Long2ObjectOpenHashMap<>();
			SkySourceEvidence sourceBefore = probeSkySourceEvidence(world, anchorBad.getX(), anchorBad.getZ(), sourceProbeBeforeCache);
			for (LevelChunk c : sourceContext) c.initializeLightSources();
			Long2ObjectOpenHashMap<SkySourceTables> sourceProbeAfterCache = new Long2ObjectOpenHashMap<>();
			SkySourceEvidence sourceAfter = probeSkySourceEvidence(world, anchorBad.getX(), anchorBad.getZ(), sourceProbeAfterCache);
			int brightestPeerSky = -1;
			String brightestPeerOffset = "none";
			boolean brightestPeerSourceMismatch = false;
			for (int[] off : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
				int px = anchorBad.getX() + (off[0] << 4);
				int pz = anchorBad.getZ() + (off[1] << 4);
				int peerSky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, new BlockPos(px, anchorBad.getY(), pz));
				SkySourceEvidence peerSource = probeSkySourceEvidence(world, px, pz, sourceProbeAfterCache);
				if (peerSky > brightestPeerSky) {
					brightestPeerSky = peerSky;
					brightestPeerOffset = off[0] + "," + off[1];
					brightestPeerSourceMismatch = peerSource.mismatch();
				}
			}
			OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-VISIBLE-DEEP-CLUSTER-SOURCE-PROBE build={} chunk={},{} sample={} sourceBefore={}/{} mismatchBefore={} sourceAfterInit={}/{} mismatchAfterInit={} brightestPeerSky={} peerOffset={} peerSourceMismatchAfterInit={} mode={} action=read-only-live-vs-recomputed-source-evidence",
					net.oceancanvas.mod.OceanCanvas.VERSION, center.getPos().x(), center.getPos().z(), anchorBad,
					sourceBefore.loaded() ? Integer.toString(sourceBefore.liveLowestSourceY()) : "unloaded",
					sourceBefore.loaded() ? Integer.toString(sourceBefore.recomputedLowestSourceY()) : "unloaded", sourceBefore.mismatch(),
					sourceAfter.loaded() ? Integer.toString(sourceAfter.liveLowestSourceY()) : "unloaded",
					sourceAfter.loaded() ? Integer.toString(sourceAfter.recomputedLowestSourceY()) : "unloaded", sourceAfter.mismatch(),
					brightestPeerSky, brightestPeerOffset, brightestPeerSourceMismatch, mode);
		}

		long perTargetTasks = (long)sectionCount * 4096L + 512L;
		long totalTasks = perTargetTasks * (long)targets.size();
		long started = System.nanoTime();
		long checksThisSlice = 0L;
		while (cursor < totalTasks && checksThisSlice < LIGHT_DEEP_REPAIR_SLICE_CHECK_BUDGET) {
			if ((checksThisSlice & 63L) == 0L) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) break;
				if (checksThisSlice > 0L && System.nanoTime() - started >= LIGHT_DEEP_REPAIR_SLICE_TIME_BUDGET_NS) break;
			}
			int targetIndex = (int)(cursor / perTargetTasks);
			long local = cursor % perTargetTasks;
			LevelChunk c = targets.get(targetIndex);
			ChunkPos cp = c.getPos();
			long sectionTaskCount = (long)sectionCount * 4096L;
			int x, y, z;
			if (local < sectionTaskCount) {
				int sectionOffset = (int)(local / 4096L);
				int cell = (int)(local % 4096L);
				int repairSectionY = minRepairSectionY + sectionOffset;
				int minY = repairSectionY << 4;
				int sectionIndex = world.getSectionIndex(minY);
				if (sectionIndex < 0 || sectionIndex >= c.getSectionsCount()) {
					cursor++;
					continue;
				}
				if (cell == 0) {
					c.initializeLightSources();
					lightEngine.updateSectionStatus(net.minecraft.core.SectionPos.of(cp, repairSectionY), c.getSection(sectionIndex).hasOnlyAir());
				}
				int yOff = cell >>> 8;
				int plane = cell & 255;
				int lx = plane >>> 4;
				int lz = plane & 15;
				x = cp.getMinBlockX() + lx;
				y = minY + yOff;
				z = cp.getMinBlockZ() + lz;
			} else {
				int boundary = (int)(local - sectionTaskCount);
				int planeIndex = boundary >>> 8;
				int cell = boundary & 255;
				int lx = cell >>> 4;
				int lz = cell & 15;
				x = cp.getMinBlockX() + lx;
				y = planeIndex == 0 ? (minRepairSectionY << 4) - 1 : (maxRepairSectionY << 4) + 16;
				z = cp.getMinBlockZ() + lz;
				if (y <= world.getMinY() || y >= world.getMaxY()) {
					cursor++;
					continue;
				}
			}
			lightEngine.checkBlock(new BlockPos(x, y, z));
			checksThisSlice++;
			cursor++;
		}

		long elapsed = System.nanoTime() - started;
		long accumulated = lightRecoverySession().visibleDeepClusterChecksAccumulated.getOrDefault(packed, 0L) + checksThisSlice;
		lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_CHECKS.addAndGet(checksThisSlice);
		lightRecoverySession().deepRepairHeapDeferralCounts.remove(packed);
		boolean yielded = cursor < totalTasks;
		recordDeepRepairSlice(checksThisSlice, elapsed, yielded);
		if (yielded) {
			lightRecoverySession().visibleDeepClusterCursor.put(packed, cursor);
			lightRecoverySession().visibleDeepClusterChecksAccumulated.put(packed, accumulated);
			return new VisibleDeepClusterRelightResult(0, accumulated, 0, mode, true, 1);
		}

		for (LevelChunk c : sourceContext) {
			lightEngine.setLightEnabled(c.getPos(), true);
			lightEngine.propagateLightSources(c.getPos());
			c.markUnsaved();
		}
		int touched = targets.size() * sectionCount;
		clearIncrementalClusterRepairState(packed);
		return new VisibleDeepClusterRelightResult(touched, accumulated, 0, mode, false, 0);
	}

	private static boolean belongsToChunk(ChunkPos chunkPos, BlockPos pos) {
		return pos != null
				&& net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()) == chunkPos.x()
				&& net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ()) == chunkPos.z();
	}

	/**
	 * Floor-band anomalies use the dedicated floor oracle rather than the fixed-depth
	 * zero-tail oracle. Re-prove only the local sample neighborhood; never require the
	 * rest of the section to be all water.
	 */
	private static boolean isLocallyProvenFloorBandWater(ServerLevel world, LevelChunk chunk, BlockPos sample, int waterTop) {
		if (sample == null || !belongsToChunk(chunk.getPos(), sample)) return false;
		if (sample.getY() > waterTop - 16 || !chunk.getBlockState(sample).is(Blocks.WATER)) return false;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		PlainWaterShaftCache cache = new PlainWaterShaftCache(world, chunk, config);
		return hasSurroundedFloorWater(chunk, cache, sample.getX(), sample.getZ(), sample.getY(), waterTop);
	}

	private static void queueAnomalousColumnLightRepair(ServerLevel world, LevelChunk chunk, SkyLightDiag diag) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();

		// v253.73.6 log-driven throughput correction. v253.73.5's verifier was
		// selective, but its fallback repair re-checked EVERY block from the ocean
		// floor through sea level in all 256 columns. At the configured Canvas depth
		// that is tens of thousands of LightEngine#checkBlock submissions for one bad
		// chunk; the runtime captured a 54ms flattener phase and >34M safe-sweep checks
		// while the strict oracle was usually reporting only ~25-50 bad deep samples.
		//
		// Keep the complete 512-point air/water boundary sweep (plus sparse floor
		// reinforcement) because it is cheap and proven. Then nudge the EXACT positions
		// that failed the strict oracle, with one vertical neighbor on either side so
		// propagation is not dependent on which side of a light edge was sampled. The
		// later strict verifier is still the only success path: a partial/insufficient
		// nudge cannot be mistaken for completion and will escalate to the existing
		// deterministic zero-section/source-reset ladder.
		queueLiveChunkLightSweep(world, chunk, true);
		java.util.LinkedHashSet<BlockPos> targeted = new java.util.LinkedHashSet<>();
		for (BlockPos bad : diag.anomalousPositions()) {
			if (bad == null) continue;
			targeted.add(bad);
			if (bad.getY() > world.getMinY()) targeted.add(bad.below());
			if (bad.getY() + 1 < world.getMaxY()) targeted.add(bad.above());
		}

		long checks = 0L;
		for (BlockPos pos : targeted) {
			if ((checks & 63L) == 0L
					&& net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return;
			lightEngine.checkBlock(pos);
			checks++;
		}
		if (checks > 0L) {
			lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEPS.incrementAndGet();
			lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEP_BLOCK_CHECKS.addAndGet(checks);
		}
	}


	/**
	 * Compare WORLD_SURFACE against the actual highest non-air block in a small,
	 * deterministic 4x4 sample. This is deliberately bounded: 16 columns per
	 * finalization is enough to identify stale terrain silhouettes without turning
	 * diagnostics into another Pregen performance problem.
	 */
	private static OceanCanvasHeightmapDiag sampleHeightmapAgreement(LevelChunk chunk) {
		int mismatches = 0, maxDelta = 0;
		int physicalMin = Integer.MAX_VALUE, physicalMax = Integer.MIN_VALUE;
		int mapMin = Integer.MAX_VALUE, mapMax = Integer.MIN_VALUE;
		int samples = 0;
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int lx : new int[]{2, 6, 10, 14}) {
			for (int lz : new int[]{2, 6, 10, 14}) {
				int x = baseX + lx, z = baseZ + lz;
				// 26.2 ChunkAccess#getHeight(WORLD_SURFACE, ...) reports the highest
				// matching occupied Y. The previous diagnostic compared it to first-free
				// Y (highest + 1), manufacturing the exact 1-block mismatch seen in every
				// runtime sample. Compare like-for-like instead.
				int physicalHighestY = chunk.getMinY() - 1;
				for (int y = chunk.getMaxY() - 1; y >= chunk.getMinY(); y--) {
					if (!chunk.getBlockState(new BlockPos(x, y, z)).isAir()) {
						physicalHighestY = y;
						break;
					}
				}
				int mapped = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
				int delta = Math.abs(mapped - physicalHighestY);
				if (delta != 0) mismatches++;
				maxDelta = Math.max(maxDelta, delta);
				physicalMin = Math.min(physicalMin, physicalHighestY);
				physicalMax = Math.max(physicalMax, physicalHighestY);
				mapMin = Math.min(mapMin, mapped);
				mapMax = Math.max(mapMax, mapped);
				samples++;
			}
		}
		return new OceanCanvasHeightmapDiag(samples, mismatches, maxDelta, physicalMin, physicalMax, mapMin, mapMax);
	}

	private static void logHeightmapSanityAfterPrime(LevelChunk chunk) {
		OceanCanvasHeightmapDiag d = sampleHeightmapAgreement(chunk);
		if (d.mismatchedColumns() > 0) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-DIAG chunk {},{} classification=HEIGHTMAP_STILL_INCONSISTENT samples={} mismatches={} maxDelta={} physicalRange={}..{} mappedRange={}..{} potentialCause=heightmap rebuild did not converge to current blocks.",
					chunk.getPos().x(), chunk.getPos().z(), d.sampledColumns(), d.mismatchedColumns(),
					d.maxAbsDelta(), d.physicalSurfaceMin(), d.physicalSurfaceMax(), d.heightmapMin(), d.heightmapMax());
		}
	}

	private static void runPostRelightDiagnostics(ServerLevel world, LevelChunk chunk, long packed) {
		OceanCanvasHeightmapDiag before = lightTelemetrySession().LIGHT_DIAG_PRE_PRIME.get(packed);
		OceanCanvasHeightmapDiag after = sampleHeightmapAgreement(chunk);
		long elapsedMs = -1L;
		long started = lightFinalizerSession().relightStartedNs.get(packed);
		if (started != OceanCanvasPrimitiveLongLongMap.ABSENT) elapsedMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
		int neighborWaits = lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAITS.getOrDefault(packed, 0);

		String classification;
		String potentialCause;
		boolean suspicious = false;
		BlockPos reactionProduct = firstUnexpectedFluidReactionProduct(world, chunk, OceanCanvasConfig.get());

		if (reactionProduct != null) {
			boolean mayRepairPhysically = lightFinalizerSession().allowPhysicalRepair.contains(packed);
			classification = mayRepairPhysically
					? "FLUID_REACTION_PRODUCT_SUSPECT"
					: "FLUID_REACTION_PRODUCT_SUSPECT_LIGHT_ONLY";
			potentialCause = "unexpected stone/cobblestone/obsidian in canonical Canvas water column at "
					+ reactionProduct.getX() + "," + reactionProduct.getY() + "," + reactionProduct.getZ()
					+ (mayRepairPhysically
							? "; PHYSICAL_AWARE lane may repair after ownership proof"
							: "; LIGHT_ONLY lane remains deliberately non-destructive until physical ownership is proven");
			long suspectCount = lightTelemetrySession().FLUID_REACTION_PRODUCT_SUSPECTS.incrementAndGet();
			lightTelemetrySession().FLUID_REACTION_PRODUCT_SUSPECT_CHUNKS.add(packed);
			// v253.125.10: the 125.9 soak emitted hundreds of identical reaction-product
			// WARNs. They are valuable evidence, but WARN-level disk/console I/O must not
			// compete with the relight pipeline. Keep every event in cumulative telemetry
			// and preserve early + power-of-two/periodic evidence while quieting repeats.
			suspicious = suspectCount <= 16L || (suspectCount & 255L) == 0L;
		} else if (after.mismatchedColumns() > 0) {
			classification = "HEIGHTMAP_STILL_INCONSISTENT";
			potentialCause = "server heightmap does not match current physical blocks even after primeHeightmaps";
			lightTelemetrySession().LIGHT_DIAG_HEIGHT_STILL_BAD.incrementAndGet();
			suspicious = true;
		} else if (before != null && before.mismatchedColumns() > 0) {
			classification = "STALE_HEIGHTMAP_CORRECTED";
			potentialCause = "raw terrain writes had left stale WORLD_SURFACE data; primeHeightmaps corrected it before relight";
			lightTelemetrySession().LIGHT_DIAG_STALE_HEIGHT_FIXED.incrementAndGet();
		} else {
			PhysicalProfileMismatch physical = null;
			// Only pay for the full physical audit when the sampled surface spread is
			// unusual; normal flat-canvas chunks stay on the cheap diagnostic path.
			if (after.physicalSurfaceMax() - after.physicalSurfaceMin() > 8) {
				physical = firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get());
			}
			if (physical != null) {
				classification = "PHYSICAL_PROFILE_MISMATCH";
				potentialCause = physical.describe();
				lightTelemetrySession().LIGHT_DIAG_PHYSICAL_SUSPECT.incrementAndGet();
				suspicious = true;
			} else if (elapsedMs > LIGHT_RELIGHT_SLOW_WARN_MS) {
				classification = "RELIGHT_SLOW";
				potentialCause = "staged relight wall age exceeded " + LIGHT_RELIGHT_SLOW_WARN_MS + "ms; this includes finalizer queue/settle latency, so use the cumulative summary plus strict SKY/profile failures to distinguish saturation from an actual bad field";
				long slowCount = lightTelemetrySession().LIGHT_DIAG_RELIGHT_SLOW.incrementAndGet();
				// v253.73.12: the supplied 84-second sample emitted 1,128 identical
				// RELIGHT_SLOW WARNs while pre/post heightmaps were clean. WARN-level log
				// I/O must not become part of the overload loop. Preserve the first evidence
				// and periodic milestones; every event remains counted in SUMMARY telemetry.
				suspicious = slowCount <= 16L || (slowCount & 255L) == 0L;
			} else {
				classification = "SERVER_STATE_HEALTHY";
				potentialCause = "physical profile, sampled heightmap, neighborhood availability and quiet-period boundary light convergence all passed; if a shadow remains visually, investigate client chunk/light cache, renderer, shader, or Voxy";
				lightTelemetrySession().LIGHT_DIAG_HEALTHY.incrementAndGet();
			}
		}

		long finalized = lightTelemetrySession().LIGHT_DIAG_FINALIZED.incrementAndGet();
		if (suspicious || OceanCanvas.LOGGER.isDebugEnabled()) {
			String line = "(Ocean Canvas) LIGHT-DIAG chunk " + chunk.getPos().x() + "," + chunk.getPos().z()
					+ " classification=" + classification
					+ " prePrimeMismatch=" + (before == null ? -1 : before.mismatchedColumns())
					+ "/" + (before == null ? -1 : before.sampledColumns())
					+ " postPrimeMismatch=" + after.mismatchedColumns() + "/" + after.sampledColumns()
					+ " maxHeightDelta=" + after.maxAbsDelta()
					+ " surfaceRange=" + after.physicalSurfaceMin() + ".." + after.physicalSurfaceMax()
					+ " neighborWaits=" + neighborWaits
					+ " relightMs=" + elapsedMs
					+ " potentialCause=" + potentialCause;
			if (suspicious) OceanCanvas.LOGGER.warn(line);
			else OceanCanvas.LOGGER.debug(line);
		}

		// v253.72.9: one cumulative summary per 1024 finalizations is enough for
		// forensic trend data; 1/128 became measurable log I/O at overnight scale.
		if ((finalized & 1023L) == 0L) {
			OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-DIAG SUMMARY build={} finalized={} healthy={} staleHeightFixed={} heightStillBad={} physicalMismatch={} neighborStarved={} neighborWaitWarnSuppressed={} relightFailed={} relightSlow={} relightTicketsActive={} ticketInstalls={} ticketReleases={} ticketRotations={} safeSweeps={} safeSweepChecks={} surfaceSkySamples={} deepSkySamples={} deepAnomalousLayers={} deepAnomalousColumns={} deepOverbrightLayers={} deepOverbrightColumns={} skyAnomalous={} skyEscalations={} sourceReseeds={} hardSkyResets={} persistentSkyBackoffs={} deepZeroScrubs={} deepZeroScrubSections={} deepZeroScrubInconclusive={} deepZeroDirectRepairs={} deepZeroDirectSections={} deepZeroDirectFailures={} visibleDeepDenseRepairs={} visibleDeepDenseSections={} visibleDeepDenseChecks={} visibleDeepDenseInconclusive={} visibleDeepClusterRepairs={} visibleDeepClusterChunkSections={} visibleDeepClusterChecks={} visibleDeepClusterInconclusive={} visibleDeepClusterThrottled={} finalPublishes={} dirtyAdds={} dirtyRearms={} dirtyCoalesced={} verifiedNeighborSkips={} verifiedNeighborAudits={} restartAuditHealthy={} persistedAuditPriorityDeferrals={} restartAuditRepairs={} rejoinRepublishes={} postCertProfileRepairs={} blocksChangedDuringSettle={} terrainBarrierTicks={} terrainBarrierTicketReleases={} structureBarrierTicks={} structureBarrierTicketReleases={} waterfallSurvivorBlocks={} flowingWaterNormalized={} waterfallSurvivorChunks={} rawPendingBlockEntityNbtRemoved={} deepRepairSlices={} deepRepairSliceYields={} deepRepairHeapDeferrals={} deepRepairHeapEscapeSlices={} deepRepairContextAborts={} deepRepairMaxSliceChecks={} deepRepairMaxSliceMicros={} adaptiveQuietEscalations={} adaptiveQuietTicketReleases={} maxInstabilityStreak={} maxAdaptiveQuietTicks={} pathologyHotspots={} slowPhaseEvents={} maxStrictSkyProofMicros={} maxPhysicalAuditMicros={} maxBoundaryFingerprintMicros={} maxLightSweepMicros={} maxHardResetMicros={} fluidReactionSuspects={} fluidReactionSuspectChunks={} fluidSettleRepairedBlocks={} fluidSettleRepairedChunks={}. Interpretation: live FULL chunks never toggle lightCorrect; canonical surface, deep-underbright, and deep chunk-seam continuity invariants remain strict before authoritative publish. No live SKY DataLayer is replaced with all-zero storage; deep-water recovery uses public source/section re-prime operations and strict re-verification. All persistent skylight faults remain fail-closed.",
					net.oceancanvas.mod.OceanCanvas.VERSION,
					finalized,
					lightTelemetrySession().LIGHT_DIAG_HEALTHY.get(),
					lightTelemetrySession().LIGHT_DIAG_STALE_HEIGHT_FIXED.get(),
					lightTelemetrySession().LIGHT_DIAG_HEIGHT_STILL_BAD.get(),
					lightTelemetrySession().LIGHT_DIAG_PHYSICAL_SUSPECT.get(),
					lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_STARVED.get(),
					lightTelemetrySession().LIGHT_DIAG_NEIGHBOR_WAIT_WARNINGS_SUPPRESSED.get(),
					lightTelemetrySession().LIGHT_DIAG_RELIGHT_FAILED.get(),
					lightTelemetrySession().LIGHT_DIAG_RELIGHT_SLOW.get(),
					lightFinalizerSession().relightResidencyLedger.activeCount(),
					lightFinalizerSession().relightResidencyLedger.installCount(),
					lightFinalizerSession().relightResidencyLedger.releaseCount(),
					lightFinalizerSession().relightResidencyLedger.rotationCount(),
					lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEPS.get(),
					lightTelemetrySession().LIGHT_DIAG_SAFE_SWEEP_BLOCK_CHECKS.get(),
					lightTelemetrySession().LIGHT_DIAG_SKY_SAMPLES.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_SAMPLES.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_ANOMALOUS_LAYERS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_ANOMALOUS_COLUMNS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_OVERBRIGHT_LAYERS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_SKY_OVERBRIGHT_COLUMNS.get(),
					lightTelemetrySession().LIGHT_DIAG_SKY_ANOMALOUS.get(),
					lightTelemetrySession().LIGHT_DIAG_SKY_ESCALATIONS.get(),
					lightTelemetrySession().LIGHT_DIAG_SOURCE_RESEEDS.get(),
					lightTelemetrySession().LIGHT_DIAG_HARD_SKY_RESETS.get(),
					lightTelemetrySession().LIGHT_DIAG_PERSISTENT_SKY_BACKOFFS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUBS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUB_SECTIONS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_SCRUB_INCONCLUSIVE.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERIES.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_SECTIONS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_FAILURES.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_REPAIRS.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_SECTIONS.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_CHECKS.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_DENSE_INCONCLUSIVE.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_REPAIRS.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_CHUNK_SECTIONS.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_CHECKS.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_INCONCLUSIVE.get(),
					lightTelemetrySession().LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_THROTTLED.get(),
					lightTelemetrySession().LIGHT_DIAG_FINAL_PUBLISHES.get(),
					lightTelemetrySession().LIGHT_DIAG_DIRTY_ADDS.get(),
					lightTelemetrySession().LIGHT_DIAG_DIRTY_REARMS.get(),
					lightTelemetrySession().LIGHT_DIAG_DIRTY_COALESCED.get(),
					lightTelemetrySession().LIGHT_DIAG_VERIFIED_NEIGHBOR_SKIPS.get(),
					lightTelemetrySession().LIGHT_DIAG_VERIFIED_NEIGHBOR_AUDITS.get(),
					lightTelemetrySession().LIGHT_DIAG_RESTART_AUDIT_HEALTHY.get(),
					lightTelemetrySession().LIGHT_DIAG_PERSISTED_AUDIT_PRIORITY_DEFERRALS.get(),
					lightTelemetrySession().LIGHT_DIAG_RESTART_AUDIT_REPAIRS.get(),
					lightTelemetrySession().LIGHT_DIAG_REJOIN_REPUBLISHES.get(),
					lightTelemetrySession().LIGHT_DIAG_PROFILE_REPAIRS_AFTER_CERT.get(),
					lightTelemetrySession().LIGHT_DIAG_POST_STAGE_MUTATIONS.get(),
					lightTelemetrySession().LIGHT_DIAG_TERRAIN_BARRIER_TICKS.get(),
					lightTelemetrySession().LIGHT_DIAG_TERRAIN_BARRIER_TICKET_RELEASES.get(),
					lightTelemetrySession().LIGHT_DIAG_STRUCTURE_BARRIER_TICKS.get(),
					lightTelemetrySession().LIGHT_DIAG_STRUCTURE_BARRIER_TICKET_RELEASES.get(),
					lightTelemetrySession().FLUID_WATERFALL_SURVIVOR_BLOCKS.get(),
					lightTelemetrySession().FLUID_FLOWING_WATER_NORMALIZED.get(),
					lightTelemetrySession().FLUID_WATERFALL_SURVIVOR_CHUNKS.get(),
					lightTelemetrySession().RAW_PENDING_BLOCK_ENTITIES_REMOVED.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_SLICES.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_SLICE_YIELDS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_HEAP_DEFERRALS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_HEAP_ESCAPE_SLICES.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_CONTEXT_ABORTS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_MAX_SLICE_CHECKS.get(),
					lightTelemetrySession().LIGHT_DIAG_DEEP_REPAIR_MAX_SLICE_NANOS.get() / 1_000L,
					lightTelemetrySession().LIGHT_DIAG_ADAPTIVE_QUIET_ESCALATIONS.get(),
					lightTelemetrySession().LIGHT_DIAG_ADAPTIVE_QUIET_TICKET_RELEASES.get(),
					lightTelemetrySession().LIGHT_DIAG_MAX_INSTABILITY_STREAK.get(),
					lightTelemetrySession().LIGHT_DIAG_MAX_ADAPTIVE_QUIET_TICKS.get(),
					lightTelemetrySession().LIGHT_DIAG_PATHOLOGY_HOTSPOTS.get(),
					lightTelemetrySession().LIGHT_DIAG_SLOW_PHASE_EVENTS.get(),
					lightTelemetrySession().LIGHT_DIAG_MAX_STRICT_SKY_PROOF_NANOS.get() / 1_000L,
					lightTelemetrySession().LIGHT_DIAG_MAX_PHYSICAL_AUDIT_NANOS.get() / 1_000L,
					lightTelemetrySession().LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS.get() / 1_000L,
					lightTelemetrySession().LIGHT_DIAG_MAX_LIGHT_SWEEP_NANOS.get() / 1_000L,
					lightTelemetrySession().LIGHT_DIAG_MAX_HARD_RESET_NANOS.get() / 1_000L,
					lightTelemetrySession().FLUID_REACTION_PRODUCT_SUSPECTS.get(),
					lightTelemetrySession().FLUID_REACTION_PRODUCT_SUSPECT_CHUNKS.size(),
					lightTelemetrySession().FLUID_SETTLE_REPAIRED_BLOCKS.get(),
					lightTelemetrySession().FLUID_SETTLE_REPAIRED_CHUNKS.get());
			logCooperativeLightTelemetry("SUMMARY");
		}
	}


	// v253.14 intentionally has no startFullVanillaRelight/lightChunk path.
	// Any future attempt to reintroduce ThreadedLevelLightEngine#lightChunk for a
	// live FULL chunk must first account for C2ME's async setLightCorrect mutation.

	/**
	 * Publishes a palette-only mutation without promoting a non-canvas chunk into
	 * the canonical canvas lighting state machine. Full chunk packets include the
	 * biome quart palette, and the terrain-change bus arms the client tint/mesh
	 * convergence path.
	 */
	private static void publishBiomeOnlyChunkUpdate(ServerLevel world, LevelChunk chunk) {
		chunk.markUnsaved();
		pushChunkWithAuthoritativeLight(world, chunk);
		net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.publish(
				new net.oceancanvas.mod.compat.OceanCanvasTerrainChange(
						world, chunk.getPos(),
						net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.CANVAS_WRITE));
	}

	private static void pushChunkWithAuthoritativeLight(ServerLevel world, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		// v253.22: Let vanilla select the complete light-section masks.  The old
		// hand-built mask used only the dimension's block-section span, but light
		// storage includes boundary light sections as well.  Supplying that shorter
		// mask could therefore resend an incomplete/stale light snapshot and leave
		// the exact rectangular dark patches seen after an otherwise healthy server
		// relight.  null/null is the packet API's canonical "all sections" path.
		// v253.69: constructing a full chunk+all-light packet serializes the entire
		// chunk even when nobody can receive it. During far-field Pregen the runtime
		// showed thousands of CLIENT-FINALIZE notifications with vanillaLoaded=0.
		// Build the authoritative packet lazily only if at least one player actually
		// tracks this chunk; future tracking gets vanilla's current full chunk anyway.
		net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet = null;
		for (net.minecraft.server.level.ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(world, pos)) {
			if (packet == null) {
				packet = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
						chunk, world.getChunkSource().getLightEngine(), null, null);
			}
			player.connection.send(packet);
		}
		net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.broadcastHolderChanges(world, chunk);
	}

	private record FluidSettleRepair(int repairedBlocks, BlockPos firstPosition, BlockState firstOldState) {}

	/**
	 * v253.25 repairs all definite vanilla fluid-reaction products in one chunk
	 * scan. v253.24 repaired the whole canonical profile after finding one product,
	 * then rediscovered more products on later finalizations. That produced 3,678
	 * repair warnings in one 4,096-chunk test and repeatedly dirtied lighting.
	 */
	private static FluidSettleRepair repairUnexpectedFluidReactionProducts(ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int variation = config.oceanFloorVariation();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		int repaired = 0; BlockPos first = null; BlockState firstOld = null;
		for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
			int x = baseX + lx, z = baseZ + lz;
			if (!strictCanvasColumnSelected(chunk, config, x, z)) continue;
			int floorY = baseFloorY + floorOffset(x, z, variation);
			for (int y = floorY + 1; y <= waterTop; y++) {
				BlockPos pos = new BlockPos(x, y, z);
				if (OceanCanvasPlayerZones.get(world).isProtected(x, y, z)) continue;
				BlockState old = chunk.getBlockState(pos);
				if (!old.is(Blocks.STONE) && !old.is(Blocks.COBBLESTONE) && !old.is(Blocks.OBSIDIAN)) continue;
				if (first == null) { first = pos; firstOld = old; }
				BlockState water = Blocks.WATER.defaultBlockState();
				world.removeBlockEntity(pos);
				setBlockStateRawSafe(world, chunk, pos, water);
				world.sendBlockUpdated(pos, old, water, 3);
				world.getChunkSource().getLightEngine().checkBlock(pos);
				repaired++;
			}
		}
		return new FluidSettleRepair(repaired, first, firstOld);
	}

	private static BlockPos firstUnexpectedFluidReactionProduct(ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int baseFloorY = config.oceanFloorY();
		int variation = config.oceanFloorVariation();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
			int x = baseX + lx, z = baseZ + lz;
			if (!strictCanvasColumnSelected(chunk, config, x, z)) continue;
			int floorY = baseFloorY + floorOffset(x, z, variation);
			for (int y = floorY + 1; y <= waterTop; y++) {
				BlockPos pos = new BlockPos(x, y, z);
				if (OceanCanvasPlayerZones.get(world).isProtected(x, y, z)) continue;
				BlockState state = chunk.getBlockState(pos);
				if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.OBSIDIAN)) return pos;
			}
		}
		return null;
	}

	/** v253.72.3 narrowly-scoped repair result for orphan fluid survivors. */
	public record FluidSurvivorRepair(int aboveSurfaceRemoved, int aboveSurfaceSourcesRemoved,
			int aboveSurfaceFlowingRemoved, int flowingWaterNormalized,
			BlockPos firstPosition, BlockState firstOldState, int minY, int maxY) {
		public int repairedBlocks() { return aboveSurfaceRemoved + flowingWaterNormalized; }
		public boolean changed() { return repairedBlocks() > 0; }
	}

	/**
	 * v253.72.3 targeted waterfall/flowing-water repair.
	 *
	 * <p>This is deliberately NOT a general historical canonicalizer. It may run on
	 * an already physically-sealed CANVAS chunk only while the same Pregen operation
	 * is still active and owns this chunk in its block-exact selection. It mutates
	 * pure fluid blocks only: orphan WATER/LAVA above the configured sea surface is
	 * removed and flowing WATER inside the canonical ocean column is normalized to
	 * source water. Solid blocks, vegetation, block entities, explicit player zones,
	 * and preserved whole-structure bounds are untouched. After Pregen completes,
	 * ordinary gameplay chunk loads remain non-destructive.</p>
	 */
	public static FluidSurvivorRepair repairTrackedPregenFluidSurvivors(
			ServerLevel world, LevelChunk chunk, String trigger) {
		if (world == null || chunk == null) return new FluidSurvivorRepair(0,0,0,0,null,null,0,0);
		int cx = chunk.getPos().x(), cz = chunk.getPos().z();
		if (!OceanCanvasActiveTerrainOperationBridge.pregenIncludesChunk(cx, cz)) {
			return new FluidSurvivorRepair(0,0,0,0,null,null,0,0);
		}
		var terrain = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(chunk.getPos());
		if (terrain != net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) {
			return new FluidSurvivorRepair(0,0,0,0,null,null,0,0);
		}
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
		if (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos())) {
			return new FluidSurvivorRepair(0,0,0,0,null,null,0,0);
		}
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int waterTop = OceanCanvasConfig.WATER_SURFACE_Y;
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds =
				computePreservedWholeBoundsForAudit(world, chunk);
		int removed = 0, sourceRemoved = 0, flowingRemoved = 0, normalized = 0;
		BlockPos first = null; BlockState firstOld = null;
		int minChangedY = Integer.MAX_VALUE, maxChangedY = Integer.MIN_VALUE;

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return new FluidSurvivorRepair(0,0,0,0,null,null,0,0);
				int x = minX + dx, z = minZ + dz;
				if (config.canvasZone(x, z) != OceanCanvasConfig.CanvasZone.INSIDE) continue;
				if (!OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(cx, cz, x, z)) continue;

				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				top = Math.min(world.getMaxY() - 1, Math.max(waterTop + 1, top + 1));
				for (int y = waterTop + 1; y <= top; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = chunk.getBlockState(pos);
					if (!(state.is(Blocks.WATER) || state.is(Blocks.LAVA))) continue;
					if (isPhysicalAuditProtected(world, x, y, z, state, preservedWholeBounds)) continue;
					if (first == null) { first = pos; firstOld = state; }
					if (state.getFluidState().isSource()) sourceRemoved++; else flowingRemoved++;
					BlockState target = Blocks.AIR.defaultBlockState();
					OceanCanvasUndoRecorderBridge.record(world, pos, state);
					setBlockStateRawSafe(world, chunk, pos, target);
					world.sendBlockUpdated(pos, state, target, 3);
					world.getChunkSource().getLightEngine().checkBlock(pos);
					removed++;
					minChangedY = Math.min(minChangedY, y); maxChangedY = Math.max(maxChangedY, y);
				}

				// Historical near-field self-heal deliberately normalizes only the sea
				// surface cell. A waterfall that reaches the ocean must cross this cell,
				// while scanning/re-writing the whole deep column on every light-resync
				// request would be both expensive and needlessly invasive. Freshly-authored
				// chunks still receive the exhaustive source-water physical audit.
				BlockPos surfacePos = new BlockPos(x, waterTop, z);
				BlockState surfaceState = chunk.getBlockState(surfacePos);
				if (surfaceState.is(Blocks.WATER) && !surfaceState.getFluidState().isSource()
						&& !isPhysicalAuditProtected(world, x, waterTop, z, surfaceState, preservedWholeBounds)) {
					if (first == null) { first = surfacePos; firstOld = surfaceState; }
					BlockState target = Blocks.WATER.defaultBlockState();
					OceanCanvasUndoRecorderBridge.record(world, surfacePos, surfaceState);
					setBlockStateRawSafe(world, chunk, surfacePos, target);
					world.sendBlockUpdated(surfacePos, surfaceState, target, 3);
					world.getChunkSource().getLightEngine().checkBlock(surfacePos);
					normalized++;
					minChangedY = Math.min(minChangedY, waterTop); maxChangedY = Math.max(maxChangedY, waterTop);
				}
			}
		}

		FluidSurvivorRepair result = new FluidSurvivorRepair(removed, sourceRemoved, flowingRemoved, normalized,
				first, firstOld, minChangedY == Integer.MAX_VALUE ? 0 : minChangedY,
				maxChangedY == Integer.MIN_VALUE ? 0 : maxChangedY);
		if (result.changed()) {
			lightTelemetrySession().FLUID_WATERFALL_SURVIVOR_BLOCKS.addAndGet(removed);
			lightTelemetrySession().FLUID_FLOWING_WATER_NORMALIZED.addAndGet(normalized);
			lightTelemetrySession().FLUID_WATERFALL_SURVIVOR_CHUNKS.incrementAndGet();
			long packed = ChunkPos.pack(cx, cz);
			resetSkyRecoveryForPhysicalMutation(packed);
			lightFinalizerSession().terrainLastMutationTick.put(packed, world.getGameTime());
			rearmCanonicalNeighborLighting(world, chunk.getPos());
			net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(
					chunk, java.util.EnumSet.allOf(net.minecraft.world.level.levelgen.Heightmap.Types.class));
			chunk.markUnsaved();
			scheduleLightSync(world, chunk.getPos(), false);
			OceanCanvas.LOGGER.warn("(Ocean Canvas) WATERFALL-SURVIVOR build={} chunk={},{} trigger={} aboveSurfaceRemoved={} sourceRemoved={} flowingRemoved={} flowingNormalized={} spanY={}..{} first={} oldState={} cumulativeSurvivorBlocks={} cumulativeNormalized={} cumulativeChunks={} action=targeted-fluid-only-repair-and-authoritative-relight",
					net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, trigger == null ? "unknown" : trigger,
					removed, sourceRemoved, flowingRemoved, normalized, result.minY(), result.maxY(), result.firstPosition(), result.firstOldState(),
					lightTelemetrySession().FLUID_WATERFALL_SURVIVOR_BLOCKS.get(), lightTelemetrySession().FLUID_FLOWING_WATER_NORMALIZED.get(), lightTelemetrySession().FLUID_WATERFALL_SURVIVOR_CHUNKS.get());
		}
		return result;
	}

	private static boolean isCanonicalCanvasWaterState(BlockState state) {
		// v253.72.3: Blocks.WATER is not synonymous with a canonical ocean block.
		// LiquidBlock uses the same block for source and flowing states; accepting
		// every WATER block let a flowing waterfall column pass physical completion.
		// The Canvas ocean itself is deterministic source water. Vegetation remains
		// explicitly valid because it intentionally occupies a water cell.
		return (state.is(Blocks.WATER) && state.getFluidState().isSource())
				|| state.is(Blocks.SEAGRASS)
				|| state.is(Blocks.TALL_SEAGRASS)
				|| state.is(Blocks.KELP)
				|| state.is(Blocks.KELP_PLANT);
	}

	private static boolean isPhysicalAuditProtected(ServerLevel world, int x, int y, int z, BlockState state,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preservedWholeBounds) {
		// Player protection is absolute by design: explicit player-owned terrain
		// must survive even when it contains ordinary water/lava.
		if (OceanCanvasPlayerZones.get(world).isProtected(x, y, z)) return true;

		// v105: flattenChunk's carve loop unconditionally preserves anything
		// inside preservedWholeBounds (ocean ruins/monuments/ruined portals kept
		// whole by a region rule), with no material filter - see that check's own
		// comment for why. A structure's bounding box commonly includes incidental
		// natural terrain the structure never actually placed (an ore vein, plain
		// stone) that happens to fall inside the box. Until this fix, the audit had
		// no visibility into that list at all, so it could never recognize this as
		// a legitimate exemption: flattenChunk would faithfully re-preserve the
		// block on every repair attempt, the audit would faithfully reject it again
		// every time, and the pair could never converge - an unbreakable retry loop
		// that pins one Pregen target open forever (observed on the v104 20k run,
		// hundreds of repeated audit-and-repair cycles per second on one chunk).
		// Must be checked before the fluid short-circuit below, matching
		// flattenChunk's own ordering (preservedWholeBounds is unconditional there
		// too, including for water/lava that happens to sit in the box).
		if (isInsideAny(preservedWholeBounds, x, y, z)) return true;

		// v77.1 correctness fix: persisted structure bounding boxes are NOT a
		// license for a free-standing fluid block to survive Canvas completion.
		// The actual carver already material-filters structure protection; the
		// physical verifier previously treated the whole box as protected, so a
		// water/lava column inside an old shipwreck/structure box could survive
		// (or flow back in) and still receive a physical-completion seal. Pure
		// fluid blocks must therefore remain auditable/repairable unless they are
		// inside an explicit player zone (or preservedWholeBounds, checked above).
		// Waterlogged structure blocks are not Blocks.WATER and remain protected
		// normally.
		if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) return false;

		if (ProtectedRegions.isProtected(world, x, y, z)) return true;
		return OceanCanvasProtectedData.get(world).isProtected(x, y, z) && isShipMaterial(state);
	}

	/**
	 * v105: read-only counterpart to flattenChunk's own preservedWholeBounds
	 * discovery, built specifically so the physical audit (and its repair path)
	 * can recognize the same exemption flattenChunk actually applies - see
	 * isPhysicalAuditProtected's doc for the infinite-loop bug this closes.
	 * Deliberately mirrors flattenChunk's two discovery passes (3x3 getAllStarts
	 * neighborhood + this chunk's own getAllReferences) but calls only the pure,
	 * non-mutating half of structure dispatch: discoverAndPreserveWholeBox for
	 * ocean ruins/ruined portals (never relocates anything), and for ocean
	 * monuments, only a persisted ALREADY-relocated bounds lookup - never
	 * relocateMonumentToFloor itself. An audit call must never have the side
	 * effect of relocating a structure; if a monument hasn't been relocated yet,
	 * this deliberately does not report it as preserved, since flattenChunk
	 * itself wouldn't yet either at that point.
	 */
	private static java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> computePreservedWholeBoundsForAudit(
			ServerLevel world, net.minecraft.world.level.chunk.ChunkAccess chunk) {
		java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preserved = new java.util.ArrayList<>();
		OceanCanvasProtectedData data = OceanCanvasProtectedData.get(world);
		ChunkPos pos = chunk.getPos();

		// v182: this loop used to call world.getChunk(...) directly with
		// NO hasChunk guard at all - worse than flattenChunk's own two
		// passes, which at least checked hasChunk() first (and were still
		// found racy - see flattenChunk's v182 fix comment). getChunkNow
		// closes both problems at once: one atomic call, no separate
		// check, and no way to reach the blocking getChunkBlocking path.
		for (int ndx = -1; ndx <= 1; ndx++) {
			for (int ndz = -1; ndz <= 1; ndz++) {
				LevelChunk neighborChunk = world.getChunkSource().getChunkNow(pos.x() + ndx, pos.z() + ndz);
				if (neighborChunk == null) continue;
				for (net.minecraft.world.level.levelgen.structure.StructureStart start
						: neighborChunk.getAllStarts().values()) {
					addPreservedWholeBoundsReadOnly(world, start, data, preserved);
				}
			}
		}

		for (java.util.Map.Entry<net.minecraft.world.level.levelgen.structure.Structure,
				it.unimi.dsi.fastutil.longs.LongSet> entry : chunk.getAllReferences().entrySet()) {
			if (!isManagedStructure(entry.getKey())) continue;
			it.unimi.dsi.fastutil.longs.LongIterator ownerIt = entry.getValue().iterator();
			while (ownerIt.hasNext()) {
				long packedOwnerPos = ownerIt.nextLong();
				ChunkPos ownerPos = new ChunkPos(ChunkPos.getX(packedOwnerPos), ChunkPos.getZ(packedOwnerPos));
				// v182: same hasChunk()-then-getChunk() race as flattenChunk -
				// replaced with the same single atomic getChunkNow() call.
				LevelChunk ownerChunk = world.getChunkSource().getChunkNow(ownerPos.x(), ownerPos.z());
				if (ownerChunk == null) continue;
				net.minecraft.world.level.levelgen.structure.StructureStart start =
						ownerChunk.getStartForStructure(entry.getKey());
				addPreservedWholeBoundsReadOnly(world, start, data, preserved);
			}
		}
		return preserved;
	}

	private static void addPreservedWholeBoundsReadOnly(ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start, OceanCanvasProtectedData data,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> preserved) {
		if (start == null || !start.isValid()) return;
		discoverAndPreserveWholeBox(world, start, OceanCanvasStructureKind.OCEAN_RUIN,
				net.minecraft.world.level.levelgen.structure.structures.OceanRuinStructure.class, preserved);
		discoverAndPreserveWholeBox(world, start, OceanCanvasStructureKind.RUINED_PORTAL,
				net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure.class, preserved);
		if (start.getStructure() instanceof net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure) {
			net.minecraft.world.level.levelgen.structure.BoundingBox extent = pieceExtent(start);
			if (extent != null) {
				BlockPos origin = new BlockPos(extent.minX(), extent.minY(), extent.minZ());
				net.minecraft.world.level.levelgen.structure.BoundingBox already =
						data.relocatedStructureBounds(OceanCanvasStructureKind.OCEAN_MONUMENT, origin);
				if (already != null) preserved.add(already);
			}
		}
	}

	private record PhysicalProfileMismatch(int x, int y, int z, String reason) {
		String describe() { return reason + " at " + x + "," + y + "," + z; }
	}

	/**
	 * Cheap, deterministic, seamless-across-chunks variation for the
	 * excavated floor height - self-contained value noise (hash +
	 * smoothstep interpolation) rather than Minecraft's own noise
	 * generators, specifically to avoid guessing at another unconfirmed
	 * internal API in an area that's purely cosmetic anyway. Returns
	 * roughly {@code -amplitude} to {@code +amplitude}.
	 *
	 * <p><b>Rewritten twice after user feedback - this is attempt
	 * three, and the two earlier attempts pointed at genuinely
	 * different problems worth recording:</b></p>
	 * <ul>
	 *   <li>Attempt 1 used two long-wavelength (~80-125 block) sine
	 *       waves. Feedback: looked like obvious "rolling hills".</li>
	 *   <li>Attempt 2 over-corrected by shortening the wavelength to
	 *       ~6-20 blocks for small, localized bumps. Feedback: now "too
	 *       busy" - lots of small bumps piled on each other, not the
	 *       broad gentle undulation over a large area that was actually
	 *       wanted. The real issue in attempt 1 was never the
	 *       wavelength - broad and gentle over a large area was correct
	 *       - it was that pure sine waves are exactly, visibly
	 *       periodic, so "rolling hills" really meant "an obviously
	 *       repeating wave pattern", not "the bumps are too big".</li>
	 *   <li>This version keeps the broad wavelength (large cell size
	 *       below) but replaces sine waves with actual value noise -
	 *       random values on a coarse grid, smoothly interpolated
	 *       between them. Unlike sine, value noise genuinely doesn't
	 *       repeat, which is what "feel randomized" actually needs -
	 *       broad + gentle + non-repeating simultaneously, matching
	 *       "waves have sculpted the ground...gently flows over an
	 *       expansive area" directly. Default amplitude restored to 5
	 *       per the user's explicit "+/- 5 blocks" spec.</li>
	 * </ul>
	 */
	static int floorOffset(int x, int z, int amplitude) {
		return OceanCanvasFloorProfile.floorOffset(x, z, amplitude);
	}

	/**
	 * Common vanilla terrain/ocean-floor materials that should never be
	 * protected inside a structure's bounding box, even though they
	 * physically sit within it.
	 *
	 * <p><b>Real bug this fixes:</b> the guaranteed spawn shipwreck
	 * became visible once structure protection worked, but the natural
	 * sand/dirt mound it originally rested on stayed untouched too,
	 * since it was inside the same bounding box - looked like the ship
	 * sitting on an odd island rather than sunk into the new ocean
	 * floor. A structure's bounding box only tells you "the structure
	 * is somewhere in this space", not "every block in this space is
	 * the structure" - natural terrain sharing that space needs to keep
	 * getting carved normally.</p>
	 *
	 * <p><b>Follow-up bug: a coal ore vein was left exposed right next
	 * to a shipwreck, uncarved.</b> Same underlying cause as above, one
	 * material the original list simply missed - ore blocks aren't
	 * covered by {@code BASE_STONE_OVERWORLD} (that tag is specifically
	 * the plain stone/deepslate "background" rock, not ore variants),
	 * so any natural ore vein falling inside a structure's bounding box
	 * was being wrongly treated as part of the structure. Fixed by
	 * explicitly excluding the standard per-ore vanilla tags too.</p>
	 */
	// On 26.2, BlockTags.COAL_ORES / REDSTONE_ORES / LAPIS_ORES / DIAMOND_ORES /
	// EMERALD_ORES no longer exist as Java constants - see the git history
	// around this comment if that lookup pattern is ever needed again
	// (it was used by the since-removed isNaturalTerrainMaterial, retired
	// in favor of isShipMaterial's small allowlist below).


	/**
	 * <p><b>Real architectural fix, requested directly by the user after
	 * a screenshot showing a floating chunk of sandstone sitting right
	 * next to a shipwreck, on top of "part of the boat missing, chest
	 * missing": "the boat should simply be cut and pasted lower, with
	 * all the blocks of the boat only, the chests, and the entities in
	 * the chest."</b> This used to be {@code isNaturalTerrainMaterial}, a
	 * DENYLIST of terrain types safe to carve even inside a protected
	 * box - sand, gravel, dirt, ice (added after the ice report), ore
	 * tags, etc. Every single "X is floating next to the ship" report
	 * across this whole project has had the exact same shape: some
	 * vanilla material wasn't on that list, so it got wrongly preserved
	 * as if it were part of the ship. Ice was one instance; this
	 * report's sandstone is another - vanilla beach/desert-adjacent
	 * seafloor terrain routinely includes real sandstone formations, and
	 * {@code #minecraft:sand} (the tag this used to check) only covers
	 * loose SAND/RED_SAND, never SANDSTONE. A denylist like this can
	 * never be complete - there will always be another vanilla block
	 * nobody thought to add.</p>
	 *
	 * <p>This is the inversion the user actually asked for: a small,
	 * curated ALLOWLIST of the real, enumerable set of blocks a vanilla
	 * shipwreck template can actually contain, checked the other way
	 * round - protect ONLY IF the block matches this list; anything
	 * else inside a protected box (sand, sandstone, ice, stone,
	 * andesite, literally anything not on this list) now carves away
	 * like ordinary terrain, with no more whack-a-mole possible. Every
	 * wood type is covered via tags (not hardcoded to oak) in case a
	 * future Minecraft update or a shipwreck-adjacent structure variant
	 * uses a different one.</p>
	 */
	private static boolean isShipMaterial(BlockState state) {
		return state.is(net.minecraft.tags.BlockTags.PLANKS)
				|| state.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS)
				|| state.is(net.minecraft.tags.BlockTags.WOODEN_SLABS)
				|| state.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)
				|| state.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS)
				|| state.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)
				|| state.is(net.minecraft.tags.BlockTags.WOODEN_PRESSURE_PLATES)
				|| state.is(net.minecraft.tags.BlockTags.WOODEN_BUTTONS)
				// FENCE_GATES, not WOODEN_FENCE_GATES - confirmed via a
				// real compile error. Vanilla's tag has no "WOODEN_"
				// prefix here (unlike WOODEN_FENCES/WOODEN_STAIRS/etc.
				// just above), since every fence gate in vanilla is
				// wood-based anyway - there's no other kind to
				// distinguish it from.
				|| state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
				|| state.is(Blocks.LADDER)
				|| state.is(Blocks.CHEST)
				|| state.is(Blocks.BARREL)
				|| state.is(Blocks.CARTOGRAPHY_TABLE)
				|| state.is(Blocks.LOOM)
				|| state.is(Blocks.COBWEB);
	}


	/**
	 * Removes any sand/gravel/concrete-powder (or anything else vanilla
	 * treats as gravity-affected - checked via {@code instanceof
	 * FallingBlock} rather than a hand-written material list, so this
	 * automatically covers every current and future falling-block type,
	 * not just the two most common ones) that this column's own carve
	 * just left resting on nothing.
	 *
	 * <p><b>Real bug, found from an actual "sand falls out of nowhere"
	 * report, not a hypothetical:</b> preserving sand/gravel found while
	 * carving is correct, since that's genuine vanilla-generated terrain,
	 * not debris. But preserving the block itself says nothing about what was
	 * supporting it. Vanilla terrain generation is careful to only ever
	 * generate sand/gravel resting on solid ground; carving straight
	 * through the stone that used to sit directly beneath a real
	 * vanilla sand/gravel pocket - very common, since this canvas's
	 * floor sits well below where an ordinary ocean floor would
	 * naturally land - leaves that block floating in the new water/air
	 * column with nothing under it. The main carve loop writes with
	 * update flag 0 specifically to avoid a physics cascade mid-carve
	 * (see the class doc's thread-dump note), so nothing falls in that
	 * same instant - but the block is now sitting in a state vanilla
	 * physics never allows on its own, and it reliably falls the moment
	 * literally anything nearby triggers a neighbor update later (the
	 * player breaking or placing any nearby block, even an unrelated
	 * random tick) - exactly the "falls out of nowhere, well after I'd
	 * already been through here" pattern reported. Very plausibly the
	 * real mechanism behind "the chest keeps breaking" too, even with
	 * shipwreck protection itself working correctly: collapsing sand
	 * from just outside a protected hull can still land on or bury its
	 * deck, which reads as damage even though the ship's own blocks
	 * were never touched. Matches this project's own stated philosophy
	 * ("only shipwrecks should ever be visible above the ocean floor...
	 * carved away like ordinary terrain if excavation would expose it")
	 * - floating debris exposed by carving was never supposed to
	 * survive a pass in the first place; this closes the real gap where
	 * it technically did.</p>
	 *
	 * <p>Runs as its own bottom-up pass over the same {@code
	 * transitionBottom..columnTop} range the main carve loop just
	 * finished, repeating until a full pass makes no further change - a
	 * single sweep isn't enough for a multi-block-tall floating vein,
	 * since removing its bottom block only exposes the next one up as
	 * newly-unsupported. Bounded by column height either way, and this
	 * only ever does meaningful work in the (rare) column where real
	 * vanilla generation actually left a sand/gravel pocket sitting
	 * exactly where this canvas's floor now cuts through - an ordinary
	 * column exits on its first, only pass.</p>
	 *
	 * @return true if this column's carve state changed (feeds the
	 * caller's {@code anyChange}, the same flag {@code /oceancanvas
	 * stats} and the persisted flattened-chunk counter rely on)
	 */
	/**
	 * Removes non-protected lava from the vertical range Ocean Canvas is about to
	 * own, before the first replacement-water block is written in this chunk.
	 * This prevents lava-water conversion products from being created mid-carve.
	 */
	private static boolean predrainCanvasLava(ServerLevel world, LevelChunk chunk, OceanCanvasConfig config,
			int minX, int minZ, int waterTop, int baseFloorY, int floorVariation, int transitionThickness) {
		boolean changed = false;
		int removedLava = 0;
		int minOwnedY = baseFloorY - Math.max(0, floorVariation) - Math.max(0, transitionThickness);
		int maxY = Math.max(waterTop + MAX_SEARCH_ABOVE_WATER,
				chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) + CLEAR_CONFIRMATION_BLOCKS);
		maxY = Math.min(maxY, world.getMaxY() - 1);
		minOwnedY = Math.max(minOwnedY, world.getMinY());

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return changed;
				int x = minX + dx, z = minZ + dz;
				if (!operationColumnSelected(chunk, x, z)) continue;
				if (config.canvasZone(x, z) == OceanCanvasConfig.CanvasZone.OUTSIDE) continue;
				for (int y = minOwnedY; y <= maxY; y++) {
					if ((y & 15) == 0 && net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return changed;
					BlockPos pos = new BlockPos(x, y, z);
					BlockState current = chunk.getBlockState(pos);
					if (!current.is(Blocks.LAVA)) continue;
					if (OceanCanvasPlayerZones.get(world).isProtected(x, y, z)) continue;
					BlockState target = y <= waterTop ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
					OceanCanvasUndoRecorderBridge.record(world, pos, current);
					world.getBlockEntity(pos);
					world.removeBlockEntity(pos);
					setBlockStateRawSafe(world, chunk, pos, target);
					world.getChunkSource().getLightEngine().checkBlock(pos);
					chunk.markUnsaved();
					changed = true;
					removedLava++;
				}
			}
		}
		if (removedLava > 0) {
			lightTelemetrySession().FLUID_LAVA_PREDRAIN_BLOCKS.addAndGet(removedLava);
			OceanCanvas.LOGGER.info("(Ocean Canvas) FLUID-DIAG build={} chunk={},{} lavaPredrainRemoved={} cumulativeRemoved={}",
				net.oceancanvas.mod.OceanCanvas.VERSION, chunk.getPos().x(), chunk.getPos().z(), removedLava, lightTelemetrySession().FLUID_LAVA_PREDRAIN_BLOCKS.get());
		}
		return changed;
	}

	private static boolean removeExposedFallingBlocks(ServerLevel world, LevelChunk chunk, int x, int z,
			int floorY, int columnTop, int waterTop,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> shipBounds,
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> wholeStructureBounds) {
		boolean changed = false;
		for (int y = floorY; y <= columnTop; y++) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState current = chunk.getBlockState(pos);
			if (!(current.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) {
				continue;
			}
			if (OceanCanvasPlayerZones.get(world).isProtected(x, y, z)
					|| isInsideAny(shipBounds, x, y, z)
					|| isInsideAny(wholeStructureBounds, x, y, z)) {
				continue;
			}
			BlockState target = y > waterTop ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState();
			OceanCanvasUndoRecorderBridge.record(world, pos, current);
			if (current.hasBlockEntity()) {
				world.removeBlockEntity(pos);
			}
			setBlockStateRawSafe(world, chunk, pos, target);
			world.sendBlockUpdated(pos, current, target, 3);
			changed = true;
		}
		return changed;
	}

	private static boolean removeUnsupportedFallingBlocks(ServerLevel world, LevelChunk chunk, int x, int z,
			int transitionBottom, int columnTop, int waterTop) {
		boolean anyChangeInColumn = false;
		boolean changedThisPass;
		do {
			changedThisPass = false;
			for (int y = transitionBottom; y <= columnTop; y++) {
				BlockPos pos = new BlockPos(x, y, z);
				BlockState current = chunk.getBlockState(pos);

				if (!(current.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) {
					continue;
				}

				BlockState below = chunk.getBlockState(new BlockPos(x, y - 1, z));
				boolean supported = !below.isAir() && below.getFluidState().isEmpty();
				if (supported) {
					continue; // genuinely resting on solid ground - leave it exactly as vanilla generated it
				}

				BlockState target = (y > waterTop) ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState();
				if (current == target) {
					continue;
				}

				OceanCanvasUndoRecorderBridge.record(world, pos, current);
				setBlockStateRawSafe(world, chunk, pos, target);
				world.sendBlockUpdated(pos, current, target, 3);
				changedThisPass = true;
				anyChangeInColumn = true;
			}
		} while (changedThisPass);

		return anyChangeInColumn;
	}

	// How many consecutive air blocks confirm nothing solid remains
	// above, for findSafeColumnTop below.
	private static final int CLEAR_CONFIRMATION_BLOCKS = 8;
	// Hard cap on how far up findSafeColumnTop will search, purely to
	// bound worst-case cost - real terrain/structures shouldn't ever
	// need to search this far above the water surface.
	private static final int MAX_SEARCH_ABOVE_WATER = 80;

	/**
	 * Scans upward from the water surface until confident nothing solid
	 * remains, instead of trusting a fixed margin above the heightmap.
	 * See the class doc's PRIORITY FOLLOW-UP note for why the fixed
	 * margin this replaced was leaving floating debris (cave ceiling
	 * fragments, overhangs) behind whenever real terrain extended
	 * higher than that margin covered.
	 */
	private static int findSafeColumnTop(LevelChunk chunk, int x, int z, int waterTop) {
		int top = Math.max(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), waterTop);
		int hardCap = waterTop + MAX_SEARCH_ABOVE_WATER;
		int consecutiveAir = 0;

		int y = top;
		while (y < hardCap && consecutiveAir < CLEAR_CONFIRMATION_BLOCKS) {
			y++;
			if (chunk.getBlockState(new BlockPos(x, y, z)).isAir()) {
				consecutiveAir++;
			} else {
				consecutiveAir = 0;
				top = y; // found more solid material - push the confirmed top up
			}
		}

		return top;
	}

	private static boolean isInsideAny(
			java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> boxes, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		for (net.minecraft.world.level.levelgen.structure.BoundingBox box : boxes) {
			if (box.isInside(pos)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Positively verifies every chunk a structure's X/Z footprint
	 * touches - AND a one-chunk buffer around it - is genuinely
	 * ticking-ready, rather than assuming a small structure's own chunk
	 * is enough. See {@link #relocateShipwreckIfNeeded}'s doc for why
	 * this exists. The buffer matches {@link #neighborsReady}'s own
	 * standard exactly: standard block placement (used by both the
	 * clear step and {@code placeInWorld}) can trigger placement
	 * behaviors that look at ADJACENT chunks (the same
	 * {@code LiquidBlock#onPlace} mechanism the thread-dump investigation
	 * root-caused the original stall to) - checking only the footprint
	 * itself, with no margin, would be exactly the same category of gap
	 * that caused that stall in the first place. Chunk readiness
	 * doesn't vary by Y-level, so only the X/Z span (plus buffer) needs
	 * checking even though the structure spans a Y range too.
	 */

	// v253.88: pending natural-shipwreck rescue lives in
	// OceanCanvasStructureRelocationRescueService; the shared relocation readiness
	// predicate below remains here because compact-structure relocation uses it too.

	private static boolean allChunksReadyForRelocation(ServerLevel world, int minX, int minZ, int maxX, int maxZ) {
		int minChunkX = Math.floorDiv(minX, 16) - 1;
		int maxChunkX = Math.floorDiv(maxX, 16) + 1;
		int minChunkZ = Math.floorDiv(minZ, 16) - 1;
		int maxChunkZ = Math.floorDiv(maxZ, 16) + 1;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				if (!world.getChunkSource().isPositionTicking(new ChunkPos(cx, cz).pack())) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * <p><b>Real bug, reported directly by the user after the previous
	 * fix round: a naturally-generated shipwreck (not the one this mod
	 * places itself) was floating well above the ocean floor, with
	 * broken chests spilling loot as dropped items.</b> Root cause:
	 * {@code ModStarterStructures}'s placement-Y fix only applies to
	 * the ONE shipwreck this mod pastes itself, when none already
	 * exists nearby. A shipwreck vanilla generated on its own, BEFORE
	 * this flattener ever runs, sits at whatever height it naturally
	 * generated at - which no longer matches the much deeper canvas
	 * floor. The broken chests were very likely a symptom of the same
	 * misalignment: parts of the ship ending up positioned oddly
	 * relative to the new floor/transition-layer boundaries.</p>
	 *
	 * <p>Unlike the guaranteed shipwreck (which this mod places itself
	 * and can simply choose the right Y for up front), an
	 * already-generated natural shipwreck has to be actively moved -
	 * captured into a template (preserving block entities, so chest
	 * loot survives intact) via {@code StructureTemplate#fillFromWorld},
	 * the original position cleared, then pasted back at the correct Y
	 * via the same {@code placeInWorld} call {@code ModStarterStructures}
	 * already uses. Runs at most once per structure, tracked by
	 * {@link OceanCanvasProtectedData#isRelocated} (keyed by the
	 * structure's original min-corner, which is stable across repeated
	 * chunk processing, and persisted so it stays true across a
	 * restart too) so it's never attempted twice.</p>
	 *
	 * <p><b>Chunk-readiness risk eliminated outright, not just noted as
	 * unlikely:</b> {@link #allChunksReadyForRelocation} positively
	 * verifies every chunk this could touch (footprint plus a one-chunk
	 * buffer, matching {@link #neighborsReady}'s own standard exactly)
	 * before any work happens at all - if anything isn't ready, this
	 * simply isn't attempted this pass and tries again later, rather
	 * than assuming a small structure won't run into the gap.
	 * {@code fillFromWorld}'s last parameter is a {@code List<Block>}
	 * to ignore during capture, not a nullable single {@code Block} as
	 * first guessed - the empty-list case wasn't handled null-safely
	 * internally (a real {@code NullPointerException} confirmed this on
	 * first test), fixed by passing {@code List.of()} instead of
	 * {@code null}.</p>
	 *
	 * <p><b>The actual, real root cause behind every "chest destroyed
	 * again" report on a natural shipwreck, finally identified after a
	 * log excerpt confirmed the broken structure was going through THIS
	 * relocation path (not the guaranteed-shipwreck one):</b> the
	 * relocated copy is pasted via {@code placeInWorld}, exactly the
	 * same way the guaranteed shipwreck is - a raw NBT paste vanilla's
	 * own structure manager never learns about. The guaranteed
	 * shipwreck stays protected forever because it's registered in
	 * {@link ProtectedRegions}; this method never did that, and only
	 * ever returned the new bounding box to whichever single
	 * {@code flattenChunk} call happened to trigger the relocation. On
	 * ANY later pass - a neighboring chunk reprocessing, the area
	 * loading again, both routine and expected in this architecture -
	 * the caller finds this structure already relocated (via
	 * {@link OceanCanvasProtectedData#isRelocated}), gets {@code null}
	 * back, and falls through to the structure's ORIGINAL (now-empty,
	 * pre-relocation) piece bounding boxes from stale vanilla data
	 * instead - leaving the actual relocated copy completely
	 * unprotected from that point on, so the very next carve through
	 * that area went straight through the chest. Fixed by registering
	 * the relocated location in {@code ProtectedRegions} too, right
	 * after pasting - the same persistent mechanism the guaranteed
	 * shipwreck already relies on, instead of a one-time return value
	 * that only helped the single call that happened to receive it.</p>
	 *
	 * <p><b>Follow-up: the exact same "chest destroyed again" report
	 * came back later, this time after a restart rather than within
	 * one session.</b> The fix above was real but incomplete - both
	 * {@code ProtectedRegions} and the relocated-origins tracking were
	 * still plain in-memory static collections, so a server restart
	 * (for singleplayer: quitting and reopening the world) silently
	 * wiped them back to empty. Vanilla's own structure-start data
	 * persists independently of block data, so the next load still
	 * finds this shipwreck's original {@code StructureStart}, sees an
	 * empty relocated-origins record, and repeats this entire method -
	 * capturing the (now-air, already-cleared) original position and
	 * pasting that empty capture directly over the real relocated
	 * shipwreck at the same deterministic spot. See
	 * {@link OceanCanvasProtectedData}'s class doc for the actual fix:
	 * both collections now live in the level's own persistent save
	 * data instead of JVM memory, via {@code OceanCanvasProtectedData}.</p>
	 *
	 * @return the new (post-relocation) bounding box to protect, or
	 *         {@code null} if this structure was already relocated on a
	 *         previous pass, or if it's not yet safe to attempt (in
	 *         either case the caller falls back to the normal per-piece
	 *         protection, which correctly covers the CURRENT location
	 *         either way, since the pieces' own bounding boxes were
	 *         captured fresh from {@code getAllStarts()} on this same
	 *         call).
	 */
	/**
	 * <p><b>New feature, requested directly by the user: relocate buried
	 * treasure one block beneath the new ocean floor, the same way
	 * natural shipwrecks already get relocated to sit on it.</b> Real,
	 * distinct vanilla structure ({@code BuriedTreasureStructure}, same
	 * naming convention and package as {@code ShipwreckStructure}) -
	 * confirmed via the Minecraft Wiki, not guessed: buried treasure is
	 * genuinely just a single loot chest (surrounding "buried" material
	 * is ordinary terrain generation, not part of the structure itself),
	 * which also generates waterlogged in real vanilla when exposed to
	 * water - directly relevant here too, not just for shipwreck stairs.</p>
	 *
	 * <p>Deliberately reuses the exact same {@code StructureTemplate}
	 * capture/paste machinery already proven working for shipwreck
	 * chests, even though this is "just one block" - a real, current
	 * Mojang overhaul moved {@code BlockEntity} NBT save/load onto a new
	 * {@code ValueInput}/{@code ValueOutput} abstraction (confirmed via
	 * Fabric's own current docs), replacing the old raw {@code
	 * CompoundTag} methods. Manually reading/writing a chest's NBT
	 * directly would mean guessing at that unfamiliar API cold; reusing
	 * {@code fillFromWorld}/{@code placeInWorld} sidesteps it completely,
	 * since that machinery already handles block-entity capture
	 * correctly regardless of which serialization system is underneath
	 * it.</p>
	 *
	 * <p>Reuses {@code relocatedShipwreckOrigins}/{@code isRelocated}/
	 * {@code markRelocated} and {@code pendingOriginalProtections} rather
	 * than adding parallel treasure-specific fields - a min-corner
	 * collision between a shipwreck and a treasure chest would require
	 * both to generate at the exact same block position, which vanilla's
	 * own structure spacing makes essentially impossible. The persisted
	 * field name still literally says "Shipwreck", which reads a little
	 * oddly if you inspect the save file directly - a cosmetic
	 * imperfection, not a functional one, accepted here rather than
	 * doing a full schema migration for it.</p>
	 */
	private static net.minecraft.world.level.levelgen.structure.BoundingBox relocateMonumentToFloor(
			ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			net.minecraft.world.level.levelgen.structure.BoundingBox extent,
			BlockPos originKey) {
		int minX = extent.minX();
		int minY = extent.minY();
		int minZ = extent.minZ();
		int maxX = extent.maxX();
		int maxY = extent.maxY();
		int maxZ = extent.maxZ();
		if (!allChunksReadyForRelocation(world, minX, minZ, maxX, maxZ)) {
			return null;
		}

		int sizeX = maxX - minX + 1;
		int sizeY = maxY - minY + 1;
		int sizeZ = maxZ - minZ + 1;
		BlockPos captureOrigin = new BlockPos(minX, minY, minZ);
		net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template =
				new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
		// Do not clone monument entities with the template. Elder guardians are
		// structure-owned persistent entities; cloning the box and leaving the originals
		// behind can duplicate or strand them at the natural Y. Capture blocks/block
		// entities only, then move the existing elder guardians by the same delta below.
		java.util.List<net.minecraft.world.entity.monster.ElderGuardian> elderGuardians = new java.util.ArrayList<>(
				world.getEntitiesOfClass(net.minecraft.world.entity.monster.ElderGuardian.class,
						new net.minecraft.world.phys.AABB(minX,minY,minZ,maxX+1,maxY+1,maxZ+1)));
		template.fillFromWorld(world, captureOrigin, new net.minecraft.core.Vec3i(sizeX, sizeY, sizeZ), false,
				java.util.List.of());

		OceanCanvasConfig config = OceanCanvasConfig.get();
		int centerX = minX + sizeX / 2;
		int centerZ = minZ + sizeZ / 2;
		int targetY = config.oceanFloorY() + floorOffset(centerX, centerZ, config.oceanFloorVariation());
		BlockPos pastePos = new BlockPos(minX, targetY, minZ);
		boolean placed = template.placeInWorld(
				world, pastePos, pastePos,
				new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
				world.getRandom(), net.minecraft.world.level.block.Block.UPDATE_ALL);
		if (!placed) {
			OceanCanvas.LOGGER.warn("Failed to relocate ocean monument from {} to {} - original left untouched",
				captureOrigin, pastePos);
			return null;
		}

		net.minecraft.world.level.levelgen.structure.BoundingBox relocatedBounds =
				net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
					pastePos, pastePos.offset(sizeX - 1, sizeY - 1, sizeZ - 1));

		// A raw bounding-box capture can include the terrain or a second
		// structure that happened to overlap the monument. Keep only the
		// vanilla monument palette in the moved copy; this also prevents
		// gravel/sand shelves and an overlapping ruined portal from hitching
		// a ride to the new floor.
		for (BlockPos pos : BlockPos.betweenClosed(
				pastePos, pastePos.offset(sizeX - 1, sizeY - 1, sizeZ - 1))) {
			BlockState state = world.getBlockState(pos);
			if (isMonumentMaterial(state)) {
				continue;
			}
			world.removeBlockEntity(pos);
			world.setBlock(pos, pos.getY() <= OceanCanvasConfig.WATER_SURFACE_Y
					? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
		}

		// Retire the original only after the paste is known-good. The new
		// copy may overlap vertically when the natural monument was already
		// close to floorY, so never clear a position belonging to the copy.
		for (BlockPos pos : BlockPos.betweenClosed(
				captureOrigin, captureOrigin.offset(sizeX - 1, sizeY - 1, sizeZ - 1))) {
			if (relocatedBounds.isInside(pos)) {
				continue;
			}
			world.removeBlockEntity(pos);
			world.setBlock(pos, pos.getY() <= OceanCanvasConfig.WATER_SURFACE_Y
					? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
		}

		// Keep vanilla structure metadata aligned with the physical monument.
		// Guardian spawn selection and the guardian spawn predicate consult the
		// StructureStart/piece bounds; if we move only blocks, vanilla continues
		// to believe the monument is at its old Y. Moving each real monument
		// piece updates the existing StructureStart in-place. The start/reference
		// maps already point at this same object, so marking the involved chunks
		// unsaved persists the corrected piece bounds.
		int monumentDeltaY = targetY - minY;
		for (net.minecraft.world.level.levelgen.structure.StructurePiece piece : start.getPieces()) {
			piece.move(0, monumentDeltaY, 0);
		}
		for (net.minecraft.world.entity.monster.ElderGuardian elder : elderGuardians) {
			elder.setPos(elder.getX(), elder.getY() + monumentDeltaY, elder.getZ());
		}
		if (!elderGuardians.isEmpty() && elderGuardians.size()!=3) {
			OceanCanvas.LOGGER.warn("Natural monument at {} had {} loaded elder guardian(s) during relocation; moved all observed guardians without cloning them", originKey, elderGuardians.size());
		}
		markStructureMetadataUnsaved(world, start, relocatedBounds);

		OceanCanvasProtectedData.get(world).markRelocatedStructure(
				OceanCanvasStructureKind.OCEAN_MONUMENT, originKey, relocatedBounds);
		OceanCanvas.LOGGER.info("Relocated ocean monument from {} to {} and moved its vanilla StructureStart metadata", captureOrigin, pastePos);
		return relocatedBounds;
	}

	private static void markStructureMetadataUnsaved(
			ServerLevel world,
			net.minecraft.world.level.levelgen.structure.StructureStart start,
			net.minecraft.world.level.levelgen.structure.BoundingBox bounds) {
		try {
			net.minecraft.world.level.ChunkPos startPos = start.getChunkPos();
			// v197: was a raw world.getChunk(...) - the surrounding try/catch
			// only guards against RuntimeException, not a synchronous block,
			// so this was still exposed to the same getChunkBlocking race
			// documented at flattenChunk's v182 fix. getChunkNow is atomic;
			// a null result here just means the metadata write is skipped for
			// that chunk (same as the existing catch-and-warn behavior below),
			// not a reason to fall through to a blocking load.
			LevelChunk startChunk = world.getChunkSource().getChunkNow(startPos.x(), startPos.z());
			if (startChunk != null) {
				startChunk.markUnsaved();
			}
			for (int cz = Math.floorDiv(bounds.minZ(), 16); cz <= Math.floorDiv(bounds.maxZ(), 16); cz++) {
				for (int cx = Math.floorDiv(bounds.minX(), 16); cx <= Math.floorDiv(bounds.maxX(), 16); cx++) {
					LevelChunk boundsChunk = world.getChunkSource().getChunkNow(cx, cz);
					if (boundsChunk != null) {
						boundsChunk.markUnsaved();
					}
				}
			}
		} catch (RuntimeException ex) {
			OceanCanvas.LOGGER.warn("Could not mark relocated monument structure metadata dirty; guardian recognition may revert after reload", ex);
		}
	}

	private static boolean isMonumentMaterial(BlockState state) {
		return state.is(Blocks.PRISMARINE)
				|| state.is(Blocks.PRISMARINE_BRICKS)
				|| state.is(Blocks.DARK_PRISMARINE)
				|| state.is(Blocks.SEA_LANTERN)
				|| state.is(Blocks.GOLD_BLOCK)
				|| state.is(Blocks.WET_SPONGE)
				|| state.is(Blocks.SPONGE)
				|| state.is(Blocks.WATER)
				|| state.isAir();
	}

	private static net.minecraft.world.level.levelgen.structure.BoundingBox relocateBuriedTreasureIfNeeded(
			ServerLevel world, net.minecraft.world.level.levelgen.structure.StructureStart start) {
		java.util.List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = start.getPieces();
		if (pieces.isEmpty()) {
			return null;
		}

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (net.minecraft.world.level.levelgen.structure.StructurePiece piece : pieces) {
			net.minecraft.world.level.levelgen.structure.BoundingBox box = piece.getBoundingBox();
			minX = Math.min(minX, box.minX());
			minY = Math.min(minY, box.minY());
			minZ = Math.min(minZ, box.minZ());
			maxX = Math.max(maxX, box.maxX());
			maxY = Math.max(maxY, box.maxY());
			maxZ = Math.max(maxZ, box.maxZ());
		}

		BlockPos originKey = new BlockPos(minX, minY, minZ);
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);

		int sizeX = maxX - minX + 1;
		int sizeY = maxY - minY + 1;
		int sizeZ = maxZ - minZ + 1;
		BlockPos captureOrigin = new BlockPos(minX, minY, minZ);

		net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template =
				new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
		template.fillFromWorld(world, captureOrigin, new net.minecraft.core.Vec3i(sizeX, sizeY, sizeZ), true,
				java.util.List.of());

		OceanCanvasConfig config = OceanCanvasConfig.get();
		int centerX = minX + sizeX / 2;
		int centerZ = minZ + sizeZ / 2;
		// "One block beneath the new ocean floor" - the one real
		// difference from the shipwreck target formula (which pastes ON
		// the floor, not below it), per the user's own explicit request.
		int floorTopY = config.oceanFloorY() + floorOffset(centerX, centerZ, config.oceanFloorVariation());
		int targetY = floorTopY - 1;
		BlockPos pastePos = new BlockPos(minX, targetY, minZ);
		net.minecraft.world.level.levelgen.structure.BoundingBox targetBounds =
				net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
						pastePos, pastePos.offset(sizeX - 1, sizeY - 1, sizeZ - 1));

		// v237 migration: older Ocean Canvas builds moved the chest blocks but left
		// the real StructureStart piece at vanilla Y. If the persisted relocation
		// marker proves the physical copy already exists, repair only the metadata;
		// never repaste/reset loot just to correct the graph.
		if (protectedData.isRelocated(originKey)) {
			int dy=targetY-minY;
			if(dy!=0){for(var piece:pieces)piece.move(0,dy,0);markStructureMetadataUnsaved(world,start,targetBounds);}
			return targetBounds;
		}
		// A start already at the intended Y is integrated. This also prevents the
		// relocated start from being pasted again on the next discovery, now that its
		// piece metadata follows the physical chest.
		if(minY==targetY){if(!protectedData.isProtected(minX,minY,minZ))ProtectedRegions.protect(world,targetBounds);return null;}

		if (!allChunksReadyForRelocation(world, minX, minZ, maxX, maxZ)) return null;

		boolean placed = template.placeInWorld(
				world,
				pastePos,
				pastePos,
				new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
				world.getRandom(),
				net.minecraft.world.level.block.Block.UPDATE_ALL
		);

		if (!placed) {
			OceanCanvas.LOGGER.warn(
					"Failed to paste relocated buried treasure at {} (original at {} left untouched) - "
							+ "will retry on a later pass",
					pastePos, captureOrigin);
			return null;
		}

		net.minecraft.world.level.levelgen.structure.BoundingBox relocatedBounds = targetBounds;

		// Same entity-spill fix as the shipwreck path - see that clear
		// loop's own comment for the full mechanism (vanilla's chest
		// removal path spills contents as loose ItemEntitys unless the
		// block entity is silently removed first).
		for (BlockPos pos : BlockPos.betweenClosed(
				captureOrigin, captureOrigin.offset(sizeX - 1, sizeY - 1, sizeZ - 1))) {
			if (!relocatedBounds.isInside(pos)) {
				world.removeBlockEntity(pos);
				BlockState fill = (pos.getY() <= OceanCanvasConfig.WATER_SURFACE_Y)
						? Blocks.WATER.defaultBlockState()
						: Blocks.AIR.defaultBlockState();
				world.setBlock(pos, fill, 3);
			}
		}

		int treasureDeltaY=targetY-minY;
		for(var piece:pieces)piece.move(0,treasureDeltaY,0);
		markStructureMetadataUnsaved(world,start,relocatedBounds);
		protectedData.markRelocated(originKey);
		ProtectedRegions.protect(world, relocatedBounds);
		protectedData.clearPendingOriginalProtection(originKey);

		OceanCanvas.LOGGER.info("Relocated buried treasure from {} to {}", captureOrigin, pastePos);

		return relocatedBounds;
	}

	private static net.minecraft.world.level.levelgen.structure.BoundingBox relocateShipwreckIfNeeded(
			ServerLevel world, net.minecraft.world.level.levelgen.structure.StructureStart start) {
		java.util.List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = start.getPieces();
		if (pieces.isEmpty()) {
			return null;
		}

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (net.minecraft.world.level.levelgen.structure.StructurePiece piece : pieces) {
			net.minecraft.world.level.levelgen.structure.BoundingBox box = piece.getBoundingBox();
			minX = Math.min(minX, box.minX());
			minY = Math.min(minY, box.minY());
			minZ = Math.min(minZ, box.minZ());
			maxX = Math.max(maxX, box.maxX());
			maxY = Math.max(maxY, box.maxY());
			maxZ = Math.max(maxZ, box.maxZ());
		}

		BlockPos originKey = new BlockPos(minX, minY, minZ);
		OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);

		// Eliminate the risk, don't just hope it's small enough to
		// avoid: this structure's own chunk is confirmed ready (it was
		// found via getAllStarts on a chunk already checked in the 3x3
		// neighborhood), but its full bounding box could in principle
		// extend into additional chunks beyond that. Chunk loading
		// doesn't vary by Y-level, and the relocated copy pastes at the
		// SAME X/Z footprint (only the Y changes) - so verifying every
		// chunk touched by that X/Z span is genuinely ticking-ready is
		// a complete, positive guarantee against reintroducing the
		// exact kind of stall isPositionTicking already fixed
		// elsewhere, not an assumption that ships are "small enough".
		// If anything isn't ready yet, this simply isn't attempted this
		// pass - the origin is NOT reserved yet, so a later pass (once
		// more of the area has loaded) will try again.
		if (!allChunksReadyForRelocation(world, minX, minZ, maxX, maxZ)) {
			return null;
		}

		int sizeX = maxX - minX + 1;
		int sizeY = maxY - minY + 1;
		int sizeZ = maxZ - minZ + 1;
		BlockPos captureOrigin = new BlockPos(minX, minY, minZ);

		net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template =
				new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
		// fillFromWorld's last param is a List<Block> to ignore during
		// capture (NOT a nullable single Block, and not null-safe
		// internally - it unconditionally calls .stream() on it) - an
		// empty list means "don't ignore anything", which is what we
		// want here anyway.
		template.fillFromWorld(world, captureOrigin, new net.minecraft.core.Vec3i(sizeX, sizeY, sizeZ), true,
				java.util.List.of());

		OceanCanvasConfig config = OceanCanvasConfig.get();
		int centerX = minX + sizeX / 2;
		int centerZ = minZ + sizeZ / 2;
		int targetY = config.oceanFloorY() + floorOffset(centerX, centerZ, config.oceanFloorVariation());
		BlockPos pastePos = new BlockPos(minX, targetY, minZ);
		net.minecraft.world.level.levelgen.structure.BoundingBox targetBounds =
				net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
						pastePos, pastePos.offset(sizeX - 1, sizeY - 1, sizeZ - 1));

		// v237 metadata migration for pre-v237 relocated wrecks. Their block copy and
		// persistent protection are already correct; move the genuine StructureStart
		// pieces to that copy instead of repasting the template and touching loot.
		if(protectedData.isRelocated(originKey)){
			int dy=targetY-minY;
			if(dy!=0){for(var piece:pieces)piece.move(0,dy,0);markStructureMetadataUnsaved(world,start,targetBounds);}
			protectedData.clearPendingOriginalProtection(originKey);
			return targetBounds;
		}
		if (minY == targetY) {
			// v253.69.6: metadata-following relocation changed the StructureStart's
			// piece bounds to the new floor. A later discovery therefore used this NEW
			// min-corner as a fresh pending-original key, then this old early return left
			// that entry behind forever. Those immortal pending entries were exactly what
			// drove structureBarrierTicks upward and pinned the lighting queue at 1024.
			// A structure already at its deterministic target Y needs no future physical
			// mutation: make its current protection durable and clear any legacy/current
			// pending key before returning.
			if (!protectedData.isPermanentlyProtected(minX, minY, minZ)) ProtectedRegions.protect(world, targetBounds);
			protectedData.clearPendingOriginalProtection(originKey);
			return null;
		}

		// Risk eliminated, not just accepted as unlikely: this used to
		// clear the original footprint to air and mark the structure
		// relocated BEFORE attempting the new paste, with the paste's own
		// success never checked. If placeInWorld had ever failed (a
		// build-height clamp, a future API change silently returning
		// false, anything) the original would already be gone, the
		// "already relocated" flag would already be persisted, and the
		// new copy would never exist - a genuine, permanent, unrecoverable
		// way to lose a shipwreck and its loot with no path back, on top
		// of every bug already root-caused and fixed in this method.
		// Reordered so nothing destructive happens until the new copy is
		// confirmed placed: paste first (the original is completely
		// untouched up to this point, so a failure here just means "try
		// again next pass", identical to the allChunksReadyForRelocation
		// early-return above), and only clear the original / mark
		// relocated / persist protection after success is confirmed.
		boolean placed = template.placeInWorld(
				world,
				pastePos,
				pastePos,
				new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
				world.getRandom(),
				net.minecraft.world.level.block.Block.UPDATE_ALL
		);

		if (!placed) {
			OceanCanvas.LOGGER.warn(
					"Failed to paste relocated shipwreck copy at {} (original at {} left untouched) - "
							+ "will retry on a later pass instead of destroying the original",
					pastePos, captureOrigin);
			return null;
		}

		// The waterlogged/fence-connection fix (revalidatePlacedStructureEnvironment)
		// used to be called right here. Moved to the end of flattenChunk
		// instead - see this method's doc for exactly why calling it this
		// early never actually worked: the real ambient water this checks
		// for doesn't exist yet at this point in the discovery/relocation
		// pass, which runs before the main carve loop that actually
		// creates it.

		net.minecraft.world.level.levelgen.structure.BoundingBox relocatedBounds = targetBounds;

		// The new copy is confirmed placed - safe now to retire the
		// original. Skips any block that falls inside the NEW bounding
		// box too (belt-and-suspenders: the deep-floor/original-height gap
		// makes vertical overlap between the two copies very unlikely in
		// practice, but this guarantees the clear can never eat part of
		// the copy we just placed, regardless of how large that gap turns
		// out to be for a given shipwreck/config combination). Normal
		// setBlock (not the raw chunk write used elsewhere) is fine here
		// since this runs at most once per structure, not per-tick/
		// per-column, so it carries none of the performance risk that
		// made raw writes necessary in the main carve loop.
		//
		// Real bug fixed here, found from an actual "air gaps under the
		// water where the boat had been" report: this used to clear
		// unconditionally to AIR regardless of depth. A shipwreck is
		// underwater by definition, so that left literal air pockets
		// sitting under the water line at the original position -
		// physically wrong, and exactly what got reported. Below sea
		// level now fills with water instead, matching what the
		// surrounding ordinary carve produces everywhere else; only
		// clears to air above it (the rare case of a piece extending
		// above the waterline, e.g. a mast).
		// Real bug fixed here, found directly from the user's own
		// description of the actual behavior: "when the upper one gets
		// cleared, the entities from the chest float to the top" - this
		// setBlock call, on its own, was what caused that. fillFromWorld
		// above already captured the chest's real contents correctly (it
		// runs first, before any of this) - the bug was entirely in HOW
		// the original gets cleared afterward. Vanilla's own chest
		// removal path checks whether a block entity is still present at
		// a position when the block there changes to something else, and
		// if so, spills its container contents as loose ItemEntitys
		// before actually removing it - completely correct behavior for
		// an ordinary player breaking a chest, but exactly wrong here:
		// this chest isn't being "broken", it's being relocated, and its
		// contents already exist safely inside the newly-pasted copy.
		// world.removeBlockEntity first means that by the time setBlock
		// runs, there's no block entity left at this position for that
		// spill-check to find - nothing to spill, so nothing spills.
		// Safe to call unconditionally on every position in this loop
		// (not just chest positions): removing a block entity that was
		// never there in the first place is a normal, side-effect-free
		// no-op.
		BlockState originalFootprintFill;
		for (BlockPos pos : BlockPos.betweenClosed(
				captureOrigin, captureOrigin.offset(sizeX - 1, sizeY - 1, sizeZ - 1))) {
			if (!relocatedBounds.isInside(pos)) {
				world.removeBlockEntity(pos);
				originalFootprintFill = (pos.getY() <= OceanCanvasConfig.WATER_SURFACE_Y)
						? Blocks.WATER.defaultBlockState()
						: Blocks.AIR.defaultBlockState();
				world.setBlock(pos, originalFootprintFill, 3);
			}
		}

		// Persisted immediately - survives a restart from this point on.
		// The actual, real root cause behind every "chest destroyed
		// again" report on a natural shipwreck, finally identified: the
		// relocated copy is pasted the same way the guaranteed
		// shipwreck is - a raw NBT paste vanilla's own structure
		// manager never learns about. The guaranteed shipwreck handles
		// that by registering permanently in ProtectedRegions; this
		// method never did, and only returned the new bounding box to
		// whichever single flattenChunk call triggered the relocation.
		// On any LATER pass (a neighboring chunk reprocessing, the area
		// loading again - both routine, expected occurrences in this
		// architecture), the caller finds this structure already
		// relocated (OceanCanvasProtectedData already has it marked),
		// gets null back, and falls through to protecting the
		// structure's ORIGINAL (now-empty, pre-relocation) piece
		// bounding boxes from stale vanilla data instead - leaving the
		// actual relocated copy completely unprotected from that point
		// on, so the very next carve through that area goes straight
		// through the chest. Fixed by registering the relocated
		// location in ProtectedRegions too, the same persistent
		// mechanism the guaranteed shipwreck already relies on, instead
		// of relying on a one-time return value.
		int shipwreckDeltaY=targetY-minY;
		for(var piece:pieces)piece.move(0,shipwreckDeltaY,0);
		markStructureMetadataUnsaved(world,start,relocatedBounds);
		protectedData.markRelocated(originKey);
		ProtectedRegions.protect(world, relocatedBounds);
		// The permanent protection above now covers the real, relocated
		// copy - the interim one registered the instant this structure
		// was first discovered (see the discovery call site's comment,
		// and OceanCanvasProtectedData's "Rev 5" class doc note) can be
		// released. Not strictly load-bearing to call this (the original
		// footprint was already cleared to water/air just above, and
		// both of those ARE natural terrain material, so leaving the old
		// protection registered forever would be harmless) - done anyway
		// for clarity and to avoid this map growing unbounded over a
		// long-running world with many relocated wrecks.
		protectedData.clearPendingOriginalProtection(originKey);

		OceanCanvas.LOGGER.info("Relocated natural shipwreck from {} to {}", captureOrigin, pastePos);
        try{net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).addWorldEvent("STRUCTURE","shipwreck@"+originKey.getX()+"_"+originKey.getZ(),"RELOCATED","Natural shipwreck relocated",captureOrigin+" → "+pastePos,"system","structure:relocation",System.currentTimeMillis());}catch(RuntimeException ignored){}

		return relocatedBounds;
	}
}

