package net.oceancanvas.mod.worldgen;

/** A light re-arm cannot run ahead of an explicitly pending physical replay. */
final class OceanCanvasPhysicalReplayProofAdmission {
    enum Action { ALLOW_LIGHT_PROOF, WAIT_ADMITTED_TERRAIN, PARK_FOR_REPLAY }

    private OceanCanvasPhysicalReplayProofAdmission() { }

    static Action choose(boolean physicalReplayTarget, boolean physicalRepairAllowed,
            boolean terrainAdmitted) {
        if (!physicalReplayTarget || physicalRepairAllowed) return Action.ALLOW_LIGHT_PROOF;
        return terrainAdmitted ? Action.WAIT_ADMITTED_TERRAIN : Action.PARK_FOR_REPLAY;
    }
}
