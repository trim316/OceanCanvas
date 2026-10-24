package net.oceancanvas.mod.lifecycle;

import java.util.Objects;

/**
 * Dependency-light read-only performance/telemetry view for terrain operations.
 *
 * <p>The concrete Pregen controller remains the sole owner of adaptive-rate,
 * progress, resource-budget and ETA state. The composition root adapts those
 * records into this neutral model so diagnostics can observe the controller
 * without importing or invoking {@code PregenManager} directly.</p>
 *
 * <p>This boundary is deliberately read-only: it cannot create, cancel, advance,
 * retire or otherwise mutate terrain work. Its inactive defaults are stable and
 * allow diagnostics to fail closed if no provider has been installed.</p>
 */
public final class OceanCanvasTerrainOperationPerformance {
    public record Telemetry(String kind, long submittedChunks, long totalChunks,
                            int adaptiveRatePerTick, int outstandingChunks,
                            double heapUseFraction, double tickMsEma) {
        public Telemetry { kind = safe(kind); }
    }

    public record Performance(String kind, String profile, String phase, String reason,
                              long handled, long total, long skipped, long settled,
                              int rate, int rateCap, int outstanding, int queued,
                              int sharedQueue, int finalDrainTickets,
                              double tickWorkMs, double tickIntervalMs, double heapFraction,
                              long heapUsedMiB, long heapMaxMiB, double chunksPerSecond,
                              long elapsedSeconds, long noProgressSeconds,
                              long etaLow, long etaHigh, String etaQuality) {
        public Performance {
            kind = safe(kind); profile = safe(profile); phase = safe(phase);
            reason = safe(reason); etaQuality = safe(etaQuality);
        }
        public boolean active() { return !kind.isBlank(); }
        public static Performance idle() {
            return new Performance("", "", "IDLE", "No active Pregen job",
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    -1, -1, 0, 0, 0, 0, 0, 0, -1, -1, "UNAVAILABLE");
        }
    }

    public record ResourceBudget(String state, String limiter,
                                 int requestedRate, int admissionCap,
                                 double cpuWorkMs, double cpuSoftBudgetMs, double cpuHardBudgetMs,
                                 double heapUseFraction, double heapSoftLimit, double heapHardLimit,
                                 int transientTickets, int ticketSoftLimit, int ticketHardLimit,
                                 String reason) {
        public ResourceBudget {
            state = safe(state); limiter = safe(limiter); reason = safe(reason);
        }
        public static ResourceBudget idle() {
            return new ResourceBudget("CLEAR", "NONE", 0, 0,
                    0.0D, 45.0D, 55.0D, 0.0D, 0.76D, 0.82D,
                    0, 1, 1, "No active Pregen resource budget");
        }
    }

    public interface Provider {
        Telemetry telemetrySnapshot();
        Performance performanceSnapshot();
        ResourceBudget resourceBudgetSnapshot();
        String status();
    }

    private static final Provider IDLE = new Provider() {
        @Override public Telemetry telemetrySnapshot() { return null; }
        @Override public Performance performanceSnapshot() { return Performance.idle(); }
        @Override public ResourceBudget resourceBudgetSnapshot() { return ResourceBudget.idle(); }
        @Override public String status() { return "No pregen/rewipe/restore/expand job is running."; }
    };

    private static volatile Provider provider = IDLE;

    private OceanCanvasTerrainOperationPerformance() { }

    public static void install(Provider next) {
        provider = Objects.requireNonNull(next, "terrain operation performance provider");
    }

    public static Telemetry telemetrySnapshot() { return provider.telemetrySnapshot(); }
    public static Performance performanceSnapshot() {
        Performance snapshot = provider.performanceSnapshot();
        return snapshot == null ? Performance.idle() : snapshot;
    }
    public static ResourceBudget resourceBudgetSnapshot() {
        ResourceBudget snapshot = provider.resourceBudgetSnapshot();
        return snapshot == null ? ResourceBudget.idle() : snapshot;
    }
    public static String status() {
        String value = provider.status();
        return value == null ? "" : value;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
