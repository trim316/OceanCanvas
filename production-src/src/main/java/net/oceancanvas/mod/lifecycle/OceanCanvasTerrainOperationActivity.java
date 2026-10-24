package net.oceancanvas.mod.lifecycle;

import java.util.Objects;

/**
 * Dependency-light read-only view of terrain-operation activity.
 *
 * <p>The concrete Pregen, Restore, Undo and terrain-regeneration owners install
 * one provider from the composition root. Consumers that only need admission /
 * busy-state information must depend on this boundary rather than importing
 * those implementation packages back into each other.</p>
 */
public final class OceanCanvasTerrainOperationActivity {
    public interface Provider {
        boolean pregenRunning();
        boolean restoreRunning();
        boolean undoRunning();
        int outstandingPregenTargets();
        int pendingRegenerationCount();
    }

    private static final Provider IDLE = new Provider() {
        @Override public boolean pregenRunning() { return false; }
        @Override public boolean restoreRunning() { return false; }
        @Override public boolean undoRunning() { return false; }
        @Override public int outstandingPregenTargets() { return 0; }
        @Override public int pendingRegenerationCount() { return 0; }
    };

    private static volatile Provider provider = IDLE;

    private OceanCanvasTerrainOperationActivity() {}

    public static void install(Provider next) {
        provider = Objects.requireNonNull(next, "terrain operation activity provider");
    }

    public static boolean pregenRunning() { return provider.pregenRunning(); }
    public static boolean restoreRunning() { return provider.restoreRunning(); }
    public static boolean undoRunning() { return provider.undoRunning(); }
    public static int outstandingPregenTargets() { return provider.outstandingPregenTargets(); }
    public static int pendingRegenerationCount() { return provider.pendingRegenerationCount(); }

    /** Destructive controller admission guard used by previews, Restore and Pregen. */
    public static boolean pregenOrRestoreRunning() {
        Provider p = provider;
        return p.pregenRunning() || p.restoreRunning();
    }

    /**
     * Conservative queue drain condition. This intentionally includes work that
     * can outlive the top-level controller while owned targets/regeneration drain.
     */
    public static boolean queuedTerrainWorkInFlight() {
        Provider p = provider;
        return p.pregenRunning() || p.restoreRunning() || p.undoRunning()
                || p.outstandingPregenTargets() > 0 || p.pendingRegenerationCount() > 0;
    }
}
