package net.oceancanvas.mod.worldgen;
/** Reserve an existing phase allowance inside the existing nominal50ms tick target. */
final class OceanCanvasCurrentFrameAdmission {
    static boolean allows(long elapsed, long phaseAllowance, long frameTarget) {
        return elapsed >= 0L && phaseAllowance >= 0L && frameTarget > 0L
                && phaseAllowance <= frameTarget && elapsed <= frameTarget - phaseAllowance;
    }
}
