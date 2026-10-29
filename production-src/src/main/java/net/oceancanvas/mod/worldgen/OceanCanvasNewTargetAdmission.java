package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;
/** Admission only: existing targets never surrender ownership under pressure. */
final class OceanCanvasNewTargetAdmission {
    static boolean allowed(boolean existing, BooleanSupplier pressure) {
        return existing || !pressure.getAsBoolean();
    }
}
