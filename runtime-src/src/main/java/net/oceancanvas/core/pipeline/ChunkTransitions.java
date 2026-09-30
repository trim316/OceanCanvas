package net.oceancanvas.core.pipeline;

/** Central authority for allowed lifecycle transitions. */
public final class ChunkTransitions {
    private ChunkTransitions() {}

    public static boolean allowed(ChunkStage from, ChunkStage to) {
        if (from == null || to == null || from.terminal()) return false;
        return switch (from) {
            case DISCOVERED -> to == ChunkStage.LOADED || to == ChunkStage.FAILED;
            case LOADED -> to == ChunkStage.PREIMAGE_CAPTURED || to == ChunkStage.FAILED;
            case PREIMAGE_CAPTURED -> to == ChunkStage.PHYSICAL_AUTHORED || to == ChunkStage.FAILED;
            case PHYSICAL_AUTHORED -> to == ChunkStage.PHYSICAL_SETTLED || to == ChunkStage.FAILED;
            case PHYSICAL_SETTLED -> to == ChunkStage.PERSISTED || to == ChunkStage.FAILED;
            case PERSISTED -> to == ChunkStage.LIGHTING_SETTLED || to == ChunkStage.FAILED;
            case LIGHTING_SETTLED -> to == ChunkStage.VERIFIED || to == ChunkStage.FAILED;
            case VERIFIED -> to == ChunkStage.RESTORED || to == ChunkStage.FAILED;
            case RESTORED -> to == ChunkStage.RESTORE_VERIFIED || to == ChunkStage.FAILED;
            case RESTORE_VERIFIED -> to == ChunkStage.COMPLETE || to == ChunkStage.FAILED;
            case COMPLETE, FAILED -> false;
        };
    }
}
