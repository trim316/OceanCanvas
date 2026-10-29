package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;
/** Admit only a NEW initial sweep; partial sweeps and visible repairs keep progressing. */
final class OceanCanvasInitialLightSweepAdmission {
    static boolean allowed(boolean visible, boolean alreadyStarted, BooleanSupplier pressure) {
        return visible || alreadyStarted || !pressure.getAsBoolean();
    }
}
