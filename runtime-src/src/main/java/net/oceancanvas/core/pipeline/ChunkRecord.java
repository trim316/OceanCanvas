package net.oceancanvas.core.pipeline;

import java.util.Objects;

/**
 * Immutable chunk lifecycle state. Every successful transition is monotonic.
 * Failed records are terminal and must be reintroduced as a new attempt rather
 * than silently moved backward.
 */
public record ChunkRecord(
        ChunkKey chunk,
        ChunkStage stage,
        long attempt,
        long revision,
        String evidence,
        String failureReason) {

    public ChunkRecord {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(stage, "stage");
        evidence = evidence == null ? "" : evidence;
        failureReason = failureReason == null ? "" : failureReason;
        if (attempt < 1) throw new IllegalArgumentException("attempt must be >= 1");
        if (revision < 0) throw new IllegalArgumentException("revision must be >= 0");
        if (stage == ChunkStage.FAILED && failureReason.isBlank()) {
            throw new IllegalArgumentException("FAILED requires a reason");
        }
    }

    public static ChunkRecord discovered(ChunkKey chunk) {
        return new ChunkRecord(chunk, ChunkStage.DISCOVERED, 1L, 0L, "", "");
    }

    public ChunkRecord advance(ChunkStage next, String newEvidence) {
        Objects.requireNonNull(next, "next");
        if (!ChunkTransitions.allowed(stage, next)) {
            throw new IllegalStateException("Illegal Ocean Canvas chunk transition " + stage + " -> " + next);
        }
        return new ChunkRecord(chunk, next, attempt, revision + 1L, newEvidence, "");
    }

    public ChunkRecord fail(String reason) {
        if (stage.terminal()) throw new IllegalStateException("Terminal chunk record cannot fail again: " + stage);
        return new ChunkRecord(chunk, ChunkStage.FAILED, attempt, revision + 1L, evidence, reason);
    }

    public ChunkRecord restartAttempt(String reason) {
        if (stage != ChunkStage.FAILED) throw new IllegalStateException("Only failed records may start a new attempt");
        String note = reason == null || reason.isBlank() ? failureReason : failureReason + "; " + reason;
        return new ChunkRecord(chunk, ChunkStage.DISCOVERED, attempt + 1L, revision + 1L,
                "retry-after:" + note, "");
    }
}
