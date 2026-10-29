package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;
/** Scheduling only: no terrain, target, ticket, or proof ownership is changed. */
final class OceanCanvasNewCarveAdmission {
    static boolean allowed(boolean ownedPregen, boolean partialCarve, boolean visible, BooleanSupplier pressure) {
        return !ownedPregen || partialCarve || visible || !pressure.getAsBoolean();
    }
}
