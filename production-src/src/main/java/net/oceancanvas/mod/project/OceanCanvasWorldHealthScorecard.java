package net.oceancanvas.mod.project;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.diagnostic.OceanCanvasStallWatchdog;
import net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;

/**
 * v253.53 transparent stewardship scorecard. This deliberately exposes
 * independent component states instead of collapsing world health to one
 * opaque number. Everything here is read-only and derived from authoritative
 * services already owned by Ocean Canvas.
 */
public final class OceanCanvasWorldHealthScorecard {
    private OceanCanvasWorldHealthScorecard() {}

    public enum State { GOOD, BUSY, ATTENTION, UNVERIFIED }
    public record Component(String id, String label, State state, String detail) {}
    public record Scorecard(List<Component> components) {
        public long attentionCount() { return components.stream().filter(c -> c.state()==State.ATTENTION).count(); }
        public long unverifiedCount() { return components.stream().filter(c -> c.state()==State.UNVERIFIED).count(); }
    }

    public static Scorecard snapshot(ServerLevel world) {
        List<Component> out = new ArrayList<>();

        // Never launch a full terrain/seal reconciliation from periodic telemetry.
        // Deep Health is an explicit diagnostic and can be O(world) on a mature 20k
        // canvas. Reuse its last authoritative result when one exists; otherwise say
        // UNVERIFIED rather than pretending that no scan means no mismatches.
        var deep = OceanCanvasDeepHealthService.lastReport(world);
        if (deep == null) {
            var terrain = OceanCanvasTerrainStateData.get(world);
            long explicit = 0L;
            for (OceanCanvasTerrainStateData.TerrainState state : OceanCanvasTerrainStateData.TerrainState.values()) {
                if (state != OceanCanvasTerrainStateData.TerrainState.UNKNOWN) explicit += terrain.stateCount(state);
            }
            out.add(new Component("metadata", "Chunk metadata", State.UNVERIFIED,
                    "No explicit Deep Health scan this session; " + explicit + " explicit terrain-state entries tracked."));
        } else {
            out.add(new Component("metadata", "Chunk metadata",
                    deep.mismatches() > 0 ? State.ATTENTION : (deep.legacyUnverified() > 0 ? State.UNVERIFIED : State.GOOD),
                    deep.mismatches() + " mismatch(es), " + deep.legacyUnverified() + " legacy/unverified (last explicit scan)"));
        }

        var links = OceanCanvasAssetIntegrityService.scan(world, 1);
        out.add(new Component("links", "Workflow links", links.issues() > 0 ? State.ATTENTION : State.GOOD,
                links.issues() + " issue(s) across " + links.projects() + " project(s)"));

        int outstanding = OceanCanvasTerrainOperationActivity.outstandingPregenTargets();
        int pending = OceanCanvasTerrainOperationActivity.pendingRegenerationCount();
        OceanCanvasTerrainOperationView.Overlay job = OceanCanvasTerrainOperationView.overlaySnapshot();
        out.add(new Component("operations", "Operation lifecycle",
                job != null || outstanding > 0 || pending > 0 ? State.BUSY : State.GOOD,
                (job == null ? "idle" : job.kind()) + "; targets=" + outstanding + ", pending=" + pending));

        var watchdog = OceanCanvasStallWatchdog.matrixSnapshot();
        out.add(new Component("watchdog", "Server/watchdog",
                watchdog.state().equals("ATTENTION") ? State.ATTENTION : (watchdog.state().equals("IDLE") ? State.GOOD : State.GOOD),
                watchdog.detail()));

        // Physical truth cannot be inferred from metadata. We only call it GOOD
        // when the physical scanner has explicitly produced a current report.
        out.add(new Component("physical", "Physical canvas", State.UNVERIFIED,
                "Physical truth requires an explicit loaded-chunk Health scan; metadata alone cannot certify it."));

        var health = OceanCanvasHealthService.snapshot(world);
        out.add(new Component("coverage", "Canvas coverage",
                health.expectedChunks() > 0 && health.flattenedChunks() >= health.expectedChunks() ? State.GOOD : State.UNVERIFIED,
                health.flattenedChunks() + "/" + health.expectedChunks() + " flattened chunks recorded"));

        out.add(new Component("protection", "Protection/regions",
                health.definedRegions() == 0 ? State.UNVERIFIED : State.GOOD,
                health.protectedRegions() + "/" + health.definedRegions() + " defined region(s) protected"));

        return new Scorecard(List.copyOf(out));
    }
}
