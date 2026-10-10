package net.oceancanvas.mod.compat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Optional-compatibility boundary for terrain replacement.
 *
 * <p>Core terrain code only calls {@link #publish}. Compatibility integrations
 * register listeners at initialization time. Listener failures are contained:
 * an optional integration is never allowed to make Pregen/Rewipe/Restore fail
 * or prevent Ocean Canvas from loading.</p>
 */
public final class OceanCanvasTerrainChangeBus {
    @FunctionalInterface
    public interface Listener {
        void onTerrainChanged(OceanCanvasTerrainChange change) throws Exception;
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private OceanCanvasTerrainChangeBus() {}

    public static void register(Listener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void publish(OceanCanvasTerrainChange change) {
        if (change == null) return;
        for (Listener listener : LISTENERS) {
            try {
                listener.onTerrainChanged(change);
            } catch (Throwable t) {
                OceanCanvas.LOGGER.warn("(Ocean Canvas) Optional terrain-change listener failed for chunk {},{} ({}); continuing without that notification: {}",
                        change.chunkPos().x(), change.chunkPos().z(), change.kind(), t.toString());
                net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.record("compat.terrain-change",
                        "listener failed for chunk " + change.chunkPos().x() + "," + change.chunkPos().z() + " kind=" + change.kind(), t);
            }
        }
    }
}
