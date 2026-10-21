package net.oceancanvas.mod.worldgen;
/** New residency admission only; does not revoke tickets, mutate proof, or change health limits. */
final class OceanCanvasRelightAdmissionPressure {
    private OceanCanvasRelightAdmissionPressure() {}
    static boolean mustClamp(double rawHeap) { return !Double.isFinite(rawHeap) || rawHeap >= 0.82D; }
}
