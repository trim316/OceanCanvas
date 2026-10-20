package net.oceancanvas.mod.worldgen;

/** Residency only: never grants a proof, repair permission or additional ticket. */
final class OceanCanvasSettleResidencyPolicy {
    private OceanCanvasSettleResidencyPolicy() {}
    static boolean retainOwnedStageOne(int remainingTicks, int pass, int settleTicks,
            boolean fingerprintPresent, boolean physicalAuditComplete) {
        return remainingTicks > 0 && remainingTicks <= settleTicks && pass == 1
                && fingerprintPresent && physicalAuditComplete;
    }
}
