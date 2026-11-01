package net.oceancanvas.mod.worldgen;

/** Admission only. Dirty certificates and existing retry debt remain authoritative. */
final class OceanCanvasHistoricalLightAdmission {
    enum Decision { ARM, RETAIN_DORMANT, PARK }

    static Decision decide(boolean alreadyActive, boolean explicitOrVisible,
            boolean dormant, int activeCount, int existingResidencyCapacity) {
        if (alreadyActive || explicitOrVisible) return Decision.ARM;
        if (dormant) return Decision.RETAIN_DORMANT;
        return canWakeBackground(activeCount, existingResidencyCapacity) ? Decision.ARM : Decision.PARK;
    }

    static boolean canWakeBackground(int activeCount, int existingResidencyCapacity) {
        return existingResidencyCapacity > 0 && activeCount < existingResidencyCapacity;
    }
}
