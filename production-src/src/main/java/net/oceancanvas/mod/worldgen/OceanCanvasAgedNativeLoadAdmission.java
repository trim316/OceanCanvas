package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;
/** Server-thread admission observation only. No tickets or completion debt are owned here. */
final class OceanCanvasAgedNativeLoadAdmission {
    private long observedTick = Long.MIN_VALUE;
    private boolean held;
    boolean hold(long tick, int targets, int queued, BooleanSupplier observeAgedLoad) {
        if (targets < 0 || queued < 0 || queued > targets) return true;
        if (targets == queued) { observedTick = Long.MIN_VALUE; held = false; return false; }
        // At most one primitive target-age scan per server tick. Membership changes
        // can postpone the observation only until the next tick; zero loading clears immediately.
        if (observedTick != tick) { held = observeAgedLoad.getAsBoolean(); observedTick = tick; }
        return held;
    }
    static boolean agedOrInvalid(long now, long first, long absent, long minimumAge) {
        return first == absent || now < first || now - first >= minimumAge;
    }
}
