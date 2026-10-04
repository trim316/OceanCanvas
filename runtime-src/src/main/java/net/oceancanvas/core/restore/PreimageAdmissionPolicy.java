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
    /**
     * Reject changed build limits or configured geometry before the first
     * restore write. The existing single-chunk capture deliberately requires
     * its lower backup boundary to be strictly above the world minimum.
     */
    public static boolean refusesRestoreGeometry(int capturedMinY, int capturedMaxY,
            int expectedMinY, int expectedMaxY, int worldMinY, int worldMaxExclusive) {
        return capturedMinY != expectedMinY || capturedMaxY != expectedMaxY
                || capturedMinY <= worldMinY || capturedMaxY >= worldMaxExclusive
                || capturedMaxY < capturedMinY;
    }

    /**
     * Reject changed world build limits or configured authoring geometry before
     * the first physical block write after a durable preimage/restart.
     */
    public static boolean refusesAuthoringGeometry(int capturedMinY, int capturedMaxY,
            int expectedMinY, int expectedMaxY, int worldMinY, int worldMaxExclusive) {
        return refusesRestoreGeometry(capturedMinY, capturedMaxY,
                expectedMinY, expectedMaxY, worldMinY, worldMaxExclusive);
    }

    public static boolean refusesStateId(int capturedId, int resolvedId, boolean resolvedSameState) {
        return capturedId < 0 || capturedId != resolvedId || !resolvedSameState;
    }
}
