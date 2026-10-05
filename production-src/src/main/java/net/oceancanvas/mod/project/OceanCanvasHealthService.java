package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;

/** Read-only health snapshot. Deep verification/repair can later plug into this same contract. */
public final class OceanCanvasHealthService {
    private OceanCanvasHealthService() {}

    public enum State { GOOD, BUSY, ATTENTION }
    public record Snapshot(State state, long flattenedChunks, long expectedChunks, int definedRegions,
                           int protectedRegions, int outstandingPregen, int pendingRegeneration,
                           String activeJob, String detail) { }

    public static Snapshot snapshot(ServerLevel world) {
        OceanCanvasConfig config = OceanCanvasConfig.get();
        OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
        var zones = OceanCanvasPlayerZones.get(world).all();
        long side = (long)Math.ceil(config.canvasSize() / 16.0D);
        long expected = side * side;
        int outstanding = OceanCanvasTerrainOperationActivity.outstandingPregenTargets();
        int pending = OceanCanvasTerrainOperationActivity.pendingRegenerationCount();
        OceanCanvasTerrainOperationView.Overlay job = OceanCanvasTerrainOperationView.overlaySnapshot();
        State state = job != null || outstanding > 0 || pending > 0 ? State.BUSY : State.GOOD;
        String detail = state == State.GOOD
                ? "No active Ocean Canvas work is waiting in the known queues."
                : "Work is still in flight; this is not by itself an integrity failure.";
        return new Snapshot(state, protectedData.flattenedChunkCount(), expected, zones.size(),
                (int)zones.stream().filter(OceanCanvasPlayerZones.Zone::protectedNow).count(),
                outstanding, pending, job == null ? "none" : job.kind(), detail);
    }
}
