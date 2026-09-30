package net.oceancanvas.core.pipeline;

/**
 * Deliberately linear chunk lifecycle for the restarted Ocean Canvas core.
 *
 * <p>The old lineage allowed physical, persistence, lighting and client-repair
 * concerns to overlap. The restart makes the ordering explicit and rejects any
 * shortcut around physical settlement or durable persistence.</p>
 */
public enum ChunkStage {
    DISCOVERED,
    LOADED,
    PREIMAGE_CAPTURED,
    PHYSICAL_AUTHORED,
    PHYSICAL_SETTLED,
    PERSISTED,
    LIGHTING_SETTLED,
    VERIFIED,
    RESTORED,
    RESTORE_VERIFIED,
    COMPLETE,
    FAILED;

    public boolean terminal() {
        return this == COMPLETE || this == FAILED;
    }
}
