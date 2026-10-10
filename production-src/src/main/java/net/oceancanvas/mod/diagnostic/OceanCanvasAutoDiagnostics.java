package net.oceancanvas.mod.diagnostic;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v226 zero-babysitting diagnostics.
 *
 * The player should only need to run Ocean Canvas normally, Save & Quit, and
 * upload logs/oceancanvas-auto-diagnostics-latest.zip. This recorder is
 * observation-only: it never changes chunk/ticket/controller state.
 */
public final class OceanCanvasAutoDiagnostics {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    // v253.125.31: the five-hour diagnostic produced nearly 2.8k heartbeat
    // snapshots. Hard stalls already have dedicated capture hooks; a 10s steady-state
    // heartbeat halves observer traffic while retaining fine-grained trend evidence.
    private static final long HEARTBEAT_NS = 10_000_000_000L;
    // v253.72.9: a persistent failure used to generate a 512 KiB thread dump every
    // 15 seconds and recompress up to 24 MiB of latest.log every minute. That made
    // diagnostics part of the workload under investigation. Preserve evidence, but
    // capture expensive snapshots at human-scale cadence and keep all I/O off-thread.
    private static final long THREAD_DUMP_COOLDOWN_NS = 300_000_000_000L;
    private static final long STALL_RECORD_COOLDOWN_NS = 60_000_000_000L;
    private static final long PROACTIVE_HEALTH_COOLDOWN_NS = 300_000_000_000L;
    private static final long BUNDLE_REFRESH_NS = 1_800_000_000_000L;
    private static final long MAX_LOG_BYTES = 24L * 1024L * 1024L;
    private static final AtomicLong LAST_HEARTBEAT_NS = new AtomicLong();
    private static final AtomicLong LAST_THREAD_DUMP_NS = new AtomicLong();
    private static final AtomicLong LAST_STALL_RECORD_NS = new AtomicLong();
    private static final AtomicLong LAST_PROACTIVE_HEALTH_NS = new AtomicLong();
    private static final AtomicLong LAST_BUNDLE_REFRESH_NS = new AtomicLong();
    // v228.5: periodic ZIP creation must never run on the integrated-server
    // thread. v228.4 wrote/compressed up to 24 MiB of latest.log synchronously
    // from END_SERVER_TICK. Even when a particular write is fast, that is an
    // unnecessary filesystem/compression stall source in the exact hot path we
    // are diagnosing. Capture the small world snapshot on-thread, then perform
    // all file I/O on one daemon worker; coalesce overlapping refreshes.
    private static final AtomicBoolean BUNDLE_WRITE_IN_FLIGHT = new AtomicBoolean();
    private static final AtomicBoolean BUNDLE_WRITE_PENDING = new AtomicBoolean();
    private static final AtomicBoolean FIRST_STALL_BUNDLE_TRIGGERED = new AtomicBoolean();
    private static volatile BundleCapture pendingBundle;
    private static final java.util.concurrent.ExecutorService BUNDLE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "OceanCanvas-DiagnosticsBundle");
                thread.setDaemon(true);
                return thread;
            });
    private record SessionContext(long generation, String sessionId, Path journal) { }
    private record BundleCapture(SessionContext session, String snapshot, String environment) { }
    private static final AtomicLong SESSION_GENERATION = new AtomicLong();
    private static volatile SessionContext currentSession;

    private OceanCanvasAutoDiagnostics() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> begin(server.overworld()));
        ServerTickEvents.END_SERVER_TICK.register(OceanCanvasAutoDiagnostics::heartbeat);
    }

    private static void begin(ServerLevel world) {
        try {
            long generation = SESSION_GENERATION.incrementAndGet();
            String sessionId = STAMP.format(Instant.now()) + "-" + generation + "-"
                    + java.util.UUID.randomUUID().toString().substring(0, 8);
            Path logs = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(logs);
            Path journal = logs.resolve("oceancanvas-auto-diagnostics-session-" + sessionId + ".txt");
            SessionContext session = new SessionContext(generation, sessionId, journal);
            Files.writeString(journal,
                    "Ocean Canvas automatic diagnostic session\n"
                    + "session=" + sessionId + "\n"
                    + "build=" + OceanCanvas.VERSION + "\n"
                    + "started=" + Instant.now() + "\n"
                    + "world=" + world.dimension() + "\n\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            currentSession = session;
            append(session, "SESSION_START", snapshot(world));
            LAST_HEARTBEAT_NS.set(System.nanoTime());
            LAST_THREAD_DUMP_NS.set(0L);
            LAST_STALL_RECORD_NS.set(0L);
            LAST_PROACTIVE_HEALTH_NS.set(0L);
            LAST_BUNDLE_REFRESH_NS.set(System.nanoTime());
            BUNDLE_WRITE_PENDING.set(false);
            FIRST_STALL_BUNDLE_TRIGGERED.set(false);
            pendingBundle = null;
            OceanCanvas.LOGGER.info("(Ocean Canvas) automatic diagnostics armed for session {}. Save & Quit will write logs/oceancanvas-auto-diagnostics-latest.zip.", sessionId);
        } catch (Throwable t) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) automatic diagnostics could not start: {}", t.toString());
        }
    }

    private static void heartbeat(MinecraftServer server) {
        if (net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.telemetrySnapshot() == null) return;
        long now = System.nanoTime();
        long last = LAST_HEARTBEAT_NS.get();
        if (last != 0L && now - last < HEARTBEAT_NS) return;
        if (!LAST_HEARTBEAT_NS.compareAndSet(last, now)) return;
        appendAsync("HEARTBEAT", compactStallSnapshot(server.overworld()));
        long bundleLast = LAST_BUNDLE_REFRESH_NS.get();
        if (bundleLast == 0L || now - bundleLast >= BUNDLE_REFRESH_NS) {
            if (LAST_BUNDLE_REFRESH_NS.compareAndSet(bundleLast, now)) {
                scheduleBundle(server.overworld());
            }
        }
    }

    public static void capturePregenStall(ServerLevel world, long stalledNs, String reason) {
        // v253.125.26: the .25 watchdog caught the server thread inside Java 25's
        // StringConcatFactory while this diagnostic was trying to explain a stall.
        // Keep the on-thread header bounded and use explicit builders so diagnostic
        // cold-linkage/giant concatenations cannot amplify the incident being measured.
        String boundedReason = reason == null ? "unspecified" : reason;
        if (boundedReason.length() > 768) boundedReason = boundedReason.substring(0, 768) + "...<truncated>";
        StringBuilder headerBuilder = new StringBuilder(896);
        headerBuilder.append("pregen-no-retirement=")
                .append(Math.max(0L, stalledNs / 1_000_000_000L))
                .append("s; ").append(boundedReason);
        final String header = headerBuilder.toString();
        long now = System.nanoTime();
        long lastRecord = LAST_STALL_RECORD_NS.get();
        if (lastRecord == 0L || now - lastRecord >= STALL_RECORD_COOLDOWN_NS) {
            if (LAST_STALL_RECORD_NS.compareAndSet(lastRecord, now)) {
                // Only a compact, allocation-bounded snapshot is captured on the server
                // thread. The verbose periodic snapshot remains available elsewhere.
                String compact = compactStallSnapshot(world);
                appendAsyncComputed("PREGEN_STALL", () -> {
                    StringBuilder out = new StringBuilder(header.length() + compact.length() + 2);
                    return out.append(header).append('\n').append(compact).toString();
                });
                // Produce one immediately useful crash/bug-report bundle when a stall
                // first becomes real, then fall back to the 30-minute refresh cadence.
                if (FIRST_STALL_BUNDLE_TRIGGERED.compareAndSet(false, true)) {
                    LAST_BUNDLE_REFRESH_NS.set(now);
                    scheduleBundle(world);
                }
            }
        }
        long last = LAST_THREAD_DUMP_NS.get();
        if (last == 0L || now - last >= THREAD_DUMP_COOLDOWN_NS) {
            if (LAST_THREAD_DUMP_NS.compareAndSet(last, now)) {
                // ThreadMXBean.dumpAllThreads can itself be non-trivial. Capture it on
                // the daemon worker instead of pausing END_SERVER_TICK.
                appendAsyncComputed("THREAD_DUMP", () -> {
                    String dump = threadDump();
                    StringBuilder out = new StringBuilder(header.length() + dump.length() + 2);
                    return out.append(header).append('\n').append(dump).toString();
                });
            }
        }
    }

    /**
     * v253.73.11 proactive evidence capture. Called before a Forever World reaches
     * a hard stall, when the supervisor predicts that recovery pressure can starve
     * the never-submitted terrain frontier. Expensive ZIP work remains off-thread
     * and is rate-limited so diagnostics cannot become part of the workload.
     */
    public static void captureProactiveHealth(ServerLevel world, String reason) {
        if (world == null) return;
        long now = System.nanoTime();
        long last = LAST_PROACTIVE_HEALTH_NS.get();
        if (last != 0L && now - last < PROACTIVE_HEALTH_COOLDOWN_NS) return;
        if (!LAST_PROACTIVE_HEALTH_NS.compareAndSet(last, now)) return;
        String compact = compactStallSnapshot(world);
        final String boundedReason = reason == null ? "unspecified" : (reason.length() > 1024 ? reason.substring(0, 1024) + "...<truncated>" : reason);
        appendAsyncComputed("PROACTIVE_HEALTH", () -> new StringBuilder(boundedReason.length() + compact.length() + 2)
                .append(boundedReason).append('\n').append(compact).toString());
        scheduleBundle(world);
    }

    public static void captureFailure(ServerLevel world, String reason, Throwable failure) {
        String compact = compactStallSnapshot(world);
        final String boundedReason = reason == null ? "unspecified" : (reason.length() > 1024 ? reason.substring(0, 1024) + "...<truncated>" : reason);
        final String failureText = failure == null ? "no-failure-object" : failure.toString();
        appendAsyncComputed("FAILURE", () -> {
            String dump = threadDump();
            return new StringBuilder(boundedReason.length() + failureText.length() + compact.length() + dump.length() + 8)
                    .append(boundedReason).append('\n').append(failureText).append('\n').append(compact).append('\n').append(dump).toString();
        });
    }

    public static void captureHardTickStall(String dump) {
        appendAsync("HARD_TICK_STALL", dump);
    }

    public static synchronized void finishSession(ServerLevel world) {
        appendAsync("SESSION_STOP", compactStallSnapshot(world));
        // v253.72.7: never compress/copy the diagnostic bundle synchronously from
        // SERVER_STOPPING. The bundle worker is daemonized and receives an immutable
        // snapshot, so graceful process exit always wins over diagnostics.
        scheduleBundle(world);
    }

    private static void scheduleBundle(ServerLevel world) {
        if (world == null) return;
        SessionContext session = currentSession;
        if (session == null) return;
        BundleCapture capture = new BundleCapture(session, compactStallSnapshot(world), environment(world));
        if (!BUNDLE_WRITE_IN_FLIGHT.compareAndSet(false, true)) {
            pendingBundle = capture;
            BUNDLE_WRITE_PENDING.set(true);
            return;
        }
        try {
            BUNDLE_EXECUTOR.execute(() -> drainBundleWrites(capture));
        } catch (Throwable t) {
            BUNDLE_WRITE_IN_FLIGHT.set(false);
            OceanCanvas.LOGGER.warn("(Ocean Canvas) automatic diagnostic bundle scheduling failed: {}", t.toString());
        }
    }

    private static void drainBundleWrites(BundleCapture first) {
        BundleCapture capture = first;
        try {
            for (;;) {
                writeBundleCaptured(capture);
                if (!BUNDLE_WRITE_PENDING.getAndSet(false)) break;
                BundleCapture next = pendingBundle;
                pendingBundle = null;
                if (next == null) break;
                capture = next;
            }
        } finally {
            BUNDLE_WRITE_IN_FLIGHT.set(false);
            if (BUNDLE_WRITE_PENDING.getAndSet(false)) {
                BundleCapture next = pendingBundle;
                pendingBundle = null;
                if (next != null && BUNDLE_WRITE_IN_FLIGHT.compareAndSet(false, true)) {
                    try {
                        BUNDLE_EXECUTOR.execute(() -> drainBundleWrites(next));
                    } catch (Throwable t) {
                        BUNDLE_WRITE_IN_FLIGHT.set(false);
                        OceanCanvas.LOGGER.debug("(Ocean Canvas) diagnostic bundle reschedule rejected: {}", t.toString());
                    }
                }
            }
        }
    }

    private static void writeBundleCaptured(BundleCapture capture) {
        SessionContext session = capture.session();
        try {
            Path logs = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(logs);
            Path latest = logs.resolve("oceancanvas-auto-diagnostics-latest.zip");
            Path temp = logs.resolve("oceancanvas-auto-diagnostics-" + session.sessionId() + ".zip.tmp");
            Files.deleteIfExists(temp);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                text(zip, "README.txt",
                        "Ocean Canvas automatic runtime diagnostics\n"
                        + "Session: " + session.sessionId() + "\n"
                        + "Build: " + OceanCanvas.VERSION + "\n\n"
                        + "Upload this single ZIP with a bug report. It contains no Minecraft region/chunk terrain or player data.\n");
                text(zip, "final-snapshot.txt", capture.snapshot());
                if (Files.isRegularFile(session.journal())) file(zip, "auto-session.txt", session.journal(), 8L * 1024L * 1024L, false);
                Path latestLog = logs.resolve("latest.log");
                if (Files.isRegularFile(latestLog)) file(zip, "latest.log", latestLog, MAX_LOG_BYTES, true);
                Path config = FabricLoader.getInstance().getConfigDir().resolve("oceancanvas.properties");
                if (Files.isRegularFile(config)) file(zip, "oceancanvas.properties", config, 512L * 1024L, false);
                text(zip, "environment.txt", capture.environment());
            }
            try {
                Files.move(temp, latest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(temp, latest, StandardCopyOption.REPLACE_EXISTING);
            }
            OceanCanvas.LOGGER.info("(Ocean Canvas) automatic diagnostic bundle ready for session {}: {}", session.sessionId(), latest);
        } catch (Throwable t) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) automatic diagnostic bundle failed for session {}: {}", session.sessionId(), t.toString());
        }
    }

    private static String environment(ServerLevel world) {
        var c = OceanCanvasConfig.get();
        String minecraft = FabricLoader.getInstance().getModContainer("minecraft")
                .map(v -> v.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        String c2me = FabricLoader.getInstance().getModContainer("c2me")
                .map(v -> v.getMetadata().getVersion().getFriendlyString()).orElse("not-loaded");
        return "build=" + OceanCanvas.VERSION + "\n"
                + "minecraft=" + minecraft + "\n"
                + "java=" + System.getProperty("java.version") + "\n"
                + "c2me=" + c2me + "\n"
                + "dimension=" + world.dimension() + "\n"
                + "pregenEnabled=" + c.pregenEnabled() + "\n"
                + "configuredChunksPerTick=" + c.pregenChunksPerTick() + "\n"
                + "viewDistance=" + world.getServer().getPlayerList().getViewDistance() + "\n"
                + "simulationDistance=" + world.getServer().getPlayerList().getSimulationDistance() + "\n";
    }

    /** Minimal stall snapshot: no String.format, no environment scan, no giant object rendering. */
    private static String compactStallSnapshot(ServerLevel world) {
        long started = System.nanoTime();
        try {
            var t = net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.telemetrySnapshot();
            var q = OceanCanvasTerrainRuntimeDiagnostics.queueSnapshot(world);
            var tickets = OceanCanvasTerrainRuntimeDiagnostics.pregenTicketSnapshot();
            var lighting = OceanCanvasTerrainRuntimeDiagnostics.lightingSnapshot();
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long max = Runtime.getRuntime().maxMemory();
            StringBuilder out = new StringBuilder(768);
            out.append("build=").append(OceanCanvas.VERSION)
                    .append(" time=").append(Instant.now())
                    .append(" queue=").append(q.shortText())
                    .append(" targetFutures=").append(tickets.targetFutures())
                    .append(" supportFutures=").append(tickets.supportFutures())
                    .append(" fullDemand=").append(tickets.fullDemandRequests()).append('/')
                    .append(tickets.fullDemandCompletions()).append('/')
                    .append(tickets.fullDemandFailures()).append('/')
                    .append(tickets.fullDemandCancellations())
                    .append(" lightPending=").append(lighting.pendingSync())
                    .append(" lightActive=").append(lighting.activeSync())
                    .append(" lightTickets=").append(lighting.residencyTickets())
                    .append(" heapBytes=").append(used).append('/').append(max);
            if (t != null) {
                out.append(" submitted=").append(t.submittedChunks()).append('/').append(t.totalChunks())
                        .append(" outstanding=").append(t.outstandingChunks())
                        .append(" adaptiveRate=").append(t.adaptiveRatePerTick());
            }
            out.append(" captureMicros=").append((System.nanoTime() - started) / 1_000L);
            return out.toString();
        } catch (Throwable t) {
            StringBuilder out = new StringBuilder(160);
            return out.append("compact-snapshot-failed=").append(t.getClass().getSimpleName())
                    .append(" captureMicros=").append((System.nanoTime() - started) / 1_000L).toString();
        }
    }

    private static String snapshot(ServerLevel world) {
        try {
            var t = net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.telemetrySnapshot();
            var perf = net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.performanceSnapshot();
            var resourceBudget = net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.resourceBudgetSnapshot();
            var q = OceanCanvasTerrainRuntimeDiagnostics.queueSnapshot(world);
            var tickets = OceanCanvasTerrainRuntimeDiagnostics.pregenTicketSnapshot();
            var lighting = OceanCanvasTerrainRuntimeDiagnostics.lightingSnapshot();
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long max = Runtime.getRuntime().maxMemory();
            return "time=" + Instant.now() + "\n"
                    + "status=" + net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.status() + "\n"
                    + "telemetry=" + (t == null ? "idle" :
                        "kind=" + t.kind() + ", submitted=" + t.submittedChunks() + "/" + t.totalChunks()
                        + ", adaptiveRate=" + t.adaptiveRatePerTick() + ", outstanding=" + t.outstandingChunks()
                        + ", heap=" + String.format(Locale.ROOT, "%.3f", t.heapUseFraction())
                        + ", tickMsEma=" + String.format(Locale.ROOT, "%.2f", t.tickMsEma())) + "\n"
                    + "queue=" + q.shortText() + "\n"
                    + "tickets=self:" + tickets.selfActive() + "(+" + tickets.selfInstalls() + "/-" + tickets.selfReleases() + ")"
                    + ", lane:" + tickets.carveLaneActive()
                    + ", leases:" + tickets.processingLeaseActive() + "(+" + tickets.processingLeaseInstalls() + "/-" + tickets.processingLeaseReleases() + ")"
                    + ", finalDrain:" + tickets.finalDrainActive() + "(+" + tickets.finalDrainInstalls() + "/-" + tickets.finalDrainReleases() + ")"
                    + ", targetFutures:" + tickets.targetFutures() + ", supportFutures:" + tickets.supportFutures()
                    + ", supportDone:" + tickets.supportFuturesDone()
                     + ", fullDemand:req=" + tickets.fullDemandRequests() + "/done=" + tickets.fullDemandCompletions() + "/fail=" + tickets.fullDemandFailures() + "/cancel=" + tickets.fullDemandCancellations()
                    + ", loadRescue:" + tickets.loadRescueActive() + "(+" + tickets.loadRescueRequests() + "/ok=" + tickets.loadRescueSuccesses() + ")"
                    + ", audit:pending=" + tickets.auditPending() + "/repeatPending=" + tickets.auditRepeatPending() + "/reject=" + tickets.auditRejections() + "/repair=" + tickets.auditRepairs() + "/repeat=" + tickets.auditRepeatFailures() + "\n"
                    + "resourceBudget=state:" + resourceBudget.state() + ", limiter:" + resourceBudget.limiter()
                    + ", cap:" + resourceBudget.admissionCap() + "/" + resourceBudget.requestedRate()
                    + ", cpuMs:" + String.format(Locale.ROOT, "%.2f", resourceBudget.cpuWorkMs())
                    + ", heap:" + String.format(Locale.ROOT, "%.3f", resourceBudget.heapUseFraction())
                    + ", tickets:" + resourceBudget.transientTickets() + "/" + resourceBudget.ticketHardLimit()
                    + ", reason:" + resourceBudget.reason() + "\n"
                    + "performance=" + perf + "\n"
                    + "lighting=pending:" + lighting.pendingSync()
                    + ", active:" + lighting.activeSync()
                    + ", persistent:" + lighting.persistentBackoff()
                    + ", residencyTickets:" + lighting.residencyTickets()
                    + ", finalPublishes:" + lighting.finalPublishes()
                    + ", lightOnlyRecoveryActive:" + lighting.lightOnlyRecoveryActive()
                    + ", lightOnlyRecoveryTracked:" + lighting.lightOnlyRecoveryTracked()
                    + ", physicalRecoveryActive:" + lighting.physicalRecoveryActive()
                    + ", physicalRecoveryTracked:" + lighting.physicalRecoveryTracked()
                    + ", newTerrainRetirements:" + lighting.newTerrainRetirements() + "\n"
                    + "heapBytes=" + used + "/" + max + "\n";
        } catch (Throwable t) {
            return "snapshot-failed=" + t + "\n";
        }
    }

    private static String threadDump() {
        StringBuilder out = new StringBuilder(128 * 1024);
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] infos = bean.dumpAllThreads(true, true);
            for (ThreadInfo info : infos) {
                if (info == null) continue;
                out.append('"').append(info.getThreadName()).append("\" Id=")
                        .append(info.getThreadId()).append(' ').append(info.getThreadState()).append('\n');
                for (StackTraceElement frame : info.getStackTrace()) out.append("\tat ").append(frame).append('\n');
                out.append('\n');
                if (out.length() >= 512 * 1024) {
                    out.append("THREAD DUMP TRUNCATED AT 512 KiB\n");
                    break;
                }
            }
        } catch (Throwable t) {
            out.append("thread-dump-failed=").append(t).append('\n');
        }
        return out.toString();
    }

    private static void appendAsync(String type, String value) {
        SessionContext session = currentSession;
        if (session == null) return;
        executeDiagnosticIo(() -> append(session, type, value));
    }

    private static void appendAsyncComputed(String type, java.util.function.Supplier<String> value) {
        SessionContext session = currentSession;
        if (session == null) return;
        executeDiagnosticIo(() -> append(session, type, value.get()));
    }

    private static void executeDiagnosticIo(Runnable task) {
        try {
            BUNDLE_EXECUTOR.execute(task);
        } catch (Throwable t) {
            OceanCanvas.LOGGER.debug("(Ocean Canvas) automatic diagnostic worker rejected task: {}", t.toString());
        }
    }

    private static void append(SessionContext session, String type, String value) {
        if (session == null) return;
        try (BufferedWriter w = Files.newBufferedWriter(session.journal(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            w.write("\n===== " + type + " " + Instant.now() + " =====\n");
            w.write(value == null ? "" : value);
            if (value == null || !value.endsWith("\n")) w.newLine();
        } catch (IOException e) {
            OceanCanvas.LOGGER.debug("(Ocean Canvas) auto diagnostic append failed for session {}: {}", session.sessionId(), e.toString());
        }
    }

    private static void text(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void file(ZipOutputStream zip, String name, Path file, long cap, boolean tail) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        long size = Files.size(file);
        long start = tail && size > cap ? size - cap : 0L;
        try (var in = Files.newInputStream(file)) {
            long skipped = 0L;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0L) break;
                skipped += n;
            }
            byte[] buf = new byte[8192];
            long left = Math.min(cap, size - start);
            while (left > 0L) {
                int n = in.read(buf, 0, (int)Math.min(buf.length, left));
                if (n < 0) break;
                zip.write(buf, 0, n);
                left -= n;
            }
        }
        zip.closeEntry();
    }
}
