package net.oceancanvas.mod.worldgen;
/** Scheduling admission only. A hold must precede queue removal or authoritative audit. */
final class OceanCanvasDirectReadyAdmission {
    static boolean allowed(boolean partial, boolean visible, long elapsed, long allowance, long target) {
        return partial || visible || OceanCanvasCurrentFrameAdmission.allows(elapsed, allowance, target);
    }
}
