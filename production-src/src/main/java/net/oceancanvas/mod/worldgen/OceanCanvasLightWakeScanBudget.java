package net.oceancanvas.mod.worldgen;

/** Bounds administrative attempts, including stale and capacity-refused entries. */
final class OceanCanvasLightWakeScanBudget {
    private int remaining = 64;
    private final long startedNs;
    OceanCanvasLightWakeScanBudget(long startedNs) { this.startedNs = startedNs; }
    boolean tryClaim(long nowNs) {
        if (remaining == 0 || nowNs - startedNs >= 8_000_000L) return false;
        remaining--;
        return true;
    }
}
