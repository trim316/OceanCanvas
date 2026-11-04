package net.oceancanvas.mod.worldgen;

import java.util.function.Supplier;

/** Missing context never supplies proof credit; repair callers may request existing residency. */
final class OceanCanvasLightProofContextAdmission {
    static <T> T observe(Supplier<T> resident, boolean repairAllowed, Runnable requestResidency) {
        T context = resident.get();
        if (context == null && repairAllowed) requestResidency.run();
        // Ticket admission is asynchronous, not evidence that neighbors are resident.
        return context;
    }
}
