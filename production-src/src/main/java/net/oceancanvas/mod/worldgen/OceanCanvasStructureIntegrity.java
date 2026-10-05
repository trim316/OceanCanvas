package net.oceancanvas.mod.worldgen;

import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime accounting for Ocean Canvas structure correctness.
 *
 * <p>This deliberately treats missing capabilities as failures instead of hiding them.
 * A FORCE_ON request is not "successful" merely because some blocks were pasted: the
 * structure lifecycle must eventually prove candidate coverage, real starts/references,
 * and deterministic completion. The ledger is intentionally lightweight/in-memory in
 * v233; persisted health integration can consume the same snapshots without changing
 * placement code.</p>
 */
public final class OceanCanvasStructureIntegrity {
    public record Snapshot(String scope, OceanCanvasStructureKind kind, String operation,
            long expectedCandidates, long naturalStarts, long syntheticPlacements,
            long protectedExisting, long metadataRegistered, long metadataFailures,
            long placementFailures, long excludedCandidates, long invariantFailures, long unsupportedAlwaysRequests,
            long distributionApproximations, boolean complete) {
        public boolean passesStrictIntegrity() {
            return complete
                    && unsupportedAlwaysRequests == 0
                    && distributionApproximations == 0
                    && placementFailures == 0
                    && invariantFailures == 0
                    && metadataFailures == 0
                    && naturalStarts + syntheticPlacements + protectedExisting + excludedCandidates >= expectedCandidates;
        }
    }

    private static final class Mutable {
        final String scope;
        final OceanCanvasStructureKind kind;
        volatile String operation;
        final AtomicLong expectedCandidates = new AtomicLong();
        final AtomicLong naturalStarts = new AtomicLong();
        final AtomicLong syntheticPlacements = new AtomicLong();
        final AtomicLong protectedExisting = new AtomicLong();
        final AtomicLong metadataRegistered = new AtomicLong();
        final AtomicLong metadataFailures = new AtomicLong();
        final AtomicLong placementFailures = new AtomicLong();
        final AtomicLong excludedCandidates = new AtomicLong();
        final AtomicLong invariantFailures = new AtomicLong();
        final AtomicLong unsupportedAlwaysRequests = new AtomicLong();
        final AtomicLong distributionApproximations = new AtomicLong();
        volatile boolean complete;

        Mutable(String scope, OceanCanvasStructureKind kind, String operation) {
            this.scope = scope;
            this.kind = kind;
            this.operation = operation == null ? "unknown" : operation;
        }

        Snapshot snapshot() {
            return new Snapshot(scope, kind, operation,
                    expectedCandidates.get(), naturalStarts.get(), syntheticPlacements.get(),
                    protectedExisting.get(), metadataRegistered.get(), metadataFailures.get(),
                    placementFailures.get(), excludedCandidates.get(), invariantFailures.get(), unsupportedAlwaysRequests.get(),
                    distributionApproximations.get(), complete);
        }
    }

    private static final Map<String, Mutable> LEDGER = new ConcurrentHashMap<>();

    private OceanCanvasStructureIntegrity() {}

    private static String key(String scope, OceanCanvasStructureKind kind) {
        return (scope == null ? "unknown" : scope) + "|" + kind.id();
    }

    private static Mutable entry(String scope, OceanCanvasStructureKind kind, String operation) {
        return LEDGER.compute(key(scope, kind), (ignored, old) -> {
            if (old == null || old.complete) return new Mutable(scope == null ? "unknown" : scope, kind, operation);
            if (operation != null) old.operation = operation;
            return old;
        });
    }

    public static void begin(String scope, OceanCanvasStructureKind kind, String operation, long expectedCandidates) {
        Mutable m = new Mutable(scope == null ? "unknown" : scope, kind, operation);
        m.expectedCandidates.set(Math.max(0L, expectedCandidates));
        LEDGER.put(key(scope, kind), m);
    }

    public static void setExpectedCandidates(String scope, OceanCanvasStructureKind kind, long expectedCandidates) {
        entry(scope, kind, null).expectedCandidates.set(Math.max(0L, expectedCandidates));
    }

