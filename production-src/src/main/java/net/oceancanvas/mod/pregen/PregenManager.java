package net.oceancanvas.mod.pregen;

import net.oceancanvas.mod.worldgen.OceanCanvasPrimitiveLongSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;
import net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime;
import net.oceancanvas.mod.operation.OceanCanvasActionLog;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;

import java.util.UUID;

/**
 * The "Chunky-like" future-work feature (see {@code docs/roadmap.md}'s
 * M0 log - "a Chunky-like pre-generation feature for the canvas, so
 * players can pre-flatten a region instead of seeing the live 'land
 * converts as you approach' transition while exploring"). Drafted per
 * the user's explicit request this round to get the bulk future-work
 * code written now, without turning it on yet - see
 * {@link OceanCanvasConfig#pregenEnabled()}, off by default.
 *
 * <p><b>Deliberately NOT a new flattening code path.</b> This only does
 * two things real Chunky-style pre-generation actually needs on top of
 * what already exists: (1) proactively force-load chunks nobody is
 * standing near, at a deliberately bounded, configurable rate, and (2)
 * feed each one into the exact same, already-proven flattening queue
 * {@link OceanCanvasSurfaceFlattener} already uses for ordinary
 * chunk-load-triggered flattening - see
 * {@link OceanCanvasSurfaceFlattener#enqueueForPregen}. Every actual
 * carving/structure-protection/vegetation/relocation rule already fixed
 * and tested there applies automatically; nothing about that logic is
 * duplicated or reimplemented here.</p>
 *
 * <p><b>Risk this is designed around, not just aware of:</b> the project
 * already has a real, confirmed incident where unbounded chunk
 * force-loading overloaded the server (~900 ticks behind - see
 * {@code OceanCanvasSurfaceFlattener#neighborsReady}'s doc for the full
 * story of the reactive force-load cascade that caused it and was
 * reverted). Pregeneration is, by its very nature, the one feature whose
 * entire job is to force-load chunks nobody is near - exactly the
 * situation that already went wrong once. This is addressed two ways:
 * only one job can run at a time (a second {@code start} request is
 * rejected, not queued or stacked), and new force-load *requests* are
 * capped to {@link OceanCanvasConfig#pregenChunksPerTick()} per tick (a
 * genuine rate limit on new work started, not just a queue depth limit) -
 * chunks already force-loading are left to resolve at their own pace
 * rather than being force-loaded again. If {@code pregenEnabled} is
 * turned off in config while a job is running, the job stops cleanly on
 * the next tick rather than continuing to force-load chunks against the
 * user's own updated wishes.</p>
 *
 * <p><b>"Async/multi-threaded pregen" - investigated per the user's
 * explicit request, and DECLINED as a separate feature, not because it
 * wasn't looked into, but because the actual finding argues against
 * building one.</b> Two things had to be checked separately:</p>
 * <ul>
 *   <li>Is the expensive part - actual terrain/noise generation for a
 *       not-yet-loaded chunk - already off the main thread? Yes: {@link
 *       Job#tick} requests a not-yet-resident chunk via {@code
 *       world.getChunkSource().getChunkFuture(...)} (the same fire-and-
 *       forget async API {@code OceanCanvasSurfaceFlattener#neighborsReady}
 *       already uses successfully), which is vanilla's own genuinely
 *       asynchronous chunk-generation pipeline - Minecraft dispatches the
 *       real CPU-heavy work (noise sampling, feature placement, etc.) to
 *       its own internal worker thread pool, not the main server thread.
 *       This job loop was already benefiting from real multithreading for
 *       the expensive part, before this was even investigated, without
 *       ever needing to spin up a thread of its own.</li>
 *   <li>Could the REMAINING single-threaded part - enqueuing an
 *       already-resident chunk into the flattening queue, and the actual
 *       block writes {@link
 *       net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener#flattenChunk}
 *       performs - safely move off the main thread too? No, and not as a
 *       tuning problem: {@code ChunkAccess#setBlockState} and the rest of
 *       Minecraft's world-mutation API are fundamentally not
 *       thread-safe - they assume single-threaded access from the server's
 *       main tick thread, the same architectural assumption behind
 *       essentially every mixin/event hook in this project. Mutating world
 *       state from a second thread risks data races and corruption with no
 *       reliable warning sign (unlike a hang or a crash, a race like this
 *       can silently corrupt saved data) - a categorically worse failure
 *       mode than the mere performance cost of staying single-threaded
 *       here, and exactly the kind of real risk this project's own
 *       standing principle ("eliminate real risk when found, don't accept
 *       it as probably small enough") says not to accept.</li>
 * </ul>
 * <p>Conclusion: there is no safe, real multithreading left to add - the
 * expensive part already has it (via vanilla's own worker pool), and the
 * part that doesn't have it can't safely get it. The one real, already-
 * existing lever for "more throughput" is {@link
 * OceanCanvasConfig#pregenChunksPerTick} (raise it to issue more concurrent
 * async generation requests per tick) - already documented, already
 * configurable, nothing new needed. This mirrors the Chunky-API interop
 * finding elsewhere in this project's history: investigated for real,
 * then honestly declined with the reasoning kept here rather than either
 * silently skipping the ask or building something that only looks like
 * the requested feature.</p>
 */
public final class PregenManager {

	/**
	 * Hard cap on requested radius, independent of {@code pregenEnabled}.
	 * Purely a sanity limit against an accidental or mistyped huge
	 * request (e.g. blocks instead of chunks) - 200 chunks is 3,200
	 * blocks in every direction from the center, 400x400 chunks
	 * (160,000 chunks) total, already a very large single job at the
	 * default throttle.
	 */
	/**
	 * Command radii are blocks, not chunks. The actual request is always
	 * clipped to the configured canvas, so this is only an integer/typo
	 * backstop rather than an artificial gameplay cap.
	 */
	private static final int MAX_RADIUS_BLOCKS = 30_000_000;

	/**
	 * Above this many chunks, {@code start} refuses to actually run unless
	 * explicitly confirmed - a second, independent safety net on top of
	 * the permission-level-2 gate already on the command itself. 2,500
	 * chunks is a 50x50-chunk area (800x800 blocks) - large enough that a
	 * genuinely intentional big job isn't blocked by a nuisance
	 * confirmation, small enough that a mis-typed radius (blocks instead
	 * of chunks, an extra digit) gets caught before it does real work.
	 */
	private static final long CONFIRM_THRESHOLD_CHUNKS = 2_500;

	// How often (in ticks) to report progress back to the requester (if
	// still online) and the console log, independent of job size. ~10s
	// at 20 TPS - frequent enough to feel alive on a long job, cheap
	// enough not to matter.
	private static final int PROGRESS_REPORT_INTERVAL_TICKS = 200;

	/**
	 * Hard backpressure: never let pregeneration build an unbounded queue of
	 * generated-but-not-yet-flattened chunks. This is intentionally small
	 * because the 4 GB OOM playtest showed that submission rate, not total
	 * requested area, was the dangerous quantity.
	 */
	private static final int PREGEN_TARGET_OUTSTANDING = 224;
	private static final int PREGEN_HARD_QUEUE_LIMIT = 448;

	/**
	 * v120 two-phase pregen. Phase 1 (raw generation) has none of Phase 2's
	 * cost - no carving, no neighbor context, no self-tickets - so it is not
	 * bound by {@code pregenChunksPerTick} (that budget is calibrated for
	 * Phase 2's much more expensive carve pass). Instead it keeps up to this
	 * many FULL chunk futures outstanding at once, the same "just ask for a
	 * lot and let the executor pool bound real throughput" shape Chunky
	 * itself uses - the real comparison run showed this exact hardware/mod
	 * stack sustaining 30-70+ chunks/s that way. Larger than Phase 2's
	 * PREGEN_TARGET_OUTSTANDING (224) since these are cheap "just generate"
	 * requests, not fully-tracked carve targets; picked as a starting point,
	 * not yet tuned against a real long run.
	 */
	private static final int PREGEN_GENERATE_MAX_OUTSTANDING = 256;
	private static final int PREGEN_ADAPTIVE_MAX_CHUNKS_PER_TICK = 64;
	private static final int PREGEN_ADAPT_INTERVAL_TICKS = 20; // ~1 second at 20 TPS
	private static final double PREGEN_HEAP_GROWTH_THRESHOLD = 0.70D;
	private static final double PREGEN_HEAP_PAUSE_FRACTION = 0.82D;
	// v95 20k-scale liveness watchdog. Backpressure is allowed to stop NEW
	// submissions, but it must never stop recovery of the requests already
	// occupying the outstanding window. Five seconds without an authoritative
	// retirement is long enough to distinguish a real C2ME tail from normal
	// generation variance; recovery itself is throttled to once per second.
	private static final long PREGEN_STALL_WATCHDOG_NS = 5_000_000_000L;
	private static final long PREGEN_STALL_RECOVERY_INTERVAL_NS = 1_000_000_000L;
	private static final long PREGEN_STALL_LOG_INTERVAL_NS = 60_000_000_000L;
	// v132.6: the v132.5 diagnostics build showed the ticket-health snapshot
	// going completely static (zero installs, zero releases, zero futures
	// change) for several consecutive 1s samples during the worst stalls,
	// while the neighbor-stall breaker sat fully engaged. That is the
	// signature of the underlying C2ME/vanilla async chunk executor itself
	// being saturated, not merely slow - and every recovery path this class
	// has (final-drain tickets, processing leases, support-load re-requests)
	// works by asking that SAME executor for MORE chunks. Once it is
	// genuinely saturated, doing that repeatedly cannot help and can only
	// add further queued demand behind whatever is already stuck, which is
	// exactly the kind of "self-referential" feedback this project's own
	// history keeps re-discovering under different names. Once a stall has
	// run for meaningfully longer than the watchdog's own kick-in threshold
	// AND the neighbor-stall breaker independently agrees the backlog is
	// congested, hold off on any BRAND NEW forced-ticket/re-request demand
	// for one nudge call. Already-installed mandatory tickets are still
	// refreshed - this never drops or fails a target - it only stops piling
	// more NEW demand onto a pipe that has already shown zero throughput for
	// several full seconds.
	private static final long PREGEN_HARD_FREEZE_HOLD_NS = 10_000_000_000L;
	// v96 scale-efficiency: periodically let the chunk system fully drain before
	// continuing a huge scan. This bounds long-lived holder residency and makes
	// Save & Quit proportional to the active window instead of the entire run.
	private static final long PREGEN_MAINTENANCE_INTERVAL_CHUNKS = 2048L;
	private static final int PREGEN_MAINTENANCE_STABLE_TICKS = 20;
	private static final long PREGEN_MAINTENANCE_MAX_DRAIN_NS = 8_000_000_000L; // retained for persisted v96/v97 state compatibility
	// v98: the v97 nonblocking escape only applied while a maintenance drain was
	// active. Runtime logs showed the ordinary adaptive window could still fill
	// to 96 targets (mostly C2ME FULL loads) and then sit indefinitely. Treat a
	// sustained full-window stall as the same recoverable condition.
	private static final long PREGEN_LIVE_STALL_DEFER_NS = 10_000_000_000L;
	private static final int PREGEN_LIVE_STALL_DEFER_BATCH = 2;
	private static final long PREGEN_DEFERRED_RETRY_NS = 2_000_000_000L;
	// v101: resumed jobs still get a short cadence grace period, but PAUSE is now
	// literal: adaptive backpressure never submits a "probe" chunk behind the user's
	// back. Stalled live targets are withdrawn into the mandatory deferred lane
	// instead of being force-loaded every few seconds.
	private static final long PREGEN_RESUME_BOOTSTRAP_NS = 5_000_000_000L;
	private static final int PREGEN_MIN_DYNAMIC_OUTSTANDING = 16;
	// v106: the v103/v104 recovery-lease mechanism (16 concurrent leases) was
	// sized for the small stall cohorts ("~40 old requests") those rounds were
	// fixing. A real 20k run showed the outstanding window itself can end up
	// 83% neighbor-stalled (185/224) - a scale the lease mechanism was never
	// meant to bulk-unstick, and simply raising its cap would multiply forced
	// full-chunk-load concurrency ~10x on a server that was already falling
	// behind. The actual defect is upstream: admission kept feeding new targets
	// toward the outstanding ceiling by COUNT alone, with no regard for whether
	// the targets already admitted were spatially resolving - so newly admitted
	// targets frequently ended up neighboring OTHER not-yet-loaded targets
	// instead of settled terrain, and the self-inflicted pile-up snowballed.
	// This breaker pauses NEW admission (existing outstanding work and the
	// lease safety net keep running) once too much of the live queue is
	// neighbor-stalled, and only resumes once that fraction has meaningfully
	// recovered. Hysteresis (pause at 60%, resume at 35%) is deliberate - this
	// project has hit real pause/resume oscillation bugs twice before (v98/
	// v101/v102) from thresholds that could flap right at one boundary.
	private static final long PREGEN_NEIGHBOR_STALL_SAMPLE_INTERVAL_NS = 1_000_000_000L;
	private static final int PREGEN_NEIGHBOR_STALL_MIN_QUEUED = 4;
	private static final double PREGEN_NEIGHBOR_STALL_PAUSE_RATIO = 0.60D;
	private static final double PREGEN_NEIGHBOR_STALL_RESUME_RATIO = 0.35D;
	// v202.43: the 2026-08-31 v202.42 run proved recovery itself works, but
	// release could immediately refill 25 -> 64 -> 124 -> 204 outstanding while
	// completion stayed ~0-1 chunks/s. Keep a short post-breaker recovery ramp
	// and treat a large unresolved target-load backlog as pressure.
	private static final long PREGEN_RECOVERY_RAMP_NS = 20_000_000_000L;
	private static final int PREGEN_RECOVERY_RAMP_START_RATE = 1;
	private static final int PREGEN_LOADING_BACKLOG_MIN = 8;
	private static final double PREGEN_LOADING_BACKLOG_FRACTION = 0.33D;
	// v117: log-only mirror of OceanCanvasSurfaceFlattener.PREGEN_NEIGHBOR_STALE_MS -
	// keep the two in sync if that threshold ever changes.
	private static final long PREGEN_NEIGHBOR_STALE_LOG_MS = 3000L;

	// v212 proactive health protocol. Runtime tests are expensive, so admission
	// must react to a deteriorating trajectory BEFORE the 60% breaker or 5s
	// liveness watchdog is reached. These thresholds are deliberately softer
	// than the hard breaker and require consecutive one-second samples to avoid
	// treating normal C2ME latency as a fault.
	private static final double PREGEN_PROACTIVE_STALE_RATIO = 0.25D;
	private static final double PREGEN_PROACTIVE_RESUME_RATIO = 0.15D;
	private static final int PREGEN_PROACTIVE_MIN_QUEUED = 8;
	private static final int PREGEN_PROACTIVE_RISING_SAMPLES = 2;
	private static final int PREGEN_PROACTIVE_NO_RETIRE_SAMPLES = 2;
	private static final int PREGEN_FLIGHT_RECORDER_SAMPLES = 12;
	private static final double PREGEN_EMERGENCY_CADENCE_PAUSE_MS = 100.0D;
	// v253.125.33: do not immediately refill after a severe heap/tick spike. The
	// .31 soak showed repeated 8-33s external stalls followed by an apparently
	// healthy single sample; without a cooldown the inlet could refill before GC,
	// JourneyMap, or advancement work had actually recovered.
	private static final long PREGEN_RUNTIME_PRESSURE_HARD_COOLDOWN_NS = 2_000_000_000L;
	private static final long PREGEN_RUNTIME_PRESSURE_CRITICAL_COOLDOWN_NS = 5_000_000_000L;
	private static final long PREGEN_FLIGHT_DUMP_COOLDOWN_NS = 60_000_000_000L;
	private static final int PREGEN_PROACTIVE_TICKET_REPAIR_BUDGET = 32;
		private static final long PREGEN_PROACTIVE_LOAD_STALL_MS = 5000L;
	// v230.5: refill at most the same two globally bounded FULL-demand slots per
	// controller tick. This is a dispatch budget, not additional concurrency.
	private static final int PREGEN_FULL_DEMAND_REFILL_BUDGET = 2;
	// v214: never let a fresh/unproven pipeline fill the historical 224-320 target
	// window merely because heap/cadence look quiet. Start with a small spatial
	// window, then EARN a deeper queue from measured retirements. Four seconds of
	// proven throughput gives C2ME enough latency hiding without allowing minutes
	// of dead work to accumulate behind a false-positive "healthy" inlet.
	// v216 startup canary: a fresh/resumed carve pipeline must prove a few
	// end-to-end physical retirements before it can expose the larger v214
	// proof-of-capacity window. Eight live targets make a broken readiness
	// contract cheap to diagnose instead of allowing a 32-320 target cohort.
	private static final int PREGEN_STARTUP_CANARY_OUTSTANDING = 8;
	private static final int PREGEN_STARTUP_CANARY_RETIREMENTS = 4;
	private static final int PREGEN_UNPROVEN_OUTSTANDING = 16;
	// v253.71: v253.70.0 ran cleanly with no stale-load debt while repeatedly
	// pinning against the old 64-target ceiling and retiring only ~20 chunks/s.
	// That ceiling contradicted the proof-of-capacity controller directly below
	// (36 chunks/s * 4s + 8 headroom = 152). Raise only this earned-capacity
	// ceiling; the 5s/10s stale-load clamps still collapse it to 24/16 immediately
	// if C2ME develops a poisoned cold tail.
	private static final int PREGEN_CARVE_PIPELINE_HARD_OUTSTANDING = 160;
	// v253.71: keep four seconds of latency-hiding proof because v253.70.0's healthy
	// cold loads routinely completed in ~0.2-2s and the job needs enough earned depth
	// to sustain the 20k overnight target. Poisoned tails are handled independently
	// by the 5s/10s age clamps below, which immediately collapse depth to 24/16.
	private static final double PREGEN_PROVEN_LATENCY_SECONDS = 4.0D;
	private static final int PREGEN_PROVEN_OUTSTANDING_HEADROOM = 8;
	private static final long PREGEN_LOAD_DEPTH_SOFT_CLAMP_MS = 5_000L;
	private static final long PREGEN_LOAD_DEPTH_HARD_CLAMP_MS = 10_000L;
	private static final int PREGEN_LOAD_DEPTH_SOFT_OUTSTANDING = 24;
	private static final int PREGEN_LOAD_DEPTH_HARD_OUTSTANDING = 16;
	private static final long PREGEN_LOAD_TAIL_DEFER_MIN_AGE_MS = 20_000L;
	private static final long PREGEN_LOAD_TAIL_DEFER_ESCALATED_AGE_MS = 60_000L;
	private static final long PREGEN_LOAD_TAIL_DEFER_CADENCE_NS = 5_000_000_000L;
	private static final double PREGEN_RISK_SOFT_RATIO = 0.10D;
	private static final double PREGEN_RISK_HARD_RATIO = 0.20D;


    private record AdaptiveProfileParams(int targetOutstanding,int hardQueue,int maxRate,double growTickMs,double backoffTickMs,double pauseTickMs) {}
	private static AdaptiveProfileParams adaptiveParams(ServerLevel world,int configuredStartRate){
        var profile=net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).pregenProfile();
        return switch(profile){
            case QUIET -> new AdaptiveProfileParams(64,160,8,45.0D,55.0D,65.0D);
            // v253.69.2: a healthy 20 TPS integrated server naturally samples near
            // 50ms/tick. The old 48ms growth threshold therefore made Balanced,
            // Custom and Overnight treat normal cadence as "not healthy enough to
            // grow", trapping long runs at 1 chunk/tick despite low heap and clean
            // queues. Preserve the proven 58/68ms backoff/pause guardrails; only
            // move the positive-growth deadband above normal 20-TPS cadence.
            case OVERNIGHT -> new AdaptiveProfileParams(320,512,64,57.0D,63.0D,72.0D);
            case CUSTOM -> {
                int max=Math.max(1,Math.min(64,configuredStartRate));
                yield new AdaptiveProfileParams(Math.max(32,Math.min(320,max*8)),Math.max(96,Math.min(512,max*16)),max,55.0D,62.0D,70.0D);
            }
            default -> new AdaptiveProfileParams(PREGEN_TARGET_OUTSTANDING,PREGEN_HARD_QUEUE_LIMIT,PREGEN_ADAPTIVE_MAX_CHUNKS_PER_TICK,55.0D,62.0D,70.0D);
        };
    }

	private static boolean heapPressureHigh() {
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory();
		if (max <= 0L) return false;
		long used = rt.totalMemory() - rt.freeMemory();
		return (double) used / (double) max >= PREGEN_HEAP_PAUSE_FRACTION;
	}

    private static OceanCanvasPregenMetrics.Calibration calibration(ServerLevel world){
        var b=net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).benchmark();
        if(b==null||b.calibrationVersion()!=OceanCanvasPregenMetrics.CALIBRATION_VERSION)return null;
        return new OceanCanvasPregenMetrics.Calibration(b.sustainableChunksPerSecond(),b.healthyTickMs(),b.preferredOutstanding(),
                b.successfulRuns(),b.samples(),b.observedProfile());
    }


	private static final class RuntimeState { Job activeJob; }

	private static RuntimeState state(MinecraftServer server) {
		return OceanCanvasServerRuntime.get(server).state(RuntimeState.class, RuntimeState::new);
	}

	private static RuntimeState existingState() {
		OceanCanvasServerRuntime runtime = OceanCanvasServerRuntime.onlyActiveOrNull();
		return runtime == null ? null : runtime.stateIfPresent(RuntimeState.class);
	}

	private static Job currentJob() {
		RuntimeState state = existingState();
		return state == null ? null : state.activeJob;
	}

	private static void setCurrentJob(Job job) {
		if (job != null) {
			state(job.world.getServer()).activeJob = job;
			return;
		}
		RuntimeState state = existingState();
		if (state != null) state.activeJob = null;
	}

	private PregenManager() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(PregenManager::onServerTick);
		// Job-persistence resume hook - see OceanCanvasJobState's class doc
		// for the full "only resumes across an actual restart, never a
		// mere pause" design. ServerLevelEvents.LOAD fires once per level
		// as it loads during server startup, which is exactly what's
		// needed here: OceanCanvasJobState is per-level SavedData (like
		// OceanCanvasProtectedData/OceanCanvasPlayerZones), so checking at
		// level-load time (rather than the single server-wide
		// SERVER_STARTED event OceanCanvas#onInitialize already uses for
		// the simulation-distance fix) naturally finds a persisted
		// snapshot in whichever specific level it was actually saved to,
		// with zero cross-dimension ambiguity. Same "not yet confirmed
		// against your exact 26.2 build" caveat as every other not-yet-
		// on-device-tested API call in this project.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD.register(PregenManager::onLevelLoad);
	}

	private static synchronized void onLevelLoad(MinecraftServer server, ServerLevel level) {
		if (currentJob() != null) {
			// Already resumed a job into a different level this same
			// startup (shouldn't normally happen - only one job can ever
			// be active - but this keeps a second LOAD event from ever
			// clobbering an already-resumed job).
			return;
		}
		OceanCanvasJobState.Snapshot snapshot = OceanCanvasJobState.get(level).get();
		if (snapshot == null) {
			OceanCanvasJobScopeState.get(level).clear();
			OceanCanvasPregenRecoveryJournalData.get(level).clear();
			OceanCanvasPregenSessionMarker.disarm(level);
			return;
		}
		String compatibilityBlock = net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(level);
		if (!compatibilityBlock.isEmpty()) {
			OceanCanvas.LOGGER.error("(Ocean Canvas) Holding persisted {} checkpoint without resuming: {}", snapshot.kind(), compatibilityBlock);
			return;
		}
        if (OceanCanvasTerrainOperationActivity.restoreRunning()) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) Pregen checkpoint exists but Restore to Vanilla resumed first; Pregen checkpoint is held for conservative recovery.");
            return;
        }
        if(!snapshot.queueEntryId().isBlank()){
            OceanCanvas.LOGGER.info("Queued Pregen recovery held for explicit Run queue: {}",snapshot.queueEntryId());
            return;
        }
		if (!OceanCanvasConfig.get().pregenEnabled()) {
			// Left in place rather than cleared - see OceanCanvasJobState's
			// class doc. It'll be picked up on a future restart once
			// pregenEnabled is true again, or explicitly abandoned via
			// /oceancanvas pregen cancel.
			OceanCanvas.LOGGER.info(
					"(Ocean Canvas) Found a persisted {} job from before the last restart, but pregenEnabled is "
							+ "off - not resuming it. It'll resume automatically on a future restart once "
							+ "pregenEnabled is true again, or run \"/oceancanvas pregen cancel\" to abandon it.",
					snapshot.kind());
			return;
		}
		// v252.6 migration guard: v252.4 and older region-pregen builds could
		// persist the destination region as protected BEFORE its chunks had been
		// physically flattened. If such a checkpoint is resumed without repairing
		// that stale flag, the flattener and the physical audit both honor the
		// region as protected and the old bug reproduces forever. Only release
		// protection for the exact region-pregen mask owned by this persisted job;
		// unrelated/overlapping protected regions remain untouched.
		releasePrematureRegionPregenProtectionOnResume(level, snapshot);
		boolean durableCrashMarker = isPregenKind(snapshot.kind()) && OceanCanvasPregenSessionMarker.isArmed(level);
		setCurrentJob(Job.resume(level, snapshot, durableCrashMarker));
		// Arm the NEW process synchronously before it is allowed to tick. This makes
		// an immediate JVM/power loss detectable even if Minecraft has not autosaved
		// the cleanStop=false SavedData checkpoint yet.
		if (isPregenKind(currentJob().kind())) currentJob().persist(false);
		String message = "Resumed " + snapshot.kind() + " job after restart: " + currentJob().describeProgress() + ".";
		OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
		OceanCanvasActionLog.recordLifecycle(level, snapshot.kind(), "RESUMED", currentJob().requesterPlayer(),
				currentJob().describeProgress());
	}


	/**
	 * Repairs the exact stale-protection state produced by the pre-v252.5
	 * region-pregen ordering bug when an in-flight job survives a restart.
	 *
	 * <p>This is intentionally narrow: it runs only for a persisted
	 * {@code region-pregen}, requires a non-empty explicit chunk mask, and only
	 * unprotects a zone whose canvas-clipped exact mask equals that checkpoint's
	 * mask. It therefore cannot unprotect a merely-overlapping build/protected
	 * region. Normal successful completion will protect the matched region again
	 * after the physical completion gate.</p>
	 */
	private static void releasePrematureRegionPregenProtectionOnResume(
			ServerLevel level, OceanCanvasJobState.Snapshot snapshot) {
		if (!"region-pregen".equals(snapshot.kind())) return;

		var zones = net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(level);
		OceanCanvasJobScopeState.Scope persistedScope = OceanCanvasJobScopeState.get(level).get();
		if (persistedScope != null && persistedScope.sameOperationScope(snapshot)
				&& !persistedScope.scopeName().isBlank()) {
			var zone = zones.zoneByName(persistedScope.scopeName());
			if (zone == null || !zone.protectedNow()) return;
			String error = zones.setEnabled(zone.name(), false, null, true);
			if (error == null) {
				OceanCanvas.LOGGER.warn(
						"(Ocean Canvas) Released premature protection on region '{}' while resuming its named region-pregen checkpoint; protection will be restored only after physical completion.",
						zone.name());
			} else {
				OceanCanvas.LOGGER.error("(Ocean Canvas) Could not release stale pregen protection on region '{}': {}", zone.name(), error);
			}
			return;
		}

		// Backward compatibility for pre-v253.71.1 checkpoints that persisted the
		// full Region mask but had no named scope companion record.
		if (snapshot.explicitChunks().isEmpty()) return;
		java.util.LinkedHashSet<Long> jobMask = new java.util.LinkedHashSet<>(snapshot.explicitChunks());
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int canvasMinChunkX = Math.floorDiv(config.centerX() - config.radius(), 16);
		int canvasMaxChunkX = Math.floorDiv(config.centerX() + config.radius() - 1, 16);
		int canvasMinChunkZ = Math.floorDiv(config.centerZ() - config.radius(), 16);
		int canvasMaxChunkZ = Math.floorDiv(config.centerZ() + config.radius() - 1, 16);
		for (var zone : zones.all()) {
			if (!zone.protectedNow()) continue;
			java.util.LinkedHashSet<Long> canvasPortion = new java.util.LinkedHashSet<>(zone.exactChunks());
			canvasPortion.removeIf(packed -> {
				int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
				return cx < canvasMinChunkX || cx > canvasMaxChunkX
						|| cz < canvasMinChunkZ || cz > canvasMaxChunkZ;
			});
			if (!canvasPortion.equals(jobMask)) continue;
			String error = zones.setEnabled(zone.name(), false, null, true);
			if (error == null) OceanCanvas.LOGGER.warn(
					"(Ocean Canvas) Released premature protection on legacy region '{}' while resuming its persisted region-pregen checkpoint.", zone.name());
			else OceanCanvas.LOGGER.error("(Ocean Canvas) Could not release stale pregen protection on legacy region '{}': {}", zone.name(), error);
			return;
		}
	}

	public static synchronized boolean isRunning() {
		return currentJob() != null;
	}

	/**
	 * v253.125.34: true while a Pregen job still has never-submitted terrain ahead
	 * of its authoritative row-major cursor. Lighting recovery uses this read-only
	 * signal to keep historical LIGHT_ONLY residency from competing with the terrain
	 * frontier. It does not change target order or job completion semantics.
	 */
	public static synchronized boolean activePregenTerrainAdmissionOpen() {
		Job job = currentJob();
		return job != null && isPregenKind(job.kind) && job.nextIndex < job.totalChunks;
	}

	/**
	 * Returns a user-facing reason a Region definition must not be mutated while a
	 * Region-scoped terrain job owns its exact chunk mask. Pregen deliberately
	 * snapshots that mask before doing any physical work and promotes the matching
	 * draft to protected only after every terrain/structure/lighting completion gate
	 * has passed. Reshaping, deleting, renaming or manually protecting the Region in
	 * the middle of that job would sever that identity -> exact-mask contract.
	 */
	public static synchronized String regionMutationBlockReason(String regionName) {
		if (currentJob() == null || regionName == null || regionName.isBlank()) return "";
		if (currentJob().scopeName == null || !currentJob().scopeName.equalsIgnoreCase(regionName)) return "";
		if (!("region-pregen".equals(currentJob().kind()) || "region-rewipe".equals(currentJob().kind())
				|| "region-reset".equals(currentJob().kind()))) return "";
		String label = "region-pregen".equals(currentJob().kind()) ? "Pregen" : "Rewipe";
		return "Region '" + regionName + "' is locked while its " + label
				+ " is running. Its geometry, name and protection state stay fixed until the exact job mask completes.";
	}

	/**
	 * Starts a new pregen job centered on the given block position with
	 * the given radius in chunks, clipped to both the configured canvas
	 * and {@link #MAX_RADIUS_BLOCKS}. Returns a human-readable status
	 * message suitable for sending straight back to whoever ran the
	 * command - this method never throws for an ordinary bad input (out
	 * of range, already running, etc.), it just returns a message
	 * explaining why nothing started.
	 *
	 * <p>{@code confirmed} must be {@code true} to actually start a job
	 * above {@link #CONFIRM_THRESHOLD_CHUNKS} - see that field's doc
	 * comment. Below the threshold, {@code confirmed} is ignored (no
	 * pointless "are you sure" for a small job).</p>
	 */
	public static synchronized String start(ServerLevel world, int centerBlockX, int centerBlockZ,
			int radiusBlocks, boolean confirmed, ServerPlayer requestedBy) {
		String compatibilityBlock = net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
		if (!compatibilityBlock.isEmpty()) return compatibilityBlock;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.pregenEnabled()) {
			return "Pregeneration is disabled - this is a drafted, not-yet-enabled feature. "
					+ "Set pregenEnabled=true in config/oceancanvas.properties (or the in-game config "
					+ "screen, if installed) once you're ready to test it.";
		}
		if (currentJob() != null) {
			return "A pregen job is already running (" + currentJob().describeProgress() + "). "
					+ "Use /oceancanvas pregen cancel first if you want to start a different one.";
		}
		if (OceanCanvasUndoManager.isRunning()) {
			return "An /oceancanvas undo is currently running - wait for it to finish before starting a pregen "
					+ "job, so the two don't modify the world at the same time.";
		}

		Region region = clipRegion(config, centerBlockX, centerBlockZ, radiusBlocks);
		if (region.error != null) {
			return region.error;
		}
		String foreverBlock = net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockRect(world,"PREGEN",region.minChunkX,region.maxChunkX,region.minChunkZ,region.maxChunkZ,"","","");
		if (!foreverBlock.isEmpty()) return foreverBlock;

		if (region.totalChunks > CONFIRM_THRESHOLD_CHUNKS && !confirmed) {
			return "That's " + region.totalChunks + " chunks - above the " + CONFIRM_THRESHOLD_CHUNKS
					+ "-chunk confirmation threshold. Center: (" + centerBlockX + ", " + centerBlockZ + "). "
					+ "If this is really what you want, re-run as "
					+ "\"/oceancanvas pregen start " + radiusBlocks + " " + centerBlockX + " " + centerBlockZ + " confirm\". "
					+ "Want to see the number first without committing? Use "
					+ "\"/oceancanvas pregen start " + radiusBlocks + " " + centerBlockX + " " + centerBlockZ + " dryrun\"."
					+ pendingStructureRulesClause();
		}

		setCurrentJob(new Job(world, region.minChunkX, region.maxChunkX, region.minChunkZ, region.maxChunkZ,
				requestedBy == null ? null : requestedBy.getUUID(), "pregen",
				region.minBlockX, region.maxBlockX, region.minBlockZ, region.maxBlockZ));
		net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureRect(world,"PREGEN","",region.minChunkX,region.minChunkZ,region.maxChunkX,region.maxChunkZ);
		currentJob().structureRules = snapshotPendingStructureRules();
		currentJob().persist();

		// Recent-destructive-actions log (backs /oceancanvas admin) -
		// pregen only ever touches untouched terrain (never a player
		// build), but it's still real, force-loading server-impacting
		// work worth showing on the dashboard alongside reset/expand -
		// see OceanCanvasActionLog's class doc. No backup hook here
		// unlike reset/expand - see that field's doc comment: pregen
		// can't destroy anything a player built, so it doesn't carry the
		// same "oops" risk a backup exists to protect against.
		OceanCanvasActionLog.record(world, "pregen", requestedBy, currentJob().totalChunks() + " chunk(s) centered at ("
				+ centerBlockX + ", " + centerBlockZ + ")");

		String message = "Started pregen centered at (" + centerBlockX + ", " + centerBlockZ + "): "
				+ currentJob().totalChunks() + " chunks for up to "
				+ (region.clampedRadiusBlocks * 2L) + " x " + (region.clampedRadiusBlocks * 2L) + " blocks ("
				+ (region.clampedRadiusBlocks != radiusBlocks ? "radius clamped to " + region.clampedRadiusBlocks + " blocks, " : "")
				+ "adaptive pregen starting at " + config.pregenChunksPerTick()
				+ " chunks/tick; auto-tunes up to " + PREGEN_ADAPTIVE_MAX_CHUNKS_PER_TICK + ")."
				+ (currentJob().structureRules.isEmpty() ? "" : " Applying staged structure rules: "
						+ describeStructureRuleMap(currentJob().structureRules) + ".");
		OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
		String acceptanceWorldUuid = net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardshipData.get(world).identity().worldUuid();
		OceanCanvas.LOGGER.info("(Ocean Canvas) PREGEN-ACCEPTANCE-START build={} worldUuid={} chunks={} widthBlocks={} centerX={} centerZ={}",
				net.oceancanvas.mod.OceanCanvas.VERSION, acceptanceWorldUuid, currentJob().totalChunks(),
				(region.clampedRadiusBlocks * 2L), centerBlockX, centerBlockZ);
		return message;
	}

	private static String archivedRegionIntersecting(ServerLevel world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		var project = net.oceancanvas.mod.project.OceanCanvasProjectData.get(world);
		for (var zone : net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).all()) {
			var meta = project.regionMeta(zone.name());
			if (meta == null || meta.parsedStage() != net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.ARCHIVED) continue;
			for (long packed : zone.exactChunks()) {
				int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
				if (cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ) return zone.name();
			}
		}
		return null;
	}

	/**
	 * The "minimal correction tool" brainstormed idea - a way to undo a
	 * bad manual edit by re-carving a small region back to the canonical
	 * canvas state (water/floor/transition-seal exactly as the flattener
	 * would generate it fresh) without a full WorldEdit/FAWE install, in
	 * keeping with "Minecraft provides the game, you provide the world".
	 *
	 * <p><b>Deliberately just {@code start} under a different name and a
	 * stricter confirmation rule, not new carving logic.</b> {@code
	 * flattenChunk} was already unconditionally idempotent - re-running it
	 * on an already-flattened chunk recomputes the exact same target
	 * state every time (that's what makes revisiting a chunk safe at
	 * all), so "rewipe" and "pregen" are the literal same underlying
	 * operation; only the intent differs (prepare ahead of exploring vs.
	 * undo a mistake). Reusing {@link Job}/{@link #clipRegion} completely
	 * avoids writing a second carving path with its own chance of new
	 * bugs.</p>
	 *
	 * <p><b>Confirmation is ALWAYS required here, unlike {@code start}'s
	 * threshold-based one</b> - reset is strictly more destructive than
	 * pregen even for a tiny radius: pregen only ever touches terrain
	 * nobody has built on (freshly generated or already-canonical), while
	 * reset will tear down anything a player built above the floor inside
	 * the excavated range, on purpose, by design. A 1-chunk "oops, wrong
	 * radius" mistake is a very different severity for the two commands.</p>
	 */
	public static synchronized String rewipe(ServerLevel world, int centerBlockX, int centerBlockZ,
			int radiusBlocks, boolean confirmed, ServerPlayer requestedBy) {
		String compatibilityBlock = net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
		if (!compatibilityBlock.isEmpty()) return compatibilityBlock;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.pregenEnabled()) {
			return "Reset reuses the same drafted, not-yet-enabled mechanism as pregen - "
					+ "set pregenEnabled=true in config/oceancanvas.properties once you're ready to test it.";
		}
		if (currentJob() != null) {
			return "A pregen/rewipe/restore/expand job is already running (" + currentJob().describeProgress() + "). "
					+ "Use /oceancanvas pregen cancel first if you want to start a different one.";
		}
		if (OceanCanvasUndoManager.isRunning()) {
			return "An /oceancanvas undo is currently running - wait for it to finish before starting a reset, "
					+ "so the two don't modify the world at the same time.";
		}

		Region region = clipRegion(config, centerBlockX, centerBlockZ, radiusBlocks);
		if (region.error != null) {
			return region.error;
		}
		String foreverBlock = net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockRect(world,"REWIPE",region.minChunkX,region.maxChunkX,region.minChunkZ,region.maxChunkZ,"","","");
		if (!foreverBlock.isEmpty()) return foreverBlock;

		String archived = archivedRegionIntersecting(world, region.minChunkX, region.maxChunkX, region.minChunkZ, region.maxChunkZ);
		if (archived != null) {
			return "Rewipe intersects Archived region '" + archived + "'. Move that region out of Archived before changing its terrain.";
		}

		if (!confirmed) {
			// "NOT undoable" here is now imprecise for a player-initiated
			// reset within the same session - see OceanCanvasUndoManager's
			// class doc and /oceancanvas undo. Left otherwise unchanged
			// deliberately: this warning should stay conservative (still
			// true for console/command-block resets, still true across a
			// restart, still true above the undo size cap) rather than
			// promise a safety net that doesn't always apply.
			return "This will re-carve " + region.totalChunks + " chunk(s) back to the canonical canvas "
					+ "state - ANYTHING built there above the floor (inside the excavated range) will be "
					+ "destroyed along with any terrain. If you're a player (not console/a command block) and "
					+ "the affected area isn't huge, \"/oceancanvas undo\" can restore block types/shapes "
					+ "afterward (not block entity contents - see that command's doc) - but don't count on it "
					+ "for anything valuable; treat this as effectively permanent. Re-run as "
					+ "\"/oceancanvas reset " + radiusBlocks + " confirm\" if you're sure."
					+ pendingStructureRulesClause();
		}

		// Scheduled/automatic backup (the user's own verbatim brainstormed
		// idea) - deliberately BEFORE the Job is constructed/persisted, so
		// a job never starts mutating the world while a backup of its
		// pre-mutation state is still in flight; see
		// OceanCanvasBackupManager's class doc for why this runs
		// synchronously right here rather than in the background. A
		// no-op (returns "") below the configured chunk threshold or if
		// disabled - see backupThresholdChunks's doc comment for why that
		// threshold is deliberately lower than CONFIRM_THRESHOLD_CHUNKS.
		String backupClause = net.oceancanvas.mod.backup.OceanCanvasBackupManager.maybeBackup(world, "rewipe", region.totalChunks);

		setCurrentJob(new Job(world, region.minChunkX, region.maxChunkX, region.minChunkZ, region.maxChunkZ,
				requestedBy == null ? null : requestedBy.getUUID(), "rewipe",
				region.minBlockX, region.maxBlockX, region.minBlockZ, region.maxBlockZ));
		net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureRect(world,"REWIPE","",region.minChunkX,region.minChunkZ,region.maxChunkX,region.maxChunkZ);
		currentJob().structureRules = snapshotPendingStructureRules();
		currentJob().persist();
		// Undo recording - reset only, see OceanCanvasUndoManager's class
		// doc for why never pregen/expand. Passes the exact centerBlockX/
		// centerBlockZ/radiusBlocks this call was made with, purely so
		// /oceancanvas redo can re-invoke this same reset later - see
		// that class's doc for why that's simpler than a second recorded
		// "after" snapshot.
		OceanCanvasUndoManager.beginRecording(world, requestedBy,
				"rewipe of " + currentJob().totalChunks() + " chunk(s) centered at (" + centerBlockX + ", " + centerBlockZ + ")",
				centerBlockX, centerBlockZ, radiusBlocks);

		// Recent-destructive-actions log (backs /oceancanvas admin) - see
		// OceanCanvasActionLog's class doc.
		OceanCanvasActionLog.record(world, "rewipe", requestedBy, currentJob().totalChunks() + " chunk(s) centered at ("
				+ centerBlockX + ", " + centerBlockZ + ")");

		String message = backupClause + "Started rewipe: " + currentJob().totalChunks() + " chunk(s) across up to "
				+ (region.clampedRadiusBlocks * 2L) + " x " + (region.clampedRadiusBlocks * 2L) + " blocks ("
				+ (region.clampedRadiusBlocks != radiusBlocks ? "radius clamped to " + region.clampedRadiusBlocks + " blocks, " : "")
				+ "up to " + config.pregenChunksPerTick() + " chunks/tick)."
				+ (currentJob().structureRules.isEmpty() ? "" : " Applying staged structure rules: "
						+ describeStructureRuleMap(currentJob().structureRules) + ".");
		OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
		return message;
	}

	/**
	 * Server-thread-safe Region scope view. Rectangle Regions are represented as an
	 * arithmetic/lazy Set rather than materializing up to ~1.56M boxed chunk keys.
	 * Explicit polygon masks reuse the Region's already-immutable Set and only add a
	 * filtered view when the Region crosses the configured Canvas edge.
	 */
	private record RegionChunkScope(java.util.Set<Long> chunks, int minChunkX, int maxChunkX,
			int minChunkZ, int maxChunkZ, boolean rectangle) {}

	private static RegionChunkScope clippedRegionScope(
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone, OceanCanvasConfig config) {
		var canvasBounds = net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.chunkBoundsForBlocks(
				config.centerX() - config.radius(), config.centerZ() - config.radius(),
				config.centerX() + config.radius() - 1, config.centerZ() + config.radius() - 1);
		var zoneBounds = net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.chunkBoundsForBlocks(
				zone.bounds().minX(), zone.bounds().minZ(), zone.bounds().maxX(), zone.bounds().maxZ());
		var clippedBounds = zoneBounds.intersect(canvasBounds);
		if (clippedBounds.isEmpty()) {
			return new RegionChunkScope(java.util.Set.of(), clippedBounds.minX(), clippedBounds.maxX(),
					clippedBounds.minZ(), clippedBounds.maxZ(), !zone.hasExplicitShape());
		}
		if (!zone.hasExplicitShape()) {
			return new RegionChunkScope(net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.rectangularChunkSet(clippedBounds),
					clippedBounds.minX(), clippedBounds.maxX(), clippedBounds.minZ(), clippedBounds.maxZ(), true);
		}
		java.util.Set<Long> base = zone.chunks(); // Zone constructor already made this immutable.
		java.util.Set<Long> chunks = zoneBounds.equals(clippedBounds) ? base
				: net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.clippedChunkSet(base, canvasBounds);
		return new RegionChunkScope(chunks, clippedBounds.minX(), clippedBounds.maxX(), clippedBounds.minZ(), clippedBounds.maxZ(), false);
	}

	/**
	 * Resets exactly the chunks overlapped by one map region using the region's current rules. Unlike the
	 * radius-based reset command this preserves the selected region's exact
	 * rectangular footprint. The caller temporarily disables that zone so its
	 * own protection does not block the repair; normal completion re-protects
	 * it through the same contained-zone completion path used by reset.
	 */
	public static synchronized String rewipeRegion(ServerLevel world,
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone, ServerPlayer requestedBy) {
		String compatibilityBlock = net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
		if (!compatibilityBlock.isEmpty()) return compatibilityBlock;
		var regionMeta = net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).regionMeta(zone.name());
		if (regionMeta != null && regionMeta.parsedStage() == net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.ARCHIVED) {
			return "Region '" + zone.name() + "' is Archived. Move it out of Archived before Rewipe.";
		}
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.pregenEnabled()) {
			return "Region Rewipe uses the pregen/rewipe job system - set pregenEnabled=true in config/oceancanvas.properties first.";
		}
		if (currentJob() != null) {
			return "A pregen/rewipe/restore/expand/region-rewipe job is already running (" + currentJob().describeProgress() + ").";
		}
		if (OceanCanvasUndoManager.isRunning()) {
			return "An /oceancanvas undo is currently running - wait for it to finish before resetting a region.";
		}

		RegionChunkScope regionScope = clippedRegionScope(zone, config);
		java.util.Set<Long> selected = regionScope.chunks();
		if (selected.isEmpty()) return "That region falls entirely outside the configured canvas - nothing to reset.";
		String projectId = net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.projectForRegion(world,zone.name());
		String foreverBlock = regionScope.rectangle()
				? net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockRect(world,"REWIPE",regionScope.minChunkX(),regionScope.maxChunkX(),regionScope.minChunkZ(),regionScope.maxChunkZ(),zone.name(),projectId,"")
				: net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockChunks(world,"REWIPE",selected,zone.name(),projectId,"");
		if (!foreverBlock.isEmpty()) return foreverBlock;
		int minChunkX = regionScope.minChunkX(), maxChunkX = regionScope.maxChunkX();
		int minChunkZ = regionScope.minChunkZ(), maxChunkZ = regionScope.maxChunkZ();
		long total = selected.size();
		/*
		 * Region Rewipe must not synchronously copy the entire world save.
		 * The 26.2 ModernFix watchdog dump showed the server thread stuck in
		 * OceanCanvasBackupManager.copyDirectory/Files.copy before the Reset
		 * Job or its boss bar even existed.
		 */
		String backupClause = "";
		OceanCanvas.LOGGER.info("(Ocean Canvas) Region Rewipe accepted; synchronous world backup skipped so Reset can start immediately.");
		// A region rewipe is a real regeneration, not merely another carve over
		// yesterday's protection decisions. Forget Ocean-Canvas-generated
		// structure/relocation state in exactly these chunks AFTER the optional
		// backup has captured the old state, so the upcoming pass evaluates the
		// region's CURRENT rules from scratch.
		net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(world).prepareRegionRewipe(selected);
		setCurrentJob(new Job(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
				requestedBy == null ? null : requestedBy.getUUID(), "region-rewipe", selected));
		currentJob().scopeName = zone.name();
		currentJob().scopeShape = regionScope.rectangle() ? "RECT" : "MASK";
		if (regionScope.rectangle())
			net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureRect(world,"REWIPE",zone.name(),minChunkX,minChunkZ,maxChunkX,maxChunkZ);
		else
			net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureChunks(world,"REWIPE",zone.name(),selected);
		currentJob().persist();
		currentJob().showInitialBossBar();
		OceanCanvasActionLog.record(world, "region-rewipe", requestedBy, total + " chunk(s) in region '" + zone.name() + "'");
		String message = backupClause + "Started region rewipe: " + total + " chunk(s), up to "
				+ config.pregenChunksPerTick() + " chunks/tick. The region will be protected again automatically when complete.";
		OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
		return message;
	}

	/** Starts a non-destructive pregen over the exact chunk mask of one map region. */
	public static synchronized String startRegion(ServerLevel world,
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone, ServerPlayer requestedBy) {
        return startRegionInternal(world,zone,requestedBy,"");
    }

    static synchronized String startQueuedRegion(ServerLevel world,
            net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone,ServerPlayer requestedBy,String queueEntryId){
        return startRegionInternal(world,zone,requestedBy,queueEntryId);
    }

    public static synchronized boolean isQueuedJob(String id){return currentJob()!=null&&!id.isBlank()&&currentJob().queueEntryId.equals(id);}

    private static String startRegionInternal(ServerLevel world,
            net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone,ServerPlayer requestedBy,String queueEntryId){
		String compatibilityBlock = net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
		if (!compatibilityBlock.isEmpty()) return compatibilityBlock;
		if(OceanCanvasTerrainOperationActivity.restoreRunning())return "Restore to Vanilla is running; wait for it to finish.";
		var regionMeta = net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).regionMeta(zone.name());
		if (regionMeta != null && regionMeta.parsedStage() == net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.ARCHIVED) {
			return "Region '" + zone.name() + "' is Archived. Move it out of Archived before Pregen.";
		}
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.pregenEnabled()) {
			return "Region pregen is disabled - set pregenEnabled=true in config/oceancanvas.properties first.";
		}
		if (currentJob() != null) {
			return "A pregen/rewipe/restore/expand job is already running (" + currentJob().describeProgress() + ").";
		}
		if (OceanCanvasUndoManager.isRunning()) {
			return "An /oceancanvas undo is currently running - wait for it to finish before pre-generating a region.";
		}
		// Protected means this region is already committed work. Ordinary Pregen
		// must move past it without loading or mutating any of its chunks; Rewipe is
		// the explicit destructive path when clearing protected work is intentional.
		if (zone.protectedNow()) {
			return "Region '" + zone.name() + "' is already pregenerated and protected; skipping it. Use Rewipe only if you intentionally want to clear it again.";
		}

		RegionChunkScope regionScope = clippedRegionScope(zone, config);
		java.util.Set<Long> selected = regionScope.chunks();
		if (selected.isEmpty()) {
			return "That region falls entirely outside the configured canvas - nothing to pre-generate.";
		}
		String projectId = net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.projectForRegion(world,zone.name());
		String foreverBlock = regionScope.rectangle()
				? net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockRect(world,"PREGEN",regionScope.minChunkX(),regionScope.maxChunkX(),regionScope.minChunkZ(),regionScope.maxChunkZ(),zone.name(),projectId,"")
				: net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockChunks(world,"PREGEN",selected,zone.name(),projectId,"");
		if (!foreverBlock.isEmpty()) return foreverBlock;

		int minChunkX = regionScope.minChunkX(), maxChunkX = regionScope.maxChunkX();
		int minChunkZ = regionScope.minChunkZ(), maxChunkZ = regionScope.maxChunkZ();

		setCurrentJob(new Job(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
				requestedBy == null ? null : requestedBy.getUUID(), "region-pregen", selected));
		currentJob().scopeName = zone.name();
		currentJob().scopeShape = regionScope.rectangle() ? "RECT" : "MASK";
		if (regionScope.rectangle())
			net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureRect(world,"PREGEN",zone.name(),minChunkX,minChunkZ,maxChunkX,maxChunkZ);
		else
			net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureChunks(world,"PREGEN",zone.name(),selected);
        currentJob().queueEntryId=queueEntryId;
		currentJob().persist();
		currentJob().showInitialBossBar();
		OceanCanvasActionLog.record(world, "region-pregen", requestedBy,
				selected.size() + " chunk(s) in region '" + zone.name() + "'");
		String message = "Started region pregen: " + selected.size() + " chunk(s) in '" + zone.name()
				+ "', adaptive pregen starting at " + config.pregenChunksPerTick()
				+ " chunks/tick; auto-tunes up to " + PREGEN_ADAPTIVE_MAX_CHUNKS_PER_TICK
				+ ". Protection activates only after physical completion.";
		OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
		return message;
	}

	/**
	 * Reports what {@code start} with the same arguments WOULD do -
	 * chunk count, clamped radius (if any), and a rough tick/time
	 * estimate at the configured throttle - without force-loading or
	 * writing a single block. Drafted per the brainstormed "pregen
	 * dry-run mode... matches Chunky's own confirm-before-run pattern"
	 * idea. Deliberately available even when {@code pregenEnabled} is
	 * false - previewing a job's size doesn't touch the world at all, so
	 * there's no reason to gate it behind the same flag that gates
	 * actually running one.
	 */
	public static synchronized String preview(int centerBlockX, int centerBlockZ, int radiusBlocks) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		Region region = clipRegion(config, centerBlockX, centerBlockZ, radiusBlocks);
		if (region.error != null) {
			return region.error;
		}

		int chunksPerTick = Math.max(1, config.pregenChunksPerTick());
		long estimatedTicks = (region.totalChunks + chunksPerTick - 1) / chunksPerTick;
		double estimatedSeconds = estimatedTicks / 20.0; // vanilla's nominal 20 TPS - a rough estimate, not a promise

		return "Dry run centered at (" + centerBlockX + ", " + centerBlockZ + "): "
				+ region.clampedRadiusBlocks + "-block radius = up to " + (region.clampedRadiusBlocks * 2L) + " x "
				+ (region.clampedRadiusBlocks * 2L) + " blocks; would cover " + region.totalChunks + " chunks "
				+ (region.clampedRadiusBlocks != radiusBlocks ? "(radius clamped to " + region.clampedRadiusBlocks + " chunks) " : "")
				+ "at up to " + chunksPerTick + " chunks/tick - roughly " + estimatedTicks
				+ " ticks (~" + String.format(java.util.Locale.ROOT, "%.0f", estimatedSeconds) + "s at a healthy "
				+ "20 TPS, likely longer in practice; a not-yet-loaded chunk can take more than one tick to "
				+ "resolve - see PregenManager.Job's class doc). No chunks were touched."
				+ pendingStructureRulesClause();
	}

    /** Server-calibrated OC-F206 forecast layered on the legacy radius dry run. */
    public static synchronized String preview(ServerLevel world,int centerBlockX,int centerBlockZ,int radiusBlocks,String kind){
        String base=preview(centerBlockX,centerBlockZ,radiusBlocks);Region region=clipRegion(OceanCanvasConfig.get(),centerBlockX,centerBlockZ,radiusBlocks);if(region.error!=null)return base;
        var f=net.oceancanvas.mod.project.OceanCanvasPreflightResourceForecast.estimate(world,kind==null?"PREGEN":kind,region.totalChunks);return base+" Resource forecast: ["+f.risk()+"] "+f.compact();
    }

	/**
	 * Sanity backstop for {@link #expand}'s new-radius argument, distinct
	 * from and much larger than {@link #MAX_RADIUS_BLOCKS} - that one
	 * guards against a mistyped "blocks instead of chunks" pregen/rewipe/restore/expand
	 * request; this one only guards against a wildly implausible typo
	 * (an extra digit) in a legitimately large expansion, since growing
	 * the actual canvas config to a large size is a normal, intended use
	 * of {@code /oceancanvas expand}, not a mistake to block. The real
	 * safety net for expand is its always-required {@code confirm} (see
	 * that method's doc) showing the real resulting size before anything
	 * happens - this is just a backstop underneath that.
	 */
	private static final int MAX_EXPAND_RADIUS_CHUNKS = 5_000;

	/**
	 * Grows the canvas to a new radius (chunks, from the canvas's own
	 * configured center - NOT the command source's position, unlike
	 * {@link #start}/{@link #reset}) and proactively flattens the newly-
	 * included ring, reusing this same job machinery per the brainstormed
	 * "guided expansion command...reusing the pregen job machinery"
	 * idea. See {@code ExpandCommand} for the config-update half of this
	 * (this method only handles the flattening job).
	 *
	 * <p><b>Deliberately processes only the new RING, not the whole new
	 * square (unlike a naive "just call {@code start} with the new
	 * radius" approach), and NOT via a "skip one chunk at a time" loop
	 * either - both were real risks caught during design, not
	 * accepted:</b></p>
	 * <ul>
	 *   <li>Re-scanning the whole new square from the center would hit
	 *       {@link #MAX_RADIUS_BLOCKS}'s 200-chunk cap immediately - for
	 *       any canvas already larger than 200 chunks in radius (the
	 *       default 20,000-block canvas already is, at 625), the
	 *       proactive job would never even reach the actual new ring at
	 *       all, silently doing nothing useful while still claiming to
	 *       "proactively flatten" it.</li>
	 *   <li>A chunk-by-chunk skip loop over the already-good interior
	 *       (to avoid the above) would be unbounded synchronous work
	 *       within a single server tick for a large canvas - up to
	 *       millions of interior chunks - a real tick-stall risk this
	 *       project has already been burned by once (see
	 *       {@code OceanCanvasSurfaceFlattener#neighborsReady}'s "~900
	 *       ticks behind" incident).</li>
	 * </ul>
	 * <p>Fixed by giving {@link Job} an O(1) arithmetic jump over an
	 * excluded interior rectangle (see its {@code tick} method) instead
	 * of either of the above - bounded per-tick work regardless of how
	 * large the already-good interior is, and actually reaches the real
	 * ring on any canvas size.</p>
	 */
	public static synchronized String expand(ServerLevel world, int oldRadiusBlocks, int centerX, int centerZ,
			int newRadiusChunks, boolean confirmed, ServerPlayer requestedBy) {
		String compatibilityBlock = net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
		if (!compatibilityBlock.isEmpty()) return compatibilityBlock;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.pregenEnabled()) {
			return "Canvas resized, but pregenEnabled is off, so the new area was NOT proactively flattened - "
					+ "it will convert to canvas normally as you explore it, exactly like the rest of the canvas "
					+ "already does. Set pregenEnabled=true first if you want it pre-flattened instead.";
		}
		if (currentJob() != null) {
			return "A pregen/rewipe/restore/expand job is already running (" + currentJob().describeProgress() + "). "
					+ "The canvas was still resized - only the proactive flattening pass was skipped. Use "
					+ "/oceancanvas pregen cancel, then re-run \"/oceancanvas pregen start " + newRadiusChunks
					+ " confirm\" once it's free if you want the new ring pre-flattened.";
		}
		if (newRadiusChunks <= 0 || newRadiusChunks > MAX_EXPAND_RADIUS_CHUNKS) {
			return "Radius must be between 1 and " + MAX_EXPAND_RADIUS_CHUNKS + " chunks.";
		}
		if (OceanCanvasUndoManager.isRunning()) {
			// Matches the currentJob()-already-running branch above: the
			// config resize already happened by the time this method is
			// called (see ExpandCommand), so this can only skip the
			// proactive flattening pass, not the resize itself.
			return "An /oceancanvas undo is currently running, so the two don't modify the world at the same "
					+ "time. The canvas was still resized - only the proactive flattening pass was skipped. Wait "
					+ "for the undo to finish, then re-run \"/oceancanvas pregen start " + newRadiusChunks
					+ " confirm\" if you want the new ring pre-flattened.";
		}

		int oldMinChunkX = Math.floorDiv(centerX - oldRadiusBlocks, 16);
		int oldMaxChunkX = Math.floorDiv(centerX + oldRadiusBlocks - 1, 16);
		int oldMinChunkZ = Math.floorDiv(centerZ - oldRadiusBlocks, 16);
		int oldMaxChunkZ = Math.floorDiv(centerZ + oldRadiusBlocks - 1, 16);

		int newRadiusBlocks = newRadiusChunks * 16;
		int newMinChunkX = Math.floorDiv(centerX - newRadiusBlocks, 16);
		int newMaxChunkX = Math.floorDiv(centerX + newRadiusBlocks - 1, 16);
		int newMinChunkZ = Math.floorDiv(centerZ - newRadiusBlocks, 16);
		int newMaxChunkZ = Math.floorDiv(centerZ + newRadiusBlocks - 1, 16);
		String foreverBlock = net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockRect(world,"EXPAND",newMinChunkX,newMaxChunkX,newMinChunkZ,newMaxChunkZ,"","","");
		if (!foreverBlock.isEmpty()) return foreverBlock;

		setCurrentJob(new Job(world, newMinChunkX, newMaxChunkX, newMinChunkZ, newMaxChunkZ,
				requestedBy == null ? null : requestedBy.getUUID(),
				oldMinChunkX, oldMaxChunkX, oldMinChunkZ, oldMaxChunkZ, "expand"));
		net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureRingRect(world,"EXPAND","",newMinChunkX,newMinChunkZ,newMaxChunkX,newMaxChunkZ,oldMinChunkX,oldMinChunkZ,oldMaxChunkX,oldMaxChunkZ);
		currentJob().structureRules = snapshotPendingStructureRules();

		// Scheduled/automatic backup - see the matching note in reset()
		// above and OceanCanvasBackupManager's class doc. Placed after
		// the Job object exists (so its already-computed new-ring chunk
		// count can be reused) but before currentJob().persist()/any
		// tick-driven flattening has had a chance to run - flattening
		// only ever happens on a LATER server tick (see
		// PregenManager#onServerTick), never synchronously inside this
		// method, so the backup still captures pre-expansion state.
		String backupClause = net.oceancanvas.mod.backup.OceanCanvasBackupManager.maybeBackup(
				world, "expand", currentJob().totalChunks());

		currentJob().persist();

		// Recent-destructive-actions log (backs /oceancanvas admin) - see
		// OceanCanvasActionLog's class doc.
		OceanCanvasActionLog.record(world, "expand", requestedBy, currentJob().totalChunks()
				+ " new chunk(s) in the ring, canvas now centered at (" + centerX + ", " + centerZ + ")");

		String message = backupClause + "Started expansion flattening: " + currentJob().totalChunks() + " chunk(s) in "
				+ "the new ring (up to " + config.pregenChunksPerTick() + " chunks/tick). Any protected zone is "
				+ "respected regardless of when it was created."
				+ (currentJob().structureRules.isEmpty() ? "" : " Applying staged structure rules: "
						+ describeStructureRuleMap(currentJob().structureRules) + ".");
		OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
		return message;
	}

	/**
	 * Reports what {@link #expand} with the same arguments WOULD flatten -
	 * new-ring chunk count and a rough tick/time estimate - without
	 * touching the config, force-loading anything, or writing a single
	 * block. The same real gap as {@link #reset}'s own new {@code dryrun}
	 * (see {@code ExpandCommand}): {@code start}/{@code pregen} has had a
	 * dry-run preview since Round 2, {@code expand} never got an
	 * equivalent explicit one - its existing unconfirmed preview (see
	 * {@code ExpandCommand#expand}) already shows the resulting canvas
	 * size, but never the actual ring chunk count/time estimate this
	 * mirrors from {@link #preview}.
	 *
	 * <p>Deliberately duplicates {@link #expand}'s own outer-square-minus-
	 * inner-square ring math rather than reusing it via a shared helper -
	 * both are short, and a shared helper would need to return either a
	 * live {@link Job} (this method must never construct one - a dry run
	 * must have zero chance of accidentally becoming a real job) or an
	 * intermediate result type solely to serve one caller each. Simpler to
	 * duplicate ~8 lines of pure arithmetic than add that indirection for
	 * two call sites.</p>
	 */
	public static synchronized String expandPreview(int oldRadiusBlocks, int centerX, int centerZ, int newRadiusChunks) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (newRadiusChunks <= 0 || newRadiusChunks > MAX_EXPAND_RADIUS_CHUNKS) {
			return "Radius must be between 1 and " + MAX_EXPAND_RADIUS_CHUNKS + " chunks.";
		}
		int newRadiusBlocks = newRadiusChunks * 16;
		if (newRadiusBlocks <= oldRadiusBlocks) {
			return newRadiusChunks + " chunks isn't larger than the current canvas - nothing would be flattened "
					+ "(shrinking isn't supported, see /oceancanvas expand's own doc for why).";
		}

		int oldMinChunkX = Math.floorDiv(centerX - oldRadiusBlocks, 16);
		int oldMaxChunkX = Math.floorDiv(centerX + oldRadiusBlocks - 1, 16);
		int oldMinChunkZ = Math.floorDiv(centerZ - oldRadiusBlocks, 16);
		int oldMaxChunkZ = Math.floorDiv(centerZ + oldRadiusBlocks - 1, 16);

		int newMinChunkX = Math.floorDiv(centerX - newRadiusBlocks, 16);
		int newMaxChunkX = Math.floorDiv(centerX + newRadiusBlocks - 1, 16);
		int newMinChunkZ = Math.floorDiv(centerZ - newRadiusBlocks, 16);
		int newMaxChunkZ = Math.floorDiv(centerZ + newRadiusBlocks - 1, 16);

		long outerChunks = (long) (newMaxChunkX - newMinChunkX + 1) * (newMaxChunkZ - newMinChunkZ + 1);
		long innerChunks = (long) (oldMaxChunkX - oldMinChunkX + 1) * (oldMaxChunkZ - oldMinChunkZ + 1);
		long ringChunks = outerChunks - innerChunks;

		int chunksPerTick = Math.max(1, config.pregenChunksPerTick());
		long estimatedTicks = (ringChunks + chunksPerTick - 1) / chunksPerTick;
		double estimatedSeconds = estimatedTicks / 20.0; // vanilla's nominal 20 TPS - a rough estimate, not a promise

		return "Dry run: expanding to " + newRadiusChunks + " chunks radius would proactively flatten " + ringChunks
				+ " new ring chunk(s) at up to " + chunksPerTick + " chunks/tick - roughly " + estimatedTicks
				+ " ticks (~" + String.format(java.util.Locale.ROOT, "%.0f", estimatedSeconds) + "s at a healthy "
				+ "20 TPS, likely longer in practice - see PregenManager.Job's class doc). The canvas config is "
				+ "NOT changed by a dry run - only \"confirm\" actually resizes anything. No chunks were touched."
				+ pendingStructureRulesClause();
	}

	/** Shared clip logic between {@link #start} and {@link #preview} - never mutates any state. */
	private static Region clipRegion(OceanCanvasConfig config, int centerBlockX, int centerBlockZ, int radiusBlocks) {
		if (radiusBlocks <= 0) {
			return Region.error("Radius must be positive.");
		}
		int clampedRadiusBlocks = Math.min(radiusBlocks, MAX_RADIUS_BLOCKS);

		// Radius is expressed in BLOCKS. A radius of 10,000 therefore means
		// a 20,000 x 20,000 block square centered on the requested point.
		long requestedMinBlockX = (long) centerBlockX - clampedRadiusBlocks;
		long requestedMaxBlockX = (long) centerBlockX + clampedRadiusBlocks - 1L;
		long requestedMinBlockZ = (long) centerBlockZ - clampedRadiusBlocks;
		long requestedMaxBlockZ = (long) centerBlockZ + clampedRadiusBlocks - 1L;

		long canvasMinBlockX = (long) config.centerX() - config.radius();
		long canvasMaxBlockX = (long) config.centerX() + config.radius() - 1L;
		long canvasMinBlockZ = (long) config.centerZ() - config.radius();
		long canvasMaxBlockZ = (long) config.centerZ() + config.radius() - 1L;

		long minBlockX = Math.max(requestedMinBlockX, canvasMinBlockX);
		long maxBlockX = Math.min(requestedMaxBlockX, canvasMaxBlockX);
		long minBlockZ = Math.max(requestedMinBlockZ, canvasMinBlockZ);
		long maxBlockZ = Math.min(requestedMaxBlockZ, canvasMaxBlockZ);

		if (minBlockX > maxBlockX || minBlockZ > maxBlockZ) {
			return Region.error("That region falls entirely outside the configured canvas - nothing to pregenerate there.");
		}

		int minChunkX = Math.floorDiv((int) minBlockX, 16);
		int maxChunkX = Math.floorDiv((int) maxBlockX, 16);
		int minChunkZ = Math.floorDiv((int) minBlockZ, 16);
		int maxChunkZ = Math.floorDiv((int) maxBlockZ, 16);

		return Region.of(minChunkX, maxChunkX, minChunkZ, maxChunkZ,
				(int) minBlockX, (int) maxBlockX, (int) minBlockZ, (int) maxBlockZ, clampedRadiusBlocks);
	}
	/** Small, purely-computed result of clipping a requested region - never touches chunk loading. */
	private static final class Region {
		final String error;
		final int minChunkX;
		final int maxChunkX;
		final int minChunkZ;
		final int maxChunkZ;
		final int minBlockX;
		final int maxBlockX;
		final int minBlockZ;
		final int maxBlockZ;
		final int clampedRadiusBlocks;
		final long totalChunks;

		private Region(String error, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
				int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, int clampedRadiusBlocks) {
			this.error = error;
			this.minChunkX = minChunkX;
			this.maxChunkX = maxChunkX;
			this.minChunkZ = minChunkZ;
			this.maxChunkZ = maxChunkZ;
			this.minBlockX = minBlockX;
			this.maxBlockX = maxBlockX;
			this.minBlockZ = minBlockZ;
			this.maxBlockZ = maxBlockZ;
			this.clampedRadiusBlocks = clampedRadiusBlocks;
			this.totalChunks = error != null ? 0L : (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
		}

		static Region error(String message) {
			return new Region(message, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		}

		static Region of(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
				int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, int clampedRadiusBlocks) {
			return new Region(null, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
					minBlockX, maxBlockX, minBlockZ, maxBlockZ, clampedRadiusBlocks);
		}
	}

	// Shared between pregen and reset since they're the same underlying
	// Job - see reset's doc comment.
	public static synchronized String cancel() {
		return cancel(null);
	}

	/** v98 cancellation hardening. A persisted job can exist before/after the
	 * in-memory handle is attached during restart. The command now passes its
	 * level so Cancel can always clear that checkpoint and transient Pregen
	 * ownership instead of replying that nothing is running while the next tick
	 * is still able to resume it. */
	public static synchronized String cancel(ServerLevel commandWorld) {
		if (currentJob() == null) {
			if (commandWorld != null && OceanCanvasJobState.get(commandWorld).get() != null) {
				OceanCanvasJobState.get(commandWorld).clear();
				OceanCanvasJobScopeState.get(commandWorld).clear();
				OceanCanvasPregenRecoveryJournalData.get(commandWorld).clear();
				OceanCanvasPregenSessionMarker.disarm(commandWorld);
				int withdrawn = OceanCanvasSurfaceFlattener.cancelQueuedPregenWork(commandWorld);
				OceanCanvasSurfaceFlattener.cancelPendingLightFinalization(commandWorld);
				assertPregenTransientCleanup(commandWorld, "persisted-cancel");
				return "Cancelled persisted pregen/rewipe/restore/expand checkpoint before resume. Withdrew "
						+ withdrawn + " queued pregen chunk(s).";
			}
			return "No pregen/rewipe/restore/expand job is running.";
		}
		String cancelledKind = currentJob().kind();
		int withdrawnPregenChunks = 0;
		if (isPregenKind(cancelledKind)) {
			withdrawnPregenChunks = OceanCanvasSurfaceFlattener.cancelQueuedPregenWork(currentJob().world);
			OceanCanvasSurfaceFlattener.cancelPendingLightFinalization(currentJob().world);
			assertPregenTransientCleanup(currentJob().world, "cancel");
		}
		String message = "Cancelled pregen/rewipe/restore/expand job (" + currentJob().describeProgress() + ")."
				+ (isPregenKind(cancelledKind)
						? " Withdrew " + withdrawnPregenChunks + " queued pregen chunk(s)."
						: "");
		// Explicit stop - clear the persisted snapshot too, so it never
		// surprises anyone by resuming on some unrelated future restart.
		// See OceanCanvasJobState's class doc.
		currentJob().clearPersisted();
		// A no-op unless the cancelled job was a rewipe that was actively
		// recording undo data - see OceanCanvasUndoManager#finishRecording's
		// doc. Whatever it recorded up to this point is still committed
		// and undoable, matching "undo a partially-run reset" being a
		// reasonable, useful thing to support.
		OceanCanvasUndoManager.finishRecording();
		// Every stop path hides the boss bar, not just normal completion -
		// see Job#hideBossBar's doc for why a cancel can't be allowed to
		// leave one stuck on screen.
		currentJob().hideBossBar();
		if (("region-rewipe".equals(currentJob().kind()) || "region-reset".equals(currentJob().kind()))) {
			net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(currentJob().world)
					.reprotectContainedZones(currentJob().minChunkX, currentJob().maxChunkX, currentJob().minChunkZ, currentJob().maxChunkZ);
		}
        OceanCanvasPregenQueue.finished(currentJob().world,currentJob().queueEntryId,false);
		OceanCanvasActionLog.recordLifecycle(currentJob().world, cancelledKind, "CANCELLED", currentJob().requesterPlayer(),
				currentJob().describeProgress());
		net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.verify(currentJob().world,"CANCELLED");
		net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(currentJob().world)
				.capture(currentJob().world, "after-" + cancelledKind + "-cancel", "Operation cancelled");
		setCurrentJob(null);
		return message;
	}

	/**
	 * A small, immutable snapshot of whatever job is running right now,
	 * for the map screen's live job overlay - or {@code null} when nothing
	 * is running.
	 *
	 * <p><b>A snapshot, not a handle to the live {@code Job}.</b> The
	 * overlay is read on the broadcast path while {@code tick()} may be
	 * advancing the cursor on the same object; copying the handful of
	 * numbers out under this class's existing monitor (the same monitor
	 * every other public method here already synchronises on) means the
	 * client can never be handed a half-advanced view, and nothing outside
	 * this class gets a reference it could mutate.</p>
	 *
	 * <p>{@code cursorChunkX}/{@code cursorChunkZ} are where the job has
	 * reached in its row-major walk - which is what lets the map draw a
	 * sweep line moving across the region rather than only a percentage.</p>
	 */
	/** v212: every explicit Pregen stop boundary proves transient ownership is gone. */
	private static boolean assertPregenTransientCleanup(ServerLevel world, String context) {
		var t = OceanCanvasSurfaceFlattener.pregenTicketDiagnostics();
		int outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
		int queued = OceanCanvasSurfaceFlattener.pendingPregenChunkCount();
		int pendingLight = OceanCanvasSurfaceFlattener.pendingLightSyncCount();
		int lightTickets = OceanCanvasSurfaceFlattener.activeLightResidencyTicketCount();
		boolean serverStop = "server-stop".equals(context);
		boolean nativeTicketCloseOk = !serverStop || OceanCanvasSurfaceFlattener.lastServerStopTicketDeactivationSucceeded();
		int trackedAtServerStop = serverStop ? OceanCanvasSurfaceFlattener.lastServerStopTrackedTransientTickets() : 0;
		int trackedNow = OceanCanvasSurfaceFlattener.trackedTransientTicketCount();
		boolean clean = t.selfActive() == 0 && t.carveLaneActive() == 0 && t.processingLeaseActive() == 0 && t.finalDrainActive() == 0
				&& t.targetFutures() == 0 && t.supportFutures() == 0 && t.loadRescueActive() == 0 && t.auditPending() == 0
				&& outstanding == 0 && queued == 0 && pendingLight == 0 && lightTickets == 0 && trackedNow == 0 && nativeTicketCloseOk;
		if (clean) {
			OceanCanvas.LOGGER.info("(Ocean Canvas) {} cleanup invariant PASS [{}]: bookkeeping zero; nativeTicketCloseOk={} trackedAtStop={}.",
				net.oceancanvas.mod.OceanCanvas.VERSION, context, nativeTicketCloseOk, trackedAtServerStop);
		} else {
			OceanCanvas.LOGGER.error("(Ocean Canvas) {} cleanup invariant FAIL [{}]: self={}, lane={}, leases={}, finalDrain={}, "
					+ "targetFutures={}, supportFutures={} (done={}), loadRescue={}, auditPending={}, outstanding={}, queued={}, pendingLight={}, lightTickets={}, trackedNow={}, nativeTicketCloseOk={}, trackedAtStop={}. This build must not be treated as clean.",
					net.oceancanvas.mod.OceanCanvas.VERSION, context, t.selfActive(), t.carveLaneActive(), t.processingLeaseActive(), t.finalDrainActive(), t.targetFutures(),
					t.supportFutures(), t.supportFuturesDone(), t.loadRescueActive(), t.auditPending(), outstanding, queued, pendingLight, lightTickets, trackedNow, nativeTicketCloseOk, trackedAtServerStop);
		}
		return clean;
	}

	/** Called during server shutdown. Preserve the persisted cursor for resume, but
	 * detach all live UI/state so the integrated server can close promptly. */
	public static synchronized void onServerStopping() {
		if (currentJob() != null) {
			// v96: persist the bounded replay list BEFORE withdrawing transient
			// requests, then cancel Ocean Canvas-owned C2ME futures/tickets. The next
			// launch replays those not-yet-retired targets before advancing the cursor.
			// This keeps Save & Quit fast without weakening resume correctness.
			currentJob().persist(true);
			if (isPregenKind(currentJob().kind())) {
				OceanCanvasSurfaceFlattener.cancelQueuedPregenWork(currentJob().world);
				OceanCanvasSurfaceFlattener.onServerStopping(currentJob().world);
				boolean cleanupClean = assertPregenTransientCleanup(currentJob().world, "server-stop");
				// Only now is graceful shutdown proven far enough to remove the durable
				// crash marker. If cleanup failed (or the process dies earlier), the marker
				// intentionally stays and overrides cleanStop=true on the next launch.
				if (cleanupClean) OceanCanvasPregenSessionMarker.disarm(currentJob().world);
			}
			currentJob().hideBossBar();
			setCurrentJob(null);
		}
		OceanCanvasUndoManager.finishRecording();
	}

	public static synchronized JobOverlay overlaySnapshot() {
		Job job = currentJob();
		if (job == null) {
			return null;
		}
		return job.toOverlay();
	}

	/** See {@link #overlaySnapshot()}. Chunk coordinates throughout, matching how a job actually walks its region. */
	public record JobOverlay(String kind, String scopeName, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
			int cursorChunkX, int cursorChunkZ, long submittedChunks, long totalChunks) {
	}

	/** OC-F253 immutable authority snapshot for the live terrain controller. */
	public record OwnerSnapshot(String kind, java.util.UUID requesterId, String requesterDisplay) { }

	public static synchronized OwnerSnapshot ownerSnapshot() {
		Job job=currentJob();if(job==null)return null;
		java.util.UUID id=job.requestedBy;String display="console/system";
		if(id!=null){ServerPlayer p=job.world.getServer().getPlayerList().getPlayer(id);display=p==null?id.toString():p.getGameProfile().name();}
		return new OwnerSnapshot(job.kind(),id,display);
	}

	/**
	 * Richer server-side telemetry contract for Adaptive Pregen v2, health diagnostics and
	 * the future per-chunk map overlay. Kept off the wire for now so this foundation pass
	 * cannot break the already-working client packet format.
	 */
	public record JobTelemetry(String kind, long submittedChunks, long totalChunks, int adaptiveRatePerTick,
			int outstandingChunks, double heapUseFraction, double tickMsEma) {
	}

	public static synchronized JobTelemetry telemetrySnapshot() {
		Job job = currentJob();
		return job == null ? null : job.toTelemetry();
	}

    /** Read-only live performance view; deliberately separate from the spatial job overlay. */
    public static synchronized OceanCanvasPregenMetrics.Snapshot performanceSnapshot() {
        Job job=currentJob();
        return job==null || !isPregenKind(job.kind) ? OceanCanvasPregenMetrics.Snapshot.idle() : job.performanceSnapshot();
    }

	/** Read-only explicit CPU/heap/I/O/ticket admission-budget snapshot. */
	public static synchronized OceanCanvasResourceBudgetGovernor.Decision resourceBudgetSnapshot() {
		Job job = currentJob();
		return job == null || !isPregenKind(job.kind)
				? OceanCanvasResourceBudgetGovernor.idle()
				: job.lastResourceBudgetDecision;
	}

	public static synchronized String status() {
		if (currentJob() == null) {
			return "No pregen/rewipe/restore/expand job is running.";
		}
		return "Pregen/reset/expand in progress: " + currentJob().describeProgress();
	}

	private static boolean isPregenKind(String kind) {
		return "pregen".equals(kind) || "region-pregen".equals(kind);
	}

	private static boolean isDestructiveRepairKind(String kind) {
		return "rewipe".equals(kind) || ("region-rewipe".equals(kind) || "region-reset".equals(kind)) || "reset".equals(kind) || "region-reset".equals(kind);
	}

	/**
	 * v121: which job kinds get the v120 two-phase (GENERATE-then-CARVE)
	 * treatment. v120 shipped this for pregen/region-pregen only, on the
	 * reasoning that rewipe/expand "operate on chunks already resident
	 * around player action... not a genuinely cold region" - true for a
	 * small correction rewipe, but not for a large rewipe of a never-
	 * visited area, and never true for expand's new ring, which is by
	 * definition unexplored, never-generated terrain (see {@code
	 * PregenManager#expand}'s own doc for why the ring can be huge). Both
	 * hit the exact same neighbor-stall problem tickGenerate() exists to
	 * avoid, so both now get the same treatment, per explicit follow-up
	 * request. Region-rewipe (map-region reset) is included too - same
	 * underlying Job machinery, same cold-region risk at scale.
	 */
	private static boolean usesTwoPhaseGeneration(String kind) {
		// v228: retired.
		//
		// The v120-v227 GENERATE->CARVE split existed solely to pre-generate the
		// entire area before the old neighbor-sensitive carve pipeline touched it.
		// v227 replaced that old ownership model with one bounded target-centered
		// radius-3 lifecycle ticket per admitted target. Keeping a separate raw
		// generation engine now duplicates ownership machinery and, in the v227
		// runtime, deadlocked immediately at 256/256 raw targets for the whole run.
		//
		// Every operation now uses the single bounded CARVE pipeline. That pipeline
		// is responsible for generation/residency, carve readiness, retirement,
		// physical audit, backpressure, and cleanup in one lifecycle.
		return false;
	}

	// v121: structure rules staged via /oceancanvas structurerules, consumed
	// by the NEXT start()/rewipe()/expand() call - see StructureRulesCommand
	// and Job#structureRules's doc for the full design. Deliberately
	// in-memory only (like currentJob() itself), not persisted: this is a
	// "set it right before you run the operation" staging area, not a
	// durable per-canvas setting - a world default already exists in
	// OceanCanvasConfig, and a durable per-area rule already exists as a
	// region rule (see OceanCanvasPlayerZones) for anyone who wants one
	// that outlives a single job.
	private static java.util.Map<net.oceancanvas.mod.worldgen.OceanCanvasStructureKind,
			net.oceancanvas.mod.config.StructureOverride> pendingStructureRules =
			new java.util.EnumMap<>(net.oceancanvas.mod.worldgen.OceanCanvasStructureKind.class);

	/** {@code /oceancanvas structurerules set <kind> <inherit|force_on|force_off>}'s backing call. */
	public static synchronized String setPendingStructureRule(net.oceancanvas.mod.worldgen.OceanCanvasStructureKind kind,
			net.oceancanvas.mod.config.StructureOverride override) {
		if (override == net.oceancanvas.mod.config.StructureOverride.INHERIT) {
			pendingStructureRules.remove(kind);
		} else {
			pendingStructureRules.put(kind, override);
		}
		return "Staged " + kind.displayName() + " -> " + override + " for the next pregen/rewipe/expand start. "
				+ describePendingStructureRules();
	}

	/** {@code /oceancanvas structurerules clear}'s backing call. */
	public static synchronized String clearPendingStructureRules() {
		if (pendingStructureRules.isEmpty()) {
			return "No staged structure rules to clear.";
		}
		pendingStructureRules.clear();
		return "Cleared all staged structure rules.";
	}

	/** {@code /oceancanvas structurerules show}'s backing call. */
	public static synchronized String describePendingStructureRules() {
		if (pendingStructureRules.isEmpty()) {
			return "No structure rules staged - the next pregen/rewipe/expand will use each region's own rules "
					+ "(if any) and the world defaults everywhere else.";
		}
		StringBuilder builder = new StringBuilder("Staged structure rules for the next pregen/rewipe/expand: ");
		boolean first = true;
		for (var entry : pendingStructureRules.entrySet()) {
			if (!first) builder.append(", ");
			first = false;
			builder.append(entry.getKey().displayName()).append(": ").append(entry.getValue());
		}
		return builder.toString();
	}

	/** Short, human-readable rendering of a job's captured rule set - shared by start/rewipe/expand's "Started..." messages. */
	private static String describeStructureRuleMap(java.util.Map<net.oceancanvas.mod.worldgen.OceanCanvasStructureKind,
			net.oceancanvas.mod.config.StructureOverride> rules) {
		StringBuilder builder = new StringBuilder();
		boolean first = true;
		for (var entry : rules.entrySet()) {
			if (!first) builder.append(", ");
			first = false;
			builder.append(entry.getKey().displayName()).append(": ").append(entry.getValue());
		}
		return builder.toString();
	}

	/**
	 * Shared clause appended to every "before you commit" message pregen/rewipe/expand show a
	 * player - the threshold-confirmation prompt above {@code CONFIRM_THRESHOLD_CHUNKS}, {@link
	 * #preview}'s dry run, {@link #rewipe}'s always-required confirmation prompt, and {@link
	 * #expandPreview}'s dry run. Before this, only the "Started..." message AFTER a job actually
	 * began showed what staged rules would apply - a player who staged {@code force_on}/{@code
	 * force_off} via {@code /oceancanvas structurerules set} and then checked a dry run or read a
	 * confirmation prompt first (exactly the "review before you commit" workflow those prompts
	 * exist for) had no way to see whether their staged rule would actually take effect until
	 * after the job had already started. Empty string when nothing is staged, so a player who
	 * never touches {@code structurerules} sees no new text at all - this only surfaces what
	 * would otherwise silently apply.
	 */
	private static String pendingStructureRulesClause() {
		var rules = snapshotPendingStructureRules();
		return rules.isEmpty() ? "" : " Staged structure rules that would apply: " + describeStructureRuleMap(rules) + ".";
	}

	/** A defensive copy - a Job's own rule set must never change after it starts. */
	private static java.util.Map<net.oceancanvas.mod.worldgen.OceanCanvasStructureKind,
			net.oceancanvas.mod.config.StructureOverride> snapshotPendingStructureRules() {
		return pendingStructureRules.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(pendingStructureRules);
	}

	/**
	 * What the currently-running job's staged structure rules (see above)
	 * say about one structure kind at one chunk position, or {@code
	 * INHERIT} if nothing applies - no job running, the job staged no
	 * rules, the position falls outside the job's own bounds/explicit
	 * chunk set, or it falls inside an expand job's excluded (already-
	 * good, untouched) interior. Consulted by {@link
	 * net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones#structureOverrideAt}
	 * as the fallback beneath a region's own explicit rule - a region's
	 * own rule always wins over a staged job-level one, matching how a
	 * region's rule already wins over the plain world default.
	 */
	public static synchronized net.oceancanvas.mod.config.StructureOverride
			activeJobStructureOverride(net.oceancanvas.mod.worldgen.OceanCanvasStructureKind kind, int chunkX, int chunkZ) {
		Job job = currentJob();
		if (job == null || job.structureRules.isEmpty()) {
			return net.oceancanvas.mod.config.StructureOverride.INHERIT;
		}
		if (chunkX < job.minChunkX || chunkX > job.maxChunkX || chunkZ < job.minChunkZ || chunkZ > job.maxChunkZ) {
			return net.oceancanvas.mod.config.StructureOverride.INHERIT;
		}
		if (!job.explicitChunks.isEmpty() && !job.explicitChunks.contains(ChunkPos.pack(chunkX, chunkZ))) {
			return net.oceancanvas.mod.config.StructureOverride.INHERIT;
		}
		if (chunkX >= job.excludeMinChunkX && chunkX <= job.excludeMaxChunkX
				&& chunkZ >= job.excludeMinChunkZ && chunkZ <= job.excludeMaxChunkZ) {
			// Excluded interior (expand only) - already-good terrain this job
			// deliberately never touches; a staged rule has no business there.
			return net.oceancanvas.mod.config.StructureOverride.INHERIT;
		}
		return job.structureRules.getOrDefault(kind, net.oceancanvas.mod.config.StructureOverride.INHERIT);
	}


	/**
	 * v237 structure-operation atomicity guard. Structure metadata and physical
	 * relocation are structure-wide operations: mutating a start that merely
	 * touches an explicitly selected Region would silently reach outside the
	 * user's operation. While a job is active, require its effective chunk mask
	 * to contain the structure's entire X/Z footprint. With no active job this is
	 * intentionally permissive so ordinary loaded-chunk maintenance can converge.
	 *
	 * <p>This is read-only introspection of the current job. It does not enqueue,
	 * load, ticket, advance, pause or otherwise change the proven scheduler.</p>
	 */
	/** v253.61.11 block-exact operation scope. Chunk selection is transport; this is the
	 * authoritative X/Z mutation mask used by the carver, physical repair, entity cleanup
	 * and finalizer. Outside an active job we preserve the historical whole-canvas behavior. */
	public static synchronized boolean activeJobColumnInMutationScope(int chunkX, int chunkZ, int blockX, int blockZ) {
		Job job = currentJob();
		if (job == null) return true;
		return job.includesChunkForWork(chunkX, chunkZ) && job.includesBlockForWork(blockX, blockZ);
	}

	/** True when the active job owns all 256 columns of this chunk. Partial edge chunks
	 * must never receive a whole-chunk Canvas completion seal. */
	public static synchronized boolean activeJobFullyCoversChunkBlocks(int chunkX, int chunkZ) {
		Job job = currentJob();
		if (job == null) return true;
		if (!job.includesChunkForWork(chunkX, chunkZ)) return false;
		int minX = chunkX * 16, minZ = chunkZ * 16;
		return job.includesBlockForWork(minX, minZ)
				&& job.includesBlockForWork(minX + 15, minZ)
				&& job.includesBlockForWork(minX, minZ + 15)
				&& job.includesBlockForWork(minX + 15, minZ + 15);
	}

	/** Used by the light finalizer to wait until every adjacent Pregen target that can
	 * still mutate terrain has physically retired before publishing a durable light certificate. */
	public static synchronized boolean activePregenJobIncludesChunk(int chunkX, int chunkZ) {
		Job job = currentJob();
		return job != null && isPregenKind(job.kind) && job.includesChunkForWork(chunkX, chunkZ);
	}

	/**
	 * True only when this chunk is in a persisted/deferred replay lane that can
	 * mutate terrain independently of the normal live target-ownership set.
	 *
	 * <p>v253.69.1: do NOT classify every untouched chunk ahead of the row-major
	 * cursor as an active terrain mutator. The lighting finalizer already checks
	 * OceanCanvasSurfaceFlattener's PREGEN_TARGET_CHUNKS before calling this helper,
	 * so admitted/in-flight neighbors are still a hard quiescence barrier. Treating
	 * future-only chunks as blockers created a circular wait at light high-water:
	 * lighting waited for future terrain, while light backpressure prevented that
	 * future terrain from ever being admitted. Later ordinary CANVAS carving only
	 * removes opacity, and verified-neighbor stability/rearm rules cover the boundary
	 * lifecycle, so future-only chunks must not pin the current light cohort.
	 */
	public static synchronized boolean activePregenJobChunkMayStillMutate(int chunkX, int chunkZ) {
		// v253.125.23: retained for bridge/API compatibility, but future replay/deferred
		// coordinates are no longer a lighting barrier. All such work is required to
		// pass requestPregenTargetLoad(), which establishes PREGEN_TARGET_CHUNKS ownership,
		// before terrain mutation can occur. The finalizer now barriers directly on that
		// admitted ownership set, avoiding a lighting-headroom <-> future-admission cycle.
		return false;
	}

	/** v253.72 callback from the light finalizer's AUTHORITATIVE_SEND boundary. */
	public static synchronized void onPregenChunkAuthoritativelyCommitted(ServerLevel world, ChunkPos pos) {
		Job job = currentJob();
		if (job == null || job.world != world || !isPregenKind(job.kind)) return;
		job.noteAuthoritativeCommit(ChunkPos.pack(pos.x(), pos.z()));
	}

	/** v253.72 CUSTOM_OR_MODIFIED chunks are explicit non-destructive exemptions and
	 * therefore satisfy crash quarantine without a Canvas light publication. */
	public static synchronized void onPregenRecoveryExemptionCommitted(ServerLevel world, ChunkPos pos) {
		Job job = currentJob();
		if (job == null || job.world != world || !isPregenKind(job.kind)) return;
		job.noteRecoveryExemption(ChunkPos.pack(pos.x(), pos.z()));
	}

	/**
	 * v253.72.5 terrain/light ownership split. A graceful-restart recovery target
	 * that has completed its physical audit and entered the staged finalizer with
	 * {@code allowPhysicalRepair=false} can no longer mutate blocks. Keep its strict
	 * lighting obligation alive, but record that normal terrain admission may
	 * safely continue past this recovery barrier. The marker is intentionally
	 * session-only and is never treated as an authoritative lighting commit; a
	 * hard crash therefore remains fail-closed at the existing committed cursor.
	 */
	public static synchronized void onPregenRecoveryLightOnlyEnteredFinalizer(ServerLevel world, ChunkPos pos) {
		Job job = currentJob();
		if (job == null || job.world != world || !isPregenKind(job.kind)) return;
		job.noteRecoveryLightOnlyFinalizer(ChunkPos.pack(pos.x(), pos.z()));
	}

	public static synchronized boolean activeJobCoversStructureFootprint(
			net.minecraft.world.level.levelgen.structure.BoundingBox box) {
		if (box == null) return true;
		Job job = currentJob();
		if (job == null) return true;
		// v253.61.11: chunk containment alone is insufficient for a block-radius
		// operation. A structure can live in an intersecting edge chunk while its
		// actual bounding box extends beyond the user's exact X/Z selection. Never
		// relocate or rewrite such a structure as part of this job.
		if (!job.includesBlockForWork(box.minX(), box.minZ())
				|| !job.includesBlockForWork(box.maxX(), box.maxZ())) return false;
		int minCx = Math.floorDiv(box.minX(), 16), maxCx = Math.floorDiv(box.maxX(), 16);
		int minCz = Math.floorDiv(box.minZ(), 16), maxCz = Math.floorDiv(box.maxZ(), 16);
		for (int cz = minCz; cz <= maxCz; cz++) {
			for (int cx = minCx; cx <= maxCx; cx++) {
				if (cx < job.minChunkX || cx > job.maxChunkX || cz < job.minChunkZ || cz > job.maxChunkZ) return false;
				long packed = ChunkPos.pack(cx, cz);
				if (!job.explicitChunks.isEmpty() && !job.explicitChunks.contains(packed)) return false;
				if (cx >= job.excludeMinChunkX && cx <= job.excludeMaxChunkX
						&& cz >= job.excludeMinChunkZ && cz <= job.excludeMaxChunkZ) return false;
			}
		}
		return true;
	}

	private static void onServerTick(MinecraftServer server) {
		if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(server)) {
			net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.logObservedOnce();
			return;
		}
		Job job;
		synchronized (PregenManager.class) {
			job = currentJob();
		}
		if (job == null) {
			return;
		}

		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.pregenEnabled()) {
			OceanCanvas.LOGGER.info(
					"(Ocean Canvas) pregenEnabled was turned off mid-job - stopping the running pregen/rewipe/restore/expand job.");
			// Same "explicit stop clears the persisted snapshot too" choice
			// as #cancel() - see OceanCanvasJobState's class doc.
			job.clearPersisted();
			// Same "commit whatever was recorded so far" choice as
			// #cancel() - see that call site's comment.
			OceanCanvasUndoManager.finishRecording();
			job.hideBossBar();
            if(isPregenKind(job.kind())) {
                OceanCanvasSurfaceFlattener.cancelQueuedPregenWork(job.world);
                OceanCanvasSurfaceFlattener.cancelPendingLightFinalization(job.world);
            }
            OceanCanvasPregenQueue.finished(job.world,job.queueEntryId,false);
			OceanCanvasActionLog.recordLifecycle(job.world, job.kind(), "CANCELLED", job.requesterPlayer(),
					"Stopped because pregenEnabled was turned off: " + job.describeProgress());
			net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.verify(job.world,"CANCELLED_DISABLED");
			net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(job.world)
					.capture(job.world, "after-" + job.kind() + "-disabled", "Operation stopped because pregenEnabled was disabled");
			if (("region-rewipe".equals(job.kind()) || "region-reset".equals(job.kind()))) {
				net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(job.world)
						.reprotectContainedZones(job.minChunkX, job.maxChunkX, job.minChunkZ, job.maxChunkZ);
			}
			synchronized (PregenManager.class) {
				setCurrentJob(null);
			}
			return;
		}

		boolean done;
        long controllerStartedNanos=System.nanoTime();
		try {
			done = job.tick(config.pregenChunksPerTick());
		} catch (Throwable failure) {
			OceanCanvas.LOGGER.error("(Ocean Canvas) {} job failed; checkpoint retained for recovery.", job.kind(), failure);
			net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureFailure(
					job.world, job.kind() + " job failed", failure);
			OceanCanvasActionLog.recordLifecycle(job.world, job.kind(), "FAILED", job.requesterPlayer(),
					failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
			net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.verify(job.world,"FAILED");
			net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(job.world)
					.capture(job.world, "after-" + job.kind() + "-failure", "Operation failed; checkpoint retained");
			job.persist();
			job.hideBossBar();
			if (isPregenKind(job.kind())) OceanCanvasSurfaceFlattener.releaseAllFinalDrainTickets(job.world);
			OceanCanvasUndoManager.finishRecording();
			OceanCanvasPregenQueue.finished(job.world, job.queueEntryId, false);
			synchronized (PregenManager.class) { setCurrentJob(null); }
			return;
		} finally {
            net.oceancanvas.mod.diagnostic.OceanCanvasWorkloadPhaseProfiler.record("pregen.controller",System.nanoTime()-controllerStartedNanos);
        }
        job.samplePerformance();
		if (done) {
			if (("region-rewipe".equals(job.kind()) || "region-reset".equals(job.kind())) && !job.explicitChunks.isEmpty()) {
				// The chunk-carving stage is complete, but FORCE_ON structures can have
				// their own staged work (notably monuments). Queue those exactly once,
				// then keep THIS SAME rewipe job/boss bar alive until the staged work is
				// finished. This makes Rewipe progress mean "the region is actually done",
				// not merely "all chunks were submitted".
				if (!job.rewipeStructuresQueued) {
					net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones zones =
							net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(job.world);
					if (!job.scopeName.isBlank()) {
						var zone = zones.zoneByName(job.scopeName);
						if (zone != null) {
							job.rewipeZoneNames.add(zone.name());
							net.oceancanvas.mod.worldgen.ModStarterStructures
									.ensureForcedStructuresForRegion(job.world, zone, job.structureRules, "Rewipe");
						}
					} else {
						for (net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone : zones.all()) {
							if (job.explicitChunks.containsAll(zone.exactChunks())) {
								job.rewipeZoneNames.add(zone.name());
								net.oceancanvas.mod.worldgen.ModStarterStructures
										.ensureForcedStructuresForRegion(job.world, zone, job.structureRules, "Rewipe");
							}
						}
					}
					job.rewipeStructuresQueued = true;
				}

				java.util.Optional<net.oceancanvas.mod.worldgen.ModStarterStructures.ForcedStructureProgress> forcedStructure =
						net.oceancanvas.mod.worldgen.ModStarterStructures.forcedStructureProgress(job.world, job.rewipeZoneNames);
				if (forcedStructure.isPresent()) {
					var progress = forcedStructure.get();
					job.updateRewipePostProcessProgress(progress.stage(), progress.completedChunks(), progress.totalChunks());
					return;
				}
			}
			if (isPregenKind(job.kind())) {
				// v105.1: Shipwreck=Always is a generation rule, not merely a
				// preservation rule. Once every target chunk is physically confirmed,
				// run the same vanilla-grid distribution pass already used by Rewipe for
				// each FORCE_ON zone fully covered by this pregen. Keep this same job and
				// boss bar alive until the staged placement-cell pass is finished, so
				// 100% means both terrain AND requested shipwreck distribution are done.
				if (!job.pregenStructuresQueued) {
					var zones = net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(job.world);
					if ("region-pregen".equals(job.kind()) && !job.scopeName.isBlank()) {
						var zone = zones.zoneByName(job.scopeName);
						if (zone != null) {
							job.pregenStructureZoneNames.add(zone.name());
							net.oceancanvas.mod.worldgen.ModStarterStructures
									.ensureForcedStructuresForPregenRegion(job.world, zone, job.structureRules);
						}
					} else {
						for (net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone : zones.all()) {
							if (!job.fullyCoversZone(zone)) continue;
							job.pregenStructureZoneNames.add(zone.name());
							net.oceancanvas.mod.worldgen.ModStarterStructures
									.ensureForcedStructuresForPregenRegion(job.world, zone, job.structureRules);
						}
					}
					job.pregenStructuresQueued = true;
				}

				java.util.Optional<net.oceancanvas.mod.worldgen.ModStarterStructures.ForcedStructureProgress> forcedShipwreck =
						net.oceancanvas.mod.worldgen.ModStarterStructures.forcedStructureProgress(job.world, job.pregenStructureZoneNames);
				if (forcedShipwreck.isPresent()) {
					var progress = forcedShipwreck.get();
					job.updatePregenPostProcessProgress(progress.stage(), progress.completedChunks(), progress.totalChunks());
					return;
				}
			}

			// v125: the world-default "Always" (see OceanCanvasStructureKind#globalDefault and
			// /oceancanvas structurerules) gets its own forced-placement pass here for every PLAIN
			// (non-region) job kind - pregen, rewipe/reset, AND expand alike, closing the exact gap
			// the "pregen/rewipe/expand should behave the same" request identified: before this,
			// only a plain pregen ever queued any active structure-forcing pass at all (shipwreck
			// only, and only for a real drawn zone's own rule - see the isPregenKind block above),
			// while a plain rewipe or expand covering the very same area queued nothing. Scoped to
			// this job's own chunk bounds via ModStarterStructures#ensureWorldDefaultForcedStructures,
			// which excludes any real zone with its own explicit rule for a kind - "region always
			// wins over world default", the same precedence carve-time resolution already uses.
			// Region-shaped jobs (region-pregen/region-rewipe/region-reset) are untouched by this -
			// job.explicitChunks is only ever non-empty for those, and they already commit their
			// own region's rules atomically via the two blocks above.
			if (job.explicitChunks.isEmpty()) {
				if (!job.worldDefaultStructuresQueued) {
					String jobLabel = "job-" + job.kind() + "-" + job.minChunkX + "_" + job.minChunkZ;
					for (String suffix : new String[]{"-shipwreck", "-ocean_ruin", "-buried_treasure", "-ruined_portal", "-ocean_monument"}) {
						job.worldDefaultStructureNames.add(jobLabel + suffix);
					}
					net.oceancanvas.mod.worldgen.ModStarterStructures.ensureWorldDefaultForcedStructures(
							job.world, jobLabel, job.minChunkX, job.maxChunkX, job.minChunkZ, job.maxChunkZ, job.structureRules);
					job.worldDefaultStructuresQueued = true;
				}

				java.util.Optional<net.oceancanvas.mod.worldgen.ModStarterStructures.ForcedStructureProgress> worldDefaultProgress =
						net.oceancanvas.mod.worldgen.ModStarterStructures.forcedStructureProgress(job.world, job.worldDefaultStructureNames);
				if (worldDefaultProgress.isPresent()) {
					var progress = worldDefaultProgress.get();
					job.updateWorldDefaultPostProcessProgress(progress.stage(), progress.completedChunks(), progress.totalChunks());
					return;
				}
			}

			// v253.27: a terrain job is not complete while its staged lighting/client
			// finalization is still active. Query the exact authored bounds: neighboring
			// chunks may be held resident for propagation, but are never promoted into
			// finalization targets merely because they border an authored chunk.
			if (isPregenKind(job.kind()) || isDestructiveRepairKind(job.kind()) || "expand".equals(job.kind())) {
				var lightFinal = OceanCanvasSurfaceFlattener.lightFinalizationDiagnostics(
						job.minChunkX, job.maxChunkX, job.minChunkZ, job.maxChunkZ);
				int globalPendingLight = OceanCanvasSurfaceFlattener.pendingLightSyncCount()
						+ OceanCanvasSurfaceFlattener.pendingPersistedLightAuditCount();
				int globalLightTickets = OceanCanvasSurfaceFlattener.activeLightResidencyTicketCount();
				// The local job count remains useful for progress, but completion also waits
				// for any global restart/audit light recovery work. This keeps the normal
				// completion invariant a real gate instead of an after-the-fact log.
				if (!lightFinal.drained() || globalPendingLight > 0 || globalLightTickets > 0) {
					job.postJobVisualIntegrityCleanStreak = 0;
					job.updateLightingFinalizationProgress(globalPendingLight, globalLightTickets);
					return;
				}

				// v253.35: do not let the diagnostic audit be advisory. Observe the
				// post-ticket-release state the player actually sees. Any loaded bad
				// skylight chunk is requeued into the normal authoritative finalizer,
				// and completion is held until the end state stays clean across three
				// separate server ticks. This closes the exact v253.33 signature where
				// the queue hit zero but the immediate audit still had skyFieldBad=2.
				var visualGate = OceanCanvasSurfaceFlattener.enforcePostJobVisualIntegrityGate(
						job.world, job.minChunkX, job.maxChunkX, job.minChunkZ, job.maxChunkZ, job.kind());
				if (!visualGate.loadedLightingClean()) {
					job.postJobVisualIntegrityCleanStreak = 0;
					var rearmed = OceanCanvasSurfaceFlattener.lightFinalizationDiagnostics(
							job.minChunkX, job.maxChunkX, job.minChunkZ, job.maxChunkZ);
					job.updateLightingFinalizationProgress(rearmed.pendingSync(), rearmed.activeResidencyTickets());
					return;
				}
				job.postJobVisualIntegrityCleanStreak++;
				if (job.postJobVisualIntegrityCleanStreak < Job.POST_JOB_VISUAL_INTEGRITY_CLEAN_STREAK_REQUIRED) {
					job.updateLightingStabilityProgress(job.postJobVisualIntegrityCleanStreak, Job.POST_JOB_VISUAL_INTEGRITY_CLEAN_STREAK_REQUIRED);
					return;
				}
			}

			if (isDestructiveRepairKind(job.kind())) {
				int reprotected = net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(job.world)
						.reprotectContainedZones(job.minChunkX, job.maxChunkX, job.minChunkZ, job.maxChunkZ);
				if (reprotected > 0) {
					OceanCanvas.LOGGER.info("(Ocean Canvas) Re-protected {} repair zone(s) after rewipe completed.", reprotected);
				}
			}
			if ("region-pregen".equals(job.kind())) {
				// New checkpoints persist Region identity separately from the chunk mask.
				// The Region is mutation-locked for the entire job, so named completion
				// is exact without re-materializing a million-key rectangle.
				var zones = net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(job.world);
				if (!job.scopeName.isBlank()) {
					zones.protectForPregen(job.scopeName);
				} else {
					// Legacy checkpoint fallback: old saves had only an explicit mask.
					for (net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone : zones.all()) {
						java.util.LinkedHashSet<Long> canvasPortion = new java.util.LinkedHashSet<>(zone.exactChunks());
						OceanCanvasConfig completionConfig = OceanCanvasConfig.get();
						int canvasMinChunkX = Math.floorDiv(completionConfig.centerX() - completionConfig.radius(), 16);
						int canvasMaxChunkX = Math.floorDiv(completionConfig.centerX() + completionConfig.radius() - 1, 16);
						int canvasMinChunkZ = Math.floorDiv(completionConfig.centerZ() - completionConfig.radius(), 16);
						int canvasMaxChunkZ = Math.floorDiv(completionConfig.centerZ() + completionConfig.radius() - 1, 16);
						canvasPortion.removeIf(packed -> {
							int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
							return cx < canvasMinChunkX || cx > canvasMaxChunkX || cz < canvasMinChunkZ || cz > canvasMaxChunkZ;
						});
						if (canvasPortion.equals(job.explicitChunks)) { zones.protectForPregen(zone.name()); break; }
					}
				}
			}
			if (isPregenKind(job.kind())) {
				job.commitCalibration();
				// Defensive cleanup: a successful job must leave no transient
				// final-drain FORCED tickets behind to inflate exploration/save cost.
				OceanCanvasSurfaceFlattener.releaseAllFinalDrainTickets(job.world);
				assertPregenTransientCleanup(job.world, "normal-completion");
			}
			if("RECT".equals(job.scopeShape) && !job.scopeName.isBlank())
				net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.recordCompleted(job.world,job.kind(),job.minChunkX,job.maxChunkX,job.minChunkZ,job.maxChunkZ,"");
			else if(!job.explicitChunks.isEmpty())net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.recordCompletedChunks(job.world,job.kind(),job.explicitChunks,"");
			else if("expand".equals(job.kind()))net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.recordCompletedRing(job.world,job.kind(),job.minChunkX,job.maxChunkX,job.minChunkZ,job.maxChunkZ,job.excludeMinChunkX,job.excludeMaxChunkX,job.excludeMinChunkZ,job.excludeMaxChunkZ,"");
			else net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.recordCompleted(job.world,job.kind(),job.minChunkX,job.maxChunkX,job.minChunkZ,job.maxChunkZ,"");
			job.reportDone();
			// Finished normally - nothing left to resume.
			job.clearPersisted();
			OceanCanvasUndoManager.finishRecording();
			job.hideBossBar();
            OceanCanvasPregenQueue.finished(job.world,job.queueEntryId,true);
			OceanCanvasActionLog.recordLifecycle(job.world, job.kind(), "COMPLETED", job.requesterPlayer(),
					job.describeProgress());
			net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.verify(job.world,"COMPLETED");
			net.oceancanvas.mod.project.OceanCanvasHarnessService.recordOperationObservation(job.world,job.kind(),job.describeProgress());
			net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(job.world)
					.capture(job.world, "after-" + job.kind() + "-complete", "Operation completed");
			synchronized (PregenManager.class) {
				setCurrentJob(null);
			}
		}
	}

	/** v253.69.7: resolve a persisted region-scoped mask back to its current Region identity once on resume. */
	private static String regionNameForExactMask(ServerLevel world, java.util.Set<Long> mask) {
		if (world == null || mask == null || mask.isEmpty()) return "";
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int minX = Math.floorDiv(config.centerX() - config.radius(), 16);
		int maxX = Math.floorDiv(config.centerX() + config.radius() - 1, 16);
		int minZ = Math.floorDiv(config.centerZ() - config.radius(), 16);
		int maxZ = Math.floorDiv(config.centerZ() + config.radius() - 1, 16);
		for (var zone : net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).all()) {
			java.util.LinkedHashSet<Long> clipped = new java.util.LinkedHashSet<>(zone.exactChunks());
			clipped.removeIf(packed -> {
				int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
				return cx < minX || cx > maxX || cz < minZ || cz > maxZ;
			});
			if (clipped.equals(mask)) return zone.name();
		}
		return "";
	}

	/**
	 * One in-progress pregen request. Not thread-safe on its own - always
	 * accessed under PregenManager's monitor.
	 *
	 * <p><b>Known, deliberate v1 limitation:</b> {@link #tick} processes
	 * the region strictly in order and never skips ahead to a later
	 * position while an earlier one is still loading - so a single slow
	 * chunk load can bottleneck the whole job to well under {@code
	 * pregenChunksPerTick} for a tick or two, rather than always fully
	 * using the budget on whichever chunks happen to be ready first. This
	 * is a deliberate simplicity/safety tradeoff for a first drafted
	 * version (correctness and a hard rate cap over throughput) - a
	 * sliding-window version that tracks several in-flight requests at
	 * once would pregen faster, but is real added complexity worth
	 * doing as a follow-up once this simpler version is confirmed
	 * working, not bundled into the same first test.</p>
	 */
	private static final class Job {
		private final ServerLevel world;
		private final int minChunkX;
		private final int maxChunkX;
		private final int minChunkZ;
		private final int maxChunkZ;
		private final int rowWidth;
		private final long totalChunks;
		// What's actually reported to the user (progress messages, the
		// "Started..." chunk count) - equal to totalChunks for ordinary
		// pregen/rewipe jobs (no exclusion, so zero behavior/message
		// change there), but the RING size only for an expand job - see
		// the constructor's doc comment. totalChunks itself stays the
		// full outer-square size because tick()'s cursor bound
		// (nextIndex < totalChunks) needs to walk the full square,
		// excluded interior included, to reach every real ring chunk -
		// reporting that raw number to the user would make an expand
		// job's progress percentage look stuck near 0% for a large
		// canvas, since the excluded interior can vastly outnumber the
		// actual ring being flattened.
		private final long reportedTotalChunks;
		private final UUID requestedBy;

		// Optional "already-good, don't touch" rectangle, used only by
		// PregenManager#expand - see that method's doc for why this
		// exists and why it's an O(1) arithmetic jump in tick() below,
		// not a chunk-by-chunk skip loop. minChunkX > maxChunkX (an
		// empty/invalid range) means "exclude nothing" - the sentinel
		// the 5-arg constructor below passes, guaranteeing zero behavior
		// change for the existing pregen/rewipe/restore/expand call sites.
		private final int excludeMinChunkX;
		private final int excludeMaxChunkX;
		private final int excludeMinChunkZ;
		private final int excludeMaxChunkZ;

		// "pregen"/"rewipe"/"expand" - purely for an accurate resumed-job
		// log/chat message (see PregenManager#onLevelLoad) and the
		// persisted snapshot; the actual carving behavior is identical
		// regardless of kind, see the class doc.
		private final String kind;
		// v253.69.7: authoritative Region identity for region-scoped operations.
		// The live map must not guess this from a moving cursor because overlapping
		// Regions/polygon envelopes can make the cursor belong to a different Region.
		private String scopeName="";
		// RECT region jobs keep an arithmetic mask and never persist/materialize millions of Long keys.
		private String scopeShape="";
        private String queueEntryId="";
		private final java.util.Set<Long> explicitChunks;
		// v253.61.11: exact inclusive block mask for radius-based operations. The chunk
		// rectangle remains only a loading/indexing envelope; edge chunks may be partial.
		private final int operationMinBlockX;
		private final int operationMaxBlockX;
		private final int operationMinBlockZ;
		private final int operationMaxBlockZ;
		// Region Rewipe has a second, staged post-processing phase for FORCE_ON
		// structures. Keep enough transient state to queue it once and to keep
		// the reset boss bar alive until that work is finished. On a server
		// restart these intentionally reset to false/empty; the resumed reset can
		// safely re-evaluate FORCE_ON and queue any still-missing structure.
		private boolean rewipeStructuresQueued;
		private final java.util.Set<String> rewipeZoneNames = new java.util.LinkedHashSet<>();
		// v105.1: Pregen now has its own staged FORCE_ON shipwreck completion
		// phase. These are intentionally transient like the Rewipe fields above:
		// after a restart the deterministic/idempotent distribution pass is simply
		// re-evaluated and any already-present/protected ships are retained.
		private boolean pregenStructuresQueued;
		private final java.util.Set<String> pregenStructureZoneNames = new java.util.LinkedHashSet<>();
		// v125: the world-default "Always" queues its own forced-placement pass for a PLAIN
		// (non-region) pregen/rewipe/expand job, scoped to that job's own chunk range - same
		// transient, restart-safe treatment as the two region-scoped fields above. Tracked by the
		// exact synthetic zone names ModStarterStructures#ensureWorldDefaultForcedStructures used,
		// not real zone names, since there is no real drawn zone behind this pass.
		private boolean worldDefaultStructuresQueued;
		private final java.util.Set<String> worldDefaultStructureNames = new java.util.LinkedHashSet<>();

		// Cursor into the row-major scan, as a single index rather than
		// two counters - keeps "how far through are we" a single cheap
		// division/modulo instead of tracked separately, and avoids ever
		// materializing the (potentially six-figure) full list of
		// ChunkPos up front.
		private long nextIndex;
		private long submittedCount;
		private int ticksSinceReport;
		private int ticksSinceQueueDiagnosticLog;
		private int ticksSinceActionBar;
		// v253.35: completion now requires three consecutive clean post-release
		// visual-integrity gates. v253.33 proved that the staged queue could drain,
		// then two loaded chunks immediately regress to bad skylight and still be
		// reported as complete. Reset this streak whenever lighting is re-armed.
		private int postJobVisualIntegrityCleanStreak;
		private boolean lightingBackpressureActive;
		private static final int POST_JOB_VISUAL_INTEGRITY_CLEAN_STREAK_REQUIRED = 3;
		// v253.49: bound metadata-only skip scanning so a restart that intentionally
		// replays from cursor 0 cannot walk hundreds of thousands of already-certified
		// chunks in one server tick.
		private static final int MAX_METADATA_SKIPS_PER_TICK = 4096;
	// v253.72 crash durability. Submission is not commitment: the main cursor may be
	// thousands of chunks ahead of the last contiguous physical+lighting publication.
	// A hard crash rewinds to the committed frontier and also distrusts a conservative
	// tail behind it, closing the non-transactional SavedData/region-file gap. A clean
	// Save & Quit uses a smaller recent tail because the v253.71.1 rejoin log proved
	// that even orderly serialization can persist stale SKY arrays for recently-live
	// chunks while the job itself is being detached.
	private static final int PREGEN_CRASH_COMMITTED_SAFETY_TAIL_INDICES = 8192;
	private static final int PREGEN_CLEAN_STOP_REPLAY_TAIL_INDICES = 8192;
	private static final int PREGEN_JOURNAL_FALLBACK_TAIL_INDICES = 512;
	private static final int MAX_COMMIT_ADVANCE_PER_TICK = 4096;
		private static final int MAX_CURSOR_STEPS_PER_TICK = 8192;
		// v253.73.9/73.10 forever-world resume guarantee. While a graceful restart
		// carries a large journal-proven LIGHT_ONLY debt above the emergency light
		// watermark, one unrelated NORMAL terrain target may be admitted only after
		// this much proven active-debt retirement. A terrain mutation can dirty its
		// center plus up to eight boundary neighbors, so 16:1 preserves a real
		// worst-case safety margin while preventing the physical Pregen frontier from
		// remaining at 0 chunks/s indefinitely.
		// v253.125.19: lighting debt is mandatory completion work, not permission to
		// starve never-submitted terrain. At true emergency pressure, the proven conservative retirement quantum
		// still buys each terrain slot; below emergency a separate <=1/tick
		// terrain lane stays open and lets the adaptive tick/heap controller decide.
		// Historical compatibility alias retained for the older regression contract.
		// v253.125.35 no longer spends gross publications as admission currency.
		private static final int FOREVER_WORLD_EMERGENCY_PUBLISHES_PER_TERRAIN_ADMISSION = 16;
		private static final int FOREVER_WORLD_EMERGENCY_NET_DEBT_REDUCTIONS_PER_TERRAIN_ADMISSION =
				FOREVER_WORLD_EMERGENCY_PUBLISHES_PER_TERRAIN_ADMISSION;
		// v253.73.11 proactive Forever World supervisor. One terrain mutation can
		// dirty the center plus eight neighbors. A continuity pulse is permitted only
		// when the emergency window has enough unused active capacity for that entire
		// worst-case fan-out plus an additional guard band. This creates a bounded
		// liveness escape that cannot itself cross the 2x-emergency hard ceiling.
		private static final int FOREVER_WORLD_MAX_LIGHT_FANOUT_PER_TERRAIN = 9;
		private static final int FOREVER_WORLD_CONTINUITY_HEADROOM_GUARD = 16;
		private static final long FOREVER_WORLD_CONTINUITY_STALL_TICKS = 400L;
		private static final long FOREVER_WORLD_CONTINUITY_PULSE_COOLDOWN_TICKS = 400L;
		// Historical journal-proven LIGHT_ONLY recovery is correctness debt, but it
		// must never become the foreground workload of a mature Forever World. Keep a
		// small active window and reserve most admission capacity for never-submitted
		// terrain. Two replay admissions/tick is still ~40/s when capacity is available,
		// enough to cycle a 30k recovery ledger in minutes without flooding lighting.
		private static final int FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW = 128;
		private static final int FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ADMISSIONS_PER_TICK = 2;
		private static final int FOREVER_WORLD_LIGHT_ONLY_PRESSURE_PARK_PER_TICK = 512;
		// v253.73.13: keep PHYSICAL_AWARE historical finalizer debt below the same
		// global low-water regime. 64 physical + 128 light-only leaves 64 entries of
		// spare capacity below the 256 low-water mark and 320 below high-water.
		private static final int FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW = 64;
		private static final int FOREVER_WORLD_PHYSICAL_RECOVERY_ADMISSIONS_PER_TICK = 2;
		private static final int FOREVER_WORLD_PHYSICAL_PRESSURE_PARK_PER_TICK = 512;
		private static final int FOREVER_WORLD_RECOVERY_TRACKING_REPAIR_LOG_THRESHOLD = 16;
		private static final int FOREVER_WORLD_RECOVERY_TRACKING_RECONCILE_PERIOD_TICKS = 200;
		private static final long FOREVER_WORLD_THROUGHPUT_RISK_HOLD_NS = 60_000_000_000L;
		/**
		 * v253.73.16. How long the global light backpressure latch may stay closed
		 * while it is provably safe to admit at least one terrain target and the job
		 * cursor has not moved at all. The v253.73.15 resume mark (highWater - fanout*7
		 * = 449 at the shipped constants) cleared the specific 446 fixed point the
		 * v253.73.14 runtime happened to settle at, by three entries. It does nothing
		 * for any fixed point inside the remaining 450..511 dead band, which is the
		 * same permanent stall with different numbers. This escape closes the class:
		 * the predictive fanout clamp already guarantees a reopened admission cannot
		 * reach high water, so a latch that outlives real progress has no safety role
		 * left to play.
		 */
		private static final long FOREVER_WORLD_LIGHT_BACKPRESSURE_STALL_NS = 30_000_000_000L;

		// v120 two-phase pregen, generalized in v121. "GENERATE" (Phase 1: raw
		// chunk generation, no carving) or "CARVE" (Phase 2: the pre-v120
		// carve pipeline, completely unchanged - see tick()'s branch at the
		// top and tickGenerate()'s class doc for the full rationale). Starts
		// as "GENERATE" for every kind usesTwoPhaseGeneration() returns true
		// for (pregen/region-pregen since v120; rewipe/region-rewipe/reset/
		// region-reset/expand since v121 - see that method's doc for why the
		// v120 exclusion of those kinds didn't hold up). Any other kind stays
		// "CARVE" for its entire life, matching pre-v120 behavior exactly.
		private String pregenPhase = "CARVE";

		/**
		 * v121: structure rules staged via {@code /oceancanvas structurerules}
		 * (see {@link PregenManager#pendingStructureRules}) at the moment this
		 * job was started - an immutable snapshot, never live-updated, so a
		 * later {@code structurerules set} while this job is already running
		 * can only affect the NEXT job, not silently change this one's rules
		 * mid-run. Empty for every job that isn't a plain radius-based
		 * pregen/rewipe/expand (region-pregen/region-rewipe intentionally keep
		 * using the region's OWN rules instead - see {@code
		 * PregenManager#startRegionInternal}'s "commit point" doc - so this
		 * stays {@code Map.of()} for those kinds and this field is simply
		 * never consulted for them). Read only through {@link
		 * PregenManager#activeJobStructureOverride}.
		 */
		private java.util.Map<net.oceancanvas.mod.worldgen.OceanCanvasStructureKind,
				net.oceancanvas.mod.config.StructureOverride> structureRules = java.util.Map.of();
		// Phase 1's own bounded set of in-flight raw-generation targets -
		// deliberately separate from PREGEN_TARGET_FUTURES/PREGEN_TARGET_CHUNKS
		// so Phase 1 never triggers markPregenTarget's self-ticket/carving
		// hookup. A chunk that happens to get carved anyway via the ordinary
		// organic onChunkLoad->PENDING_CHUNKS path while Phase 1 runs is
		// harmless (just work done slightly early), so this set's only job is
		// bounding Phase 1's own concurrency and knowing when every target has
		// actually loaded.
		//
		// v207: this used to be a Map<Long, CompletableFuture<?>>, tracking
		// completion via world.getChunkSource().getChunkFuture(...,false).
		// A real thread dump proved that call is NOT safely non-blocking
		// merely because require=false - it can and did synchronously
		// execute real main-thread work (see
		// OceanCanvasSurfaceFlattener#requestPregenTargetLoad's v207 doc for
		// the full evidence). This loop is the single highest-volume caller
		// of that pattern in the whole project (Phase 1 submits every target
		// in the run, up to millions of chunks), so it was the single
		// highest-risk site once the assumption behind it was disproven.
		// Fixed by tracking outstanding targets as plain packed positions and
		// checking real residency via getChunkNow (the same atomic,
		// already-proven-safe primitive v182 introduced for this exact
		// purpose elsewhere in the project) instead of ever creating a
		// future here at all.
		private final OceanCanvasPrimitiveLongSet generateOutstanding = new OceanCanvasPrimitiveLongSet();

		// Adaptive C2ME feed controller. pregenChunksPerTick is the STARTING
		// request rate, not a permanent ceiling. Every ~1 second the controller
		// compares submitted work with work that actually drained through the
		// OceanCanvas pipeline, then raises/lowers the feed rate accordingly.
		private int adaptivePregenRate;
		private int adaptiveTicks;
		// v210: fractional recovery admission. rate=1/tick is still ~20 chunks/s,
		// so the old "retirement-paced" ramp could not represent the observed 1-3 chunks/s drain.
		private int recoveryAdmissionTick;
		private long adaptiveLastSubmitted;
		private long adaptiveLastSkipped;
		private long adaptiveLastWindowNanos;
		// v253.72.9: controller sampling remains 1 Hz, but an idle/blocked inlet no
		// longer writes identical adaptive INFO lines every second.
		private long adaptiveLastInfoLogNanos;
		private int adaptiveLastOutstanding;
		private long pregenWatchdogLastSettled = -1L;
		private long pregenWatchdogLastProgressNanos = System.nanoTime();
		private long pregenWatchdogLastRecoveryNanos;
		private long pregenWatchdogLastLogNanos;
		private long pregenWatchdogLastDeferNanos;
		private long resumeBootstrapUntilNanos;
		private long adaptivePauseStartedNanos;
		// v253.125.33 post-spike recovery cooldown. Session-only flow control; it
		// never changes completion state and never drops queued correctness debt.
		private long pregenRuntimePressureCooldownUntilNanos;
		private boolean pregenRuntimePressureCooldownLogged;
		private long pregenRuntimePressureLastLogNanos;
		private long adaptiveLastProbeNanos;
		// v106 neighbor-stall admission breaker. Sampled at most once/second
		// (a full pregenQueueDiagnostics pass is a few hundred hasChunk() checks
		// - fine once a second, not something to run every tick). The active
		// flag carries hysteresis across samples deliberately; see the constants'
		// doc comment for why.
		private long pregenNeighborStallSampledNanos;
		private boolean pregenNeighborStallBreakerActive;
		// v202.43 post-neighbor-stall admission ramp. This is intentionally
		// transient/session-only; it controls how quickly we refill after recovery,
		// not authoritative job state.
		private long pregenRecoveryRampUntilNanos;
		private double adaptiveCompletionRateEma;
		private long adaptiveLastPhysicalRetired;
		// v253.73.16 forward-progress companion to the carve-only EMA above. See
		// OceanCanvasSurfaceFlattener#jobForwardRetirementCount for why two counters
		// exist and which question each one answers.
		private double adaptiveForwardRateEma;
		private long adaptiveLastForwardRetired;
		// v253.73.18 net-completion tracking. See updateForeverWorldThroughputPrediction.
		private double adaptiveConfirmedRateEma;
		private long adaptiveLastConfirmed = -1L;
		private long adaptiveLastConfirmedNanos;
		private boolean foreverWorldBacklogRegressionLogged;
		// v253.125.33 hysteresis for the net-completion warning. The .31 soak
		// crossed zero repeatedly and emitted the same warning 30 times; require a
		// sustained regression and rate-limit repeats so diagnostics do not add I/O
		// pressure to an already unhealthy run.
		private long foreverWorldBacklogRegressionSinceNanos;
		private long foreverWorldBacklogRegressionLastLogNanos;
		private long foreverWorldBacklogRecoverySinceNanos;
		// v253.73.16 backpressure stall escape. A latch that can only clear below a
		// fixed resume mark is a permanent stall for any pressure fixed point that
		// settles inside the dead band. These record when the latch closed and what
		// forward progress existed at that moment.
		private long lightingBackpressureSinceNanos;
		private long lightingBackpressureForwardBaseline;
		private int lightingBackpressureStallEscapes;
		// v216 session-local canary baseline. A resumed server process must
		// prove the newly-created ticket/chunk pipeline again.
		private long pregenCanaryBaselineConfirmed = -1L;
		private boolean pregenCanaryUnlockLogged;
		private long pregenCanaryRecoveryBaseline = -1L;
		// v253.72.2 diagnostic latch: log one explicit recovery-escape event per
		// emergency-lighting episode so another zero-throughput circular wait is
		// visible immediately in the runtime log instead of only through the UI.
		private boolean lightingEmergencyRecoveryEscapeLogged;
		// v253.125.10: emergency admission can flicker between adjacent branches,
		// resetting the boolean latch every tick. Keep an independent wall-clock
		// limiter so a sustained recovery episode cannot emit hundreds of WARN lines.
		private long lightingEmergencyRecoveryEscapeLastLogNanos;
		// v222 wall-time admission bucket. Per-tick limits become unsafe while the
		// integrated server is catching up after a stall because Minecraft may run
		// many ticks in far less than one wall-clock second.
		private double pregenAdmissionTokens = 0.0D;
		private long pregenAdmissionTokenNanos;
		// v132.5 diagnostic build: one aggregate ticket-health line per second,
		// independent of whether adaptive admission is currently paused.
		private long pregenTicketHealthLoggedNanos;
		// v212 proactive controller + rolling flight recorder. The recorder is
		// session-only and bounded to the last ~60 one-second samples.
		private long pregenProactiveSampledNanos;
		private double pregenProactiveLastStaleRatio;
		private int pregenProactiveRisingSamples;
		private int pregenProactiveNoRetireSamples;
		private long pregenProactiveLastConfirmed = -1L;
		private boolean pregenProactivePauseActive;
		private String pregenHealthState = "STARTING";
		private long pregenLastFlightDumpNanos;
		private final java.util.ArrayDeque<String> pregenFlightRecorder = new java.util.ArrayDeque<>();
		// v96: bounded resume/recovery lane, maintenance drains, and adaptive
		// outstanding-window control. None of these change completion semantics.
		private final OceanCanvasPrimitiveLongDeque resumeReplayChunks = new OceanCanvasPrimitiveLongDeque();
		// v253.72: contiguous authoritative commit cursor. This advances only when
		// every selected index before it has either reached AUTHORITATIVE_SEND or is
		// an explicit CUSTOM_OR_MODIFIED exemption. It is persisted independently of
		// nextIndex/submittedCount and is the only cursor trusted after a hard crash.
		private long committedNextIndex;
		private long committedSubmittedCount;
		private final LongOpenHashSet authoritativelyCommittedChunks = new LongOpenHashSet();
		// Recovery quarantine remains a strict authoritative-light obligation, but
		// v253.72.5 separates that obligation from terrain admission once every
		// remaining graceful-restart target has irreversibly retired terrain ownership.
		private final LongLinkedOpenHashSet restartRecoveryQuarantinePending = new LongLinkedOpenHashSet();
		private final LongLinkedOpenHashSet restartRecoveryPhysicalPending = new LongLinkedOpenHashSet();
		private final LongOpenHashSet terrainSafeRecoveryFinalizerChunks = new LongOpenHashSet();
		private boolean restartRecoveryQuarantineActive;
		// v253.72.5: once the graceful-restart cohort has proven that every
		// remaining quarantine entry is strictly LIGHT_ONLY, latch terrain
		// admission open. Re-testing outstandingPregenTargetCount()==0 every tick
		// would immediately close the gate again as soon as the first normal batch
		// was submitted, reducing the resumed scan to stop/start waves.
		private boolean restartRecoveryTerrainGateOpen;
		private boolean restartRecoveryTerrainGateOpenLogged;
		// v253.73.5: pure LIGHT_ONLY restart debt may share the existing bounded
		// admission budget with the main/rescan cursor, but only while the strict
		// finalizer is demonstrably retiring authoritative publications. If lighting
		// stops making progress (including a dead light executor), sharing closes
		// automatically within five seconds and the historical fail-closed path wins.
		private long recoveryLightShareLastPublishCount = -1L;
		private long recoveryLightShareLastProgressTick = Long.MIN_VALUE;
		// v253.125.35 debt-negative emergency terrain reserve. Gross publications and
		// active->dormant transitions are not durable progress because boundary rearms
		// or newly-authored terrain can add the same obligations back. Spend one terrain
		// admission only after the authoritative total uncertified-light debt itself has
		// fallen by the proven 16-chunk quantum. This is flow-control state only; it
		// never certifies lighting or changes the recovery journal.
		private int foreverWorldEmergencyTerrainDebtBaseline = -1;
		private boolean foreverWorldEmergencyTerrainReserveLogged;
		private long foreverWorldSupervisorLastSubmittedCount = -1L;
		private long foreverWorldSupervisorLastSubmissionProgressTick = Long.MIN_VALUE;
		private long foreverWorldSupervisorLastPulseTick = Long.MIN_VALUE;
		private long foreverWorldSupervisorPulseCount;
		private boolean foreverWorldSupervisorPreflightLogged;
		private boolean foreverWorldSupervisorStallLogged;
		private long foreverWorldLightOnlyWindowHolds;
		private boolean foreverWorldLightOnlyWindowLogged;
		private long foreverWorldPhysicalWindowHolds;
		private boolean foreverWorldPhysicalWindowLogged;
		private boolean foreverWorldPressureParkLogged;
		private boolean foreverWorldLightHeadroomClampLogged;
		private long foreverWorldThroughputRiskSinceNanos;
		private boolean foreverWorldThroughputRiskLogged;
		// Hard-crash recovery then replays the uncommitted submission range through
		// the normal bounded scanner, but every such chunk is forced through recovery
		// proof even if old metadata claims it is complete. Admission stops exactly at
		// the old saved cursor until those in-range targets have authoritatively retired.
		private final LongLinkedOpenHashSet restartRecoveryRescanPending = new LongLinkedOpenHashSet();
		private boolean restartRecoveryRescanActive;
		private long restartRecoveryRescanEndIndex;
		private long recoveryJournalGeneration = System.currentTimeMillis();
		private long nextMaintenanceAt = PREGEN_MAINTENANCE_INTERVAL_CHUNKS;
		private boolean maintenanceDrainActive;
		private int maintenanceStableTicks;
		private long maintenanceDrainStartedNanos;
		private final OceanCanvasPrimitiveLongDeque deferredPregenChunks = new OceanCanvasPrimitiveLongDeque();
		// v253.125.39: one reusable primitive dedup set for checkpoint construction.
		// The durable List<Long> codecs stay backward-compatible, but six-figure recovery
		// checkpoints no longer allocate multiple LinkedHashSet<Long>/ArrayList<Long> graphs.
		private final LongLinkedOpenHashSet checkpointSetScratch = new LongLinkedOpenHashSet();
		private long lastDeferredRetryNanos;
		private int adaptiveOutstandingTarget;
		private double heapSlopeEma;
		private double previousHeapSample = -1.0D;
        private final long performanceStartedNanos=System.nanoTime();
        private final OceanCanvasPregenMetrics.RateWindow rateWindow=new OceanCanvasPregenMetrics.RateWindow(performanceStartedNanos);
        private long lastPerformanceLightPublishCount=OceanCanvasSurfaceFlattener.lightFinalizationPublishCount();
        private long sessionBaseSubmitted;
        private long skippedProcessed;
        private int lastFeedBudget;
        private String feedReason="Starting";
		private int finalDrainTicks;
		private long lastAdaptiveWallTickNanos;
		private double adaptiveTickMsEma = 50.0D;
		private OceanCanvasResourceBudgetGovernor.Decision lastResourceBudgetDecision = OceanCanvasResourceBudgetGovernor.idle();
        private final String calibrationProfile;

		// A real vanilla boss bar (the same UI raids and the wither fight
		// use) for this job's progress, alongside the existing action-bar
		// text - not instead of it, since the action bar is still the only
		// readout with an exact chunk count. The bar itself is created
		// eagerly in the constructor (cheap, and gives resumed jobs one
		// too), but the PLAYER is only ever added to it lazily, in
		// updateActionBar() below, where the requester's online status is
		// already being resolved every tick for the action-bar send - a
		// job resumed after a server restart has no requester online yet,
		// and this way the bar simply appears the moment they log back in
		// rather than needing a second "is anyone here to show this to"
		// check duplicated from that method.
		private final ServerBossEvent bossEvent;

		Job(ServerLevel world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, UUID requestedBy,
				String kind) {
			this(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ, requestedBy, 1, 0, 1, 0, kind, java.util.Set.of(),
					minChunkX * 16, maxChunkX * 16 + 15, minChunkZ * 16, maxChunkZ * 16 + 15);
		}

		Job(ServerLevel world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, UUID requestedBy,
				String kind, int operationMinBlockX, int operationMaxBlockX, int operationMinBlockZ, int operationMaxBlockZ) {
			this(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ, requestedBy, 1, 0, 1, 0, kind, java.util.Set.of(),
					operationMinBlockX, operationMaxBlockX, operationMinBlockZ, operationMaxBlockZ);
		}

		Job(ServerLevel world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, UUID requestedBy, String kind, java.util.Set<Long> explicitChunks) {
			this(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ, requestedBy, 1, 0, 1, 0, kind, explicitChunks,
					minChunkX * 16, maxChunkX * 16 + 15, minChunkZ * 16, maxChunkZ * 16 + 15);
		}

		Job(ServerLevel world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, UUID requestedBy,
				int excludeMinChunkX, int excludeMaxChunkX, int excludeMinChunkZ, int excludeMaxChunkZ, String kind) {
			this(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ, requestedBy, excludeMinChunkX, excludeMaxChunkX, excludeMinChunkZ, excludeMaxChunkZ, kind, java.util.Set.of(),
					minChunkX * 16, maxChunkX * 16 + 15, minChunkZ * 16, maxChunkZ * 16 + 15);
		}

		Job(ServerLevel world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, UUID requestedBy,
				int excludeMinChunkX, int excludeMaxChunkX, int excludeMinChunkZ, int excludeMaxChunkZ, String kind, java.util.Set<Long> explicitChunks) {
			this(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ, requestedBy, excludeMinChunkX, excludeMaxChunkX, excludeMinChunkZ, excludeMaxChunkZ, kind, explicitChunks,
					minChunkX * 16, maxChunkX * 16 + 15, minChunkZ * 16, maxChunkZ * 16 + 15);
		}

		private Job(ServerLevel world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, UUID requestedBy,
				int excludeMinChunkX, int excludeMaxChunkX, int excludeMinChunkZ, int excludeMaxChunkZ, String kind, java.util.Set<Long> explicitChunks,
				int operationMinBlockX, int operationMaxBlockX, int operationMinBlockZ, int operationMaxBlockZ) {
			this.world = world;
			this.minChunkX = minChunkX;
			this.maxChunkX = maxChunkX;
			this.minChunkZ = minChunkZ;
			this.maxChunkZ = maxChunkZ;
			this.rowWidth = maxChunkX - minChunkX + 1;
			this.totalChunks = (long) rowWidth * (maxChunkZ - minChunkZ + 1);
			this.requestedBy = requestedBy;
			this.excludeMinChunkX = excludeMinChunkX;
			this.excludeMaxChunkX = excludeMaxChunkX;
			this.excludeMinChunkZ = excludeMinChunkZ;
			this.excludeMaxChunkZ = excludeMaxChunkZ;
			this.kind = kind;
			this.operationMinBlockX = operationMinBlockX;
			this.operationMaxBlockX = operationMaxBlockX;
			this.operationMinBlockZ = operationMinBlockZ;
			this.operationMaxBlockZ = operationMaxBlockZ;
			if (isPregenKind(kind)) {
				OceanCanvasSurfaceFlattener.beginPregenSession(world);
			}
			this.calibrationProfile=net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).pregenProfile().name();
			// Critical startup invariant: never duplicate a Region mask on the integrated-server thread.
			// Zone masks are immutable; resume-only mutable sets are private to this Job after construction.
			this.explicitChunks = explicitChunks == null || explicitChunks.isEmpty() ? java.util.Set.of() : explicitChunks;

			long excludedCount = 0;
			int clippedExMinX = Math.max(excludeMinChunkX, minChunkX);
			int clippedExMaxX = Math.min(excludeMaxChunkX, maxChunkX);
			int clippedExMinZ = Math.max(excludeMinChunkZ, minChunkZ);
			int clippedExMaxZ = Math.min(excludeMaxChunkZ, maxChunkZ);
			if (clippedExMinX <= clippedExMaxX && clippedExMinZ <= clippedExMaxZ) {
				excludedCount = (long) (clippedExMaxX - clippedExMinX + 1) * (clippedExMaxZ - clippedExMinZ + 1);
			}
			this.reportedTotalChunks = this.explicitChunks.isEmpty() ? totalChunks - excludedCount : this.explicitChunks.size();

			String verb = switch (kind) {
				case "rewipe", "reset" -> "Rewiping";
				case "region-rewipe", "region-reset" -> "Rewiping region";
				case "region-pregen" -> "Pre-generating region";
				case "expand" -> "Expanding";
				default -> "Pre-generating";
			};
			this.bossEvent = new ServerBossEvent(
					UUID.randomUUID(),
					Component.literal("Ocean Canvas - " + verb + " the canvas"),
					BossEvent.BossBarColor.BLUE,
					BossEvent.BossBarOverlay.PROGRESS
			);
			this.pregenPhase = "CARVE";
			this.structureRules = java.util.Map.of();
		}

		private boolean includesChunkForWork(int cx, int cz) {
			if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) return false;
			long packed = ChunkPos.pack(cx, cz);
			if (!explicitChunks.isEmpty() && !explicitChunks.contains(packed)) return false;
			return !(cx >= excludeMinChunkX && cx <= excludeMaxChunkX
					&& cz >= excludeMinChunkZ && cz <= excludeMaxChunkZ);
		}

		private boolean includesBlockForWork(int x, int z) {
			return x >= operationMinBlockX && x <= operationMaxBlockX
					&& z >= operationMinBlockZ && z <= operationMaxBlockZ;
		}

		/** Row-major index for a packed chunk in this job envelope, or -1 outside it. */
		private long indexForPacked(long packed) {
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) return -1L;
			return (long)(cz - minChunkZ) * (long)rowWidth + (long)(cx - minChunkX);
		}

		private void addRecoveryChunkWithHalo(LongSet out, long packed) {
			int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int nx = cx + dx, nz = cz + dz;
					if (includesChunkForWork(nx, nz)) out.add(ChunkPos.pack(nx, nz));
				}
			}
		}

		private void addReplayTail(LongSet replay, long endExclusive, int tailIndices, boolean withHalo) {
			long end = Math.max(0L, Math.min(totalChunks, endExclusive));
			long start = Math.max(0L, end - Math.max(0, tailIndices));
			for (long index = start; index < end; index++) {
				int cx = minChunkX + (int)(index % rowWidth);
				int cz = minChunkZ + (int)(index / rowWidth);
				if (!includesChunkForWork(cx, cz)) continue;
				long packed = ChunkPos.pack(cx, cz);
				if (withHalo) addRecoveryChunkWithHalo(replay, packed);
				else replay.add(packed);
			}
		}

		/** Build the small persisted uncertainty journal. The large crash safety tail is
		 * reconstructed arithmetically on restart and is intentionally not serialized.
		 *
		 * <p>v253.125.13 separates terrain uncertainty from already-physical lighting
		 * finalization debt. A live light finalizer installs its own bounded radius-1
		 * residency ticket when the exact center is replayed; serializing a permanent
		 * 3x3 replay halo for every light-only entry multiplied 7,786 shutdown entries
		 * into 58,094 restart obligations in the v253.125.12 runtime. Only work that can
		 * still own terrain admission receives the conservative persisted halo.</p> */
		private LongArrayList primitiveSnapshot(LongLinkedOpenHashSet values) {
			LongArrayList out = new LongArrayList(values.size());
			for (LongIterator it = values.iterator(); it.hasNext();) out.add(it.nextLong());
			return out;
		}

		private static void addPrimitiveListToSet(java.util.List<Long> values, LongLinkedOpenHashSet out) {
			if (values instanceof it.unimi.dsi.fastutil.longs.LongList primitive) {
				for (int i = 0; i < primitive.size(); i++) out.add(primitive.getLong(i));
				return;
			}
			for (Long value : values) if (value != null) out.add(value.longValue());
		}

		/** Build the persisted uncertainty journal without materializing boxed Long
		 * sets. The same exact identities/halos are retained; only checkpoint scratch
		 * storage changes. One reusable primitive insertion-ordered set is shared by
		 * the three sequential checkpoint builders and converted to a primitive-backed
		 * List<Long> only after deduplication is complete. */
		private LongArrayList recoveryJournalUncertainChunks(java.util.List<Long> pendingLightSnapshot) {
			LongLinkedOpenHashSet out = checkpointSetScratch;
			out.clear();
			java.util.function.LongConsumer addTerrainUncertain = packed -> {
				// Quarantine/rescan sets were already expanded/classified when armed.
				// Re-expanding them at every checkpoint grows the safety band on each save.
				if (restartRecoveryQuarantinePending.contains(packed) || restartRecoveryRescanPending.contains(packed)) out.add(packed);
				else addRecoveryChunkWithHalo(out, packed);
			};
			resumeReplayChunks.forEachLong(addTerrainUncertain);
			deferredPregenChunks.forEachLong(addTerrainUncertain);
			for (LongIterator it = restartRecoveryQuarantinePending.iterator(); it.hasNext();) out.add(it.nextLong());
			for (LongIterator it = restartRecoveryRescanPending.iterator(); it.hasNext();) out.add(it.nextLong());
			if (isPregenKind(kind)) {
				for (long packed : OceanCanvasSurfaceFlattener.outstandingPregenTargetsSnapshot()) addRecoveryChunkWithHalo(out, packed);
				if (pendingLightSnapshot instanceof it.unimi.dsi.fastutil.longs.LongList primitive) {
					for (int i = 0; i < primitive.size(); i++) {
						long packed = primitive.getLong(i);
						if (includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) out.add(packed);
					}
				} else {
					for (Long value : pendingLightSnapshot) {
						if (value == null) continue;
						long packed = value.longValue();
						if (includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) out.add(packed);
					}
				}
			}
			LongArrayList snapshot = primitiveSnapshot(out);
			out.clear();
			return snapshot;
		}

		/** Persist the exact subset that is allowed to repair physical state. */
		private LongArrayList recoveryJournalPhysicalChunks() {
			LongLinkedOpenHashSet out = checkpointSetScratch;
			out.clear();
			if (isPregenKind(kind)) {
				java.util.List<Long> physical = OceanCanvasSurfaceFlattener.physicalRepairRecoveryChunksSnapshot();
				if (physical instanceof it.unimi.dsi.fastutil.longs.LongList primitive) {
					for (int i = 0; i < primitive.size(); i++) {
						long packed = primitive.getLong(i);
						if (includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) out.add(packed);
					}
				} else {
					for (Long value : physical) {
						if (value == null) continue;
						long packed = value.longValue();
						if (includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) out.add(packed);
					}
				}
			}
			LongArrayList snapshot = primitiveSnapshot(out);
			out.clear();
			return snapshot;
		}

		private boolean indexDurablyCommitted(int cx, int cz, long packed) {
			// Keep the v253.72 crash frontier strictly AUTHORITATIVE. The separate
			// terrainSafeRecoveryFinalizerChunks set is an in-session admission/liveness
			// proof only; it must never advance the durable crash cursor before light
			// publication. A hard crash can therefore rewind behind a still-bad light
			// chunk, which is slower but remains fail-closed.
			if (authoritativelyCommittedChunks.contains(packed)) return true;
			var terrainState = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(new ChunkPos(cx, cz));
			if (terrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED) return true;
			if (terrainState != net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) return false;
			return net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(world).isChunkLightingVerified(new ChunkPos(cx, cz));
		}

		/** Advance only the contiguous authoritative prefix. Out-of-order finalized
		 * chunks are recovered cheaply from durable metadata once an earlier hole closes. */
		private void advanceCommittedFrontier(int budget) {
			if (!isPregenKind(kind) || budget <= 0) return;
			int steps = 0;
			long limit = Math.min(nextIndex, totalChunks);
			while (committedNextIndex < limit && steps++ < budget) {
				long index = committedNextIndex;
				int cx = minChunkX + (int)(index % rowWidth);
				int cz = minChunkZ + (int)(index / rowWidth);
				long packed = ChunkPos.pack(cx, cz);
				if (!includesChunkForWork(cx, cz)) {
					committedNextIndex++;
					continue;
				}
				if (!indexDurablyCommitted(cx, cz, packed)) break;
				authoritativelyCommittedChunks.remove(packed);
				committedNextIndex++;
				committedSubmittedCount++;
			}
		}

		private void armRecoveryQuarantine(java.util.Collection<Long> chunks, java.util.Collection<Long> physicalRepairChunks, String reason) {
			if (!isPregenKind(kind) || chunks == null || chunks.isEmpty()) return;
			LongLinkedOpenHashSet ordered = new LongLinkedOpenHashSet();
			for (long packed : chunks) {
				if (includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) ordered.add(packed);
			}
			if (ordered.isEmpty()) return;
			LongOpenHashSet physical = new LongOpenHashSet();
			if (physicalRepairChunks != null) physical.addAll(physicalRepairChunks);
			restartRecoveryQuarantineActive = true;
			restartRecoveryTerrainGateOpen = false;
			restartRecoveryTerrainGateOpenLogged = false;
			restartRecoveryQuarantinePending.addAll(ordered);
			// v253.73.7: put the rare PHYSICAL_AWARE recovery subset first. Under
			// emergency light pressure this lets the liveness escape issue only work that
			// can still have a terrain-retirement dependency; the large LIGHT_ONLY tail
			// remains queued but cannot feed the already-overloaded finalizer.
			LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
			for (long packed : resumeReplayChunks) if (physical.contains(packed)) queue.add(packed);
			for (long packed : ordered) if (physical.contains(packed)) queue.add(packed);
			queue.addAll(resumeReplayChunks);
			queue.addAll(ordered);
			resumeReplayChunks.clear();
			resumeReplayChunks.addAll(queue);
			int physicalCount = 0;
			for (long packed : ordered) {
				boolean allowPhysicalRepair = physical.contains(packed);
				if (!allowPhysicalRepair) continue;
				physicalCount++;
				restartRecoveryPhysicalPending.add(packed);
				// v253.125.13: PHYSICAL_AWARE recovery remains eagerly armed because it
				// can still own terrain correctness. Historical LIGHT_ONLY debt is deliberately
				// left dormant here and is armed only when its bounded replay slot is admitted.
				// v253.125.12 eagerly marked all 58,077 light-only entries, defeating the
				// 128-entry recovery window before admission control could run.
				OceanCanvasSurfaceFlattener.markPregenCrashRecoveryTarget(world,
						new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)), true);
			}
			OceanCanvas.LOGGER.warn("(Ocean Canvas) CRASH-RECOVERY quarantine armed: reason={} chunks={} physicalRepair={} lightOnly={} lazyLightOnly=true committedCursor={} submittedCursor={}",
				reason, ordered.size(), physicalCount, ordered.size() - physicalCount, committedNextIndex, nextIndex);
		}

		private void noteAuthoritativeCommit(long packed) {
			if (!includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) return;
			terrainSafeRecoveryFinalizerChunks.remove(packed);
			restartRecoveryPhysicalPending.remove(packed);
			// A full chunk seal is already durable metadata. Keep an in-memory marker only
			// for partial-edge operations whose whole-chunk certificate is intentionally absent.
			ChunkPos pos = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
			var seals = net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(world);
			if (!seals.isChunkLightingVerified(pos)) authoritativelyCommittedChunks.add(packed);
			restartRecoveryQuarantinePending.remove(packed);
			restartRecoveryRescanPending.remove(packed);
			// Frontier advancement is deliberately tick-budgeted in tick(); do not scan
			// thousands of metadata entries once per finalizer callback during restart.
			if (restartRecoveryQuarantineActive && restartRecoveryQuarantinePending.isEmpty()) {
				restartRecoveryQuarantineActive = false;
				restartRecoveryTerrainGateOpen = false;
				OceanCanvas.LOGGER.info("(Ocean Canvas) CRASH-RECOVERY quarantine PASS: all replay/tail chunks authoritatively verified; normal Pregen admission may resume.");
			}
		}

		private void noteRecoveryExemption(long packed) {
			terrainSafeRecoveryFinalizerChunks.remove(packed);
			restartRecoveryPhysicalPending.remove(packed);
			restartRecoveryQuarantinePending.remove(packed);
			restartRecoveryRescanPending.remove(packed);
			if (restartRecoveryQuarantineActive && restartRecoveryQuarantinePending.isEmpty()) {
				restartRecoveryQuarantineActive = false;
				restartRecoveryTerrainGateOpen = false;
				OceanCanvas.LOGGER.info("(Ocean Canvas) CRASH-RECOVERY quarantine PASS after explicit CUSTOM_OR_MODIFIED exemption(s).");
			}
		}

		private void noteRecoveryLightOnlyFinalizer(long packed) {
			if (!restartRecoveryQuarantinePending.contains(packed)) return;
			// This callback is emitted only AFTER PREGEN_TARGET_CHUNKS ownership has
			// retired and only for allowPhysicalRepair=false. It is therefore a
			// terrain-safety marker, never a lighting certificate.
			terrainSafeRecoveryFinalizerChunks.add(packed);
		}

		/**
		 * True only while a PHYSICAL_AWARE recovery coordinate still needs to enter
		 * the terrain pipeline. restartRecoveryPhysicalPending intentionally survives
		 * until AUTHORITATIVE_SEND, so using that durable-proof set as an admission
		 * mutex can freeze the main cursor even after the physical target has already
		 * retired PREGEN_TARGET ownership into the finalizer. Initial recovery ordering
		 * keeps physical entries at the replay head; a watchdog-routed physical entry
		 * is also present in deferredPregenChunks until promotePhysicalRecoveryReplayCandidate
		 * moves it back to the head.
		 */
		private boolean physicalRecoveryAwaitingAdmission() {
			if (restartRecoveryPhysicalPending.isEmpty()) return false;
			Long head = resumeReplayChunks.peekFirst();
			if (head != null && restartRecoveryPhysicalPending.contains(head.longValue())) return true;
			for (long packed : deferredPregenChunks) {
				if (restartRecoveryPhysicalPending.contains(packed)) return true;
			}
			return false;
		}

		private boolean classifiedLightOnlyQuarantine() {
			return restartRecoveryQuarantineActive
					&& !restartRecoveryQuarantinePending.isEmpty()
					&& !physicalRecoveryAwaitingAdmission();
		}

		/**
		 * v253.73.7 emergency admission is classified per chunk, not per restart
		 * cohort. A graceful tail can legitimately contain a tiny PHYSICAL_AWARE
		 * subset plus tens of thousands of journal-proven LIGHT_ONLY chunks. Treating
		 * that mixed cohort as wholly physical recreated the v253.73.5 pressure leak.
		 * Physical recovery is queued first when the quarantine is armed; once those
		 * entries have actually been issued, a LIGHT_ONLY entry at the head closes the
		 * emergency inlet until active finalizer pressure drains.
		 */
		private boolean promotePhysicalRecoveryReplayCandidate() {
			Long head = resumeReplayChunks.peekFirst();
			if (head != null && restartRecoveryPhysicalPending.contains(head.longValue())) return true;

			// A hard-stall quarantine may have moved a still-mandatory physical target to
			// deferredPregenChunks. Pull at most one such target back to the front without
			// scanning/reordering the much larger LIGHT_ONLY replay cohort.
			for (java.util.Iterator<Long> it = deferredPregenChunks.iterator(); it.hasNext();) {
				long packed = it.next().longValue();
				if (!restartRecoveryPhysicalPending.contains(packed)) continue;
				it.remove();
				resumeReplayChunks.remove(packed);
				resumeReplayChunks.addFirst(packed);
				return true;
			}
			return false;
		}

		private boolean lightFinalizerRecentlyRetiring() {
			long now = world.getGameTime();
			long published = OceanCanvasSurfaceFlattener.lightFinalizationPublishCount();
			if (recoveryLightShareLastPublishCount < 0L) {
				recoveryLightShareLastPublishCount = published;
				return false;
			}
			if (published != recoveryLightShareLastPublishCount) {
				recoveryLightShareLastPublishCount = published;
				recoveryLightShareLastProgressTick = now;
			}
			return recoveryLightShareLastProgressTick != Long.MIN_VALUE
					&& now - recoveryLightShareLastProgressTick <= 100L;
		}


		/**
		 * v253.73.9 forever-world continuity rule. A graceful save can legitimately
		 * restart with tens of thousands of journal-proven LIGHT_ONLY chunks while
		 * still having never-submitted terrain beyond the saved cursor. 73.7/73.8
		 * correctly stopped LIGHT_ONLY replay above the emergency light watermark,
		 * but also returned before the main cursor could move, which made a mature
		 * forever world show 0 terrain submissions indefinitely even while thousands
		 * of strict light finalizations were successfully publishing.
		 *
		 * This reserve is deliberately debt-negative: above emergency pressure it
		 * grants at most one NORMAL terrain admission only after the total durable
		 * uncertified-light debt has fallen by the configured small quantum. Gross
		 * publications and active-to-dormant moves no longer buy terrain credit because
		 * they can be erased by rearms without reducing the eventual work. The reserve
		 * remains below 2x the emergency watermark and only runs when no
		 * PHYSICAL_AWARE target is still waiting for terrain admission, and never
		 * during a hard-crash rescan. A physical-aware target already inside the
		 * finalizer does not globally freeze unrelated future terrain; its own strict
		 * physical/light proof remains mandatory. LIGHT_ONLY replay itself remains
		 * drain-only at this pressure.
		 */
		private boolean mayAdmitForeverWorldEmergencyTerrainReserve(int activeLightingPressure, int emergencyWater,
				boolean absoluteCapHit) {
			if (absoluteCapHit || !isPregenKind(kind)) return false;
			if (!restartRecoveryQuarantineActive || restartRecoveryQuarantinePending.isEmpty()) return false;
			// The caller invokes this only after failing to find a PHYSICAL_AWARE target
			// that still needs admission. Chunks already inside the finalizer do not starve
			// unrelated never-submitted terrain, but their debt still counts below.
			if (physicalRecoveryAwaitingAdmission() || restartRecoveryRescanActive) return false;
			if (nextIndex >= totalChunks) return false;
			long hardCeiling = Math.max((long) emergencyWater + 1L, (long) emergencyWater * 2L);
			if ((long) activeLightingPressure >= hardCeiling) return false;

			int durableDebt = OceanCanvasSurfaceFlattener.pendingLightSyncCount();
			if (foreverWorldEmergencyTerrainDebtBaseline < 0) {
				foreverWorldEmergencyTerrainDebtBaseline = durableDebt;
				return false;
			}

			// Do NOT ratchet the baseline upward if new rearms/terrain work add debt. The
			// reserve must first pay that added debt back and then reduce the previous
			// durable baseline. This is the key debt-negative invariant missing from the
			// publication/active-pressure accounting used before v253.125.35.
			int netDebtReduction = foreverWorldEmergencyTerrainDebtBaseline - durableDebt;
			if (netDebtReduction < FOREVER_WORLD_EMERGENCY_NET_DEBT_REDUCTIONS_PER_TERRAIN_ADMISSION) return false;
			if (!lightFinalizerRecentlyRetiring()) return false;

			foreverWorldEmergencyTerrainDebtBaseline = durableDebt;
			return true;
		}

		/**
		 * v253.73.13 PHYSICAL_AWARE recovery admission window. Physical repair still
		 * has priority and remains mandatory; only the number of historical repairs
		 * allowed to wait in the strict light finalizer at once is bounded.
		 */
		private int physicalRecoveryReplayCeiling(int budget) {
			if (budget <= 0 || !physicalRecoveryAwaitingAdmission()) return budget;
			int active = OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount();
			int available = Math.max(0, FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW - active);
			if (available <= 0) {
				foreverWorldPhysicalWindowHolds++;
				if (!foreverWorldPhysicalWindowLogged) {
					foreverWorldPhysicalWindowLogged = true;
					OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-PHYSICAL-RECOVERY-WINDOW build={} state=FULL activePhysicalRecovery={}/{} replayQueued={} action=pause-new-physical-recovery-admission-until-strict-finalizer-retires",
							net.oceancanvas.mod.OceanCanvas.VERSION, active, FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW, resumeReplayChunks.size());
				}
				return 0;
			}
			return Math.min(budget, Math.min(FOREVER_WORLD_PHYSICAL_RECOVERY_ADMISSIONS_PER_TICK, available));
		}

		/**
		 * v253.73.11 historical LIGHT_ONLY recovery partition. Recovery replay is
		 * terrain-safe once no PHYSICAL_AWARE target still waits for admission. Keep
		 * no more than 128 such entries active/in-flight and spend at most two replay
		 * admissions per tick. If normal terrain remains, reserve at least one slot
		 * from every multi-slot budget (and alternate when budget==1). This is the
		 * proactive fix for the 30k-entry mature-world restart: old light debt drains
		 * in the background instead of first flooding the active finalizer to ~2k.
		 */
		private int lightOnlyRecoveryReplayCeiling(int budget) {
			if (budget <= 0 || !classifiedLightOnlyQuarantine()) return budget;
			int active = OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount();
			int available = Math.max(0, FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW - active);
			if (available <= 0) {
				foreverWorldLightOnlyWindowHolds++;
				if (!foreverWorldLightOnlyWindowLogged) {
					foreverWorldLightOnlyWindowLogged = true;
					OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-RECOVERY-WINDOW build={} state=FULL activeLightOnlyRecovery={}/{} replayQueued={} action=pause-historical-light-only-admission; reserve-capacity-for-new-terrain",
							net.oceancanvas.mod.OceanCanvas.VERSION, active, FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW, resumeReplayChunks.size());
				}
				return 0;
			}
			int recoverySlots = Math.min(FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ADMISSIONS_PER_TICK, available);
			if (nextIndex >= totalChunks) return Math.min(budget, recoverySlots);
			if (budget == 1) {
				// Even/odd alternation guarantees a main-terrain turn without enlarging
				// the admission budget. A full recovery window always yields to terrain.
				return (world.getGameTime() & 1L) == 0L ? Math.min(1, recoverySlots) : 0;
			}
			return Math.min(Math.max(0, budget - 1), recoverySlots);
		}

		/** Proactive overnight-SLO warning. This is diagnostics/control evidence only;
		 * it never certifies success or bypasses any safety hold. */
		private void updateForeverWorldThroughputPrediction(long nowNanos, double newTerrainPerSecond, double forwardPerSecond) {
			if (!isPregenKind(kind) || nextIndex >= totalChunks || !restartRecoveryQuarantineActive) {
				foreverWorldThroughputRiskSinceNanos = 0L;
				foreverWorldThroughputRiskLogged = false;
				return;
			}
			long confirmedNow = confirmedCompleteCount();
			long remaining = Math.max(0L, reportedTotalChunks - confirmedNow);
			// v253.73.18. NET completion rate. v253.73.16 moved this predictor onto
			// max(carve, forward) because the carve counter could not see a resumed job
			// re-walking its existing-seal band. That fixed a false alarm and created the
			// opposite fault: a forward retirement counts rework, and rework does not
			// reduce `remaining`. The v253.73.17 runtime reported forward~4-8 chunks/s for
			// twelve straight minutes while confirmedCompleteCount went 1,374,136 ->
			// 1,370,198 and the replay queue grew 29,006 -> 35,211. The run was going
			// BACKWARDS and this predictor stayed silent, because 6 > 4.56.
			//
			// confirmedCompleteCount() is submitted - outstanding - deferred - replay. It
			// is the exact quantity `remaining` is derived from, so its first derivative
			// is the only rate that cannot be inflated by re-doing work: anything that
			// re-queues a chunk shows up as a smaller (or negative) delta immediately.
			// carve and forward remain REPORTED for liveness ("is anything happening at
			// all") and still drive the retirement pacer, which is a different question.
			if (adaptiveLastConfirmed < 0L) {
				adaptiveLastConfirmed = confirmedNow;
				adaptiveLastConfirmedNanos = nowNanos;
			} else if (nowNanos > adaptiveLastConfirmedNanos) {
				double seconds = Math.max(0.001D, (nowNanos - adaptiveLastConfirmedNanos) / 1_000_000_000.0D);
				double confirmedPerSecond = (confirmedNow - adaptiveLastConfirmed) / seconds;
				adaptiveConfirmedRateEma = adaptiveLastConfirmedNanos == 0L
						? confirmedPerSecond
						: adaptiveConfirmedRateEma * 0.70D + confirmedPerSecond * 0.30D;
				adaptiveLastConfirmed = confirmedNow;
				adaptiveLastConfirmedNanos = nowNanos;
			}
			double netPerSecond = adaptiveConfirmedRateEma;
			if (netPerSecond <= 0.0D) {
				foreverWorldBacklogRecoverySinceNanos = 0L;
				if (foreverWorldBacklogRegressionSinceNanos == 0L) foreverWorldBacklogRegressionSinceNanos = nowNanos;
				boolean sustained = nowNanos - foreverWorldBacklogRegressionSinceNanos >= 10_000_000_000L;
				boolean logDue = foreverWorldBacklogRegressionLastLogNanos == 0L
						|| nowNanos - foreverWorldBacklogRegressionLastLogNanos >= 120_000_000_000L;
				if (sustained && logDue) {
					// A sustained negative net rate is categorically different from a slow one:
					// no amount of waiting finishes the job. Hysteresis avoids zero-crossing
					// log flaps while preserving the actual failure signal.
					foreverWorldBacklogRegressionLogged = true;
					foreverWorldBacklogRegressionLastLogNanos = nowNanos;
					OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-BACKLOG-REGRESSION build={} netConfirmedRate={} chunks/s carveRate={} chunks/s forwardRate={} chunks/s confirmed={} remaining={} replayQueued={} deferred={} outstanding={} sustainedSeconds={} action=rework-is-outpacing-completion; job cannot finish at this rate",
							net.oceancanvas.mod.OceanCanvas.VERSION,
							String.format(java.util.Locale.ROOT, "%.2f", netPerSecond),
							String.format(java.util.Locale.ROOT, "%.2f", newTerrainPerSecond),
							String.format(java.util.Locale.ROOT, "%.2f", forwardPerSecond),
							confirmedNow, remaining, resumeReplayChunks.size(), deferredPregenChunks.size(),
							OceanCanvasSurfaceFlattener.outstandingPregenTargetCount(),
							Math.max(0L, (nowNanos - foreverWorldBacklogRegressionSinceNanos) / 1_000_000_000L));
					net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureProactiveHealth(world,
							"Forever World net confirmed completion is zero or negative; rework outpacing progress");
				}
			} else {
				foreverWorldBacklogRegressionSinceNanos = 0L;
				if (foreverWorldBacklogRegressionLogged) {
					if (foreverWorldBacklogRecoverySinceNanos == 0L) foreverWorldBacklogRecoverySinceNanos = nowNanos;
					if (nowNanos - foreverWorldBacklogRecoverySinceNanos >= 10_000_000_000L) {
						foreverWorldBacklogRegressionLogged = false;
						foreverWorldBacklogRecoverySinceNanos = 0L;
					}
				} else {
					foreverWorldBacklogRecoverySinceNanos = 0L;
				}
			}
			if (remaining <= 0L) return;
			long targetSeconds = Math.max(1L, net.oceancanvas.mod.config.OceanCanvasConfig.get().foreverWorldTargetHours()) * 3600L;
			double requiredPerSecond = remaining / (double)targetSeconds;
			// v253.73.16: judge the SLO against forward progress, not against carve-only
			// retirements. The v253.73.14 runtime reported normalTerrainRate=0.00 and
			// projectedEta=52300.0h while its own FLUID-DIAG chunk coordinates showed the
			// frontier walking 182 chunks in 46 seconds (~4 chunks/s, against a 4.36
			// chunks/s requirement). A prediction that cannot see the cursor move is a
			// cry-wolf alarm, and it also fed the retirement pacer.
			double progressPerSecond = netPerSecond;
			boolean tooSlow = progressPerSecond < requiredPerSecond;
			if (!tooSlow) {
				foreverWorldThroughputRiskSinceNanos = 0L;
				foreverWorldThroughputRiskLogged = false;
				return;
			}
			if (foreverWorldThroughputRiskSinceNanos == 0L) foreverWorldThroughputRiskSinceNanos = nowNanos;
			if (!foreverWorldThroughputRiskLogged
					&& nowNanos - foreverWorldThroughputRiskSinceNanos >= FOREVER_WORLD_THROUGHPUT_RISK_HOLD_NS) {
				foreverWorldThroughputRiskLogged = true;
				double etaHours = remaining / Math.max(0.001D, progressPerSecond) / 3600.0D;
				double freshSquareRequiredPerSecond = reportedTotalChunks / (double)targetSeconds;
				OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-THROUGHPUT-RISK build={} netConfirmedRate={} chunks/s normalTerrainRate={} chunks/s forwardRate={} chunks/s requiredForRemaining={} chunks/s freshRunRequired={} chunks/s targetHours={} projectedEta={}h remaining={} totalChunks={} activeLightOnlyRecovery={} activePhysicalRecovery={} recoveryQueued={} action=capture-proactive-health-and-keep-recovery-window-backgrounded",
						net.oceancanvas.mod.OceanCanvas.VERSION,
						String.format(java.util.Locale.ROOT, "%.2f", netPerSecond),
						String.format(java.util.Locale.ROOT, "%.2f", newTerrainPerSecond),
						String.format(java.util.Locale.ROOT, "%.2f", forwardPerSecond),
						String.format(java.util.Locale.ROOT, "%.2f", requiredPerSecond),
						String.format(java.util.Locale.ROOT, "%.2f", freshSquareRequiredPerSecond),
						net.oceancanvas.mod.config.OceanCanvasConfig.get().foreverWorldTargetHours(),
						String.format(java.util.Locale.ROOT, "%.1f", etaHours), remaining, reportedTotalChunks,
						OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount(), OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount(), resumeReplayChunks.size());
				net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureProactiveHealth(world,
						"Forever World projected completion exceeds configured completion target under recovery load");
			}
		}

		/**
		 * v253.73.11 bounded liveness pulse. Retirement-backed credit remains the
		 * preferred path, but a Forever World must not depend on AUTHORITATIVE_SEND
		 * to make *any* future terrain progress: the historical cohort can be doing
		 * useful scrub/reseed/backoff work for a long time without publishing. After
		 * twenty seconds with no new terrain admission, and only when there is enough
		 * measured active-light headroom for the full 3x3 worst-case fan-out plus a
		 * guard band, allow exactly one NORMAL terrain target. Re-evaluate from live
		 * pressure before another pulse; never spend this escape on recovery replay.
		 */
		private boolean mayAdmitForeverWorldContinuityPulse(int activeLightingPressure, int emergencyWater,
				boolean absoluteCapHit, int outstanding) {
			if (absoluteCapHit || !isPregenKind(kind)) return false;
			if (!restartRecoveryQuarantineActive || restartRecoveryQuarantinePending.isEmpty()) return false;
			if (physicalRecoveryAwaitingAdmission() || restartRecoveryRescanActive) return false;
			if (nextIndex >= totalChunks || outstanding > 0) return false;

			long now = world.getGameTime();
			if (foreverWorldSupervisorLastSubmittedCount < 0L) {
				foreverWorldSupervisorLastSubmittedCount = submittedCount;
				foreverWorldSupervisorLastSubmissionProgressTick = now;
				return false;
			}
			if (submittedCount != foreverWorldSupervisorLastSubmittedCount) {
				foreverWorldSupervisorLastSubmittedCount = submittedCount;
				foreverWorldSupervisorLastSubmissionProgressTick = now;
				foreverWorldSupervisorStallLogged = false;
				return false;
			}
			if (foreverWorldSupervisorLastSubmissionProgressTick == Long.MIN_VALUE)
				foreverWorldSupervisorLastSubmissionProgressTick = now;
			long stalledTicks = now - foreverWorldSupervisorLastSubmissionProgressTick;
			if (stalledTicks < FOREVER_WORLD_CONTINUITY_STALL_TICKS) return false;
			if (foreverWorldSupervisorLastPulseTick != Long.MIN_VALUE
					&& now - foreverWorldSupervisorLastPulseTick < FOREVER_WORLD_CONTINUITY_PULSE_COOLDOWN_TICKS) return false;

			long hardCeiling = Math.max((long) emergencyWater + 1L, (long) emergencyWater * 2L);
			int requiredHeadroom = FOREVER_WORLD_MAX_LIGHT_FANOUT_PER_TERRAIN
					+ FOREVER_WORLD_CONTINUITY_HEADROOM_GUARD;
			long headroom = hardCeiling - (long)activeLightingPressure;
			if (headroom < requiredHeadroom) {
				if (!foreverWorldSupervisorStallLogged) {
					foreverWorldSupervisorStallLogged = true;
					OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-SUPERVISOR predictive hold: terrain stalled={}s activePressure={}/{} headroom={} requiredHeadroom={} recoveryPending={} action=do-not-admit; persistent-retry governor must drain/dormantize active debt first",
							stalledTicks / 20L, activeLightingPressure, hardCeiling, headroom, requiredHeadroom, restartRecoveryQuarantinePending.size());
					net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureProactiveHealth(world,
							"Forever World predictive hold before hard stall; active light headroom is below safe 3x3 fan-out guard");
				}
				return false;
			}

			foreverWorldSupervisorLastPulseTick = now;
			foreverWorldSupervisorPulseCount++;
			foreverWorldSupervisorStallLogged = false;
			OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-CONTINUITY-PULSE build={} pulse={} stalled={}s activePressure={}/{} headroom={} requiredHeadroom={} submitted={}/{} action=admit-exactly-one-normal-terrain-target-without-spending-light-only-replay-budget",
					net.oceancanvas.mod.OceanCanvas.VERSION, foreverWorldSupervisorPulseCount, stalledTicks / 20L,
					activeLightingPressure, hardCeiling, headroom, requiredHeadroom, submittedCount, reportedTotalChunks);
			net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureProactiveHealth(world,
					"Forever World continuity pulse after bounded no-terrain-progress window");
			return true;
		}

		/** Log/capture the recovery shape once, before it can become a stall. */
		private void maybeCaptureForeverWorldPreflight(int pendingLighting, int activeLightingPressure,
				int persistentSkyBackoff, int highWater, int emergencyWater, int absolutePendingCap) {
			if (foreverWorldSupervisorPreflightLogged || !restartRecoveryQuarantineActive
					|| restartRecoveryQuarantinePending.isEmpty() || nextIndex >= totalChunks) return;
			foreverWorldSupervisorPreflightLogged = true;
			long unsubmitted = Math.max(0L, totalChunks - nextIndex);
			long hardCeiling = Math.max((long) emergencyWater + 1L, (long) emergencyWater * 2L);
			String risk = pendingLighting >= absolutePendingCap ? "ABSOLUTE_CAP"
					: activeLightingPressure >= emergencyWater ? "EMERGENCY_RECOVERY"
					: activeLightingPressure >= highWater ? "RECOVERY_HEAVY" : "NORMAL";
			String message = "Forever World preflight risk=" + risk
					+ " recoveryPending=" + restartRecoveryQuarantinePending.size()
					+ " activeLightOnlyRecovery=" + OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount()
					+ "/" + FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW
					+ " trackedLightOnlyRecovery=" + OceanCanvasSurfaceFlattener.trackedLightOnlyRecoveryWorkCount()
					+ " activePhysicalRecovery=" + OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount()
					+ "/" + FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW
					+ " trackedPhysicalRecovery=" + OceanCanvasSurfaceFlattener.trackedPhysicalRecoveryWorkCount()
					+ " physicalAwaitingAdmission=" + physicalRecoveryAwaitingAdmission()
					+ " lightPending=" + pendingLighting + " active=" + activeLightingPressure
					+ " persistentFaults=" + persistentSkyBackoff
					+ " pressureParked=" + OceanCanvasSurfaceFlattener.pressureParkedLightSyncCount()
					+ " hardCeiling=" + hardCeiling
					+ " unsubmittedTerrain=" + unsubmitted;
			OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-PREFLIGHT build={} {}", net.oceancanvas.mod.OceanCanvas.VERSION, message);
			if (!"NORMAL".equals(risk))
				net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureProactiveHealth(world, message);
		}


		/**
		 * The v253.72.4 runtime exposed a liveness inversion: a graceful restart had
		 * drained all 10,694 replay targets and all terrain tickets, yet two
		 * LIGHT_ONLY chunks held the entire 1.56M-chunk main scan closed while their
		 * strict light verifier retried. Once every remaining quarantine entry is
		 * already inside a non-physical light finalizer, admitting unrelated future
		 * terrain cannot invalidate those chunks' block state. Keep the light proof
		 * mandatory, but stop making it a global TERRAIN admission mutex.
		 *
		 * The historical full-open latch below still keeps hard-crash rescan fail-closed.
		 * v253.73.5 adds a separate bounded fair-share overlay for a journal-proven
		 * LIGHT_ONLY quarantine; it does not change this durable full-open condition.
		 */
		private boolean mayResumeTerrainAdmissionBehindLightOnlyRecovery() {
			if (!restartRecoveryQuarantineActive || restartRecoveryQuarantinePending.isEmpty()) {
				restartRecoveryTerrainGateOpen = false;
				return false;
			}
			if (restartRecoveryRescanActive) {
				restartRecoveryTerrainGateOpen = false;
				return false;
			}

			// Once opened, keep terrain admission open for the rest of this graceful
			// quarantine. Newly admitted NORMAL Pregen targets are unrelated work;
			// counting them as a reason to re-close the gate recreates a wave-by-wave
			// stall. Correctness is still enforced by the active quarantine/completion
			// gate and by emergency light-backpressure below.
			if (restartRecoveryTerrainGateOpen) return true;

			// Opening transition: require a completely quiescent recovery terrain
			// frontier and prove that every remaining quarantine coordinate is owned
			// by the light finalizer. This is checked once, before NORMAL work can make
			// outstandingPregenTargetCount non-zero again.
			// v253.125.52: durable PHYSICAL_AWARE identity survives until
			// AUTHORITATIVE_SEND, but it must not remain a global terrain mutex once
			// the live exhaustive physical audit has passed. Only work that still awaits
			// terrain admission blocks this transition.
			if (physicalRecoveryAwaitingAdmission()) return false;
			if (!resumeReplayChunks.isEmpty()) return false;
			if (OceanCanvasSurfaceFlattener.outstandingPregenTargetCount() != 0) return false;
			// Prove every remaining recovery coordinate is terrain-safe in this server
			// lifetime and still owned by authoritative lighting debt.
			for (LongIterator it = restartRecoveryQuarantinePending.iterator(); it.hasNext();) {
				long packed = it.nextLong();
				if (!terrainSafeRecoveryFinalizerChunks.contains(packed)
						&& !OceanCanvasSurfaceFlattener.isPhysicalRecoveryTerrainSafe(packed)) return false;
				if (!OceanCanvasSurfaceFlattener.hasPendingLightFinalization(packed)) return false;
			}
			restartRecoveryTerrainGateOpen = true;
			return true;
		}

		/**
		 * Reconstructs a {@code Job} from a persisted {@link
		 * OceanCanvasJobState.Snapshot} after a restart, with the cursor
		 * ({@code nextIndex}/{@code submittedCount}) restored to exactly
		 * where it left off rather than starting the region over from
		 * scratch - the entire point of persisting a snapshot at all. Goes
		 * through the same 11-arg constructor as {@link
		 * PregenManager#expand} (an ordinary pregen/rewipe snapshot just has
		 * the same "exclude nothing" sentinel {@code expand} passes for
		 * non-expand jobs, see that constructor's other overload), so a
		 * resumed job's exclusion-rectangle jump behaves identically to a
		 * freshly-started one.
		 */
		static Job resume(ServerLevel world, OceanCanvasJobState.Snapshot snapshot, boolean durableCrashMarker) {
			OceanCanvasJobScopeState.Scope persistedScope = OceanCanvasJobScopeState.get(world).get();
			boolean modernCheckpoint = persistedScope != null && persistedScope.matches(snapshot);
			int opMinX = modernCheckpoint ? persistedScope.minBlockX() : snapshot.minChunkX() * 16;
			int opMaxX = modernCheckpoint ? persistedScope.maxBlockX() : snapshot.maxChunkX() * 16 + 15;
			int opMinZ = modernCheckpoint ? persistedScope.minBlockZ() : snapshot.minChunkZ() * 16;
			int opMaxZ = modernCheckpoint ? persistedScope.maxBlockZ() : snapshot.maxChunkZ() * 16 + 15;
			boolean samePersistedRegionScope = persistedScope != null
					&& persistedScope.sameOperationScope(snapshot) && !persistedScope.scopeName().isBlank();
			java.util.Set<Long> resumeMask;
			if (samePersistedRegionScope && "RECT".equals(persistedScope.scopeShape())) {
				resumeMask = net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.rectangularChunkSet(
						new net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.ChunkBounds(snapshot.minChunkX(), snapshot.maxChunkX(), snapshot.minChunkZ(), snapshot.maxChunkZ()));
			} else if (samePersistedRegionScope && "MASK".equals(persistedScope.scopeShape())) {
				var zone = net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(persistedScope.scopeName());
				resumeMask = zone == null ? new java.util.LinkedHashSet<>(snapshot.explicitChunks())
						: clippedRegionScope(zone, OceanCanvasConfig.get()).chunks();
			} else {
				resumeMask = new java.util.LinkedHashSet<>(snapshot.explicitChunks());
			}
			Job job = new Job(world, snapshot.minChunkX(), snapshot.maxChunkX(), snapshot.minChunkZ(), snapshot.maxChunkZ(),
					snapshot.requestedByUuid(), snapshot.excludeMinChunkX(), snapshot.excludeMaxChunkX(),
					snapshot.excludeMinChunkZ(), snapshot.excludeMaxChunkZ(), snapshot.kind(), resumeMask,
					opMinX, opMaxX, opMinZ, opMaxZ);
			if (samePersistedRegionScope) {
				job.scopeName = persistedScope.scopeName();
				job.scopeShape = persistedScope.scopeShape();
			}
			if ("GENERATE".equals(snapshot.phase())) {
				// v228 migration from the retired raw-generation phase. Its
				// submittedCount/nextIndex mean "raw load requested", NOT "carved".
				// Restart at zero so none of those targets are silently skipped.
				job.nextIndex = 0L;
				job.submittedCount = 0L;
				job.pregenPhase = "CARVE";
				OceanCanvas.LOGGER.warn("(Ocean Canvas) v228 migrated persisted raw-generation job to the unified CARVE pipeline from cursor 0; raw-phase requests will be safely revalidated/carved.");
			} else {
				// v253.72: submission cursor and durable commit cursor are distinct. The
				// recovery journal carries the uncertain live set, while the scope record
				// carries a second copy of the committed frontier so a damaged/missing
				// journal still has a fail-closed fallback.
				if (isPregenKind(snapshot.kind()) && !modernCheckpoint) {
					job.nextIndex = 0L;
					job.submittedCount = 0L;
					job.committedNextIndex = 0L;
					job.committedSubmittedCount = 0L;
					job.resumeReplayChunks.clear();
					OceanCanvas.LOGGER.warn("(Ocean Canvas) v253.72 legacy checkpoint migration: one-time cursor-0 reconciliation because this snapshot predates the committed-frontier recovery journal.");
				} else {
					long savedNext = Math.max(0L, Math.min(job.totalChunks, snapshot.nextIndex()));
					long savedSubmitted = isPregenKind(snapshot.kind())
							? Math.max(0L, Math.min(job.reportedTotalChunks, snapshot.submittedCount()))
							: Math.max(0L, snapshot.submittedCount());
					job.nextIndex = savedNext;
					job.submittedCount = savedSubmitted;
					job.resumeReplayChunks.addAll(snapshot.replayChunks());
					if (isPregenKind(snapshot.kind())) {
						job.committedNextIndex = Math.max(0L, Math.min(savedNext, persistedScope.committedNextIndex()));
						job.committedSubmittedCount = Math.max(0L, Math.min(savedSubmitted, persistedScope.committedSubmittedCount()));
						var journal = OceanCanvasPregenRecoveryJournalData.get(world).get();
						boolean journalMatches = journal != null && journal.matchesCheckpoint(snapshot);
						if (journalMatches) {
							job.committedNextIndex = Math.min(job.committedNextIndex,
									Math.max(0L, Math.min(savedNext, journal.committedNextIndex())));
							job.committedSubmittedCount = Math.min(job.committedSubmittedCount,
									Math.max(0L, Math.min(savedSubmitted, journal.committedSubmittedCount())));
							job.recoveryJournalGeneration = Math.max(job.recoveryJournalGeneration, journal.generation() + 1L);
						}

						boolean effectiveCleanStop = persistedScope.cleanStop() && !durableCrashMarker;
						LongLinkedOpenHashSet quarantine = new LongLinkedOpenHashSet();
						LongLinkedOpenHashSet physicalRecovery = new LongLinkedOpenHashSet();
						for (long packed : snapshot.replayChunks()) {
							// A proven graceful stop serializes exact live obligations. Their radius-1
							// neighborhood is re-established by the live finalizer ticket; expanding every
							// center here caused the v253.125.12 7.8k -> 58k restart explosion.
							if (effectiveCleanStop) quarantine.add(packed);
							else job.addRecoveryChunkWithHalo(quarantine, packed);
						}
						if (journalMatches) {
							// uncertainChunks already contains its conservative light halo. Do not
							// expand it a second time on every restart.
							for (long packed : journal.uncertainChunks()) {
								if (job.includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) quarantine.add(packed);
							}
							for (long packed : journal.physicalRepairChunks()) {
								if (job.includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) physicalRecovery.add(packed);
							}
						} else {
							// Legacy/no-journal replay entries were specifically unfinished work;
							// retain the historical ability to repair their physical profile.
							physicalRecovery.addAll(snapshot.replayChunks());
						}

						if (effectiveCleanStop) {
							// Orderly stop keeps the true submission cursor, but the last run proved
							// that recently-live SKY arrays can still serialize stale. Revalidate a
							// bounded recent tail before normal admission resumes. The safety tail is
							// light-only; only explicitly unfinished journal entries may repair blocks.
							job.addReplayTail(quarantine, savedNext, PREGEN_CLEAN_STOP_REPLAY_TAIL_INDICES, false);
							job.armRecoveryQuarantine(quarantine, physicalRecovery, "GRACEFUL_STOP_RECENT_TAIL");
						} else {
							// Hard crash/power loss: never trust work beyond the contiguous committed
							// frontier. Rewind the main scan, then quarantine a LIGHT-ONLY tail behind
							// the commit to close SavedData-vs-region-file serialization races without
							// rewriting already-committed player-visible blocks.
							job.nextIndex = job.committedNextIndex;
							job.submittedCount = job.committedSubmittedCount;
							job.restartRecoveryRescanEndIndex = savedNext;
							job.restartRecoveryRescanActive = savedNext > job.committedNextIndex;
							int tail = journalMatches ? PREGEN_CRASH_COMMITTED_SAFETY_TAIL_INDICES : PREGEN_JOURNAL_FALLBACK_TAIL_INDICES;
							job.addReplayTail(quarantine, job.committedNextIndex, tail, true);
							// Anything at/after the committed cursor will be re-scanned by the main
							// cursor and explicitly marked physical-aware there. Keep only the
							// pre-commit safety band in this light-only replay lane.
							quarantine.removeIf(packed -> {
								long index = job.indexForPacked(packed);
								return index >= job.committedNextIndex;
							});
							physicalRecovery.removeIf(packed -> job.indexForPacked(packed) >= job.committedNextIndex);
							job.resumeReplayChunks.clear();
							job.armRecoveryQuarantine(quarantine, physicalRecovery, journalMatches ? "UNCLEAN_STOP_COMMITTED_TAIL" : "UNCLEAN_STOP_JOURNAL_FALLBACK");
						}
						OceanCanvas.LOGGER.warn("(Ocean Canvas) v253.72 restart reconciliation: shutdown={} savedCursor={}/{} committedCursor={}/{} replayPending={} journal={} exactBlockScope={}..{} x {}..{}.",
							effectiveCleanStop ? "GRACEFUL" : (durableCrashMarker ? "UNCLEAN_MARKER" : "UNCLEAN"), savedNext, savedSubmitted,
							job.committedNextIndex, job.committedSubmittedCount, job.resumeReplayChunks.size(),
							journalMatches ? "VALID" : "MISSING_OR_STALE", job.operationMinBlockX, job.operationMaxBlockX,
							job.operationMinBlockZ, job.operationMaxBlockZ);
					}
				}
				job.pregenPhase = "CARVE";
			}
			// v121: staged structure rules (see Job#structureRules's doc) are
			// deliberately NOT part of the persisted snapshot - they're an
			// in-memory-only "about to run this" staging area, same as
			// currentJob() itself. A job resumed after a restart therefore
			// resumes with structureRules empty (falls back to each region's
			// own rules and the world defaults, exactly like pre-v121
			// behavior) even if it was started with staged rules. Acceptable:
			// the operator can re-stage and re-run for whatever's left, and
			// this matches every other purely-transient per-job field here
			// (rewipeStructuresQueued, pregenStructuresQueued, etc.).
			// v253.61.11: modern Pregen checkpoints restore their persisted replay lane
			// above and continue from the true cursor. Non-Pregen snapshots keep their
			// historical replay restore here for compatibility with older state shapes.
			if (!isPregenKind(snapshot.kind()) && job.resumeReplayChunks.isEmpty()) job.resumeReplayChunks.addAll(snapshot.replayChunks());
			job.nextMaintenanceAt = ((Math.max(0L, job.submittedCount) / PREGEN_MAINTENANCE_INTERVAL_CHUNKS) + 1L)
					* PREGEN_MAINTENANCE_INTERVAL_CHUNKS;
            job.sessionBaseSubmitted=job.submittedCount;
			// v99: restart/load ticks are not representative of steady-state server
			// cadence. Start the controller clean instead of carrying a startup spike
			// into an indefinite "paused" state.
			job.resumeBootstrapUntilNanos = System.nanoTime() + PREGEN_RESUME_BOOTSTRAP_NS;
			job.adaptiveTickMsEma = 50.0D;
			job.lastAdaptiveWallTickNanos = 0L;
			job.previousHeapSample = -1.0D;
			job.heapSlopeEma = 0.0D;
			job.pregenWatchdogLastProgressNanos = System.nanoTime();
			if (job.scopeName.isBlank() && ("region-pregen".equals(snapshot.kind()) || "region-rewipe".equals(snapshot.kind())) && !job.explicitChunks.isEmpty()) {
				job.scopeName = regionNameForExactMask(world, job.explicitChunks);
				if (!job.scopeName.isBlank()) {
					var zone = net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(job.scopeName);
					job.scopeShape = zone != null && !zone.hasExplicitShape() ? "RECT" : "MASK";
				}
			}
			return job;
		}

		String kind() {
			return kind;
		}

		/** True only when this job actually covers every chunk governed by the zone. */
		private boolean fullyCoversZone(net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone zone) {
			if (zone == null) return false;
			for (long packed : zone.exactChunks()) {
				int cx = ChunkPos.getX(packed);
				int cz = ChunkPos.getZ(packed);
				if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) return false;
				if (!explicitChunks.isEmpty() && !explicitChunks.contains(packed)) return false;
				if (cx >= excludeMinChunkX && cx <= excludeMaxChunkX
						&& cz >= excludeMinChunkZ && cz <= excludeMaxChunkZ) return false;
			}
			return true;
		}

		/** Copies this job's current position and extent out for {@link PregenManager#overlaySnapshot()}. */
		JobOverlay toOverlay() {
			// nextIndex walks the full outer square row-major (see the
			// field's own doc for why that bound is totalChunks and not
			// reportedTotalChunks), so the cursor is recovered the same
			// way tick() derives it.
			int cursorX = rowWidth == 0 ? minChunkX : minChunkX + (int) (nextIndex % rowWidth);
			int cursorZ = rowWidth == 0 ? minChunkZ : minChunkZ + (int) (nextIndex / rowWidth);
			return new JobOverlay(kind, scopeName, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
					Math.min(cursorX, maxChunkX), Math.min(cursorZ, maxChunkZ),
					submittedCount, reportedTotalChunks);
		}


		JobTelemetry toTelemetry() {
			return new JobTelemetry(kind, submittedCount, reportedTotalChunks,
					Math.max(0, adaptivePregenRate),
					OceanCanvasSurfaceFlattener.outstandingPregenTargetCount(),
					heapUseFraction(), adaptiveTickMsEma);
		}

        private void samplePerformance() {
            if(!isPregenKind(kind))return;
            long now=System.nanoTime();
            rateWindow.observe(now,OceanCanvasPregenMetrics.completedNewWork(submittedCount,
                    sessionBaseSubmitted,skippedProcessed,OceanCanvasSurfaceFlattener.outstandingPregenTargetCount()));
            long lightPublishes=OceanCanvasSurfaceFlattener.lightFinalizationPublishCount();
            if(lightPublishes>lastPerformanceLightPublishCount)rateWindow.noteProgress(now);
            lastPerformanceLightPublishCount=Math.max(lastPerformanceLightPublishCount,lightPublishes);
        }

        private OceanCanvasPregenMetrics.Snapshot performanceSnapshot() {
            long now=System.nanoTime();
            int outstanding=OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
            long settled=OceanCanvasPregenMetrics.settledEstimate(submittedCount,reportedTotalChunks,outstanding);
            boolean draining=nextIndex>=totalChunks;
            boolean paused=lastFeedBudget==0;
            var estimate=rateWindow.estimate(now,Math.max(0,reportedTotalChunks-settled),draining,paused);
            var params=adaptiveParams(world,OceanCanvasConfig.get().pregenChunksPerTick());
            String phase=draining?"FINAL_DRAIN":paused?"THROTTLED":"FEEDING";
            Runtime rt=Runtime.getRuntime();
            long usedBytes=Math.max(0L,rt.totalMemory()-rt.freeMemory());
            long maxBytes=Math.max(0L,rt.maxMemory());
            return new OceanCanvasPregenMetrics.Snapshot(kind,
                    net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).pregenProfile().name(),phase,
                    draining?"Waiting for owned targets to finish; not complete yet":feedReason,
                    submittedCount,reportedTotalChunks,skippedProcessed,settled,lastFeedBudget,params.maxRate(),outstanding,
                    OceanCanvasSurfaceFlattener.pendingPregenChunkCount(),OceanCanvasSurfaceFlattener.pendingChunkCount(),
                    OceanCanvasSurfaceFlattener.finalDrainTicketCount(),
                    OceanCanvasTickTelemetry.workMs(),OceanCanvasTickTelemetry.intervalMs(),heapUseFraction(),
                    usedBytes/(1024L*1024L),maxBytes/(1024L*1024L),estimate.chunksPerSecond(),
                    Math.max(0,(now-performanceStartedNanos)/1_000_000_000L),rateWindow.noProgressSeconds(now),
                    estimate.lowSeconds(),estimate.highSeconds(),estimate.quality());
        }

        private void commitCalibration(){
            long newWork=OceanCanvasPregenMetrics.completedNewWork(submittedCount,sessionBaseSubmitted,skippedProcessed,0);
            if(!rateWindow.hasSufficientSamples(System.nanoTime())||newWork<OceanCanvasPregenMetrics.MIN_CALIBRATION_NEW_CHUNKS)return;
            var project=net.oceancanvas.mod.project.OceanCanvasProjectData.get(world);
            var merged=OceanCanvasPregenMetrics.mergeCalibration(calibration(world),calibrationProfile,rateWindow.mean(),adaptiveTickMsEma,
                    Math.max(1,adaptiveLastOutstanding),rateWindow.sampleCount());
            if(merged==null||!merged.usableFor(calibrationProfile))return;
            project.setBenchmark(new net.oceancanvas.mod.project.OceanCanvasProjectData.BenchmarkProfile(merged.chunksPerSecond(),merged.cadenceMs(),
                    merged.preferredOutstanding(),System.currentTimeMillis(),OceanCanvasPregenMetrics.CALIBRATION_VERSION,
                    merged.successfulRuns(),merged.samples(),merged.profile()));
        }

		/** The count shown to the user - see {@link #reportedTotalChunks}'s field doc for why this differs from the cursor bound. */
		long totalChunks() {
			return reportedTotalChunks;
		}

		/**
		 * Snapshots this job's current state for persistence - see {@link
		 * #persist()} for when this is actually called.
		 */
		private OceanCanvasJobState.Snapshot toSnapshot(java.util.List<Long> pendingLightSnapshot) {
			LongLinkedOpenHashSet replay = checkpointSetScratch;
			replay.clear();
			resumeReplayChunks.forEachLong(replay::add);
			deferredPregenChunks.forEachLong(replay::add);
			// Recovery barriers are durable obligations, not transient scheduling hints.
			for (LongIterator it = restartRecoveryQuarantinePending.iterator(); it.hasNext();) replay.add(it.nextLong());
			for (LongIterator it = restartRecoveryRescanPending.iterator(); it.hasNext();) replay.add(it.nextLong());
			if (isPregenKind(kind)) {
				for (long packed : OceanCanvasSurfaceFlattener.outstandingPregenTargetsSnapshot()) replay.add(packed);
				if (pendingLightSnapshot instanceof it.unimi.dsi.fastutil.longs.LongList primitive) {
					for (int i = 0; i < primitive.size(); i++) {
						long packed = primitive.getLong(i);
						if (includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) replay.add(packed);
					}
				} else {
					for (Long value : pendingLightSnapshot) {
						if (value == null) continue;
						long packed = value.longValue();
						if (includesChunkForWork(ChunkPos.getX(packed), ChunkPos.getZ(packed))) replay.add(packed);
					}
				}
			}
			LongArrayList replaySnapshot = primitiveSnapshot(replay);
			replay.clear();
			// Named Region jobs reconstruct immutable geometry from OceanCanvasPlayerZones.
			LongArrayList persistedMask = new LongArrayList(!scopeName.isBlank() ? 0 : explicitChunks.size());
			if (scopeName.isBlank()) for (Long packed : explicitChunks) if (packed != null) persistedMask.add(packed.longValue());
			return new OceanCanvasJobState.Snapshot(kind, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
					excludeMinChunkX, excludeMaxChunkX, excludeMinChunkZ, excludeMaxChunkZ,
					nextIndex, submittedCount, persistedMask, replaySnapshot,
					requestedBy == null ? java.util.List.of() : java.util.List.of(requestedBy.toString()),queueEntryId, pregenPhase);
		}

		/**
		 * Writes this job's current state to per-level {@code SavedData},
		 * overwriting whatever was there before. Called once right after a
		 * new job is created ({@link PregenManager#start}/{@code reset}/
		 * {@code expand}), so even a crash within the first
		 * {@code PROGRESS_REPORT_INTERVAL_TICKS} ticks still has SOMETHING
		 * to resume from, and again on the same interval as {@link
		 * #reportProgress()} thereafter (see {@link #tick}) - piggybacking
		 * on an already-proven cadence rather than introducing a new timer,
		 * cheap enough (one small SavedData write) not to matter at ~10s
		 * intervals.
		 */
		void persist() { persist(false); }

		private void persist(boolean cleanStop) {
			// Bring the durable frontier as far forward as current authoritative metadata
			// permits before snapshotting. Clean shutdown gets a larger bounded scan because
			// this is the last chance to capture a recently-closed hole before process exit.
			advanceCommittedFrontier(cleanStop ? PREGEN_CLEAN_STOP_REPLAY_TAIL_INDICES : MAX_COMMIT_ADVANCE_PER_TICK);
			// v253.125.31: capture lighting debt once per checkpoint. The .30 full
			// diagnostic showed tens of thousands of light obligations; rebuilding the
			// same boxed-long snapshot separately for the recovery journal and JobState
			// doubled allocation and GC pressure every persistence interval.
			java.util.List<Long> pendingLightSnapshot = isPregenKind(kind)
					? OceanCanvasSurfaceFlattener.pendingLightFinalizationChunksSnapshot() : java.util.List.of();
			LongArrayList uncertain = recoveryJournalUncertainChunks(pendingLightSnapshot);
			LongArrayList physicalRecovery = recoveryJournalPhysicalChunks();
			long candidateGeneration = recoveryJournalGeneration + 1L;

			// Scope identity comes first: named Region snapshots intentionally omit the giant explicit
			// chunk mask, so a crash must never be able to expose a new-format JobState without its
			// tiny companion record identifying the Region used to reconstruct that geometry.
			OceanCanvasJobScopeState.get(world).save(new OceanCanvasJobScopeState.Scope(
					kind, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
					operationMinBlockX, operationMaxBlockX, operationMinBlockZ, operationMaxBlockZ,
					nextIndex, submittedCount, committedNextIndex, committedSubmittedCount, cleanStop, scopeName, scopeShape));
			long generation = OceanCanvasPregenRecoveryJournalData.get(world).saveIfChanged(
					new OceanCanvasPregenRecoveryJournalData.Journal(
						kind, minChunkX, maxChunkX, minChunkZ, maxChunkZ, nextIndex, submittedCount,
						committedNextIndex, committedSubmittedCount, cleanStop, candidateGeneration,
						uncertain, physicalRecovery));
			recoveryJournalGeneration = Math.max(recoveryJournalGeneration, generation);
			OceanCanvasJobState.Snapshot durableSnapshot = toSnapshot(pendingLightSnapshot);
			OceanCanvasJobState.get(world).save(durableSnapshot);
			if (!cleanStop && isPregenKind(kind) && !OceanCanvasPregenSessionMarker.isArmed(world)) {
				OceanCanvasPregenSessionMarker.arm(world, durableSnapshot);
			}
			OceanCanvas.LOGGER.debug("(Ocean Canvas) CRASH-RECOVERY checkpoint generation={} cleanStop={} submittedCursor={}/{} committedCursor={}/{} uncertain={} physicalRepair={}",
					generation, cleanStop, nextIndex, submittedCount, committedNextIndex, committedSubmittedCount, uncertain.size(), physicalRecovery.size());
		}

		/** Clears this job's persisted snapshot - called on an explicit stop (cancel, or pregenEnabled turned off) and on normal completion. See {@link OceanCanvasJobState}'s class doc for why every stop path clears it. */
		void clearPersisted() {
			OceanCanvasJobState.get(world).clear();
			OceanCanvasJobScopeState.get(world).clear();
			OceanCanvasPregenRecoveryJournalData.get(world).clear();
			if (isPregenKind(kind)) OceanCanvasPregenSessionMarker.disarm(world);
		}

		/** @return true once every chunk in the region has been submitted into the flattening queue. */
		private double heapUseFraction() {
			Runtime rt = Runtime.getRuntime();
			long max = rt.maxMemory();
			if (max <= 0L) return 0.0D;
			return (double)(rt.totalMemory() - rt.freeMemory()) / (double)max;
		}

		private double sampleAdaptiveTickMs() {
			long now = System.nanoTime();
			if (lastAdaptiveWallTickNanos != 0L) {
				double sampleMs = (now - lastAdaptiveWallTickNanos) / 1_000_000.0D;
				// Clamp pauses such as alt-tab/debugger so one unrelated long gap
				// doesn't suppress generation for minutes. Real server stalls still
				// saturate this at 1000 ms and force an immediate backoff.
				sampleMs = Math.max(1.0D, Math.min(1000.0D, sampleMs));
				adaptiveTickMsEma = adaptiveTickMsEma * 0.80D + sampleMs * 0.20D;
			}
			lastAdaptiveWallTickNanos = now;
			return adaptiveTickMsEma;
		}

		/**
		 * v95 liveness watchdog for the 20k runtime stall. The adaptive feeder can
		 * legitimately pause because of heap/tick pressure; however, v94 returned
		 * immediately in those states and therefore also stopped servicing the old
		 * outstanding C2ME requests that were CAUSING the pressure. Once the bounded
		 * outstanding window filled, no new chunk could enter and no recovery path
		 * ran until the entire 1.56M-chunk scan had somehow been submitted -- an
		 * impossible condition.
		 *
		 * This watchdog is deliberately independent of feed permission. It observes
		 * authoritative retirements (issued non-skip work minus currently-owned
		 * targets), directly finishes one ready target, and periodically nudges a
		 * bounded rotating recovery window. It never retires a target without the
		 * existing v74 physical completion gate.
		 */
		private int servicePregenLivenessWatchdog(int outstanding) {
			long now = System.nanoTime();
			long issued = Math.max(0L, submittedCount - skippedProcessed);
			long settled = Math.max(0L, issued - Math.max(0, outstanding));

			if (pregenWatchdogLastSettled < 0L || settled > pregenWatchdogLastSettled || outstanding <= 0) {
				pregenWatchdogLastSettled = settled;
				pregenWatchdogLastProgressNanos = now;
				if (outstanding <= 0) {
					pregenWatchdogLastRecoveryNanos = 0L;
				}
				return outstanding;
			}

			long stalledFor = now - pregenWatchdogLastProgressNanos;
			var watchdogDiag = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
			// v224: READY work is not a load/ticket liveness failure. Give it one
			// bounded direct retirement attempt and never install recovery tickets
			// merely because the carve budget has not reached it yet.
			if (watchdogDiag.ready() > 0) {
				int before = outstanding;
				OceanCanvasSurfaceFlattener.finishOneReadyPregenTarget(world);
				outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
				if (outstanding < before) {
					pregenWatchdogLastSettled = Math.max(0L, issued - outstanding);
					pregenWatchdogLastProgressNanos = now;
				}
				return outstanding;
			}
			if (stalledFor < PREGEN_STALL_WATCHDOG_NS) return outstanding;

			feedReason = "Diagnosing stalled outstanding chunks";
			// v101 removed this call outright to kill an oscillation in the
			// FORCE-LOAD half of nudgeOutstandingPregenTargets (stale/unloaded
			// targets getting a fresh radius-2 ticket every second). That was
			// correct on its own, but it also silently disabled the other half
			// of that same function: v100's processing lease for targets that
			// are already RESIDENT but whose 3x3 support neighborhood is not.
			// With no lease mechanism running during the main scan (only in
			// final-drain, after the whole 1.56M-chunk cursor is exhausted),
			// a large v102 outstanding window has no way to unstick itself once
			// most of it converges on "neighbor-stalled" - which is exactly the
			// runtime signature this restores from: outstanding==neighbor,
			// loading=0, completed~0 chunks/s, sustained for minutes. The
			// function's own internal caps (PREGEN_RECOVERY_MAX_TICKETS,
			// PREGEN_PROCESSING_LEASE_MAX) already bound how much force-load/
			// lease work happens per call, so restoring the call does not
			// reintroduce the old oscillation - it only restores servicing of
			// already-owned outstanding targets, never new submissions.
			if (now - pregenWatchdogLastRecoveryNanos >= PREGEN_STALL_RECOVERY_INTERVAL_NS) {
				pregenWatchdogLastRecoveryNanos = now;
				// v224: resident targets are serviced by the proactive carve lane, not
				// by a five-second emergency promotion. Only a genuinely old pure-load
				// tail may enter the legacy final-drain load recovery path.
				OceanCanvasSurfaceFlattener.servicePregenCarveLane(world);
				var recoveryDiag = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
				if (stalledFor >= 15_000_000_000L && recoveryDiag.queued() == 0
						&& recoveryDiag.loadingNotQueued() > 0) {
					// v225: v224 passed holdNewRequests=true here, which suppresses both
					// final-drain ticket installation and target re-request. Pure-load
					// recovery was therefore a no-op. Enable the bounded recovery path.
					OceanCanvasSurfaceFlattener.nudgeOutstandingPregenTargets(world, false);
				}
			}

			// v98: v97 could route around a pathological tail only at a scheduled
			// maintenance barrier. The latest 20k run proved the ordinary adaptive
			// window can itself become the barrier: 96/96 live targets, mostly stuck
			// in C2ME FULL loading, with zero submissions/retirements for minutes.
			// Once that condition persists, withdraw only a small oldest slice into
			// the same persisted deferred lane. They remain mandatory and will be
			// retried later; the healthy scan is allowed to continue now.
			// v102: deferral is an escape hatch, not normal flow control. Only a
			// genuinely saturated window that has made no retirement progress for
			// 20s may shed a tiny oldest slice. Healthy async latency must never
			// create the old 5-second defer/refill oscillation.
			// v225: the legacy minimum of 16 made this unreachable for v224's
			// observed 8-target deadlock. Bound it to the active architecture.
			int saturationFloor = Math.max(4, Math.min(PREGEN_CARVE_PIPELINE_HARD_OUTSTANDING,
					Math.max(1, (adaptiveOutstandingTarget * 7) / 8)));
			var hardStallDiag = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
			boolean saturatedStall = outstanding >= saturationFloor;
			boolean pureLoadTailStall = hardStallDiag.queued() == 0
					&& hardStallDiag.loadingNotQueued() >= Math.min(4, Math.max(1, outstanding))
					&& hardStallDiag.loadingStale() >= Math.max(1, hardStallDiag.loadingNotQueued() / 2)
					&& hardStallDiag.oldestLoadingMs() >= PREGEN_LOAD_TAIL_DEFER_MIN_AGE_MS;
			// v253.125.32: once a pure-load target has spent 20s outside CHUNK_LOAD,
			// release transient C2ME ownership at a five-second cadence rather than
			// letting one cold tail monopolize the adaptive window. Keep the escape tiny:
			// one target normally, two only once the oldest pure load reaches 60s.
			int staleTailBatch = hardStallDiag.oldestLoadingMs() >= PREGEN_LOAD_TAIL_DEFER_ESCALATED_AGE_MS
					? PREGEN_LIVE_STALL_DEFER_BATCH : 1;
			if (stalledFor >= 20_000_000_000L
					&& (saturatedStall || pureLoadTailStall)
					&& now - pregenWatchdogLastDeferNanos >= PREGEN_LOAD_TAIL_DEFER_CADENCE_NS) {
				pregenWatchdogLastDeferNanos = now;
				java.util.List<Long> routed = OceanCanvasSurfaceFlattener.deferOldestStalledPregenTargets(
						world, staleTailBatch, PREGEN_LOAD_TAIL_DEFER_MIN_AGE_MS);
				for (long packed : routed) {
					if (!deferredPregenChunks.contains(packed)) deferredPregenChunks.addLast(packed);
					if ((restartRecoveryQuarantinePending.contains(packed) || restartRecoveryRescanPending.contains(packed))
							&& !resumeReplayChunks.contains(packed)) {
						resumeReplayChunks.addLast(packed);
					}
				}
				if (!routed.isEmpty()) {
					persist();
					outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
					OceanCanvas.LOGGER.warn(
							"(Ocean Canvas) v253.125.32 stale-load-tail escape deferred {} unresolved target(s) after a >=20s {} stall; active outstanding now {}. Deferred targets remain mandatory and are replayed before completion.",
							routed.size(), pureLoadTailStall ? "pure-load-tail" : "saturated-window", outstanding);
				}
			}

			if (now - pregenWatchdogLastLogNanos >= PREGEN_STALL_LOG_INTERVAL_NS) {
				pregenWatchdogLastLogNanos = now;
				var d = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
				var ticketDiag = OceanCanvasSurfaceFlattener.pregenTicketDiagnostics();
				int recoveryTickets = ticketDiag.finalDrainActive();
				OceanCanvas.LOGGER.warn(
						"(Ocean Canvas) v230.5 Pregen liveness diagnostic after {}s without retirement: outstanding={}, recoveryTickets={}, {}",
						Math.max(5L, stalledFor / 1_000_000_000L), outstanding, recoveryTickets, d.shortText());
				net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.capturePregenStall(
						world, stalledFor, "outstanding=" + outstanding + ", recoveryTickets="
						+ recoveryTickets + ", " + d.shortText());
			}
			return outstanding;
		}

		/**
		 * v106: refreshes {@link #pregenNeighborStallBreakerActive} at most once
		 * per {@link #PREGEN_NEIGHBOR_STALL_SAMPLE_INTERVAL_NS}. Deliberately
		 * separate from the reactive liveness watchdog (which only engages after
		 * PREGEN_STALL_WATCHDOG_NS of zero retirement) - this runs proactively,
		 * every sample, regardless of whether the queue has stalled outright,
		 * because the goal is to stop the pile-up before it reaches that point.
		 */
		private void updateNeighborStallBreaker(long now) {
			if (now - pregenNeighborStallSampledNanos < PREGEN_NEIGHBOR_STALL_SAMPLE_INTERVAL_NS
					&& pregenNeighborStallSampledNanos != 0L) {
				return;
			}
			pregenNeighborStallSampledNanos = now;
			var d = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
			int queued = d.queued();
			int carveLane = d.ready() + d.missingNeighbor() + d.missingStructureOwner();
			// v117: use the STALE missing-neighbor count, not the raw one. A
			// freshly-submitted target is expected to be missing a neighbor for
			// its first few seconds - the scan hasn't reached those columns yet.
			// The raw count could not tell that apart from a genuine stall, so
			// this breaker was tripping on completely healthy, fast-draining
			// queues (a real comparison run showed Chunky hitting 30-70+
			// chunks/s on identical hardware/mods while this breaker sat
			// reporting "queued=30, neighbor=30 (100%)" and pausing admission
			// the whole time). See pregenQueueDiagnostics's doc for the staleness
			// threshold.
			int missingNeighbor = d.missingNeighborStale();
			if (carveLane < PREGEN_NEIGHBOR_STALL_MIN_QUEUED) {
				// v210: a small queue is too small to ENGAGE the breaker, but it is not
				// evidence that an ALREADY-ENGAGED breaker recovered. v209 repeatedly
				// drained to 19 stale targets, released solely because 19 < 24, then
				// immediately admitted ~20-25 new chunks/s and rebuilt the same stall.
				// Once engaged, keep admission closed until the residual queue actually
				// drains to zero or its stale ratio meets the normal resume threshold.
				if (pregenNeighborStallBreakerActive && carveLane > 0) {
					double smallRatio = missingNeighbor / (double) carveLane;
					if (smallRatio > PREGEN_NEIGHBOR_STALL_RESUME_RATIO) return;
				}
				if (pregenNeighborStallBreakerActive) {
					pregenNeighborStallBreakerActive = false;
					pregenRecoveryRampUntilNanos = now + PREGEN_RECOVERY_RAMP_NS;
					recoveryAdmissionTick = 0;
					adaptivePregenRate = Math.min(Math.max(1, adaptivePregenRate), PREGEN_RECOVERY_RAMP_START_RATE);
					OceanCanvas.LOGGER.info(
							"(Ocean Canvas) v210 neighbor-stall breaker released after real residual recovery ({} queued, {} stale); entering fractional retirement-paced ramp.",
							queued, missingNeighbor);
				}
				return;
			}
			double ratio = missingNeighbor / (double)Math.max(1, carveLane);
			if (!pregenNeighborStallBreakerActive && ratio >= PREGEN_NEIGHBOR_STALL_PAUSE_RATIO) {
				pregenNeighborStallBreakerActive = true;
				OceanCanvas.LOGGER.warn(
						"(Ocean Canvas) v230.5 neighbor-stall breaker engaged: {}/{} queued targets ({}%) STALE-missing a "
								+ "loaded neighbor (missing for {}ms+, not just newly submitted). Pausing new pregen "
								+ "admission until the existing backlog recovers below {}%; already-outstanding targets "
								+ "and recovery leases keep running.",
						missingNeighbor, queued, Math.round(ratio * 100.0D), PREGEN_NEIGHBOR_STALE_LOG_MS,
						Math.round(PREGEN_NEIGHBOR_STALL_RESUME_RATIO * 100.0D));
			} else if (pregenNeighborStallBreakerActive && ratio <= PREGEN_NEIGHBOR_STALL_RESUME_RATIO) {
				pregenNeighborStallBreakerActive = false;
				pregenRecoveryRampUntilNanos = now + PREGEN_RECOVERY_RAMP_NS;
				recoveryAdmissionTick = 0;
				adaptivePregenRate = Math.min(Math.max(1, adaptivePregenRate), PREGEN_RECOVERY_RAMP_START_RATE);
				OceanCanvas.LOGGER.info(
						"(Ocean Canvas) v202.43 neighbor-stall breaker released: {}/{} queued targets ({}%) STALE-missing a "
								+ "loaded neighbor. Entering a 20s retirement-paced recovery ramp instead of immediately refilling the full window.",
						missingNeighbor, queued, Math.round(ratio * 100.0D));
			}
			// Between the two thresholds, deliberately leave the flag exactly as
			// it was - that gap is the hysteresis band and must not flap.
		}

		/** v212: classify current queue state without mutating chunk/ticket state. */
		private String classifyPregenHealth(OceanCanvasSurfaceFlattener.PregenQueueDiagnostics d, int outstanding) {
			if (outstanding <= 0) return "IDLE";
			if (d.loadingStale() > 0 && d.oldestLoadingMs() >= PREGEN_PROACTIVE_LOAD_STALL_MS) return "LOAD_STALLED";
			if (d.loadingNotQueued() > 0 && d.queued() == 0) return "WAITING_FOR_LOAD";
			if (d.queued() <= 0) return "GENERATING";
			double staleRatio = d.missingNeighborStale() / (double)Math.max(1, d.queued());
			if (staleRatio >= PREGEN_NEIGHBOR_STALL_PAUSE_RATIO) return "NEIGHBOR_STALLED";
			if (staleRatio >= PREGEN_PROACTIVE_STALE_RATIO) return "NEIGHBOR_RISK";
			if (d.ready() > 0) return "READY_TO_RETIRE";
			if (d.missingStructureOwner() > 0) return "WAITING_FOR_STRUCTURE";
			if (d.missingNeighbor() > 0) return "WAITING_FOR_NEIGHBOR";
			if (d.loadingNotQueued() > 0) return "WAITING_FOR_LOAD";
			return "HEALTHY_DRAIN";
		}

		/**
		 * v212 proactive invariant monitor. Samples once/second, records the last
		 * minute, and clamps admission on a deteriorating trend before the hard
		 * breaker/watchdog fires. Returns whether proactive admission hold is active.
		 */
		private boolean updateProactivePregenHealth(long now, OceanCanvasSurfaceFlattener.PregenQueueDiagnostics d,
				int outstanding, double tickMs, double heap) {
			if (pregenProactiveSampledNanos != 0L
					&& now - pregenProactiveSampledNanos < PREGEN_NEIGHBOR_STALL_SAMPLE_INTERVAL_NS) {
				return pregenProactivePauseActive;
			}
			pregenProactiveSampledNanos = now;

			// v213: validate ownership BEFORE interpreting readiness. The normal
			// architecture requires one routine radius-3 self-ticket for every live
			// target. If that invariant is broken, repair a bounded batch immediately
			// instead of waiting for the 5s recovery lease to mask the real defect.
			int missingSelfOwnership = OceanCanvasSurfaceFlattener.missingPregenSelfTicketCount();
			if (missingSelfOwnership > 0) {
				OceanCanvasSurfaceFlattener.repairMissingPregenSelfTickets(world, PREGEN_PROACTIVE_TICKET_REPAIR_BUDGET);
				missingSelfOwnership = OceanCanvasSurfaceFlattener.missingPregenSelfTicketCount();
			}
			// v230.5: explicit FULL-demand slot refill is now serviced by the per-tick
			// adaptive controller, not by this once-per-second health sampler. Keeping
			// it here imposed an accidental ~2 chunks/second rescue ceiling: once both
			// bounded bridges completed, their slots could sit idle for almost a full
			// second before this sampler ran again. The global two-active cap still
			// provides the concurrency bound; the hot controller only removes slot-idle
			// latency while a genuinely cold target remains.
			var tickets = OceanCanvasSurfaceFlattener.pregenTicketDiagnostics();
			boolean ticketOwnershipGap = missingSelfOwnership > 0;
			boolean queueOwnershipGap = d.queued() > outstanding;
			boolean laneOwnershipGap = tickets.carveLaneActive() > d.queued();
			// v228.3: do NOT add a second radius-0 FORCED ticket to a target that
			// already owns the normal radius-3 FORCED ticket. v228.2 runtime proved
			// those "rescue pulses" were logically weaker than existing ownership and
			// mostly measured eventual coincidence (hundreds of requests for tens of
			// successes) while adding TicketStorage churn. Hard stale-load tails are
			// now escaped by mandatory persisted deferral in the liveness controller.

			// v218: controller health uses actual current-session pipeline retirements;
			// free skips are valid user progress but must not prove pipeline health.
			long confirmed = OceanCanvasSurfaceFlattener.newTerrainRetirementCount();
			boolean retired = pregenProactiveLastConfirmed >= 0L && confirmed > pregenProactiveLastConfirmed;
			if (pregenProactiveLastConfirmed >= 0L && !retired && outstanding > 0) pregenProactiveNoRetireSamples++;
			else pregenProactiveNoRetireSamples = 0;
			pregenProactiveLastConfirmed = confirmed;

			int carveLane = d.ready() + d.missingNeighbor() + d.missingStructureOwner();
			double staleRatio = d.missingNeighborStale() / (double)Math.max(1, carveLane);
			if (carveLane >= PREGEN_PROACTIVE_MIN_QUEUED
					&& staleRatio >= PREGEN_PROACTIVE_STALE_RATIO
					&& staleRatio > pregenProactiveLastStaleRatio + 0.02D) {
				pregenProactiveRisingSamples++;
			} else if (staleRatio < PREGEN_PROACTIVE_STALE_RATIO || retired) {
				pregenProactiveRisingSamples = 0;
			}
			pregenProactiveLastStaleRatio = staleRatio;

			String state = classifyPregenHealth(d, outstanding);
			// v253.125.26: .25 captured a 1.8s controller stall while the watchdog
			// was cold-linking/concatenating large diagnostic strings. Keep the rolling
			// sample intentionally compact and builder-based; detailed ticket state is
			// already logged separately once per second.
			StringBuilder sampleBuilder = new StringBuilder(320);
			sampleBuilder.append("state=").append(state)
					.append(",confirmed=").append(confirmed)
					.append(",out=").append(outstanding)
					.append(",q=").append(d.queued())
					.append(",ready=").append(d.ready())
					.append(",load=").append(d.loadingNotQueued())
					.append(",loadStale=").append(d.loadingStale())
					.append(",neighborStale=").append(d.missingNeighborStale())
					.append(",self=").append(tickets.selfActive())
					.append(",fut=").append(tickets.targetFutures())
					.append(",fd=").append(tickets.fullDemandRequests()).append('/')
					.append(tickets.fullDemandCompletions()).append('/')
					.append(tickets.fullDemandFailures()).append('/')
					.append(tickets.fullDemandCancellations())
					.append(",gap=").append(ticketOwnershipGap || queueOwnershipGap || laneOwnershipGap)
					.append(",noRetire=").append(pregenProactiveNoRetireSamples)
					.append(",tickMs=").append(Math.round(tickMs))
					.append(",heapPct=").append(Math.round(heap * 100.0D));
			String sample = sampleBuilder.toString();
			pregenFlightRecorder.addLast(sample);
			while (pregenFlightRecorder.size() > PREGEN_FLIGHT_RECORDER_SAMPLES) pregenFlightRecorder.removeFirst();

			boolean trajectoryRisk = carveLane >= PREGEN_PROACTIVE_MIN_QUEUED
					&& pregenProactiveRisingSamples >= PREGEN_PROACTIVE_RISING_SAMPLES;
			boolean zeroProgressInvariant = carveLane >= 4 && d.loadingNotQueued() == 0 && d.ready() == 0
					&& d.missingNeighborStale() > 0
					&& pregenProactiveNoRetireSamples >= PREGEN_PROACTIVE_NO_RETIRE_SAMPLES;
			boolean staleLoadRisk = d.loadingStale() >= Math.max(4, outstanding / 2)
					&& d.oldestLoadingMs() >= PREGEN_PROACTIVE_LOAD_STALL_MS
					&& pregenProactiveNoRetireSamples >= PREGEN_PROACTIVE_NO_RETIRE_SAMPLES;
			boolean auditRegression = tickets.auditRepeatPending() > 0;
			boolean shouldHold = trajectoryRisk || zeroProgressInvariant || staleLoadRisk || auditRegression
					|| ticketOwnershipGap || queueOwnershipGap || laneOwnershipGap;
			if (!pregenProactivePauseActive && shouldHold) {
				pregenProactivePauseActive = true;
				if (ticketOwnershipGap || queueOwnershipGap || laneOwnershipGap) {
					OceanCanvas.LOGGER.error("(Ocean Canvas) v213 Pregen ownership invariant failed BEFORE stall: {}. "
							+ "Admission is held; do not treat this run as clean until ownership converges.", sample);
				}
				if (staleLoadRisk) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) v230.5 predictive LOAD-STALLED hold BEFORE watchdog: {}. Bounded cold-tail FULL demand is active; hard-stall quarantine remains armed.", sample);
					net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.capturePregenStall(
							world, d.oldestLoadingMs() * 1_000_000L, "predictive-load-stall " + sample);
				}
				if (auditRegression) {
					OceanCanvas.LOGGER.error("(Ocean Canvas) v230.5 predictive AUDIT-REGRESSION hold: repeated post-flatten physical audit failure is still pending. {}", sample);
				}
				OceanCanvas.LOGGER.warn("(Ocean Canvas) v230.5 proactive admission hold BEFORE watchdog: {}. "
						+ "No new targets will be admitted while existing work proves it can retire.", sample);
				if (now - pregenLastFlightDumpNanos >= PREGEN_FLIGHT_DUMP_COOLDOWN_NS) {
					pregenLastFlightDumpNanos = now;
					StringBuilder history = new StringBuilder(4096);
					int count = 0;
					for (String prior : pregenFlightRecorder) {
						if (count++ > 0) history.append(" || ");
						if (history.length() + prior.length() > 4096) { history.append("<truncated>"); break; }
						history.append(prior);
					}
					OceanCanvas.LOGGER.warn("(Ocean Canvas) v253.125.26 compact pregen flight recorder samples={} chars={} history={}",
							count, history.length(), history);
				}
			} else if (pregenProactivePauseActive) {
				boolean ownershipHealthy = !ticketOwnershipGap && !queueOwnershipGap && !laneOwnershipGap && !auditRegression;
				// v228.3: v228.2 incorrectly treated "everything is still loading" as
				// recovery. That released the hold while loadingStale==outstanding and
				// oldestLoadMs was already 10-20s, immediately admitting more work and
				// creating the repeated release/re-hold sawtooth in the real log. A load
				// state is recovered only after stale loading actually clears; a genuine
				// retirement is also sufficient proof of forward progress.
				// v228.5: one lucky retirement is not recovery while the remaining
				// cohort is still neighbor/load stalled. v228.4 released a proactive
				// hold at 88% stale neighbors solely because one target retired, then
				// immediately fell back into the breaker. Require both load and neighbor
				// health before a retirement can prove recovery.
				boolean neighborHealthy = d.missingNeighborStale() == 0
						|| staleRatio <= PREGEN_PROACTIVE_RESUME_RATIO;
				boolean loadHealthy = d.loadingStale() == 0
						&& d.oldestLoadingMs() < PREGEN_PROACTIVE_LOAD_STALL_MS;
				boolean recovered = outstanding == 0
						|| (ownershipHealthy && retired && neighborHealthy && loadHealthy)
						|| (ownershipHealthy && loadHealthy && neighborHealthy
								&& (d.ready() > 0 || d.loadingNotQueued() > 0));
				if (recovered) {
					pregenProactivePauseActive = false;
					pregenProactiveRisingSamples = 0;
					OceanCanvas.LOGGER.info("(Ocean Canvas) v230.5 proactive admission hold released after observed recovery: {}", sample);
				}
			}
			if (!state.equals(pregenHealthState)) {
				OceanCanvas.LOGGER.info("(Ocean Canvas) v230.5 pregen health {} -> {}: {}", pregenHealthState, state, d.shortText());
				pregenHealthState = state;
			}
			return pregenProactivePauseActive;
		}

		private int adaptivePregenBudget(int configuredStartRate) {
            AdaptiveProfileParams params=adaptiveParams(world,configuredStartRate);
			if (adaptivePregenRate <= 0) {
				adaptivePregenRate = OceanCanvasPregenMetrics.conservativeStartRate(calibration(world),calibrationProfile,
                        configuredStartRate,params.maxRate());
				adaptiveLastSubmitted = submittedCount;
				adaptiveLastSkipped = skippedProcessed;
				adaptiveLastWindowNanos = System.nanoTime();
				adaptiveLastOutstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
				adaptiveLastPhysicalRetired = OceanCanvasSurfaceFlattener.newTerrainRetirementCount();
				adaptiveLastForwardRetired = OceanCanvasSurfaceFlattener.jobForwardRetirementCount();
			}
            // A switch to Quiet/Custom must obey its lower cap immediately, not at the next growth step.
            adaptivePregenRate=Math.min(adaptivePregenRate,params.maxRate());
            feedReason="Feeding within profile limits";

			int outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
			double heap = heapUseFraction();
			double tickMs = sampleAdaptiveTickMs();

			// v96: tune queue DEPTH independently from chunks/tick. A large fixed
			// outstanding window was efficient in short tests but allowed C2ME holder
			// residency to accumulate across a multi-hour 20k run. React to both
			// current pressure and the direction heap use is moving.
			if (previousHeapSample >= 0.0D) {
				double delta = heap - previousHeapSample;
				heapSlopeEma = heapSlopeEma * 0.80D + delta * 0.20D;
			}
			previousHeapSample = heap;
			// v102 restores the spatial streaming window that made the proven
			// pre-v96 pipeline fast. The v98 48-target cap was small enough for a
			// few 3x3 support neighborhoods to monopolize the whole window. Keep
			// the original profile ceilings (Balanced 224 / Overnight 320) and
			// contract only when real heap/cadence pressure exists.
			int ceiling = params.targetOutstanding();
			int desiredOutstanding;
			if (heap >= 0.82D || tickMs >= params.pauseTickMs()) desiredOutstanding = PREGEN_MIN_DYNAMIC_OUTSTANDING;
			else if (heap >= 0.76D || heapSlopeEma >= 0.015D || tickMs >= params.backoffTickMs()) desiredOutstanding = Math.min(64, ceiling);
			else if (heap >= 0.68D || heapSlopeEma >= 0.010D || tickMs >= params.growTickMs()) desiredOutstanding = Math.min(128, ceiling);
			else desiredOutstanding = ceiling;

			// v216 startup canary. Establish a session-local confirmed-completion
			// baseline and keep the inlet tiny until real physical retirement proves
			// ownership -> loading -> readiness -> carve -> audit -> retirement works.
			var canaryTickets = OceanCanvasSurfaceFlattener.pregenTicketDiagnostics();
			if (pregenCanaryBaselineConfirmed < 0L) {
				pregenCanaryBaselineConfirmed = OceanCanvasSurfaceFlattener.newTerrainRetirementCount();
				pregenCanaryRecoveryBaseline = canaryTickets.finalDrainInstalls();
			}
			long canaryRecoveryInstalls = canaryTickets.finalDrainInstalls();
			if (!pregenCanaryUnlockLogged && pregenCanaryRecoveryBaseline >= 0L
					&& canaryRecoveryInstalls > pregenCanaryRecoveryBaseline) {
				pregenCanaryBaselineConfirmed = OceanCanvasSurfaceFlattener.newTerrainRetirementCount();
				pregenCanaryRecoveryBaseline = canaryRecoveryInstalls;
				OceanCanvas.LOGGER.warn("(Ocean Canvas) v224 startup canary proof reset after emergency final-drain assistance; four emergency-recovery-free physical retirements are required before admission can unlock.");
			}
			long canaryRetirements = Math.max(0L, OceanCanvasSurfaceFlattener.newTerrainRetirementCount() - pregenCanaryBaselineConfirmed);
			boolean startupCanaryActive = canaryRetirements < PREGEN_STARTUP_CANARY_RETIREMENTS;
			if (startupCanaryActive) desiredOutstanding = Math.min(desiredOutstanding, PREGEN_STARTUP_CANARY_OUTSTANDING);
			else if (!pregenCanaryUnlockLogged) {
				pregenCanaryUnlockLogged = true;
				OceanCanvas.LOGGER.info("(Ocean Canvas) v230.5 startup canary PASS: {} emergency-recovery-free authoritative pipeline retirements; proof-of-capacity admission unlocked.", canaryRetirements);
			}

			// v214 proof-of-capacity queue window. v96-v213 primarily decided queue
			// depth from resource pressure, which answers "can the machine hold more?"
			// but not the more important question "can this pipeline RETIRE more?".
			// A brand-new/resumed pipeline gets 32 targets. Thereafter its permitted
			// depth is derived from observed completion throughput plus four seconds of
			// latency-hiding headroom. The observed v230.1 C2ME cold-tail is commonly
			// ~3.5-4.0s, so the proof window must cover four seconds of retirement
			// capacity. A 3 chunks/s drain earns ~20 outstanding; a 36 chunks/s drain earns ~152; high-throughput runs can still reach the
			// profile ceiling. This prevents a silent downstream failure from ever
			// accumulating a 200+ target stale cohort before a watchdog notices.
			int provenOutstanding = adaptiveCompletionRateEma <= 0.0D
					? PREGEN_UNPROVEN_OUTSTANDING
					: PREGEN_PROVEN_OUTSTANDING_HEADROOM
						+ (int)Math.ceil(adaptiveCompletionRateEma * PREGEN_PROVEN_LATENCY_SECONDS);
			provenOutstanding = Math.max(PREGEN_MIN_DYNAMIC_OUTSTANDING, Math.min(ceiling, provenOutstanding));
			desiredOutstanding = Math.min(desiredOutstanding, provenOutstanding);
			// v228.3 latency-aware depth governor. Throughput alone is not proof that
			// every admitted target is healthy: v228.2 sustained ~20/s while one cold
			// tail aged beyond 80s. Contract the earned window as soon as load age says
			// the scheduler is accumulating debt, before the binary health hold fires.
			var depthDiag = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
			if (depthDiag.loadingStale() > 0 && depthDiag.oldestLoadingMs() >= PREGEN_LOAD_DEPTH_HARD_CLAMP_MS) {
				desiredOutstanding = Math.min(desiredOutstanding, PREGEN_LOAD_DEPTH_HARD_OUTSTANDING);
			} else if (depthDiag.loadingStale() > 0 && depthDiag.oldestLoadingMs() >= PREGEN_LOAD_DEPTH_SOFT_CLAMP_MS) {
				desiredOutstanding = Math.min(desiredOutstanding, PREGEN_LOAD_DEPTH_SOFT_OUTSTANDING);
			}
			desiredOutstanding = Math.min(desiredOutstanding, PREGEN_CARVE_PIPELINE_HARD_OUTSTANDING);
			if (adaptiveOutstandingTarget <= 0) adaptiveOutstandingTarget = desiredOutstanding;
			else if (desiredOutstanding < adaptiveOutstandingTarget) adaptiveOutstandingTarget = Math.max(desiredOutstanding, adaptiveOutstandingTarget - 8);
			else if (desiredOutstanding > adaptiveOutstandingTarget) adaptiveOutstandingTarget = Math.min(desiredOutstanding, adaptiveOutstandingTarget + 16);

			// The previous controller watched queue depth + heap but could ramp up
			// while the integrated server was hundreds of seconds behind. Treat
			// actual tick cadence as a first-class backpressure signal. v99 gives a
			// resumed job a short cadence-grace window because client/server startup
			// spikes are not representative of steady-state Pregen capacity.
			long adaptiveNow = System.nanoTime();
			boolean resumeBootstrap = adaptiveNow < resumeBootstrapUntilNanos;
			// v220: startup/resume grace may suppress ordinary cadence backpressure for a
			// few seconds, but it must never suppress an extreme integrated-server stall.
			// v219's first canary samples were already ~211-321ms while the 15s grace
			// was active; allowing more admission in that state contributed to a 24.2s
			// server-behind event. Grace is now only five seconds, and >=120ms always
			// closes admission immediately.
			boolean cadencePause = tickMs >= PREGEN_EMERGENCY_CADENCE_PAUSE_MS
					|| (tickMs >= params.pauseTickMs() && !resumeBootstrap);
			// v253.125.33: preserve the stop decision across a short run of deceptively
			// good samples after a severe spike. This is especially important on an
			// integrated client where JourneyMap/advancement/GC work may complete just
			// before the controller samples again.
			double previousTickWorkMs = OceanCanvasTickTelemetry.workMs();
			double previousTickIntervalMs = OceanCanvasTickTelemetry.intervalMs();
			long pressureCooldownNs = 0L;
			String pressureCooldownReason = "clear";
			if (heap >= 0.96D || previousTickWorkMs >= 250.0D || previousTickIntervalMs >= 500.0D) {
				pressureCooldownNs = PREGEN_RUNTIME_PRESSURE_CRITICAL_COOLDOWN_NS;
				pressureCooldownReason = "critical";
			} else if (heap >= 0.90D || previousTickWorkMs >= 100.0D || previousTickIntervalMs >= 150.0D) {
				pressureCooldownNs = PREGEN_RUNTIME_PRESSURE_HARD_COOLDOWN_NS;
				pressureCooldownReason = "hard";
			}
			if (pressureCooldownNs > 0L) {
				long oldUntil = pregenRuntimePressureCooldownUntilNanos;
				pregenRuntimePressureCooldownUntilNanos = Math.max(oldUntil, adaptiveNow + pressureCooldownNs);
				if (oldUntil <= adaptiveNow && !pregenRuntimePressureCooldownLogged) {
					pregenRuntimePressureCooldownLogged = true;
					// .33 could re-enter this short cooldown hundreds of times during a
					// sustained high-heap run. Keep the control action, but emit at most one
					// heartbeat every 30 seconds so logging is not part of the pressure.
					if (pregenRuntimePressureLastLogNanos == 0L
							|| adaptiveNow - pregenRuntimePressureLastLogNanos >= 30_000_000_000L) {
						pregenRuntimePressureLastLogNanos = adaptiveNow;
						OceanCanvas.LOGGER.info("(Ocean Canvas) PREGEN-RUNTIME-PRESSURE-HOLD build={} reason={} heapPct={} previousTickWorkMs={} previousTickIntervalMs={} cooldownMs={} action=stop-new-admission-until-runtime-recovers",
								net.oceancanvas.mod.OceanCanvas.VERSION, pressureCooldownReason,
								Math.round(heap * 100.0D), Math.round(previousTickWorkMs), Math.round(previousTickIntervalMs),
								pressureCooldownNs / 1_000_000L);
					}
				}
			}
			boolean runtimePressureCooldown = adaptiveNow < pregenRuntimePressureCooldownUntilNanos;
			if (!runtimePressureCooldown) pregenRuntimePressureCooldownLogged = false;
			boolean autosaveGuardPause = OceanCanvasTickTelemetry.autosaveGuardActive();
			boolean hardPause = heap >= PREGEN_HEAP_PAUSE_FRACTION || outstanding >= params.hardQueue();
			boolean depthPause = outstanding >= adaptiveOutstandingTarget;
			updateNeighborStallBreaker(adaptiveNow);
			boolean neighborStallPause = pregenNeighborStallBreakerActive;
			var adaptiveQueue = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
            // P2/F217: storage pressure is measured independently from tick/heap pressure.
            // The governor is fail-safe: it may only reduce or close admission and never owns
            // completion. Existing v230.5 stale-load holds remain the authoritative hard gates.
            var ioPressure=OceanCanvasIoPressureGovernor.evaluate(adaptivePregenRate,outstanding,
                    adaptiveQueue.loadingNotQueued(),adaptiveQueue.loadingStale(),adaptiveQueue.oldestLoadingMs());
            int ioAdmissionCap=ioPressure.admissionCap();
            boolean ioPressurePause=ioPressure.state()==OceanCanvasIoPressureGovernor.State.SATURATED
                    ||ioPressure.state()==OceanCanvasIoPressureGovernor.State.STALLED;
			int transientTickets = OceanCanvasSurfaceFlattener.trackedTransientTicketCount();
			int ticketSoftLimit = Math.max(128, params.hardQueue());
			int ticketHardLimit = Math.max(ticketSoftLimit + 64, (ticketSoftLimit * 3) / 2);
			var resourceBudget = OceanCanvasResourceBudgetGovernor.evaluate(
					adaptivePregenRate, OceanCanvasTickTelemetry.workMs(), heap, ioPressure,
					transientTickets, ticketSoftLimit, ticketHardLimit);
			lastResourceBudgetDecision = resourceBudget;
			int resourceAdmissionCap = resourceBudget.admissionCap();
			boolean resourceBudgetPause = resourceBudget.state() == OceanCanvasResourceBudgetGovernor.State.SATURATED;
			// v230.5: keep the bounded cold-tail accelerator work-conserving. v230.4
			// correctly held each FULL-demand slot until authoritative server-thread
			// delivery, but only refilled empty slots from the 1 Hz health sampler. In
			// the 06:53-06:56 runtime that produced six LOAD_STALLED episodes and a
			// 12.341s oldest target while req/done advanced almost exactly two per
			// second. Refill from this per-tick controller instead. At most two bridges
			// can still exist globally, each target still gets the 1s ticket-first grace,
			// and a slot is still not released until its server-thread handoff completes.
			if (!runtimePressureCooldown && adaptiveQueue.loadingNotQueued() > 0) {
				OceanCanvasSurfaceFlattener.serviceStalledPregenTargetLoads(world, PREGEN_FULL_DEMAND_REFILL_BUDGET);
			}
			int loadingPressureThreshold = Math.max(PREGEN_LOADING_BACKLOG_MIN,
					(int)Math.ceil(adaptiveOutstandingTarget * PREGEN_LOADING_BACKLOG_FRACTION));
			boolean loadingBacklogPause = adaptiveQueue.loadingNotQueued() >= loadingPressureThreshold;
			// v230.5: v230.2 proved that aggregate ~20 chunks/s retirement can hide a
			// genuinely sick cold-load cohort. The old proactive stale-load hold also
			// required several zero-retirement samples, so unrelated healthy retirements
			// kept resetting the evidence while individual targets aged past 11s. Treat
			// load age as target-local debt: a majority stale cohort at >=5s closes the
			// inlet, and any >=10s stale target closes it even if other targets retire.
			int staleLoadMajority = Math.max(4, (adaptiveQueue.loadingNotQueued() + 1) / 2);
			boolean staleLoadCohortPause = adaptiveQueue.loadingNotQueued() >= 4
					&& adaptiveQueue.loadingStale() >= staleLoadMajority
					&& adaptiveQueue.oldestLoadingMs() >= PREGEN_LOAD_DEPTH_SOFT_CLAMP_MS;
			boolean staleLoadHardPause = adaptiveQueue.loadingStale() > 0
					&& adaptiveQueue.oldestLoadingMs() >= PREGEN_LOAD_DEPTH_HARD_CLAMP_MS;
			boolean staleLoadDebtPause = staleLoadCohortPause || staleLoadHardPause;
			boolean proactiveHealthPause = updateProactivePregenHealth(adaptiveNow, adaptiveQueue, outstanding, tickMs, heap);
			int adaptiveCarveLane = adaptiveQueue.ready() + adaptiveQueue.missingNeighbor() + adaptiveQueue.missingStructureOwner();
			double admissionStaleRatio = adaptiveQueue.missingNeighborStale()
					/ (double)Math.max(1, adaptiveCarveLane);
			// v214: trajectory pressure now changes the inlet BEFORE the binary hold.
			// 10% stale halves new work; 20% stale with no active loading closes the
			// inlet immediately. This converts stale ratio from telemetry into a true
			// leading control signal and prevents two one-second samples from adding a
			// large cohort while the trend is already visibly wrong.
			boolean preemptiveNoReadyPause = adaptiveCarveLane >= PREGEN_PROACTIVE_MIN_QUEUED
					&& adaptiveQueue.loadingNotQueued() == 0 && adaptiveQueue.ready() == 0
					&& admissionStaleRatio >= PREGEN_RISK_HARD_RATIO;
			boolean recoveryRampActive = adaptiveNow < pregenRecoveryRampUntilNanos;

			// v132.5: the previous logs showed lease INSTALLS but no successful
			// RELEASES, making a backend ticket leak indistinguishable from a
			// healthy rotating recovery window. Emit one compact INFO snapshot per
			// second even while admission is paused, plus DEBUG lifecycle events in
			// OceanCanvasSurfaceFlattener. INFO is intentional so latest.log alone
			// is sufficient for the diagnostic run without launcher logger changes.
			long ticketHealthIntervalNs = (outstanding == 0
					&& adaptiveQueue.queued() == 0
					&& adaptiveQueue.ready() == 0
					&& adaptiveQueue.missingNeighbor() == 0
					&& adaptiveQueue.missingStructureOwner() == 0
					&& adaptiveQueue.loadingNotQueued() == 0)
					? 15_000_000_000L : 5_000_000_000L;
			if (pregenTicketHealthLoggedNanos == 0L
					|| adaptiveNow - pregenTicketHealthLoggedNanos >= ticketHealthIntervalNs) {
				pregenTicketHealthLoggedNanos = adaptiveNow;
				var tickets = OceanCanvasSurfaceFlattener.pregenTicketDiagnostics();
				var queue = adaptiveQueue;
				OceanCanvas.LOGGER.info(
						"(Ocean Canvas) v230.5 ticket health: self={}(+{}/-{}), lane={}, leases={}(+{}/-{}), finalDrain={}(+{}/-{}), futures=target:{}/support:{}(done={}), fullDemand=req:{}/done:{}/fail:{}/cancel:{}, loadRescue={}(+{}/ok={}), audit=pending:{}/repeatPending:{}/reject:{}/repair:{}/repeatTotal:{}, outstanding={}, {}",
						tickets.selfActive(), tickets.selfInstalls(), tickets.selfReleases(), tickets.carveLaneActive(),
						tickets.processingLeaseActive(), tickets.processingLeaseInstalls(), tickets.processingLeaseReleases(),
						tickets.finalDrainActive(), tickets.finalDrainInstalls(), tickets.finalDrainReleases(),
						tickets.targetFutures(), tickets.supportFutures(), tickets.supportFuturesDone(),
						tickets.fullDemandRequests(), tickets.fullDemandCompletions(), tickets.fullDemandFailures(), tickets.fullDemandCancellations(),
						tickets.loadRescueActive(), tickets.loadRescueRequests(), tickets.loadRescueSuccesses(),
						tickets.auditPending(), tickets.auditRepeatPending(), tickets.auditRejections(), tickets.auditRepairs(), tickets.auditRepeatFailures(),
						outstanding, queue.shortText());
			}

			if (cadencePause || runtimePressureCooldown || autosaveGuardPause || hardPause || depthPause || neighborStallPause || loadingBacklogPause || staleLoadDebtPause || ioPressurePause || resourceBudgetPause || proactiveHealthPause || preemptiveNoReadyPause) {
				if (adaptivePauseStartedNanos == 0L) adaptivePauseStartedNanos = adaptiveNow;
				if (cadencePause) feedReason = "Tick cadence limit";
				else if (runtimePressureCooldown) feedReason = "Post-spike runtime pressure cooldown";
				else if (autosaveGuardPause) feedReason = "Vanilla autosave guard (drain dirty-set before serializer sweep)";
				else if (hardPause) feedReason = heap >= PREGEN_HEAP_PAUSE_FRACTION ? "Heap pressure" : "Hard queue limit";
				else if (neighborStallPause) feedReason = "Neighbor-stall circuit breaker (backlog recovering)";
				else if (staleLoadDebtPause) feedReason = staleLoadHardPause
						? "Cold-load debt hard hold (>=10s target)"
						: "Cold-load debt cohort hold (majority stale >=5s)";
                else if (ioPressurePause) feedReason = "I/O pressure governor: "+ioPressure.reason();
				else if (resourceBudgetPause) feedReason = "Resource budget: " + resourceBudget.reason();
				else if (proactiveHealthPause) feedReason = "Proactive health hold (trajectory risk)";
				else if (preemptiveNoReadyPause) feedReason = "Preemptive no-ready hold (stale frontier)";
				else if (loadingBacklogPause) feedReason = "Outstanding load backlog " + adaptiveQueue.loadingNotQueued();
				else feedReason = startupCanaryActive
						? "Startup canary: proving retirement " + canaryRetirements + "/" + PREGEN_STARTUP_CANARY_RETIREMENTS
						: "Adaptive queue-depth limit " + adaptiveOutstandingTarget;
				// v116: this halving is a real-overload signal - heap pressure,
				// tick cadence, or queue depth all mean "the server told us to
				// slow down." A pure neighborStallPause with none of those other
				// signals active is a DIFFERENT thing: the real v113-v115 logs
				// showed the breaker engaging on ~90-100% of the queue almost
				// continuously (every ~10-15s), and every single engagement
				// collapsed adaptivePregenRate straight to its floor of 1 -
				// even though nothing about server load actually changed. That
				// crushed rate then had only a few brief "released" windows per
				// minute to recover via the slow additive growth path below,
				// producing exactly the flatlined rate=1-3/tick and
				// completed~1-3 chunks/s seen in every prior round's log
				// despite heap sitting comfortably at 20-50%. Skip the halve
				// when neighbor-stall is the ONLY active reason - admission is
				// still correctly paused below via the early return, so no new
				// targets get force-fed either way.
				if (cadencePause || autosaveGuardPause || hardPause || depthPause || loadingBacklogPause) {
					adaptivePregenRate = Math.max(1, adaptivePregenRate / 2);
				}

				// v101: paused means paused. Do not smuggle one new target through
				// after a timer expires. Existing live work is allowed to retire, and
				// the liveness watchdog may DEFER a wedged target without counting it
				// complete, but adaptive backpressure itself never force-feeds work.
				return 0;
			}
			adaptivePauseStartedNanos = 0L;

			// v210: an integer chunks-per-tick controller cannot pace below ~20 chunks/s.
			// During the post-stall ramp, use tick spacing to represent the actual
			// retirement rate. This prevents a 1-3 chunks/s drain from being refilled
			// at 20 chunks/s simply because the minimum integer rate is 1/tick.
			if (recoveryRampActive) {
				double provenEma = Math.max(adaptiveCompletionRateEma, adaptiveForwardRateEma);
				double observed = provenEma > 0.0D ? provenEma : 1.0D;
				double allowedPerSecond = Math.max(1.0D, Math.min(20.0D, observed * 1.25D));
				int ticksPerAdmission = Math.max(1, (int)Math.ceil(20.0D / allowedPerSecond));
				recoveryAdmissionTick++;
				if (recoveryAdmissionTick < ticksPerAdmission) {
					feedReason = "Recovery ramp (fractional retirement pace)";
					return 0;
				}
				recoveryAdmissionTick = 0;
			}

			adaptiveTicks++;
			if (adaptiveTicks >= PREGEN_ADAPT_INTERVAL_TICKS) {
				long submittedDelta = Math.max(0L, (submittedCount-skippedProcessed) - (adaptiveLastSubmitted-adaptiveLastSkipped));
				// v218: capacity credit comes only from authoritative physical retirements.
				// The old submittedDelta-outstandingDelta estimate falsely counted deferral
				// and replay withdrawal as successful completion, allowing an unhealthy
				// pipeline to earn a larger queue.
				long physicalRetiredNow = OceanCanvasSurfaceFlattener.newTerrainRetirementCount();
				long completedDelta = Math.max(0L, physicalRetiredNow - adaptiveLastPhysicalRetired);
				// v253.73.16: a resumed job re-walks the band between the committed and
				// submitted cursors. Those chunks retire via "existing-seal" (and recovery
				// targets retire with crashRecoveryAtEntry set), so completedDelta above
				// can be structurally zero for the entire session while the cursor is
				// visibly advancing. Measure that advance separately.
				long forwardRetiredNow = OceanCanvasSurfaceFlattener.jobForwardRetirementCount();
				long forwardDelta = Math.max(0L, forwardRetiredNow - adaptiveLastForwardRetired);
				long windowNow=System.nanoTime();
				double windowSeconds=Math.max(0.001,(windowNow-adaptiveLastWindowNanos)/1_000_000_000.0);
				double completedPerSecond=completedDelta/windowSeconds;
				double forwardPerSecond=forwardDelta/windowSeconds;
				adaptiveCompletionRateEma = adaptiveCompletionRateEma <= 0.0D
						? completedPerSecond
						: adaptiveCompletionRateEma * 0.70D + completedPerSecond * 0.30D;
				adaptiveForwardRateEma = adaptiveForwardRateEma <= 0.0D
						? forwardPerSecond
						: adaptiveForwardRateEma * 0.70D + forwardPerSecond * 0.30D;
				updateForeverWorldThroughputPrediction(windowNow, adaptiveCompletionRateEma, adaptiveForwardRateEma);

				// v102: C2ME is a latency pipeline. A one-second window where newly
				// submitted chunks have not retired yet is not evidence that we are
				// falling behind. Grow/back off from actual pressure and queue
				// saturation; completion throughput remains telemetry, not a false
				// immediate-pressure signal.
				boolean comfortablyDraining =
						outstanding < Math.max(PREGEN_MIN_DYNAMIC_OUTSTANDING, (adaptiveOutstandingTarget * 3) / 4)
						&& heap < PREGEN_HEAP_GROWTH_THRESHOLD
						&& tickMs < params.growTickMs();

				boolean fallingBehind =
						outstanding >= adaptiveOutstandingTarget
						|| heap >= 0.76D
						|| tickMs >= params.backoffTickMs();

				if (recoveryRampActive) {
					// v202.43: pace refill from observed RETIREMENTS, not merely from
					// low queue depth. A run completing ~1 chunk/s must not immediately
					// be allowed to submit 40-60 chunks/s again. Convert EMA chunks/s
					// to a conservative per-tick ceiling with 50% headroom; floor at 1.
					// v253.73.16: pace against the larger of the two PROVEN retirement
					// rates. v202.43's invariant - never refill faster than observed
					// retirement - is preserved, because a forward retirement is just as
					// real a retirement as a carve. Using the carve-only figure while a
					// resume re-walks an existing seal band pins the cap at 1/tick for a
					// pipeline that is in fact retiring several chunks per second.
					double provenRetirementEma = Math.max(adaptiveCompletionRateEma, adaptiveForwardRateEma);
					int retirementPacedCap = Math.max(1,
							(int)Math.ceil((provenRetirementEma * 1.50D) / 20.0D));
					adaptivePregenRate = Math.min(adaptivePregenRate, Math.min(params.maxRate(), retirementPacedCap));
					if (completedDelta > 0 && comfortablyDraining) {
						adaptivePregenRate = Math.min(Math.min(params.maxRate(), retirementPacedCap),
								adaptivePregenRate + 1);
					}
					feedReason = "Recovery ramp (retirement-paced)";
				} else if (comfortablyDraining) {
					// Normal steady-state additive/mild multiplicative growth.
					int increase = Math.max(1, adaptivePregenRate / 4);
					adaptivePregenRate = Math.min(params.maxRate(), adaptivePregenRate + increase);
				} else if (fallingBehind) {
					adaptivePregenRate = Math.max(1,
							(int)Math.ceil(adaptivePregenRate * 0.70D));
				}

				boolean adaptiveWorkChanged = submittedDelta > 0L || completedDelta > 0L || outstanding > 0;
				if (adaptiveWorkChanged || adaptiveLastInfoLogNanos == 0L
						|| windowNow - adaptiveLastInfoLogNanos >= 10_000_000_000L) {
					adaptiveLastInfoLogNanos = windowNow;
					OceanCanvas.LOGGER.info(
							"(Ocean Canvas) Adaptive pregen: rate={}/tick, outstanding={}/{}, heap={}%, slope={}ppt, cadence~{}ms, "
									+ "submitted={} chunks/s, completed~{} chunks/s, forward~{} chunks/s (wall-time, skips excluded)",
							adaptivePregenRate, outstanding, adaptiveOutstandingTarget, (int)Math.round(heap * 100.0D),
							String.format(java.util.Locale.ROOT, "%.2f", heapSlopeEma * 100.0D),
							(int)Math.round(tickMs), Math.round(submittedDelta/windowSeconds), Math.round(completedDelta/windowSeconds),
							Math.round(forwardDelta/windowSeconds));
				}

				adaptiveLastSubmitted = submittedCount;
                adaptiveLastSkipped=skippedProcessed;
                adaptiveLastWindowNanos=windowNow;
				adaptiveLastOutstanding = outstanding;
				adaptiveLastPhysicalRetired = physicalRetiredNow;
				adaptiveLastForwardRetired = forwardRetiredNow;
				adaptiveTicks = 0;
			}
			// v214 soft leading-edge throttle. Even before a hard hold, a frontier
			// with >=10% stale targets may admit at most half the learned rate. This
			// is intentionally applied last so no later growth branch can undo it.
			if (adaptiveCarveLane >= PREGEN_PROACTIVE_MIN_QUEUED
					&& admissionStaleRatio >= PREGEN_RISK_SOFT_RATIO) {
				int softCap = Math.max(1, adaptivePregenRate / 2);
				if (softCap < adaptivePregenRate) feedReason = "Predictive stale-frontier throttle";
				return Math.min(softCap, resourceAdmissionCap);
			}
            if(ioPressure.state()==OceanCanvasIoPressureGovernor.State.ELEVATED&&ioAdmissionCap<adaptivePregenRate)
                feedReason="I/O pressure governor: elevated FULL-load backlog";
			if (resourceBudget.constrained() && resourceBudget.limiter() != OceanCanvasResourceBudgetGovernor.Limiter.IO)
				feedReason = "Resource budget: " + resourceBudget.reason();
			return Math.min(adaptivePregenRate, resourceAdmissionCap);
		}

		/**
		 * v222 real-time admission governor. Minecraft catch-up ticks can run much
		 * faster than 20 TPS after a long server stall; a chunks-per-tick limit alone
		 * therefore permits huge wall-time bursts exactly when C2ME is already under
		 * pressure. Refill a small token bucket from measured physical retirement
		 * throughput and cap its burst at eight targets.
		 */
		private int wallTimeAdmissionBudget(int requested, AdaptiveProfileParams params) {
			long now = System.nanoTime();
			if (pregenAdmissionTokenNanos == 0L) {
				pregenAdmissionTokenNanos = now;
				// v223: never begin a fresh/resumed stream with an eight-target burst.
				// The canary itself is supposed to prove capacity, so bootstrap with a
				// single token and earn subsequent admissions from wall time.
				pregenAdmissionTokens = Math.min(1.0D, Math.max(0.0D, pregenAdmissionTokens));
			}
			double elapsed = Math.max(0.0D, (now - pregenAdmissionTokenNanos) / 1_000_000_000.0D);
			pregenAdmissionTokenNanos = now;

			// v223: calling this with requested<=0 is intentional. A paused inlet must
			// advance the wall clock and collapse burst credit; otherwise a long cadence/
			// health pause silently refills the bucket and releases a burst the instant
			// the pause ends. That was a hidden positive-feedback path in v222.
			if (requested <= 0) {
				pregenAdmissionTokens = Math.min(pregenAdmissionTokens, 1.0D);
				return 0;
			}

			// v253.125.28: a resumed 20k square can make authoritative forward
			// retirements through the existing-seal/recovery path while the new-terrain
			// retirement counter is near zero. Pace from the larger PROVEN retirement
			// signal, matching the recovery-ramp controller, so a healthy resumed stream
			// is not artificially clamped to the 2-4 chunks/s bootstrap floor.
			double observed = Math.max(adaptiveCompletionRateEma, adaptiveForwardRateEma);
			if (observed <= 0.0D) observed = 2.0D;
			// Stay close to proven drain capacity. The additive allowance only keeps a
			// low-throughput pipeline from deadlocking at exactly zero proof.
			double allowedPerSecond = Math.max(2.0D,
					Math.min(params.maxRate() * 20.0D, observed * 1.20D + 4.0D));
			pregenAdmissionTokens = Math.min(6.0D, pregenAdmissionTokens + elapsed * allowedPerSecond);
			int allowed = Math.min(requested, (int)Math.floor(pregenAdmissionTokens));
			if (allowed <= 0) {
				feedReason = "Wall-time retirement-paced admission";
				return 0;
			}
			pregenAdmissionTokens -= allowed;
			return allowed;
		}

		/**
		 * v120 two-phase pregen, Phase 1: raw chunk generation only.
		 *
		 * <p>The real Chunky-comparison run showed this hardware/mod stack
		 * sustaining 30-70+ chunks/s of raw generation with nothing else
		 * running - the ceiling was never generation throughput itself. What
		 * strangled it was Phase 2's carve pass needing a target and its 8
		 * neighbors all genuinely resident AT THE SAME MOMENT during live
		 * generation, which is a fundamentally harder problem than Chunky
		 * ever solves (Chunky never needs cross-chunk-boundary context - each
		 * chunk is an independent unit of work for it). v103 through v119
		 * were all real fixes to that same hard problem, one edge case at a
		 * time, and still weren't enough over a longer run - see docs/roadmap.md's
		 * Round 25/26 entries for the full history.</p>
		 *
		 * <p>This phase sidesteps the hard problem instead of solving it
		 * further: request FULL status for every target in the region with
		 * no carving, no neighbor checks, and no self-tickets at all - purely
		 * "make it exist", matching what the Chunky comparison run actually
		 * did. Once every target's generation future has resolved, {@link
		 * #tick} switches to Phase 2 (the pre-v120 carve pipeline,
		 * completely unchanged) with a clean cursor. By then every target
		 * and its neighbors are already on disk, so a Phase 2 "load" is a
		 * cheap, fast, reliable disk read instead of a race against live
		 * worldgen threads - the entire class of neighbor-stall problem this
		 * project has spent six rounds patching around should mostly stop
		 * applying, since the condition that caused it (a neighbor
		 * generating, sitting resident a moment, then evicting again before
		 * the carve scan catches up) can't happen when nothing needs to
		 * generate anymore.</p>
		 *
		 * <p>Deliberately NOT gated by {@code pregenChunksPerTick} - that
		 * budget was calibrated for Phase 2's expensive carve cost model and
		 * would throttle this far below what the hardware can actually do.
		 * {@link PregenManager#PREGEN_GENERATE_MAX_OUTSTANDING} bounds
		 * concurrency instead; real throughput is bounded by C2ME's own
		 * executor pool, exactly as it is for Chunky's own raw pregen.</p>
		 *
		 * <p>A target that happens to get carved anyway via the ordinary
		 * organic {@code onChunkLoad -> PENDING_CHUNKS} path while Phase 1 is
		 * still running (possible if its neighbors happen to already be
		 * resident) is harmless - it's just work completed slightly early.
		 * This phase makes no attempt to suppress that; it only guarantees
		 * every target WILL exist on disk before Phase 2 begins, not that
		 * none of them get carved before then.</p>
		 */
		private boolean tickGenerate() {
			generateOutstanding.removeIf(packed -> world.getChunkSource().getChunkNow(
					ChunkPos.getX(packed), ChunkPos.getZ(packed)) != null);

			while (generateOutstanding.size() < PREGEN_GENERATE_MAX_OUTSTANDING && nextIndex < totalChunks) {
				int cx = minChunkX + (int) (nextIndex % rowWidth);
				int cz = minChunkZ + (int) (nextIndex / rowWidth);

				if (!explicitChunks.isEmpty() && !explicitChunks.contains(ChunkPos.pack(cx, cz))) {
					nextIndex++;
					continue;
				}
				if (cx >= excludeMinChunkX && cx <= excludeMaxChunkX
						&& cz >= excludeMinChunkZ && cz <= excludeMaxChunkZ) {
					nextIndex += excludeMaxChunkX - cx + 1;
					continue;
				}

				long packed = ChunkPos.pack(cx, cz);
				// v207: was getChunkFuture(...,false) here, believed safe purely
				// because require=false - proven wrong by a real thread dump
				// (see generateOutstanding's own doc comment above for the full
				// evidence). Now just the real non-blocking ticket force-load;
				// generateOutstanding tracks the position directly, and
				// tickGenerate's own sweep above (getChunkNow) is what detects
				// completion, with no future/blocking call anywhere in this loop.
				net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world, cx, cz);
				generateOutstanding.add(packed);
				nextIndex++;
				submittedCount++;
			}

			updateGenerateBossBar();

			ticksSinceReport++;
			if (ticksSinceReport >= PROGRESS_REPORT_INTERVAL_TICKS) {
				ticksSinceReport = 0;
				String message = "Phase 1 (raw generation): " + submittedCount + "/" + reportedTotalChunks
						+ " chunks requested, " + generateOutstanding.size() + " in flight.";
				OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
				sendToRequesterIfOnline(message);
				persist();
			}

			if (nextIndex < totalChunks || !generateOutstanding.isEmpty()) {
				return false;
			}

			// Every target's raw-generation future has resolved. Hand off to
			// Phase 2 with a clean cursor - see tick()'s branch and this
			// method's class doc for why the pre-v120 carve pipeline below is
			// reused completely unchanged rather than reimplemented.
			pregenPhase = "CARVE";
			nextIndex = 0;
			submittedCount = 0;
			ticksSinceReport = 0;
			String doneMessage = "Phase 1 (raw generation) complete for " + reportedTotalChunks
					+ " target chunk(s) - starting Phase 2 (carving).";
			OceanCanvas.LOGGER.info("(Ocean Canvas) v120 {}", doneMessage);
			sendToRequesterIfOnline(doneMessage);
			persist();
			return false;
		}

		/** Minimal Phase-1-only boss bar update - deliberately not the same
		 * method Phase 2 uses, since that one reads {@code
		 * OceanCanvasSurfaceFlattener.outstandingPregenTargetCount()} and other
		 * carve-pipeline state that Phase 1 never touches. */
		private void updateGenerateBossBar() {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			double fraction = reportedTotalChunks == 0 ? 1.0 : Math.min(1.0, (double) submittedCount / reportedTotalChunks);
			bossEvent.setName(Component.literal(
					"Ocean Canvas - Generating raw chunks (" + submittedCount + "/" + reportedTotalChunks + ")"));
			bossEvent.setProgress((float) fraction);
			bossEvent.addPlayer(player);
		}

		boolean tick(int budget) {
			if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) {
				lastFeedBudget = 0;
				feedReason = "Save & Quit / shutdown preemption";
				return false;
			}
			boolean lightingEmergencyRecoveryEscapeActive = false;
			boolean lightingEmergencyPhysicalOnlyAdmission = false;
			boolean lightingEmergencyForeverWorldTerrainAdmission = false;
			boolean lightingEmergencyForeverWorldContinuityPulse = false;
			// v120 two-phase pregen: Phase 1 (raw generation) is a completely
			// separate, much simpler code path - see tickGenerate()'s class doc
			// for the full rationale. Everything below this branch is Phase
			// 2/legacy single-phase behavior, unchanged from pre-v120.
			// Pregeneration is allowed to be slow; it is not allowed to OOM the
			// client. Stop feeding chunk generation until the flattener catches
			// up, and also pause under heap pressure so GC/chunk unloading can
			// recover. Reset keeps its existing behavior because it operates on
			// an explicitly selected repair area and needs deterministic drain.
			if (isPregenKind(kind)) {
                int configuredBudget=budget;
				int outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
				// v224: proactively promote a tiny resident/FULL cohort into the proven
				// target-centered carve lane before any watchdog logic runs.
				OceanCanvasSurfaceFlattener.servicePregenCarveLane(world);
				// Always service already-issued work BEFORE asking whether heap/tick
				// pressure permits new submissions. This is the critical v95 ordering
				// change: backpressure may close the inlet, never the drain.
                long recoveryStartedNanos=System.nanoTime();
				outstanding = servicePregenLivenessWatchdog(outstanding);
				advanceCommittedFrontier(MAX_COMMIT_ADVANCE_PER_TICK);
                net.oceancanvas.mod.diagnostic.OceanCanvasWorkloadPhaseProfiler.record("pregen.recovery",System.nanoTime()-recoveryStartedNanos);

				// v102 non-blocking maintenance checkpoint. v96/v97 stopped the
				// entire stream every 2,048 submissions and waited for outstanding=0;
				// that destroyed spatial overlap and could make four slow FULL futures
				// stall a 1.56M-chunk job. Save/restart is already bounded by the live
				// target window and cancellable future maps, so maintenance now only
				// checkpoints state while normal retirement/unloading continues.
				if (submittedCount >= nextMaintenanceAt) {
					maintenanceDrainActive = false;
					maintenanceStableTicks = 0;
					maintenanceDrainStartedNanos = 0L;
					nextMaintenanceAt = Math.max(nextMaintenanceAt + PREGEN_MAINTENANCE_INTERVAL_CHUNKS,
							submittedCount + PREGEN_MAINTENANCE_INTERVAL_CHUNKS);
					persist();
					OceanCanvas.LOGGER.info("(Ocean Canvas) v230.5 Pregen maintenance checkpoint at {} submitted; stream remains open (outstanding={}).", submittedCount, outstanding);
				}

                long admissionStartedNanos=System.nanoTime();
				int adaptiveBudget = adaptivePregenBudget(configuredBudget);
                net.oceancanvas.mod.diagnostic.OceanCanvasWorkloadPhaseProfiler.record("pregen.admission",System.nanoTime()-admissionStartedNanos);

				AdaptiveProfileParams params=adaptiveParams(world,configuredBudget);
				// v253.73.13: recovery pressure is dual-windowed. 73.12 correctly bounded
				// LIGHT_ONLY at 128, but the supplied runtime then showed 823 PHYSICAL_AWARE
				// recovery chunks refilling the same global finalizer from 276 -> 1025 active.
				// Self-heal both derived ACTIVE sets before they control admission.
				// v253.125.31: ACTIVE recovery identity is maintained incrementally at arm,
				// wake, park and retirement boundaries. The five-hour .30 diagnostic showed
				// that re-scanning the entire live lighting map every server tick was itself
				// a measurable observer/admin cost. Keep the self-heal as a low-frequency
				// safety audit instead of a hot-path operation.
				int lightTrackingRepairs = 0;
				int physicalTrackingRepairs = 0;
				if (Math.floorMod(world.getGameTime(), (long)FOREVER_WORLD_RECOVERY_TRACKING_RECONCILE_PERIOD_TICKS) == 0L) {
					lightTrackingRepairs = OceanCanvasSurfaceFlattener.reconcileLightOnlyRecoveryTracking();
					physicalTrackingRepairs = OceanCanvasSurfaceFlattener.reconcilePhysicalRecoveryTracking();
				}
				int trackingRepairs = lightTrackingRepairs + physicalTrackingRepairs;
				if (trackingRepairs >= FOREVER_WORLD_RECOVERY_TRACKING_REPAIR_LOG_THRESHOLD) {
					OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-RECOVERY-TRACKING-REPAIRED build={} repaired={} lightTracked={} lightActive={} physicalTracked={} physicalActive={} action=self-heal-derived-recovery-classification-before-pressure-control",
							net.oceancanvas.mod.OceanCanvas.VERSION, trackingRepairs,
							OceanCanvasSurfaceFlattener.trackedLightOnlyRecoveryWorkCount(),
							OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount(),
							OceanCanvasSurfaceFlattener.trackedPhysicalRecoveryWorkCount(),
							OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount());
				}
				int parkedLight = 0;
				int parkedPhysical = 0;
				if (restartRecoveryQuarantineActive) {
					if (OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount() > FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW) {
						parkedLight = OceanCanvasSurfaceFlattener.parkExcessLightOnlyRecoveryForPressure(
								world, FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW, FOREVER_WORLD_LIGHT_ONLY_PRESSURE_PARK_PER_TICK);
					}
					if (OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount() > FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW) {
						parkedPhysical = OceanCanvasSurfaceFlattener.parkExcessPhysicalRecoveryForPressure(
								world, FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW, FOREVER_WORLD_PHYSICAL_PRESSURE_PARK_PER_TICK);
					}
				}
				if (restartRecoveryQuarantineActive) {
					// v253.125.5: scheduler-only pressure parks refill only through the same
					// bounded recovery windows that created them. They no longer enter the
					// persistent-fault retry lane, so wake->repark churn cannot masquerade as
					// thousands of failed skylight proofs or starve genuine fault retries.
					OceanCanvasSurfaceFlattener.wakePressureParkedRecoveryForPressure(
							world,
							FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW,
							FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW,
							FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ADMISSIONS_PER_TICK,
							FOREVER_WORLD_PHYSICAL_RECOVERY_ADMISSIONS_PER_TICK);
				}
				if ((parkedLight > 0 || parkedPhysical > 0) && !foreverWorldPressureParkLogged) {
					// One warning/capture per recovery episode. The 73.12 run emitted 244
					// pressure-park WARNs because the latch reset whenever a single new replay
					// briefly pushed the count above its window. Aggregates keep total parks.
					foreverWorldPressureParkLogged = true;
					OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-PRESSURE-PARK build={} lightParked={} physicalParked={} activeLightOnly={}/{} activePhysical={}/{} action=dormantize-excess-historical-finalizer-work-before-backpressure",
							net.oceancanvas.mod.OceanCanvas.VERSION, parkedLight, parkedPhysical,
							OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount(), FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW,
							OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount(), FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW);
					net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureProactiveHealth(world,
							"Forever World parked excess historical LIGHT_ONLY/PHYSICAL_AWARE finalizer work before backpressure");
				}
				// Backpressure the GLOBAL Ocean Canvas light queue. The normal 61.7 path no
				// longer recursively invalidates verified neighbors, but restart/audit
				// recovery is still global and must remain bounded.
				int pendingLighting = OceanCanvasSurfaceFlattener.pendingLightSyncCount();
				int persistentSkyBackoff = OceanCanvasSurfaceFlattener.persistentSkyBackoffCount();
				int activeLightingPressure = OceanCanvasSurfaceFlattener.activeLightSyncCount();
				int lightHighWater = OceanCanvasSurfaceFlattener.lightFinalizationBackpressureHighWater();
				// v253.125.19: high-water is flow control, not a terrain mutex. Reserve the
				// emergency lane for a genuinely saturated finalizer (3x high-water); the
				// 512..1535 band keeps <=1/tick terrain progress under adaptive control.
				int lightEmergencyWater = Math.max(lightHighWater + 1, lightHighWater * 3);
				// v253.73.15: the 73.14 runtime settled at active=446 while highWater=512
				// and then stayed paused forever because the old hysteresis demanded a drain
				// all the way to lowWater=256. The predictive fanout clamp already guarantees
				// that reopened terrain cannot overshoot high-water in one tick. Keep a small
				// ~7-fanout hysteresis band instead of a 256-entry dead zone.
				int lightResumeWater = Math.max(
						OceanCanvasSurfaceFlattener.lightFinalizationBackpressureLowWater(),
						lightHighWater - Math.max(32, FOREVER_WORLD_MAX_LIGHT_FANOUT_PER_TERRAIN * 7));
				// v253.73.14 predictive HIGH-water clamp. 73.13 bounded the 512..1023
				// recovery band, but below 512 the previous clamp budgeted against the 1024
				// emergency mark. A 41/tick burst can fan out across center+8 neighbors and
				// jump directly from a healthy low-water state into backpressure. Reserve
				// against 512 itself so ordinary terrain cannot create the pause it is trying
				// to avoid. PHYSICAL_AWARE liveness may still receive one bounded slot.
				if (activeLightingPressure < lightHighWater && adaptiveBudget > 0) {
					int headroom = Math.max(0, lightHighWater - activeLightingPressure);
					int fanoutSafeBudget = headroom / FOREVER_WORLD_MAX_LIGHT_FANOUT_PER_TERRAIN;
					if (physicalRecoveryAwaitingAdmission() && fanoutSafeBudget <= 0) fanoutSafeBudget = 1;
					if (fanoutSafeBudget < adaptiveBudget) {
						adaptiveBudget = Math.max(0, fanoutSafeBudget);
						feedReason = "Predictive light-headroom clamp before high-water overshoot";
						if (!foreverWorldLightHeadroomClampLogged) {
							foreverWorldLightHeadroomClampLogged = true;
							OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-LIGHT-HEADROOM-CLAMP build={} active={} highWater={} emergency={} headroom={} fanout={} cappedBudget={} action=prevent-single-tick-high-water-overshoot",
									net.oceancanvas.mod.OceanCanvas.VERSION, activeLightingPressure, lightHighWater, lightEmergencyWater, headroom, FOREVER_WORLD_MAX_LIGHT_FANOUT_PER_TERRAIN, adaptiveBudget);
						}
					} else {
						foreverWorldLightHeadroomClampLogged = false;
					}
				}
				// v253.72.9: persistent failures now live in a dormant retry queue, so the
				// active pressure calculation is exact and cheap. The old 8192 TOTAL cap
				// could eventually recreate the same deadlock even when all 8192 entries
				// were sleeping. Raise the catastrophic memory-safety ceiling to 65536;
				// ordinary flow control remains based only on active work. Reaching this
				// ceiling means repair itself is systemically broken and intentionally
				// fails closed rather than allowing an unbounded 1.5M-entry debt ledger.
				int absolutePendingCap = Math.max(65536, OceanCanvasSurfaceFlattener.lightFinalizationBackpressureHighWater() * 128);
				boolean lightingBackpressureWasActive = lightingBackpressureActive;
				boolean absoluteCeilingHit = pendingLighting >= absolutePendingCap;
				if (absoluteCeilingHit || activeLightingPressure >= lightHighWater) {
					lightingBackpressureActive = true;
				} else if (activeLightingPressure <= lightResumeWater) {
					lightingBackpressureActive = false;
				}
				// v253.73.16 dead-band stall escape. Conditions, all required:
				//  - the latch has been closed continuously for the stall window;
				//  - the 65536 catastrophic memory ceiling is NOT the reason (that one
				//    fails closed on purpose and is never escaped);
				//  - a full worst-case fanout still fits strictly below high water, so the
				//    reopened admission cannot create the pressure it is avoiding;
				//  - the job cursor has retired nothing at all since the latch closed.
				// That last condition is what makes this an escape and not a tuning knob:
				// a latch that is merely slow keeps holding, a latch that has stopped the
				// run gets one bounded reopen and says so in the log.
				long forwardRetiredNowForLatch = OceanCanvasSurfaceFlattener.jobForwardRetirementCount();
				if (!lightingBackpressureActive) {
					lightingBackpressureSinceNanos = 0L;
				} else {
					long latchNow = System.nanoTime();
					if (lightingBackpressureSinceNanos == 0L) {
						lightingBackpressureSinceNanos = latchNow;
						lightingBackpressureForwardBaseline = forwardRetiredNowForLatch;
					} else if (forwardRetiredNowForLatch > lightingBackpressureForwardBaseline) {
						// Real progress under backpressure: rearm the window rather than
						// escaping a latch that is doing its job slowly but correctly.
						lightingBackpressureSinceNanos = latchNow;
						lightingBackpressureForwardBaseline = forwardRetiredNowForLatch;
					} else if (!absoluteCeilingHit
							&& activeLightingPressure + FOREVER_WORLD_MAX_LIGHT_FANOUT_PER_TERRAIN < lightHighWater
							&& latchNow - lightingBackpressureSinceNanos >= FOREVER_WORLD_LIGHT_BACKPRESSURE_STALL_NS) {
						long stalledSeconds = (latchNow - lightingBackpressureSinceNanos) / 1_000_000_000L;
						lightingBackpressureActive = false;
						lightingBackpressureStallEscapes++;
						lightingBackpressureSinceNanos = 0L;
						OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-BACKPRESSURE STALL-ESCAPE build={} activePressure={} highWater={} resumeWater={} fanout={} stalledSeconds={} forwardRetirements={} escapes={} action=reopen-bounded-terrain-admission-below-high-water",
								net.oceancanvas.mod.OceanCanvas.VERSION, activeLightingPressure, lightHighWater, lightResumeWater,
								FOREVER_WORLD_MAX_LIGHT_FANOUT_PER_TERRAIN,
								stalledSeconds,
								forwardRetiredNowForLatch, lightingBackpressureStallEscapes);
						net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureProactiveHealth(world,
								"Light backpressure latch held with provable admission headroom and zero forward retirement");
					}
				}
				if (lightingBackpressureWasActive != lightingBackpressureActive) {
					OceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-BACKPRESSURE build={} state={} pending={} activePressure={} persistentFaultBackoff={} pressureParked={} absoluteCap={} highWater={} resumeWater={} lowWater={} activeRelightTickets={} submitted={}/{}",
							net.oceancanvas.mod.OceanCanvas.VERSION, lightingBackpressureActive ? "ENTER" : "EXIT", pendingLighting, activeLightingPressure, persistentSkyBackoff, OceanCanvasSurfaceFlattener.pressureParkedLightSyncCount(), absolutePendingCap,
							OceanCanvasSurfaceFlattener.lightFinalizationBackpressureHighWater(), lightResumeWater,
							OceanCanvasSurfaceFlattener.lightFinalizationBackpressureLowWater(),
							OceanCanvasSurfaceFlattener.lightFinalizationDiagnostics(minChunkX, maxChunkX, minChunkZ, maxChunkZ).activeResidencyTickets(),
							submittedCount, reportedTotalChunks);
				}
				if (lightingBackpressureActive) {
					// v253.61.7: 61.6 turned the high/low watermark into a multi-minute
					// on/off sawtooth: terrain stopped completely at 2048 and did not resume
					// until lighting reached 1024. Keep a tiny 1-chunk/tick inlet open so the
					// naturally resident frontier continues to feed cheap finalizations.
					int highWater = lightHighWater;
					int emergencyWater = lightEmergencyWater;
					boolean absoluteCapHit = pendingLighting >= absolutePendingCap;
					maybeCaptureForeverWorldPreflight(pendingLighting, activeLightingPressure, persistentSkyBackoff,
							highWater, emergencyWater, absolutePendingCap);
					if (activeLightingPressure >= emergencyWater || absoluteCapHit) {
						// v253.73.6: a journal-proven LIGHT_ONLY quarantine has no terrain
						// retirement dependency left. v253.73.5 kept feeding its replay lane at
						// <=1/tick even after active finalizer pressure crossed the 1024
						// emergency watermark; the 20k runtime reached 2462/1024, proving that
						// one new entry per tick can still outpace strict finalization. Drain
						// already-owned light work in place until pressure falls below the
						// emergency mark. PHYSICAL_AWARE/hard-crash recovery keeps the 72.2
						// escape below because its terrain retirement can be a liveness
						// prerequisite. This is a recovery-overlay flow-control fix only; the
						// v230.5 scheduler, target order and ownership rules are unchanged.
						// v253.73.7: classify the NEXT recovery admission, not the whole
						// quarantine. The v253.73.6 runtime restarted with 86 physical chunks
						// mixed into 22,467 LIGHT_ONLY chunks; the old all-or-nothing predicate
						// therefore kept admitting the light-only majority and reached 1979/1024.
						// At emergency pressure, admit at most one PHYSICAL_AWARE target whose
						// terrain retirement can still be a liveness prerequisite. If no such
						// target is at the recovery head (or recoverable from deferred work),
						// close the inlet and let strict lighting drain.
						boolean quarantinePhysicalCandidate = restartRecoveryQuarantineActive
								&& !restartRecoveryPhysicalPending.isEmpty()
								&& promotePhysicalRecoveryReplayCandidate();
						// v253.73.8: the hard-crash committed-frontier rescan is PHYSICAL_AWARE
						// by construction: the main cursor marks each rescan coordinate with
						// allowPhysicalRepair=true immediately before requesting its load. 73.7
						// accidentally looked only at restartRecoveryPhysicalPending, which is the
						// graceful-quarantine subset, and could therefore close the 72.2 liveness
						// escape before a hard-crash rescan target was ever eligible to enter it.
						boolean hardCrashRescanPhysicalCandidate = restartRecoveryRescanActive
								&& nextIndex < restartRecoveryRescanEndIndex;
						boolean physicalRecoveryCandidate = quarantinePhysicalCandidate || hardCrashRescanPhysicalCandidate;
						// v253.73.9: a mature forever world must not be forced to finish a
						// historical LIGHT_ONLY debt ledger before its never-submitted terrain can
						// move again. Tie a tiny main-cursor reserve directly to observed strict
						// finalizer retirements so the light backlog remains debt-negative.
						boolean retirementBackedForeverWorldCandidate = !physicalRecoveryCandidate && adaptiveBudget > 0
								&& mayAdmitForeverWorldEmergencyTerrainReserve(activeLightingPressure, emergencyWater, absoluteCapHit);
						boolean continuityPulseCandidate = !physicalRecoveryCandidate && !retirementBackedForeverWorldCandidate
								&& adaptiveBudget > 0
								&& mayAdmitForeverWorldContinuityPulse(activeLightingPressure, emergencyWater, absoluteCapHit, outstanding);
						boolean foreverWorldTerrainCandidate = retirementBackedForeverWorldCandidate || continuityPulseCandidate;
						if (!physicalRecoveryCandidate && !foreverWorldTerrainCandidate) {
							lightingEmergencyRecoveryEscapeActive = false;
							lightingEmergencyRecoveryEscapeLogged = false;
							wallTimeAdmissionBudget(0, params);
							lastFeedBudget = 0;
							feedReason = absoluteCapHit
									? "Lighting recovery safety cap reached " + pendingLighting + "/" + absolutePendingCap + "; fail-closed until debt drains"
									: "Lighting recovery emergency pressure " + activeLightingPressure + "/" + emergencyWater + "; waiting for retirement credit or a safe proactive continuity pulse";
							updateLightingBackpressureProgress(pendingLighting,
									OceanCanvasSurfaceFlattener.activeLightResidencyTicketCount());
							return false;
						}
						lightingEmergencyPhysicalOnlyAdmission = physicalRecoveryCandidate;
						lightingEmergencyForeverWorldTerrainAdmission = foreverWorldTerrainCandidate;
						lightingEmergencyForeverWorldContinuityPulse = continuityPulseCandidate;

						// v253.72.2: emergency backpressure may close NORMAL terrain admission,
						// but it may not close the restart-recovery lane whose terrain retirement
						// is itself required to release lighting quiescence barriers. The 72.1
						// runtime proved that doing so deadlocks at ~1024 pending: normal tickets
						// drain to zero, recovery cannot advance, and lighting cannot drain. Keep
						// the escape bounded to one requested target and still pass it through the
						// existing wall-time/heap/cadence controller below.
						boolean restartRecoveryNeedsAdmission = lightingEmergencyForeverWorldTerrainAdmission
								? nextIndex < totalChunks
								: (!resumeReplayChunks.isEmpty()
										|| (restartRecoveryRescanActive && !restartRecoveryRescanPending.isEmpty())
										|| (!deferredPregenChunks.isEmpty() && restartRecoveryQuarantineActive));
						if (!restartRecoveryNeedsAdmission || adaptiveBudget <= 0) {
							lightingEmergencyRecoveryEscapeLogged = false;
							wallTimeAdmissionBudget(0, params);
							lastFeedBudget = 0;
							feedReason = absoluteCapHit
									? "Lighting repair safety cap reached " + pendingLighting + "/" + absolutePendingCap + "; fail-closed while bounded repair drains"
									: "Lighting finalization emergency backlog total=" + pendingLighting + " active=" + activeLightingPressure + "/" + emergencyWater + "; draining";
							updateLightingBackpressureProgress(pendingLighting,
									OceanCanvasSurfaceFlattener.activeLightResidencyTicketCount());
							return false;
						}
						adaptiveBudget = Math.min(adaptiveBudget, 1);
						lightingEmergencyRecoveryEscapeActive = true;
						if (lightingEmergencyForeverWorldTerrainAdmission) {
							feedReason = lightingEmergencyForeverWorldContinuityPulse
									? "Forever-world proactive continuity pulse: 1 NORMAL terrain target inside measured safe headroom"
									: "Forever-world resume reserve: 1 NORMAL terrain target after 16 net durable lighting-debt reductions";
							if (!foreverWorldEmergencyTerrainReserveLogged) {
								foreverWorldEmergencyTerrainReserveLogged = true;
								OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD-RESUME build={} mode=DEBT_NEGATIVE_TERRAIN_RESERVE pending={} activePressure={}/{} quarantinePending={} lightOnlyReplayQueued={} submitted={}/{}. Normal Pregen receives <=1 terrain target per {} net durable lighting-debt reductions while pressure remains below 2x emergency; PHYSICAL_AWARE recovery still has priority and LIGHT_ONLY replay remains paused at emergency pressure.",
										net.oceancanvas.mod.OceanCanvas.VERSION, pendingLighting, activeLightingPressure, emergencyWater,
										restartRecoveryQuarantinePending.size(), resumeReplayChunks.size(), submittedCount, reportedTotalChunks,
										FOREVER_WORLD_EMERGENCY_NET_DEBT_REDUCTIONS_PER_TERRAIN_ADMISSION);
							}
						} else {
							feedReason = "Lighting finalization emergency backlog total=" + pendingLighting + " active=" + activeLightingPressure + "/" + emergencyWater
									+ "; bounded restart-recovery escape <=1/tick";
							long escapeLogNow = System.nanoTime();
							if (!lightingEmergencyRecoveryEscapeLogged
									|| escapeLogNow - lightingEmergencyRecoveryEscapeLastLogNanos >= 10_000_000_000L) {
								lightingEmergencyRecoveryEscapeLogged = true;
								lightingEmergencyRecoveryEscapeLastLogNanos = escapeLogNow;
								OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-BACKPRESSURE RECOVERY-ESCAPE build={} pending={} activePressure={}/{} persistentBackoff={} absoluteCapHit={} quarantinePending={} replayQueued={} rescanPending={} deferred={} adaptiveBudget={}. Base emergency flow remains <=1/tick and is reserved for either a queued per-chunk PHYSICAL_AWARE quarantine target or the next PHYSICAL_AWARE hard-crash rescan cursor target; LIGHT_ONLY replay is drain-only above the emergency watermark.",
										net.oceancanvas.mod.OceanCanvas.VERSION, pendingLighting, activeLightingPressure, emergencyWater, persistentSkyBackoff, absoluteCapHit,
										restartRecoveryQuarantinePending.size(), resumeReplayChunks.size(),
										restartRecoveryRescanPending.size(), deferredPregenChunks.size(), adaptiveBudget);
							}
						}

					} else {
						lightingEmergencyRecoveryEscapeLogged = false;
						foreverWorldEmergencyTerrainReserveLogged = false;
						// v253.73.13: 73.12 proved the 512..1023 band was not actually
						// backpressure: an unconditional 1/tick inlet let active debt climb
						// 512 -> 1025. During restart recovery, make this band debt-negative.
						if (restartRecoveryQuarantineActive) {
							boolean physicalRecoveryCandidate = !restartRecoveryPhysicalPending.isEmpty()
									&& promotePhysicalRecoveryReplayCandidate()
									&& OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount() < FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW;
							if (physicalRecoveryCandidate) {
								lightingEmergencyPhysicalOnlyAdmission = true;
								adaptiveBudget = Math.min(adaptiveBudget, 1);
								feedReason = "Recovery-heavy light pressure; one windowed PHYSICAL_AWARE target may enter";
							} else if (adaptiveBudget > 0 && !absoluteCapHit && nextIndex < totalChunks) {
								// v253.125.19: do not make historical/global lighting debt a terrain
								// mutex. The light queue remains strictly bounded by its own active
								// work/time/ticket budgets, while never-submitted terrain retains one
								// adaptive admission slot per server tick below the true emergency mark.
								lightingEmergencyForeverWorldTerrainAdmission = true;
								lightingEmergencyForeverWorldContinuityPulse = false;
								adaptiveBudget = Math.min(adaptiveBudget, 1);
								feedReason = "Recovery-heavy lighting; bounded NORMAL-terrain lane remains open <=1/tick";
							} else {
								wallTimeAdmissionBudget(0, params);
								adaptiveBudget = 0;
								feedReason = absoluteCapHit
										? "Lighting safety cap reached; fail-closed until debt drains"
										: "No adaptive terrain budget available while lighting pressure drains";
							}
						} else {
							adaptiveBudget = Math.min(adaptiveBudget, 1);
							feedReason = "Lighting finalization flow-control total=" + pendingLighting + " active=" + activeLightingPressure + "/" + highWater + "; terrain capped at 1/tick";
						}
					}
				} else {
					lightingEmergencyRecoveryEscapeLogged = false;
					foreverWorldEmergencyTerrainReserveLogged = false;
				}
				if (adaptiveBudget <= 0) {
					// v223: advance/decay the real-time token bucket even while every other
					// controller has the inlet closed. No pause may accumulate burst credit.
					wallTimeAdmissionBudget(0, params);
                    lastFeedBudget=0;
					updatePregenAdaptiveBossBar(outstanding, true);
					return false;
				}

				if (lightingEmergencyForeverWorldTerrainAdmission) {
					// This slot is already paced by sixteen observed AUTHORITATIVE_SEND events
					// or an equivalent sixteen-entry net active-pressure retirement, which is
					// stricter than a per-wall-second token during recovery. Advance the
					// ordinary bucket with a zero request so it cannot accumulate burst credit,
					// but do not let an empty normal-terrain EMA erase the debt-backed
					// forever-world guarantee. All adaptive heap/cadence/I/O holds have already
					// run above; this remains exactly one target.
					wallTimeAdmissionBudget(0, params);
					adaptiveBudget = 1;
				} else {
					adaptiveBudget = wallTimeAdmissionBudget(adaptiveBudget, params);
				}
				if (adaptiveBudget <= 0) {
					lastFeedBudget=0;
					updatePregenAdaptiveBossBar(outstanding, true);
					return false;
				}
				budget = adaptiveBudget;
				if (outstanding >= params.targetOutstanding()) {
					// Keep the C2ME pipeline alive but don't continue filling it
					// at the full learned rate while we're above the target depth.
					budget = Math.min(budget, 2);
                    feedReason="Above target queue depth; reduced feed";
				}
                lastFeedBudget=budget;
			}
			int issuedThisTick = 0;
			int metadataSkipsThisTick = 0;

			// v101: deferred targets are intentionally NOT re-injected during the
			// healthy main scan. Replaying them here recreated the exact oscillation
			// seen in v100: defer a wedged target, wait briefly, force it back into
			// the same C2ME dependency state, repeat. Deferred work remains persisted,
			// mandatory, and is handled only after the main cursor is exhausted.

			// v96 restart recovery lane. These requests were already counted before
			// shutdown, so replaying them consumes feed budget but does NOT advance the
			// cursor or submittedCount. Fully physical+lighting-certified chunks are
			// discarded cheaply; physical-only chunks are replayed through the
			// non-destructive lighting path.
			//
			// v253.73.5: the 20k restart log proved a pure 9,568-chunk LIGHT_ONLY
			// quarantine could monopolize this loop for minutes even though the persisted
			// journal already proved physicalRepair=0. Share, never enlarge, the existing
			// admission budget: with budget=1 alternate replay/main turns; with a larger
			// budget reserve exactly one slot for the main/rescan cursor. Sharing is
			// enabled only after AUTHORITATIVE_SEND publications are observed and closes
			// within 5s if the finalizer stops retiring.
			boolean classifiedLightOnlyWindowShare = isPregenKind(kind) && classifiedLightOnlyQuarantine();
			int replayAdmissionCeiling = budget;
			if (lightingEmergencyForeverWorldTerrainAdmission) {
				// The emergency reserve is specifically for never-submitted NORMAL terrain.
				// Spending this debt-backed slot on another LIGHT_ONLY replay would recreate
				// the same forever-world starvation that v253.73.9 is fixing.
				replayAdmissionCeiling = 0;
			} else if (physicalRecoveryAwaitingAdmission()) {
				replayAdmissionCeiling = physicalRecoveryReplayCeiling(budget);
			} else if (classifiedLightOnlyWindowShare) {
				replayAdmissionCeiling = lightOnlyRecoveryReplayCeiling(budget);
			}
			while (isPregenKind(kind) && issuedThisTick < replayAdmissionCeiling && !resumeReplayChunks.isEmpty()) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) {
					lastFeedBudget = issuedThisTick;
					feedReason = "Save & Quit / shutdown preemption";
					return false;
				}
				long packed = resumeReplayChunks.removeFirst();
				if (lightingEmergencyPhysicalOnlyAdmission && !restartRecoveryPhysicalPending.contains(packed)) {
					resumeReplayChunks.addFirst(packed);
					feedReason = "Emergency recovery inlet reserved for PHYSICAL_AWARE target; LIGHT_ONLY replay held";
					break;
				}
				ChunkPos replayPos = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
				var seals = net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(world);
				var terrainState = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(replayPos);
				boolean recoveryObligation = restartRecoveryQuarantinePending.contains(packed)
						|| restartRecoveryRescanPending.contains(packed);
				if (!OceanCanvasSurfaceFlattener.isPregenCrashRecoveryTarget(packed)
						&& seals.isChunkLightingVerified(replayPos)
						&& terrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) {
					if (recoveryObligation) noteAuthoritativeCommit(packed);
					continue;
				}
				if (recoveryObligation && !OceanCanvasSurfaceFlattener.isPregenCrashRecoveryTarget(packed)) {
					// v253.125.13: classify/dirty LIGHT_ONLY history only at the point this
					// exact center owns one of the bounded replay slots. This makes the 128-entry
					// window an admission invariant rather than a cleanup operation after a flood.
					OceanCanvasSurfaceFlattener.markPregenCrashRecoveryTarget(world, replayPos,
							restartRecoveryPhysicalPending.contains(packed) || restartRecoveryRescanPending.contains(packed));
				}
				if (!OceanCanvasSurfaceFlattener.requestPregenTargetLoad(world, replayPos)) {
					resumeReplayChunks.addFirst(packed);
					feedReason = "Admission ownership precondition failed; cursor held";
					break;
				}
				issuedThisTick++;
			}
			// v253.72.2 emergency escape is recovery-only. If this was a plain replay
			// cohort (no quarantine/rescan boundary), do not spend any unused one-target
			// escape budget on ordinary new row-major terrain after the replay attempt.
			if (lightingEmergencyRecoveryEscapeActive
					&& !restartRecoveryQuarantineActive && !restartRecoveryRescanActive) {
				lastFeedBudget = issuedThisTick;
				updateLightingBackpressureProgress(OceanCanvasSurfaceFlattener.pendingLightSyncCount(),
						OceanCanvasSurfaceFlattener.activeLightResidencyTicketCount());
				return false;
			}
			// v253.72 restart quarantine remains a hard AUTHORITATIVE-COMPLETION
			// boundary. v253.72.5 no longer makes strict LIGHT_ONLY proof a permanent
			// global TERRAIN mutex: after the one-time quiescent ownership proof below,
			// unrelated main-cursor terrain may continue while the quarantine lighting
			// finalizers remain mandatory. PHYSICAL_AWARE/hard-crash work is unchanged.
			if (restartRecoveryQuarantineActive) {
				if (resumeReplayChunks.isEmpty() && !restartRecoveryQuarantinePending.isEmpty()
						&& OceanCanvasSurfaceFlattener.outstandingPregenTargetCount() == 0) {
					// A hard-stall deferral inside recovery remains mandatory. Pull one such
					// coordinate back into the recovery lane instead of waiting for main-scan
					// final drain, which is intentionally blocked by this quarantine.
					for (long packed : deferredPregenChunks.toLongArray()) {
						if (!restartRecoveryQuarantinePending.contains(packed)) continue;
						deferredPregenChunks.remove(packed);
						resumeReplayChunks.addLast(packed);
						break;
					}
				}

				boolean concurrentLightOnlyRecovery = mayResumeTerrainAdmissionBehindLightOnlyRecovery();
				// v253.125.53: a full PHYSICAL_AWARE recovery window must not become a
				// global mutex for never-submitted terrain. .52 correctly released a slot
				// after the exhaustive physical audit, but a large physical recovery prefix
				// can still keep resumeReplayChunks headed by PHYSICAL_AWARE work for many
				// minutes. When all 64 physical slots are already occupied, no additional
				// physical recovery can be admitted this tick anyway. Spend at most ONE of
				// the already-computed adaptive slots on unrelated main-cursor terrain. The
				// quarantine, physical repair permission and strict lighting certificates
				// remain mandatory; hard-crash rescan never uses this continuity pulse.
				boolean physicalRecoveryTerrainContinuityPulse = !restartRecoveryRescanActive
						&& physicalRecoveryAwaitingAdmission()
						&& OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount() >= FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW
						&& issuedThisTick < budget
						&& nextIndex < totalChunks;
				// v253.73.5/73.9: once no PHYSICAL_AWARE recovery target is still waiting
				// for terrain admission, the remaining recovery cohort is terrain-admission-
				// safe even if some physical-aware chunks are already inside their finalizer.
				// That is NOT a lighting certificate. The LIGHT_ONLY cohort is now admitted through
				// a hard 128-entry active window with at most two replay admissions/tick, so it
				// may share the SAME adaptive budget with main terrain without requiring recent
				// publication as a liveness prerequisite. No extra targets are admitted. Under
				// emergency pressure the existing <=1/tick debt-negative escape still applies.
				boolean classifiedShareNow = classifiedLightOnlyWindowShare;
				if (!concurrentLightOnlyRecovery && !classifiedShareNow && !lightingEmergencyForeverWorldTerrainAdmission
						&& !physicalRecoveryTerrainContinuityPulse) {
					feedReason = "Restart recovery quarantine: " + restartRecoveryQuarantinePending.size() + " chunk(s) awaiting authoritative proof";
					lastFeedBudget = issuedThisTick;
					updatePregenFinalizingProgress(Math.max(1, restartRecoveryQuarantinePending.size()));
					return false;
				}

				// Keep restartRecoveryQuarantineActive and its pending set intact: these
				// chunks are NOT being declared light-correct. Their authoritative finalizer
				// continues concurrently and the ordinary completion gate still waits for it.
				feedReason = lightingEmergencyForeverWorldTerrainAdmission
						? "Forever-world debt-negative terrain reserve; recovery proof still pending"
						: (physicalRecoveryTerrainContinuityPulse
								? "PHYSICAL_AWARE window saturated; one main-cursor terrain continuity slot reserved"
								: (classifiedShareNow
										? "Windowed historical LIGHT_ONLY recovery fair-share; strict completion still pending"
										: "Recovery lighting finalization concurrent; terrain admission resumed"));
				if (!restartRecoveryTerrainGateOpenLogged) {
					restartRecoveryTerrainGateOpenLogged = true;
					if (lightingEmergencyForeverWorldTerrainAdmission) {
						OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD terrain admission ACTIVE: {} recovery chunk(s) still require authoritative proof, but no PHYSICAL_AWARE target is waiting for admission; main Pregen advances only through the debt-negative publication-backed reserve.",
								restartRecoveryQuarantinePending.size());
					} else if (classifiedShareNow && !concurrentLightOnlyRecovery) {
						OceanCanvas.LOGGER.warn("(Ocean Canvas) FOREVER-WORLD TERRAIN-SAFE WINDOWED FAIR-SHARE OPEN: {} recovery chunk(s) remain; replayQueued={} activeLightOnlyRecovery={} lightWindow={} activePhysicalRecovery={} physicalWindow={} physicalAwaitingAdmission={} rescanActive={} strict proof remains mandatory; historical recovery admission is background-capped.",
								restartRecoveryQuarantinePending.size(), resumeReplayChunks.size(), OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount(), FOREVER_WORLD_LIGHT_ONLY_RECOVERY_ACTIVE_WINDOW, OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount(), FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW, physicalRecoveryAwaitingAdmission(), restartRecoveryRescanActive);
					} else {
						OceanCanvas.LOGGER.warn("(Ocean Canvas) CRASH-RECOVERY terrain gate OPEN: {} recovery chunk(s) remain in authoritative finalization; normal Pregen admission resumes without certifying their unfinished recovery state.",
								restartRecoveryQuarantinePending.size());
					}
				}
			}
			int terrainAdmissionCeiling = budget;
			if (restartRecoveryQuarantineActive && !restartRecoveryRescanActive
					&& physicalRecoveryAwaitingAdmission()
					&& OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount() >= FOREVER_WORLD_PHYSICAL_RECOVERY_ACTIVE_WINDOW) {
				// Preserve PHYSICAL_AWARE priority while guaranteeing one independent terrain
				// retirement opportunity whenever the recovery window itself is the blocker.
				terrainAdmissionCeiling = Math.min(budget, issuedThisTick + 1);
			}
			int cursorStepsThisTick = 0;
			while (issuedThisTick < terrainAdmissionCeiling && nextIndex < totalChunks
					&& cursorStepsThisTick++ < MAX_CURSOR_STEPS_PER_TICK) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) {
					lastFeedBudget = issuedThisTick;
					feedReason = "Save & Quit / shutdown preemption";
					return false;
				}
				if (restartRecoveryRescanActive && nextIndex >= restartRecoveryRescanEndIndex) break;
				int cx = minChunkX + (int) (nextIndex % rowWidth);
				int cz = minChunkZ + (int) (nextIndex / rowWidth);

				if (!explicitChunks.isEmpty() && !explicitChunks.contains(ChunkPos.pack(cx, cz))) {
					nextIndex++;
					continue;
				}

				if (cx >= excludeMinChunkX && cx <= excludeMaxChunkX && cz >= excludeMinChunkZ && cz <= excludeMaxChunkZ) {
					// Already-good interior (expand only) - jump straight
					// past the whole excluded run in this row in one
					// arithmetic step, not a chunk-by-chunk skip loop -
					// see the constructor's doc comment for why an
					// unbounded skip loop was a real tick-stall risk on a
					// large canvas, caught and avoided rather than
					// accepted. Doesn't count against issuedThisTick -
					// free, since nothing is actually touched.
					nextIndex += excludeMaxChunkX - cx + 1;
					continue;
				}

				ChunkPos pos = new ChunkPos(cx, cz);
				long packedPos = ChunkPos.pack(cx, cz);
				if (restartRecoveryRescanActive && nextIndex < restartRecoveryRescanEndIndex) {
					restartRecoveryRescanPending.add(packedPos);
					OceanCanvasSurfaceFlattener.markPregenCrashRecoveryTarget(world, pos, true);
				}

				// Pregen is preparation, not repair. If OceanCanvas has already
				// sealed this chunk as processed, do not even ask Minecraft to load
				// it. This makes overlapping/off-center pregen jobs cheap and lets a
				// player work outward in tiles without repeatedly dragging old
				// interior chunks back through the chunk system.
				if (isPregenKind(kind)) {
					var seals = net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(world);
					var terrainState = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos);
					// v253.49: only a physically AND lighting-verified CANVAS seal is
					// cheap-skippable. A physical-only seal is deliberately loaded once
					// for the non-destructive light finalizer. Older non-physical seals
					// are deliberately loaded once so the flattener can
					// distinguish healthy old Canvas from the stale-seal failure that
					// produced visible vanilla cliffs after a "100%" pregen. Restored
					// VANILLA chunks are also not Canvas-complete and must be prepared.
					if (!OceanCanvasSurfaceFlattener.isPregenCrashRecoveryTarget(packedPos)
							&& seals.isChunkLightingVerified(pos)
							&& terrainState == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS) {
						nextIndex++;
						submittedCount++;
						skippedProcessed++;
						if (++metadataSkipsThisTick >= MAX_METADATA_SKIPS_PER_TICK) {
							feedReason = "Bounded metadata replay through already light-certified Canvas chunks";
							break;
						}
						continue;
					}
				}

				// v215: admission ownership is a PRECONDITION, not a diagnostic.
				// Secure the radius-3 routine ticket before lookahead, target marking,
				// cursor advancement, or submittedCount changes. If the ticket system
				// refuses it, leave this exact cursor position untouched and stop feeding
				// for the tick; the existing health controller will keep the inlet closed.
				if (isPregenKind(kind) && !OceanCanvasSurfaceFlattener.preparePregenAdmission(world, pos)) {
					feedReason = "Admission ownership precondition failed; cursor held";
					break;
				}

				// v115: request the next-row (cz+1) neighbor's load one row
				// ahead of the scan, purely as a fire-and-forget support
				// request. See requestPregenRowLookaheadLoad's doc for why
				// the row-major scan otherwise leaves almost every target in
				// the current row structurally neighbor-stalled until the
				// scan itself advances a whole row later. Only for genuine
				// pregen kinds - reset/rewipe/restore work an explicitly
				// bounded area where this lookahead has no benefit.
				// v163.3: moved to AFTER the already-sealed free-skip above -
				// that skip deliberately does not cost issuedThisTick budget
				// so the scan can race through already-processed runs for
				// free, but this call used to fire on every one of those free
				// iterations too, adding an unbounded, budget-independent
				// flood of entries into PREGEN_SUPPORT_FUTURES. Now it only
				// fires for a chunk the scan is actually about to admit,
				// tying its rate to the same budget that bounds real targets.
				if (isPregenKind(kind)) {
					OceanCanvasSurfaceFlattener.requestPregenRowLookaheadLoad(world, cx, cz + 1);
				}

				// v197: was hasChunk(cx, cz) then a separate world.getChunk(cx, cz) -
				// not atomic, same race flattenChunk's v182 fix closed (see that
				// comment). getChunkNow folds the check and the fetch into one
				// atomic call with no separate check-then-use window left to race.
				LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
				if (chunk != null) {
					// Already resident - hand straight to the existing,
					// already-proven flattening queue instead of any new
					// carving logic. See enqueueForPregen's doc comment.
					if (isDestructiveRepairKind(kind)) {
						OceanCanvasSurfaceFlattener.enqueueForRegeneration(chunk);
					} else {
						OceanCanvasSurfaceFlattener.enqueueForPregen(world, chunk);
					}
					nextIndex++;
					submittedCount++;
					issuedThisTick++;
				} else {
					if (isDestructiveRepairKind(kind)) {
						// Mark ownership BEFORE requesting the asynchronous load. In v85 only
						// already-resident chunks entered FORCE_REPROCESS_CHUNKS; C2ME-loaded
						// chunks arrived through CHUNK_LOAD as ordinary work, which could leave
						// Region Rewipe at 100% submitted forever or skip a previously sealed
						// chunk. The forced marker survives until flattenChunk retires it.
						OceanCanvasSurfaceFlattener.markRegenerationTarget(pos);
					}
					// Not resident yet - kick off loading with the same
					// fire-and-forget call OceanCanvasSurfaceFlattener's
					// neighborsReady already uses successfully, then
					// leave the cursor where it is and retry this exact
					// position next tick. Counts against this tick's
					// budget (issuedThisTick) either way, so a run of
					// not-yet-loaded chunks can't bypass the rate limit
					// by retrying instantly in a tight loop.
					// v215 preparePregenAdmission above already secured ownership before
					// this branch. requestPregenTargetLoad re-checks that invariant and
					// returns false rather than silently creating an unticketed target.
					// C2ME is built around concurrent/asynchronous chunk generation.
					// Do not serialize that pipeline by parking nextIndex on this
					// chunk until it becomes resident. Submit the FULL future,
					// mark ownership first so CHUNK_LOAD can route it into the
					// OceanCanvas flattener, then immediately advance to submit
					// more independent work up to this tick's bounded budget.
					if (isPregenKind(kind) && !OceanCanvasSurfaceFlattener.requestPregenTargetLoad(world, pos)) {
						feedReason = "Admission ownership precondition failed; cursor held";
						break;
					}
					if (!isPregenKind(kind)) {
						// v217: destructive repair kinds do not own PREGEN_TARGET_CHUNKS and
						// must not be routed through the Pregen radius-3 admission contract.
						// They still need the proven nonblocking radius-0 load request.
						OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world, pos.x(), pos.z());
					}
					nextIndex++;
					submittedCount++;
					issuedThisTick++;
					continue;
				}
			}

			advanceCommittedFrontier(MAX_COMMIT_ADVANCE_PER_TICK);

			if (restartRecoveryRescanActive && nextIndex >= restartRecoveryRescanEndIndex) {
				if (restartRecoveryRescanPending.isEmpty()) {
					restartRecoveryRescanActive = false;
					OceanCanvas.LOGGER.info("(Ocean Canvas) CRASH-RECOVERY uncommitted-range PASS: verified through old submitted cursor {}; normal new admission may resume.",
						restartRecoveryRescanEndIndex);
					persist();
				} else {
					// A recovery target can be routed into the ordinary deferred lane by the
					// liveness watchdog. At the old submitted frontier normal admission is
					// intentionally stopped, so explicitly pull deferred recovery work back
					// into the replay lane or the drain could wait forever.
					if (resumeReplayChunks.isEmpty() && OceanCanvasSurfaceFlattener.outstandingPregenTargetCount() == 0) {
						for (long packed : deferredPregenChunks.toLongArray()) {
							if (!restartRecoveryRescanPending.contains(packed)) continue;
							deferredPregenChunks.remove(packed);
							resumeReplayChunks.addLast(packed);
							break;
						}
					}
					feedReason = "Crash recovery rescan drain: " + restartRecoveryRescanPending.size() + " chunk(s) awaiting authoritative proof";
					lastFeedBudget = issuedThisTick;
					updatePregenFinalizingProgress(Math.max(1, restartRecoveryRescanPending.size()));
					return false;
				}
			}

			// Keep the boss bar visible and current every server tick. This is intentionally
			// separate from the lower-frequency action-bar/chat cadence so a Reset cannot
			// finish or enter structure work before the player ever sees the bar.
			updateBossBarOnly();

			ticksSinceReport++;
			if (ticksSinceReport >= PROGRESS_REPORT_INTERVAL_TICKS && nextIndex < totalChunks) {
				ticksSinceReport = 0;
				reportProgress();
				// Same interval as the progress report above - see
				// #persist()'s doc for why this cadence, not a new timer.
				persist();
			}

			// Much more frequent than the chat-log report above - see
			// ActionBarUtil's doc for why a separate, faster cadence.
			ticksSinceActionBar++;
			if (ticksSinceActionBar >= ActionBarUtil.INTERVAL_TICKS && nextIndex < totalChunks) {
				ticksSinceActionBar = 0;
				updateActionBar();
			}

			if (nextIndex < totalChunks) {
				return false;
			}
			// Reset is destructive and explicitly bypasses the normal processed-
			// chunk seal. Do not report it complete until the flattener has
			// actually consumed every forced chunk, not merely queued them.
			if (!isDestructiveRepairKind(kind)) {
				if (isPregenKind(kind)) {
					int outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
					// v97: deferred/restart-replay targets are mandatory work, not
					// successful completions. Once the main scan is exhausted, feed one
					// back into the ordinary authoritative pipeline whenever the live
					// window is empty. This guarantees final 100% still means zero
					// deferred targets, while allowing the main 20k scan to route around
					// pathological chunks earlier in the run.
					if (outstanding == 0 && (!resumeReplayChunks.isEmpty() || !deferredPregenChunks.isEmpty())) {
						boolean fromReplay = !resumeReplayChunks.isEmpty();
						long packed = fromReplay ? resumeReplayChunks.removeFirst() : deferredPregenChunks.removeFirst();
						ChunkPos pos = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
						var seals = net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(world);
						var state = net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(pos);
						if (!(seals.isChunkLightingVerified(pos)
								&& state == net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS)) {
							if (fromReplay && (restartRecoveryQuarantinePending.contains(packed)
									|| restartRecoveryRescanPending.contains(packed))
									&& !OceanCanvasSurfaceFlattener.isPregenCrashRecoveryTarget(packed)) {
								OceanCanvasSurfaceFlattener.markPregenCrashRecoveryTarget(world, pos,
										restartRecoveryPhysicalPending.contains(packed) || restartRecoveryRescanPending.contains(packed));
							}
							// v217: admission can now fail by design. Never consume mandatory
							// replay/deferred ownership before success; put the coordinate back
							// at the front if the ticket precondition cannot be secured.
							if (!OceanCanvasSurfaceFlattener.requestPregenTargetLoad(world, pos)) {
								if (fromReplay) resumeReplayChunks.addFirst(packed);
								else deferredPregenChunks.addFirst(packed);
								feedReason = "Final-drain admission ownership precondition failed; mandatory target retained";
							}
							outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
						} else if (fromReplay && (restartRecoveryQuarantinePending.contains(packed)
								|| restartRecoveryRescanPending.contains(packed))) {
							noteAuthoritativeCommit(packed);
						}
						updatePregenFinalizingProgress(Math.max(1, outstanding));
						return false;
					}
					if (outstanding > 0) {
						finalDrainTicks++;
						// A READY target must make forward progress. This directly
						// retires one ready target per tick, bypassing the queue-rotation
						// state that produced the observed "1 ready forever" failure.
						OceanCanvasSurfaceFlattener.finishOneReadyPregenTarget(world);
						if (finalDrainTicks == 1 || finalDrainTicks % 20 == 0) {
							// Final drain never holds back new demand - by this point the
							// live window is small and the zero-failure invariant matters
							// more than protecting an already-mostly-idle executor.
							OceanCanvasSurfaceFlattener.nudgeOutstandingPregenTargets(world, false);
						}
						if (finalDrainTicks % 100 == 0) {
							var d = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
							OceanCanvas.LOGGER.info(
									"(Ocean Canvas) Pregen final-drain diagnostic: outstanding={}, {}",
									outstanding, d.shortText());
						}
						updatePregenFinalizingProgress(outstanding);
						return false;
					}
				}
				return true;
			}
			// Arbitrary Region Rewipe must wait on its exact chunk mask, not the
			// rectangular envelope around it. Otherwise unrelated/stale forced
			// chunks inside that envelope can leave the job at 100% forever and
			// block the next Reset even though this region is already finished.
			if (("region-rewipe".equals(kind) || "region-reset".equals(kind)) && !explicitChunks.isEmpty()) {
				int pending = OceanCanvasSurfaceFlattener.pendingRegenerationCount(explicitChunks);
				if (pending > 0) {
					finalDrainTicks++;
					OceanCanvasSurfaceFlattener.finishOneReadyRegenerationTarget(world, explicitChunks);
					if (finalDrainTicks == 1 || finalDrainTicks % 20 == 0) {
						OceanCanvasSurfaceFlattener.nudgeOutstandingRegenerationTargets(world, explicitChunks);
					}
					pending = OceanCanvasSurfaceFlattener.pendingRegenerationCount(explicitChunks);
					updateRewipeFinalizingProgress(pending);
					return pending == 0;
				}
				return true;
			}
			return !OceanCanvasSurfaceFlattener.hasPendingRegeneration(
					minChunkX, maxChunkX, minChunkZ, maxChunkZ);
		}

		private void updatePregenAdaptiveBossBar(int outstanding, boolean paused) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			int rate = Math.max(1, adaptivePregenRate);
			int heapPct = (int)Math.round(heapUseFraction() * 100.0D);
			String title;
			if (paused && lightingBackpressureActive) {
				int activeLight = OceanCanvasSurfaceFlattener.activeLightSyncCount();
				int highWater = OceanCanvasSurfaceFlattener.lightFinalizationBackpressureHighWater();
				title = "Ocean Canvas - Pregen paused: lighting " + activeLight + "/" + highWater
						+ ", " + outstanding + " in flight, " + heapPct + "% heap";
			} else if (paused) {
				String conciseReason = feedReason == null || feedReason.isBlank() ? "flow control" : feedReason;
				if (conciseReason.length() > 52) conciseReason = conciseReason.substring(0, 49) + "...";
				title = "Ocean Canvas - Pregen paused: " + conciseReason
						+ ", " + outstanding + " in flight, " + heapPct + "% heap";
			} else {
				title = "Ocean Canvas - Pregen running: " + rate + "/tick, "
						+ outstanding + " in flight, " + heapPct + "% heap";
			}
			bossEvent.setName(Component.literal(title));
			double fraction = reportedTotalChunks == 0 ? 0.0D
					: Math.min(1.0D, (double)confirmedCompleteCount() / (double)reportedTotalChunks);
			bossEvent.setProgress((float)fraction);
			bossEvent.addPlayer(player);
		}

		private void updatePregenThrottleBossBar(int pending, boolean stopped) {
			OceanCanvasSurfaceFlattener.PregenQueueDiagnostics diag =
					OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);

			if (requestedBy != null) {
				ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
				if (player != null) {
					String title;
					if (heapPressureHigh()) {
						title = "Ocean Canvas - Pregen paused: memory pressure";
					} else {
						// Keep the most actionable reasons in the boss bar. The
						// complete snapshot, including loading-not-queued, is logged.
						String reasons = diag.missingNeighbor() + " neighbor, "
								+ diag.missingStructureOwner() + " structure, "
								+ diag.ready() + " ready";
						title = stopped
								? "Ocean Canvas - Pregen blocked (" + diag.queued() + "): " + reasons
								: "Ocean Canvas - Pregen throttled (" + diag.queued() + "): " + reasons;
					}
					bossEvent.setName(Component.literal(title));
					double fraction = reportedTotalChunks == 0 ? 0.0D
							: Math.min(1.0D, (double)submittedCount / (double)reportedTotalChunks);
					bossEvent.setProgress((float)fraction);
					bossEvent.addPlayer(player);
				}
			}

			// Hard-stop diagnostics must still advance even though Job.tick()
			// returns before the normal report counters below are incremented.
			ticksSinceQueueDiagnosticLog++;
			if (stopped && ticksSinceQueueDiagnosticLog >= 100) {
				ticksSinceQueueDiagnosticLog = 0;
				OceanCanvas.LOGGER.warn(
						"(Ocean Canvas) Pregen hard-backpressure diagnostic at {} submitted / {} total: {}. "
								+ "If ready remains >0, the flattener budget is the bottleneck; "
								+ "if neighbor dominates, dependency loading is blocked; "
								+ "if structure dominates, referenced owner chunks are blocked.",
						submittedCount, reportedTotalChunks, diag.shortText());
			}
		}

		private void updatePregenFinalizingProgress(int outstanding) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			var d = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
			bossEvent.setName(Component.literal(
					"Ocean Canvas - Pregen finalizing: " + outstanding + " left; "
							+ d.ready() + " ready, " + d.missingNeighbor() + " neighbor, "
							+ d.missingStructureOwner() + " structure, " + d.loadingNotQueued() + " loading"));
			// Submission can legitimately reach 100% before C2ME's asynchronous
			// generation/carving pipeline drains. Keep the bar visibly below
			// complete until every target has actually been carved/protected.
			bossEvent.setProgress(0.99F);
		}

		private void updateLightingFinalizationProgress(int pending, int tickets) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			String label = ("region-rewipe".equals(kind) || "region-reset".equals(kind) || "rewipe".equals(kind) || "reset".equals(kind))
					? "Reset" : ("expand".equals(kind) ? "Expand" : "Pregen");
			bossEvent.setName(Component.literal("Ocean Canvas - " + label + ": Finalizing lighting ("
					+ pending + " pending; terrain complete, " + tickets + " tickets)"));
			bossEvent.setProgress(0.99F);
			ActionBarUtil.send(player, "[Ocean Canvas] " + label + " - finalizing lighting: " + pending
					+ " pending; terrain submission is complete");
		}

		/**
		 * v253.125.17 truthful progress surface for the mixed terrain+lighting phase.
		 * Backpressure can temporarily stop terrain admission and later resume it; the
		 * lighting queue may therefore legitimately gain newly authored chunks. Calling
		 * that number "remaining" made a healthy flow-controlled Pregen look like its
		 * finalization was running backward. Reserve "finalizing" for the terminal gate.
		 */
		private void updateLightingBackpressureProgress(int pending, int tickets) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			String label = ("region-rewipe".equals(kind) || "region-reset".equals(kind) || "rewipe".equals(kind) || "reset".equals(kind))
					? "Reset" : ("expand".equals(kind) ? "Expand" : "Pregen");
			bossEvent.setName(Component.literal("Ocean Canvas - " + label + ": Lighting backpressure ("
					+ pending + " pending, " + tickets + " tickets; bounded terrain lane stays open when safe)"));
			bossEvent.setProgress(0.985F);
			ActionBarUtil.send(player, "[Ocean Canvas] " + label + " - lighting backpressure: "
					+ pending + " pending; terrain is still active/resumable through a bounded lane");
		}

		private void updateLightingStabilityProgress(int cleanPass, int requiredPasses) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			String label = ("region-rewipe".equals(kind) || "region-reset".equals(kind) || "rewipe".equals(kind) || "reset".equals(kind))
					? "Reset" : ("expand".equals(kind) ? "Expand" : "Pregen");
			bossEvent.setName(Component.literal("Ocean Canvas - " + label + ": Verifying lighting stability ("
					+ cleanPass + "/" + requiredPasses + ")"));
			bossEvent.setProgress(0.995F);
			ActionBarUtil.send(player, "[Ocean Canvas] " + label + " - verifying lighting stability: "
					+ cleanPass + "/" + requiredPasses);
		}

		private void updateRewipeFinalizingProgress(int pending) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			int completed = (int)Math.max(0L, reportedTotalChunks - pending);
			bossEvent.setName(Component.literal("Ocean Canvas - Reset: Finalizing chunks (" + pending + " remaining)"));
			// Keep this within the carving allocation. It should visibly move to
			// structure work only after the exact reset mask has drained.
			double fraction = reportedTotalChunks == 0 ? 0.90D
					: 0.90D * Math.min(1.0D, (double)completed / (double)reportedTotalChunks);
			bossEvent.setProgress((float)fraction);
			bossEvent.addPlayer(player);
		}

		private void updateBossBarOnly() {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			double fraction = reportedTotalChunks == 0 ? 1.0D
					: Math.min(1.0D, (double) (isPregenKind(kind) ? confirmedCompleteCount() : submittedCount) / (double) reportedTotalChunks);
			if (("region-rewipe".equals(kind) || "region-reset".equals(kind))) {
				fraction *= 0.90D;
				bossEvent.setName(Component.literal("Ocean Canvas - Reset: Carving chunks"));
			} else if (isPregenKind(kind)) {
				int outstanding = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount();
				int heapPct = (int)Math.round(heapUseFraction() * 100.0D);
				bossEvent.setName(Component.literal(
						"Ocean Canvas - Pregen: " + Math.max(1, adaptivePregenRate)
								+ "/tick, " + outstanding + " in flight, " + heapPct + "% heap"));
			}
			bossEvent.setProgress((float) fraction);
		}

		private void updateActionBar() {
			if (requestedBy == null) {
				return;
			}
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) {
				return;
			}
			long displayedComplete = isPregenKind(kind) ? confirmedCompleteCount() : submittedCount;
			ActionBarUtil.send(player, "[Ocean Canvas] " + kind + " " + ActionBarUtil.progressBar(displayedComplete, reportedTotalChunks));
			// addPlayer every tick this fires, not once - it's backed by a
			// Set (repeat calls for a player already added are harmless),
			// and re-checking each time means a player who disconnects and
			// reconnects mid-job gets the bar back automatically, the same
			// way the action-bar send above already re-resolves "is the
			// requester online" from scratch every tick rather than
			// assuming a one-time answer still holds.
			bossEvent.addPlayer(player);
			double chunkFraction = reportedTotalChunks == 0 ? 1.0D
					: Math.min(1.0D, (double) displayedComplete / (double) reportedTotalChunks);
			// Reserve the final 10% of a region Reset for staged structure work.
			// If no staged structure is needed, completion simply jumps 90 -> 100.
			if (("region-rewipe".equals(kind) || "region-reset".equals(kind))) chunkFraction *= 0.90D;
			bossEvent.setProgress((float) chunkFraction);
		}

		/** Show reset progress immediately, even for a tiny region that can finish
		 * its chunk-submission phase before the normal action-bar cadence fires. */
		private void showInitialBossBar() {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;

			if (("region-rewipe".equals(kind) || "region-reset".equals(kind))) {
				bossEvent.setName(Component.literal("Ocean Canvas - Reset: Starting"));
			}
			bossEvent.setProgress(0.0F);
			bossEvent.addPlayer(player);
			if (("region-rewipe".equals(kind) || "region-reset".equals(kind))) {
				OceanCanvas.LOGGER.info("(Ocean Canvas) Rewipe progress bar attached; entering rewipe job.");
			}
		}

		/** Keep the existing Pregen boss bar visible through staged FORCE_ON shipwreck work. */
		private void updatePregenPostProcessProgress(String stage, int completed, int total) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			String countText = total > 1 ? " (" + completed + "/" + total + ")" : "";
			bossEvent.setName(Component.literal("Ocean Canvas - Pregen: " + stage + countText));
			double stageFraction = total <= 0 ? 1.0D : Math.min(1.0D, (double) completed / (double) total);
			bossEvent.setProgress((float) (0.90D + 0.10D * stageFraction));
			ActionBarUtil.send(player, "[Ocean Canvas] Pregen - " + stage + " "
					+ ActionBarUtil.progressBar(completed, Math.max(1, total)));
		}

		/** Keep the existing Reset boss bar visible through staged FORCE_ON work. */
		private void updateRewipePostProcessProgress(String stage, int completed, int total) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			String countText = total > 1 ? " (" + completed + "/" + total + ")" : "";
			bossEvent.setName(Component.literal("Ocean Canvas - Reset: " + stage + countText));
			double stageFraction = total <= 0 ? 1.0D : Math.min(1.0D, (double) completed / (double) total);
			bossEvent.setProgress((float) (0.90D + 0.10D * stageFraction));
			ActionBarUtil.send(player, "[Ocean Canvas] Reset - " + stage + " "
					+ ActionBarUtil.progressBar(completed, Math.max(1, total)));
		}

		/**
		 * v125: keeps a PLAIN (non-region) pregen/rewipe/expand job's own boss bar visible through
		 * its staged world-default "Always" forced-placement work - the same treatment {@link
		 * #updatePregenPostProcessProgress}/{@link #updateRewipePostProcessProgress} already give
		 * their own region-scoped equivalents, generalized to whichever plain kind is actually
		 * running (rewipe/expand had no such phase before this - only pregen did, and only for
		 * real drawn zones).
		 */
		private void updateWorldDefaultPostProcessProgress(String stage, int completed, int total) {
			if (requestedBy == null) return;
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player == null) return;
			bossEvent.addPlayer(player);
			String label = switch (kind) {
				case "rewipe", "reset" -> "Reset";
				case "expand" -> "Expand";
				default -> "Pregen";
			};
			String countText = total > 1 ? " (" + completed + "/" + total + ")" : "";
			bossEvent.setName(Component.literal("Ocean Canvas - " + label + ": " + stage + countText));
			double stageFraction = total <= 0 ? 1.0D : Math.min(1.0D, (double) completed / (double) total);
			bossEvent.setProgress((float) (0.90D + 0.10D * stageFraction));
			ActionBarUtil.send(player, "[Ocean Canvas] " + label + " - " + stage + " "
					+ ActionBarUtil.progressBar(completed, Math.max(1, total)));
		}

		/**
		 * Removes the boss bar from every player it was shown to - called
		 * from every path that stops a job (normal completion, an explicit
		 * cancel, {@code pregenEnabled} turned off mid-job), so a boss bar
		 * can never outlive the job it was reporting on. {@code
		 * removeAllPlayers()} is safe to call even if {@link
		 * #bossPlayerAdded} is still false (nobody was ever added) - no
		 * separate guard needed.
		 */
		void hideBossBar() {
			bossEvent.removeAllPlayers();
		}

		/**
		 * v253.125.13 terrain accounting for restart replay. A graceful recovery deque
		 * can contain tens of thousands of LIGHT_ONLY proof obligations whose terrain is
		 * already retired. Counting that entire deque as unfinished terrain made progress
		 * jump backward by 58k chunks and falsely held the no-progress/P2 controller in a
		 * permanent failure state. Hard-crash rescans remain fully terrain-unresolved;
		 * during graceful quarantine only PHYSICAL_AWARE entries still awaiting admission
		 * count against terrain completion.
		 */
		private long terrainUnresolvedReplayCount() {
			if (resumeReplayChunks.isEmpty()) return 0L;
			if (restartRecoveryRescanActive) return resumeReplayChunks.size();
			if (restartRecoveryQuarantineActive) {
				if (!physicalRecoveryAwaitingAdmission()) return 0L;
				return Math.min((long) resumeReplayChunks.size(), (long) restartRecoveryPhysicalPending.size());
			}
			return resumeReplayChunks.size();
		}

		/** v218 controller-only success signal: excludes already-verified free skips
		 * and mandatory terrain-deferred/replay work. This is what may earn more capacity. */
		private long physicallyRetiredPregenCount() {
			if (!isPregenKind(kind)) return submittedCount;
			long issued = Math.max(0L, submittedCount - skippedProcessed);
			return Math.max(0L, issued
					- OceanCanvasSurfaceFlattener.outstandingPregenTargetCount()
					- deferredPregenChunks.size()
					- terrainUnresolvedReplayCount());
		}

		private long confirmedCompleteCount() {
			if (!isPregenKind(kind)) return submittedCount;
			// v253.61.7: lighting is a separate, explicitly reported finalization gate.
			// Subtracting its mutable queue size made user-visible progress run backward
			// whenever a chunk was re-armed even though no terrain completion was lost.
			// Count only terrain retirement here; final completion still waits for the
			// global light queue/tickets and the post-release visual-integrity gate above.
			return Math.max(0L, Math.min(reportedTotalChunks,
					submittedCount - OceanCanvasSurfaceFlattener.outstandingPregenTargetCount()
							- deferredPregenChunks.size() - terrainUnresolvedReplayCount()));
		}

		String describeProgress() {
			// v120: Phase 1 has no carve-side completion signal at all -
			// outstandingPregenTargetCount() is always 0 during Phase 1 since
			// it never touches PREGEN_TARGET_CHUNKS (see tickGenerate's class
			// doc). Reporting via confirmedCompleteCount() here would show the
			// raw-generation count as if it were confirmed-carved progress -
			// wrong and misleading for /oceancanvas status, the cancel
			// message, and the action log, all of which call this method.
			long complete = confirmedCompleteCount();
			long percent = reportedTotalChunks == 0 ? 100 : (complete * 100L) / reportedTotalChunks;
			if (isPregenKind(kind)) {
				long terrainUnresolved = OceanCanvasSurfaceFlattener.outstandingPregenTargetCount()
						+ deferredPregenChunks.size() + terrainUnresolvedReplayCount();
				int liveLightingPending = OceanCanvasSurfaceFlattener.lightFinalizationDiagnostics(
						minChunkX, maxChunkX, minChunkZ, maxChunkZ).pendingSync();
				long durableLightingPending = Math.max((long) liveLightingPending,
						Math.max((long) restartRecoveryQuarantinePending.size(), (long) restartRecoveryRescanPending.size()));
				return complete + "/" + reportedTotalChunks + " chunks terrain-confirmed (" + percent + "%; "
						+ terrainUnresolved + " terrain unresolved; " + durableLightingPending + " lighting pending)";
			}
			return submittedCount + "/" + reportedTotalChunks + " chunks submitted (" + percent + "%)";
		}

		private void reportProgress() {
			// Generic wording ("pregen/rewipe/restore/expand") since this Job class is
			// shared by both commands - see PregenManager#rewipe's doc.
			String message = "Pregen/reset/expand progress: " + describeProgress();
			OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
			sendToRequesterIfOnline(message);
		}

		void reportDone() {
			String message;
			if (isPregenKind(kind)) {
				message = "Pregen finished: " + reportedTotalChunks + "/" + reportedTotalChunks
						+ " target chunks confirmed complete; zero failed targets; terrain, lighting finalization, and Ocean Canvas-owned work are fully drained."
						+ (skippedProcessed > 0 ? " " + skippedProcessed + " chunks already had verified Canvas seals and were not regenerated." : "");
			} else {
				message = "Pregen/reset/expand finished: " + reportedTotalChunks + " target chunks completed.";
			}
			OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
			if (isPregenKind(kind)) {
				String acceptanceWorldUuid = net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardshipData.get(world).identity().worldUuid();
				OceanCanvas.LOGGER.info("(Ocean Canvas) PREGEN-ACCEPTANCE-DONE build={} worldUuid={} chunks={}",
						net.oceancanvas.mod.OceanCanvas.VERSION, acceptanceWorldUuid, reportedTotalChunks);
			}
			sendToRequesterIfOnline(message);
			// Final action bar update at 100% - it fades on its own after
			// vanilla's normal action-bar display duration, no explicit
			// clear needed.
			if (requestedBy != null) {
				ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
				ActionBarUtil.send(player, "[Ocean Canvas] " + kind + " " + ActionBarUtil.progressBar(reportedTotalChunks, reportedTotalChunks));
				if (player != null) {
					bossEvent.setProgress(1.0F);
					// A vanilla-style title card, the same beat a raid's
					// victory banner gives - the boss bar alone can go from
					// visible to gone in under a second at low chunk counts,
					// which reads as a glitch rather than a finished job
					// without something else marking the moment.
					sendTitle(player, "Canvas " + (kind.equals("expand") ? "expanded" : kind + " complete"));
				}
			}
		}

		/**
		 * A vanilla title/subtitle card - the same channel a raid's "Raid
		 * Won"/"Raid Bad Omen" banner uses, borrowed here so a finished
		 * canvas job reads as a game event rather than a chat line nobody
		 * was looking at. Subtitle carries the actual numbers; the title
		 * itself stays short enough to read at the large default title
		 * font size.
		 */
		private void sendTitle(ServerPlayer player, String titleText) {
			player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
					Component.literal(titleText)));
			player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
					Component.literal(reportedTotalChunks + " chunks confirmed complete; zero failures")));
		}

		ServerPlayer requesterPlayer() {
			return requestedBy == null ? null : world.getServer().getPlayerList().getPlayer(requestedBy);
		}

		private void sendToRequesterIfOnline(String message) {
			if (requestedBy == null) {
				return;
			}
			ServerPlayer player = world.getServer().getPlayerList().getPlayer(requestedBy);
			if (player != null) {
				player.sendSystemMessage(Component.literal("[Ocean Canvas] " + message));
			}
		}
	}
}
