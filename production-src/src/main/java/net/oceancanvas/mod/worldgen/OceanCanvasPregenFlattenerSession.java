package net.oceancanvas.mod.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concrete-server-owned transient Pregen/flattener state.
 *
 * <p>Policy remains in {@link OceanCanvasSurfaceFlattener}; this object only owns mutable
 * job/session identity and bounded ticket/future ledgers so a second integrated server in the
 * same JVM cannot inherit stale ownership from the previous world.</p>
 */
final class OceanCanvasPregenFlattenerSession implements AutoCloseable {
    final Deque<LevelChunk> PENDING_CHUNKS = new ArrayDeque<>();
    final Set<ChunkPos> QUEUED_CHUNK_POSITIONS = new HashSet<>();
    final OceanCanvasPrimitiveLongSet FORCE_REPROCESS_CHUNKS = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet FORCE_LOADED_CHUNK_KEYS = new OceanCanvasPrimitiveLongSet();
    int startupSweepTicksRemaining;
    final OceanCanvasPrimitiveLongSet PREGEN_TARGET_CHUNKS = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet PREGEN_CRASH_RECOVERY_TARGETS = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE = new OceanCanvasPrimitiveLongSet();
    // v253.125.52: session-local proof that a PHYSICAL_AWARE recovery chunk has
    // completed the exhaustive pre-light physical audit. It may still retain
    // physical-repair permission for fail-closed late-drift handling, but it no
    // longer consumes the PHYSICAL recovery admission window while strict lighting
    // proof settles. This set is intentionally not persisted; a restart re-audits.
    final OceanCanvasPrimitiveLongSet PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet PREGEN_CRASH_RECOVERY_PHYSICAL_TARGETS = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongSet PREGEN_QUEUED_CHUNKS = new OceanCanvasPrimitiveLongSet();
    final AtomicLong PREGEN_NEW_TERRAIN_RETIREMENTS = new AtomicLong();
    final AtomicLong PREGEN_JOB_FORWARD_RETIREMENTS = new AtomicLong();
    final OceanCanvasPrimitiveLongLongMap PREGEN_TARGET_FIRST_REQUEST_MS = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongLongMap PREGEN_TARGET_LOAD_RESCUE_LAST_MS = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongSet PREGEN_TARGET_LOAD_RESCUED = new OceanCanvasPrimitiveLongSet();
    final AtomicLong PREGEN_TARGET_LOAD_RESCUE_REQUESTS = new AtomicLong();
    final AtomicLong PREGEN_TARGET_LOAD_RESCUE_SUCCESSES = new AtomicLong();
    final OceanCanvasPrimitiveLongIntMap PREGEN_AUDIT_REJECTION_COUNTS = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongIntMap PREGEN_POST_FLATTEN_AUDIT_FAILURE_COUNTS = new OceanCanvasPrimitiveLongIntMap();
    final AtomicLong PREGEN_AUDIT_REJECTIONS = new AtomicLong();
    final AtomicLong PREGEN_AUDIT_REPAIRS = new AtomicLong();
    final AtomicLong PREGEN_AUDIT_REPEAT_FAILURES = new AtomicLong();
    final OceanCanvasPrimitiveLongObjectMap<CompletableFuture<?>> PREGEN_TARGET_FUTURES = new OceanCanvasPrimitiveLongObjectMap<>();
    final AtomicLong PREGEN_FULL_DEMAND_REQUESTS = new AtomicLong();
    final AtomicLong PREGEN_FULL_DEMAND_COMPLETIONS = new AtomicLong();
    final AtomicLong PREGEN_FULL_DEMAND_FAILURES = new AtomicLong();
    final AtomicLong PREGEN_FULL_DEMAND_CANCELLATIONS = new AtomicLong();
    final OceanCanvasPrimitiveLongLongMap PREGEN_FULL_DEMAND_LAST_MS = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongObjectMap<CompletableFuture<?>> PREGEN_SUPPORT_FUTURES = new OceanCanvasPrimitiveLongObjectMap<>();
    long PREGEN_RECOVERY_CURSOR;
    final OceanCanvasPregenFinalDrainTicketLedger PREGEN_FINAL_DRAIN_LEDGER = new OceanCanvasPregenFinalDrainTicketLedger();
    final OceanCanvasPregenProcessingLeaseLedger PREGEN_PROCESSING_LEASE_LEDGER = new OceanCanvasPregenProcessingLeaseLedger();
    final OceanCanvasForceLoadTicketLedger OCEANCANVAS_FORCE_LOAD_LEDGER = new OceanCanvasForceLoadTicketLedger();
    final OceanCanvasPregenSelfTicketLedger PREGEN_SELF_TICKET_LEDGER = new OceanCanvasPregenSelfTicketLedger();
    final OceanCanvasPrimitiveLongLongMap PREGEN_SUPPORT_LAST_REQUEST_MS = new OceanCanvasPrimitiveLongLongMap();

    volatile boolean LAST_SERVER_STOP_TICKET_DEACTIVATION_SUCCEEDED = true;
    volatile int LAST_SERVER_STOP_TRACKED_TRANSIENT_TICKETS;
    volatile ServerLevel LAST_SERVER_STOP_DEACTIVATED_WORLD;

    final ExecutorService PREGEN_FULL_DEMAND_EXECUTOR;

    OceanCanvasPregenFlattenerSession(int fullDemandThreads, int startupSweepTicks) {
        startupSweepTicksRemaining = startupSweepTicks;
        PREGEN_FULL_DEMAND_EXECUTOR = Executors.newFixedThreadPool(fullDemandThreads, runnable -> {
            Thread thread = new Thread(runnable, "OceanCanvas-PregenFullDemand");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public void close() {
        PREGEN_FULL_DEMAND_EXECUTOR.shutdownNow();
        for (CompletableFuture<?> future : PREGEN_TARGET_FUTURES.valuesSnapshot()) if (future != null) future.cancel(false);
        for (CompletableFuture<?> future : PREGEN_SUPPORT_FUTURES.valuesSnapshot()) if (future != null) future.cancel(false);
        PREGEN_TARGET_FUTURES.clear();
        PREGEN_SUPPORT_FUTURES.clear();
    }
}
