package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

/** Server-session-owned mutable state for the staged authoritative lighting finalizer. */
final class OceanCanvasLightFinalizerSession {
    // v253.125.41: active delay/pass bookkeeping is primitive and striped. The
    // previous ConcurrentHashMap<Long,Integer> pair boxed every hot scheduler key
    // and countdown value even though neither is persisted or exposed as world state.
    final OceanCanvasSurfaceFlattener.LightRetryTicks pendingTicks = new OceanCanvasSurfaceFlattener.LightRetryTicks();
    final OceanCanvasPrimitiveLongIntMap pendingPasses = new OceanCanvasPrimitiveLongIntMap();
    // v253.125.43: scalar/session-only finalizer membership is primitive too; authoritative world state is unchanged.
    final OceanCanvasPrimitiveLongSet allowPhysicalRepair = new OceanCanvasPrimitiveLongSet();
    final AtomicLong scanCursor = new AtomicLong();
    // v253.125.31: allocation-light rotating work order. The .30 five-hour
    // diagnostic proved that rebuilding/sorting every pending key each tick was a
    // major GC/admin cost outside the 8ms light-work budget. Membership prevents
    // duplicate queue nodes; sampled entries are requeued while they remain pending.
    final OceanCanvasPrimitiveLongQueue pendingWorkOrder = new OceanCanvasPrimitiveLongQueue();
    final OceanCanvasPrimitiveLongSet pendingWorkMembership = new OceanCanvasPrimitiveLongSet();
    // v253.125.35: event-driven priority lanes. Visible and freshly-authored
    // physical work no longer need to be rediscovered by repeatedly sampling the
    // generic rotating queue. Ordinary background work retains pendingWorkOrder.
    // Stale queue nodes are tolerated; membership sets are the authoritative lane.
    final OceanCanvasPrimitiveLongQueue visibleWorkOrder = new OceanCanvasPrimitiveLongQueue();
    final OceanCanvasPrimitiveLongSet visibleWorkMembership = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongQueue terrainWorkOrder = new OceanCanvasPrimitiveLongQueue();
    final OceanCanvasPrimitiveLongSet terrainWorkMembership = new OceanCanvasPrimitiveLongSet();
    // v253.125.38: scheduler lane/load-hint membership is primitive and synchronized;
    // scheduler locality only needs a load-state hint; execution still
    // performs the authoritative getChunkNow check. Maintaining this set from Fabric
    // load/unload events avoids hundreds of ServerChunkCache lookups per scheduler
    // tick without allowing a stale hint to certify or mutate an unloaded chunk.
    final OceanCanvasPrimitiveLongSet loadedChunkHints = new OceanCanvasPrimitiveLongSet();
    // v253.125.32: fixed primitive scheduler scratch. The .31 rotating queue bounded
    // how many obligations are considered, but it still allocated boxed candidate
    // lists/sets/records and sorted them every tick. These buffers are session-owned
    // and reused for the lifetime of the server.
    static final int SCHEDULER_CANDIDATE_CAPACITY = 1024;
    static final int VISIBLE_PRIORITY_CAPACITY = 96;
    final long[] schedulerCandidatePacked = new long[SCHEDULER_CANDIDATE_CAPACITY];
    final long[] schedulerWorkWindow = new long[SCHEDULER_CANDIDATE_CAPACITY];
    // v253.125.36: stale queue history is compacted from authoritative membership
    // before it can dominate scheduler administration. These counters are diagnostic
    // only and do not affect fairness or correctness.
    final AtomicLong schedulerLaneCompactions = new AtomicLong();
    final AtomicLong schedulerStaleNodesDropped = new AtomicLong();
    final long[] schedulerVisiblePacked = new long[VISIBLE_PRIORITY_CAPACITY];
    final long[] schedulerVisibleDistanceSq = new long[VISIBLE_PRIORITY_CAPACITY];

