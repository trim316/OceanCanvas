package net.oceancanvas.mod.worldgen;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Observation/rate-limit state that must not leak across integrated-server lifetimes. */
final class OceanCanvasLightTelemetrySession {
    final AtomicLong LIGHT_DIAG_SAFE_SWEEPS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_SAFE_SWEEP_BLOCK_CHECKS = new AtomicLong();
    final OceanCanvasPrimitiveLongIntMap LIGHT_DIAG_NEIGHBOR_WAITS = new OceanCanvasPrimitiveLongIntMap();
    final AtomicLong LIGHT_DIAG_FINALIZED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_HEALTHY = new AtomicLong();
    final AtomicLong LIGHT_DIAG_STALE_HEIGHT_FIXED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_HEIGHT_STILL_BAD = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PHYSICAL_SUSPECT = new AtomicLong();
    final AtomicLong LIGHT_DIAG_NEIGHBOR_STARVED = new AtomicLong();
    // v253.125.24: the .23 soak produced 3,500+ one-shot neighbor-load WARN lines.
    // Keep the full starvation count, but separately count warnings suppressed by
    // milestone-only logging so log I/O can never become part of the hot path.
    final AtomicLong LIGHT_DIAG_NEIGHBOR_WAIT_WARNINGS_SUPPRESSED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_RELIGHT_FAILED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_RELIGHT_SLOW = new AtomicLong();
    final AtomicLong LIGHT_DIAG_FINALIZER_STALL_ESCAPES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_QUARANTINED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_QUARANTINE_RELEASES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_QUARANTINE_WAKES = new AtomicLong();
    final AtomicInteger LIGHT_QUARANTINE_PROBES = new AtomicInteger();
    final OceanCanvasPrimitiveLongLongMap LIGHT_DIAG_LAST_DETAIL_WARN_TICK = new OceanCanvasPrimitiveLongLongMap();
    final AtomicLong LIGHT_DIAG_SUPPRESSED_DETAIL_WARNINGS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PERSISTENT_DEFERRED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PERSISTENT_WOKEN = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PERSISTENT_WAKE_PRESSURE_HOLDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_FOREVER_WORLD_PRESSURE_PARKS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_FOREVER_WORLD_PRESSURE_WAKES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_WORK_BUDGET_DEFERRALS = new AtomicLong();
    // v253.125.28 square-overnight locality/fair-share telemetry.
    final AtomicLong LIGHT_DIAG_TILE_LOCALITY_HITS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_TILE_RESIDENT_FIRST = new AtomicLong();
    final AtomicLong LIGHT_DIAG_CURRENT_TERRAIN_FIRST = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PATHOLOGY_FAIR_SHARE_DEFERRALS = new AtomicLong();
    // v253.125.29: admission-only relight residency governor. Existing productive
    // tickets are never torn down solely for heap pressure; the cap constrains new
    // installs until natural releases bring resident memory back under control.
    final AtomicLong LIGHT_DIAG_RESIDENCY_CAP_HOLDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_RESIDENCY_CAP_TRANSITIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_RESIDENCY_BUDGET_SAMPLES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_RESIDENCY_EMERGENCY_DOWNSHIFTS = new AtomicLong();
    // v253.125.52: keep the governor's initial state identical to the actual hard
    // residency ceiling. .51 lowered LIGHT_RELIGHT_RESIDENCY_TICKET_MAX to 48 but
    // left this session-local seed at 64, so a fresh server could still admit 64
    // historical relight tickets before the heap governor sampled a transition.
    volatile int lightDiagEffectiveResidencyCap = 48;
    volatile long lightDiagResidencyLastSampleTick = Long.MIN_VALUE;
    volatile long lightDiagResidencyLastHoldTick = Long.MIN_VALUE;
    volatile double lightDiagResidencyHeapEma = -1.0D;
    volatile int lightDiagResidencyHighSamples;
    volatile int lightDiagResidencyLowSamples;
    volatile long lightDiagResidencyLastTransitionTick = Long.MIN_VALUE;
    volatile long lightDiagHeapSampleTick = Long.MIN_VALUE;
    volatile double lightDiagHeapSampleFraction;
    // v253.125.31 scheduler/diagnostic administration telemetry.
    final AtomicLong LIGHT_DIAG_SCHEDULER_SAMPLED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_SCHEDULER_QUEUE_REPAIRS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_SCHEDULER_MAX_ADMIN_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_FAST_PENDING_COUNT_FALLBACKS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_HEAVY_ESCAPE_GLOBAL_THROTTLES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_LATE_SHUTDOWN_CHUNK_LOADS_IGNORED = new AtomicLong();
    // v253.125.10: log-driven liveness/priority evidence. These counters distinguish
    // strict-proof shortcuts and visible-ticket rescue from ordinary finalizer work.
    final AtomicLong LIGHT_DIAG_FAST_CERTIFIED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_FAST_CERTIFIED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PRESSURE_FAST_CERTIFIED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_TICKET_PREEMPTS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_CRASH_RECOVERY_HANDOFFS = new AtomicLong();
    long lightDiagDetailBudgetTick = Long.MIN_VALUE;
    int lightDiagDetailBudgetUsed;
    long lightDiagLastAggregateTick = Long.MIN_VALUE;
    final AtomicLong LIGHT_DIAG_SOURCE_RESEEDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_HARD_SKY_RESETS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_SKY_SAMPLES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_SKY_SAMPLES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_SKY_ANOMALOUS_LAYERS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_SKY_ANOMALOUS_COLUMNS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_SKY_OVERBRIGHT_LAYERS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_SKY_OVERBRIGHT_COLUMNS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_SKY_ANOMALOUS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_SKY_ESCALATIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_FINAL_PUBLISHES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DIRTY_ADDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DIRTY_REARMS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DIRTY_COALESCED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_BOUNDARY_RECOVERY_EPOCH_PRESERVED = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VERIFIED_NEIGHBOR_SKIPS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VERIFIED_NEIGHBOR_AUDITS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_POST_STAGE_MUTATIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_TERRAIN_BARRIER_TICKS = new AtomicLong();
    // v253.125.23: pre-stage residency slots surrendered while real admitted
    // adjacent terrain owns the boundary. This proves barrier liveness directly.
    final AtomicLong LIGHT_DIAG_TERRAIN_BARRIER_TICKET_RELEASES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_STRUCTURE_BARRIER_TICKS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PERSISTENT_SKY_BACKOFFS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_ZERO_SCRUBS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_ZERO_SCRUB_SECTIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_ZERO_SCRUB_INCONCLUSIVE = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERIES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_SECTIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_ZERO_PUBLIC_RECOVERY_FAILURES = new AtomicLong();
    // v253.125.14 player-visible dense deep-water decrease propagation.
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_DENSE_REPAIRS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_DENSE_SECTIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_DENSE_CHECKS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_DENSE_INCONCLUSIVE = new AtomicLong();
    // v253.125.16 cross-chunk visible repair for seam-fed stale light islands.
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_REPAIRS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_CHUNK_SECTIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_CHECKS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_INCONCLUSIVE = new AtomicLong();
    final AtomicLong LIGHT_DIAG_VISIBLE_DEEP_CLUSTER_THROTTLED = new AtomicLong();
    // v253.125.25: large public checkBlock waves are resumable. These counters prove
    // the server-tick bound and expose heap/context deferrals instead of hiding them.
    final AtomicLong LIGHT_DIAG_DEEP_REPAIR_SLICES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_REPAIR_SLICE_YIELDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_REPAIR_HEAP_DEFERRALS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_REPAIR_HEAP_ESCAPE_SLICES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_REPAIR_CONTEXT_ABORTS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_REPAIR_MAX_SLICE_CHECKS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_DEEP_REPAIR_MAX_SLICE_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PROFILE_REPAIRS_AFTER_CERT = new AtomicLong();
    // Adaptive physical quiescence after proven post-carve block instability.
    final AtomicLong LIGHT_DIAG_ADAPTIVE_QUIET_ESCALATIONS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_ADAPTIVE_QUIET_TICKET_RELEASES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_INSTABILITY_STREAK = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_ADAPTIVE_QUIET_TICKS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PATHOLOGY_HOTSPOTS = new AtomicLong();
    // v253.125.25 phase-level maxima. These make a future controller/flattener
    // spike attributable without needing a profiler attached during an overnight run.
    final AtomicLong LIGHT_DIAG_MAX_STRICT_SKY_PROOF_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_PHYSICAL_AUDIT_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_BOUNDARY_FINGERPRINT_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_LIGHT_SWEEP_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_HARD_RESET_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_SLOW_PHASE_EVENTS = new AtomicLong();
    // v253.125.26 cooperative proof/audit/sweep telemetry.
    final AtomicLong LIGHT_DIAG_PHYSICAL_AUDIT_SLICES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_PHYSICAL_AUDIT_YIELDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_STRICT_PROOF_SLICES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_STRICT_PROOF_YIELDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_LIGHT_SWEEP_SLICES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_LIGHT_SWEEP_YIELDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_FINGERPRINT_SLICES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_FINGERPRINT_YIELDS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_HEAVY_PHASE_HEAP_DEFERRALS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_HEAVY_PHASE_HEAP_ESCAPES = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_PHYSICAL_AUDIT_SLICE_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_STRICT_PROOF_SLICE_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_LIGHT_SWEEP_SLICE_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_MAX_FINGERPRINT_SLICE_NANOS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_STRUCTURE_BARRIER_TICKET_RELEASES = new AtomicLong();
    final OceanCanvasPrimitiveLongSet FLUID_REACTION_PRODUCT_SUSPECT_CHUNKS = new OceanCanvasPrimitiveLongSet();
    final AtomicLong LIGHT_DIAG_RESTART_AUDIT_HEALTHY = new AtomicLong();
    // v253.125.24: healthy persisted-certificate audits yield to real uncertified
    // active/backoff/pressure debt. This counts controller ticks deferred by that
    // priority rule; pending audit entries remain strict completion debt.
    final AtomicLong LIGHT_DIAG_PERSISTED_AUDIT_PRIORITY_DEFERRALS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_RESTART_AUDIT_REPAIRS = new AtomicLong();
    final AtomicLong LIGHT_DIAG_REJOIN_REPUBLISHES = new AtomicLong();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasHeightmapDiag> LIGHT_DIAG_PRE_PRIME = new OceanCanvasPrimitiveLongObjectMap<>();
    final java.util.concurrent.atomic.AtomicBoolean LIGHT_DEEP_ZERO_PUBLIC_RECOVERY_UNAVAILABLE_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();
    final AtomicLong FLUID_LAVA_PREDRAIN_BLOCKS = new AtomicLong();
    final AtomicLong FLUID_REACTION_PRODUCT_SUSPECTS = new AtomicLong();
    final AtomicLong FLUID_SETTLE_REPAIRED_BLOCKS = new AtomicLong();
    final AtomicLong FLUID_SETTLE_REPAIRED_CHUNKS = new AtomicLong();
    final AtomicLong FLUID_WATERFALL_SURVIVOR_BLOCKS = new AtomicLong();
    final AtomicLong FLUID_FLOWING_WATER_NORMALIZED = new AtomicLong();
    final AtomicLong FLUID_WATERFALL_SURVIVOR_CHUNKS = new AtomicLong();
    final AtomicLong RAW_PENDING_BLOCK_ENTITIES_REMOVED = new AtomicLong();
    final AtomicLong LIGHT_POST_AUDIT_REQUEUES = new AtomicLong();
}