    public static void naturalStart(String scope, OceanCanvasStructureKind kind) {
        entry(scope, kind, null).naturalStarts.incrementAndGet();
    }

    public static void syntheticPlacement(String scope, OceanCanvasStructureKind kind, boolean metadataRegistered) {
        Mutable m = entry(scope, kind, null);
        m.syntheticPlacements.incrementAndGet();
        if (metadataRegistered) m.metadataRegistered.incrementAndGet();
        else m.metadataFailures.incrementAndGet();
    }

    public static void protectedExisting(String scope, OceanCanvasStructureKind kind) {
        entry(scope, kind, null).protectedExisting.incrementAndGet();
    }

    public static void excluded(String scope, OceanCanvasStructureKind kind) {
        entry(scope, kind, null).excludedCandidates.incrementAndGet();
    }

    public static void placementFailure(String scope, OceanCanvasStructureKind kind) {
        entry(scope, kind, null).placementFailures.incrementAndGet();
    }

    public static void invariantFailure(String scope, OceanCanvasStructureKind kind, String detail) {
        entry(scope, kind, null).invariantFailures.incrementAndGet();
        OceanCanvas.LOGGER.error("[OceanCanvas][StructureIntegrity:{}] {} invariant failure: {}", scope, kind.displayName(), detail);
    }

    public static void recordUnsupportedAlways(String scope, OceanCanvasStructureKind kind, String operation) {
        Mutable m = entry(scope, kind, operation);
        m.unsupportedAlwaysRequests.incrementAndGet();
        m.complete = true;
        OceanCanvas.LOGGER.error("[OceanCanvas][StructureIntegrity:{}] {}=Always is not yet backed by a strict forced-placement implementation during {}. This is a release blocker, not a silent no-op.",
                scope, kind.displayName(), operation);
    }


    public static void recordDistributionApproximation(String scope, OceanCanvasStructureKind kind, String operation) {
        Mutable m = entry(scope, kind, operation);
        m.distributionApproximations.incrementAndGet();
        OceanCanvas.LOGGER.error("[OceanCanvas][StructureIntegrity:{}] {}=Always is using an approximation rather than the active Minecraft structure-placement policy during {}. Release gate remains RED.",
                scope, kind.displayName(), operation);
    }

    public static Snapshot complete(String scope, OceanCanvasStructureKind kind) {
        Mutable m = entry(scope, kind, null);
        m.complete = true;
        Snapshot s = m.snapshot();
        if (s.passesStrictIntegrity()) {
            OceanCanvas.LOGGER.info("[OceanCanvas][StructureIntegrity:{}] {} PASS expected={} natural={} synthetic={} metadata={} protected={} excluded={}",
                    scope, kind.displayName(), s.expectedCandidates(), s.naturalStarts(), s.syntheticPlacements(),
                    s.metadataRegistered(), s.protectedExisting(), s.excludedCandidates());
        } else {
            OceanCanvas.LOGGER.error("[OceanCanvas][StructureIntegrity:{}] {} FAIL expected={} natural={} synthetic={} metadata={} metadataFailures={} placementFailures={} invariantFailures={} protected={} excluded={} unsupported={}",
                    scope, kind.displayName(), s.expectedCandidates(), s.naturalStarts(), s.syntheticPlacements(),
                    s.metadataRegistered(), s.metadataFailures(), s.placementFailures(), s.invariantFailures(), s.protectedExisting(),
                    s.excludedCandidates(), s.unsupportedAlwaysRequests() + s.distributionApproximations());
        }
        return s;
    }

    public static List<Snapshot> snapshots() {
        List<Snapshot> out = new ArrayList<>();
        for (Mutable m : LEDGER.values()) out.add(m.snapshot());
        out.sort(Comparator.comparing(Snapshot::scope).thenComparing(s -> s.kind().id()));
        return List.copyOf(out);
    }

    public static void clear() {
        LEDGER.clear();
    }
}
