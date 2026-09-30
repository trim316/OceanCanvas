package net.oceancanvas.core.restore;

/**
 * A block-state-only backup cannot restore block-entity NBT. Refuse a chunk
 * containing either a state that declares a block entity or an already-loaded
 * entity, even if one signal is temporarily missing during chunk loading.
 */
public final class PreimageAdmissionPolicy {
    private PreimageAdmissionPolicy() {}

    public static boolean refuses(boolean stateDeclaresBlockEntity, boolean blockEntityPresent) {
        return stateDeclaresBlockEntity || blockEntityPresent;
    }

    /**
     * A runtime-ID preimage must be losslessly reversible in the current
     * registry before publishing it as authoritative recovery evidence.
     * Reject missing/negative IDs and round-trip mismatches before mutation.
     */
    public static boolean refusesStateId(int capturedId, int resolvedId, boolean resolvedSameState) {
        return capturedId < 0 || capturedId != resolvedId || !resolvedSameState;
    }
}