    // Small-debt exact counting without HashSet allocation. Stamps make clearing O(1).
    // Capacity 16384 keeps load <= 25% for the 4096-entry exact-count threshold.
    final long[] pendingCountKeys = new long[16384];
    final int[] pendingCountStamps = new int[16384];
    int pendingCountGeneration = 1;
    // v253.125.35: exact small-debt counting is cached by a monotonic membership
    // generation. Delay/pass updates do not invalidate it; only movement into or
    // out of active/backoff/pressure debt does. This keeps the expensive tail of
    // Pregen from rescanning the same few thousand keys on every status/controller
    // sample while preserving fail-closed exact zero.
    final AtomicLong debtMembershipGeneration = new AtomicLong();
    volatile long cachedPendingCountGeneration = Long.MIN_VALUE;
    volatile int cachedPendingCountExact;
    // v253.125.39: reusable primitive dedup scratch for crash/checkpoint light-debt snapshots.
    // The returned snapshot owns its own long[]; this set is cleared and reused so a six-figure
    // recovery tail does not allocate a new boxed LinkedHashSet graph every ~10-second checkpoint.
    final LongLinkedOpenHashSet checkpointPendingScratch = new LongLinkedOpenHashSet();
    // v253.125.46: reusable primitive iteration scratch for recovery reconciliation
    // and pressure parking. Keys are copied while one ledger is locked, then all
    // cross-ledger reads/mutations happen after those locks are released. This
    // avoids both repeated full-key array allocation and striped lock inversion.
    final LongArrayList recoveryIterationScratch = new LongArrayList(1024);
    final AtomicLong heavyPhaseNextGlobalEscapeTick = new AtomicLong(Long.MIN_VALUE);
    // v253.125.33: server-stability circuit breaker. A previous long tick or
    // critical heap sample may pause expensive light proof for a bounded number
    // of game ticks. Correctness debt stays armed; this only prevents the
    // finalizer from piling work onto an already-stalled integrated server.
    final AtomicLong runtimePressureHoldUntilTick = new AtomicLong(Long.MIN_VALUE);
    final AtomicLong runtimePressureLastLogTick = new AtomicLong(Long.MIN_VALUE);
    final AtomicLong runtimePressureNextEscapeTick = new AtomicLong(Long.MIN_VALUE);
    // Retain harder/tick-pressure cooldown history when metrics improve to mild heap pressure.
    final AtomicLong runtimePressureConservativeUntilTick = new AtomicLong(Long.MIN_VALUE);
    final AtomicLong runtimePressureHolds = new AtomicLong();
    // v253.125.34: historical LIGHT_ONLY recovery must not churn a new radius-1
    // forced neighborhood on every successful certificate. Completed historical
    // tickets may remain warm briefly so adjacent obligations can reuse the same
    // 3x3 residency, and new historical installs are globally paced. Fresh terrain
    // and player-visible repairs bypass this limiter.
    final OceanCanvasPrimitiveLongLongMap historicalWarmResidencyUntilTick = new OceanCanvasPrimitiveLongLongMap();
    final long[] historicalWarmReleaseScratch = new long[16];
    final AtomicLong nextHistoricalResidencyInstallTick = new AtomicLong(Long.MIN_VALUE);
    final AtomicLong historicalResidencyInstallDeferrals = new AtomicLong();
    final AtomicLong historicalWarmResidencyReuses = new AtomicLong();
    // Successful visible-repair publication is expected recovery telemetry, not an
    // error. Bound detail volume so a reconnect wave cannot emit hundreds of WARNs.
    final AtomicLong visiblePublishLogWindowStartTick = new AtomicLong(Long.MIN_VALUE);
    final AtomicLong visiblePublishDetailedLogs = new AtomicLong();
    final AtomicLong visiblePublishSuppressedLogs = new AtomicLong();
    // v253.125.28: locality anchor for the 20k x 20k overnight pipeline. The
    // authoritative target order is unchanged; this only lets the light finalizer
    // spend its bounded per-tick budget on chunks from the most recently productive
    // 8x8 tile before paying for unrelated residency churn.
    final AtomicLong lastProductiveTileKey = new AtomicLong(Long.MIN_VALUE);
    // v253.125.6: player-visible lighting repairs must not wait behind an unrelated
    // historical recovery cohort. Entries remain in the same strict finalizer; this
    // set only changes fair scan order and never changes proof/repair semantics.
    final OceanCanvasPrimitiveLongSet visibleLightPriority = new OceanCanvasPrimitiveLongSet();
    // v253.125.9: ConcurrentHashMap key-set iteration destroyed the nearest-first
    // ordering from the rejoin audit. Preserve the smallest observed player-distance
    // rank per chunk so the exact chunks the player is standing over are serviced
    // before the hundreds of farther tracked-but-uncertified chunks.
    final OceanCanvasPrimitiveLongLongMap visibleLightPriorityDistanceSq = new OceanCanvasPrimitiveLongLongMap();
    final AtomicLong visibleLightPriorityCursor = new AtomicLong();
    // JOIN fires before the integrated-server tracking window is populated on 26.2.
    // Keep a bounded per-player audit window so spawn/rejoin chunks are proved only
    // after the player actually tracks them instead of doing a one-shot zero-candidate scan.
    final Map<UUID, Long> rejoinAuditDeadlineTick = new ConcurrentHashMap<>();
    final Map<UUID, OceanCanvasPrimitiveLongSet> rejoinAuditSeen = new ConcurrentHashMap<>();
    final Map<UUID, Integer> rejoinAuditHealthy = new ConcurrentHashMap<>();
    final Map<UUID, Integer> rejoinAuditBad = new ConcurrentHashMap<>();
    // v253.125.7: the fixed repro spot proved a lighting-visible chunk may be
    // tracked and physically canonical without yet owning a lighting certificate.
    // Count those separately so the spawn audit cannot silently call them healthy.
    final Map<UUID, Integer> rejoinAuditUnverified = new ConcurrentHashMap<>();
    // v253.125.18: the bounded join audit cannot protect a player who walks or
    // teleports to another already-resident Canvas area later in the same session.
    // Keep a tiny rotating cursor per player for the continuous visible-light sentinel.
    final Map<UUID, Long> visibleSentinelCursor = new ConcurrentHashMap<>();
    final AtomicLong lastRetirementNs = new AtomicLong();
    final OceanCanvasPrimitiveLongLongMap relightStartedNs = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongLongMap stagedBlockFingerprint = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongLongMap terrainLastMutationTick = new OceanCanvasPrimitiveLongLongMap();
    // v253.125.35: per-tick mutation generations collapse duplicate callbacks that
    // describe the same physical/boundary epoch. Genuine later-tick mutations still
    // re-arm proof exactly as before.
    final OceanCanvasPrimitiveLongLongMap physicalMutationGenerationTick = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongLongMap boundaryMutationGenerationTick = new OceanCanvasPrimitiveLongLongMap();
    // v253.125.26: expensive read/proof phases are resumable so one pathological
    // chunk cannot monopolize END_SERVER_TICK. State is server-session-owned and
    // is discarded on physical-epoch changes, retirement and shutdown.
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.PhysicalAuditState> preLightPhysicalAuditState = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.PhysicalAuditState> prePublishPhysicalAuditState = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongSet preLightPhysicalAuditComplete = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet prePublishPhysicalAuditComplete = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.LightSweepState> lightSweepState = new OceanCanvasPrimitiveLongObjectMap<>();
    // Compatibility names retained for the historical cooperative-proof gate; the
    // .34 implementation stores the actual cursor in lightSweepState.
    final OceanCanvasPrimitiveLongIntMap lightSweepColumnCursor = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongLongMap lightSweepChecksAccumulated = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongSet lightSweepSectionStatusDone = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.StrictSkyProofState> strictSkyProofState = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.SkyLightDiag> completedStrictSkyProof = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.FingerprintState> stage0FingerprintState = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.FingerprintState> stage1FingerprintState = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.FingerprintState> postProofFingerprintState = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongObjectMap<OceanCanvasSurfaceFlattener.FingerprintState> hardResetFingerprintState = new OceanCanvasPrimitiveLongObjectMap<>();
    final OceanCanvasPrimitiveLongSet hardResetFingerprintPending = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet stage1FingerprintVerified = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongIntMap heavyPhaseHeapDeferralStreak = new OceanCanvasPrimitiveLongIntMap();
    /** v253.125.25 per-chunk late-physics streak; successful certification clears it. */
    final OceanCanvasPrimitiveLongIntMap terrainInstabilityStreak = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasLightRetryLedger retryLedger = new OceanCanvasLightRetryLedger();
    /** Chunks with physical repair authority acquired during the active operation only. */
    final OceanCanvasPrimitiveLongSet postJobPhysicalRepairAuthority = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasLightRelightResidencyLedger relightResidencyLedger = new OceanCanvasLightRelightResidencyLedger();
    final OceanCanvasPersistedLightAuditSession persistedAuditSession = new OceanCanvasPersistedLightAuditSession();
}
