package net.oceancanvas.core.restore;

/** Keep exact-snapshot writes from deleting other saved states mid-restore. */
public final class RestoreWritePolicy {
    // Minecraft 26.2 Block.UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE |
    // UPDATE_SUPPRESS_DROPS. No UPDATE_NEIGHBORS bit.
    public static final int EXACT_SNAPSHOT_FLAGS = 2 | 16 | 32;

    private RestoreWritePolicy() {}

    public static boolean preservesSnapshotShapes(int flags) {
        return (flags & 16) != 0 && (flags & 1) == 0 && (flags & 32) != 0;
    }
}
