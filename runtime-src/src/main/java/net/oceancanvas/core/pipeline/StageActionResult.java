package net.oceancanvas.core.pipeline;

import java.util.Objects;

/** Result of one bounded adapter step. WAITING never advances durable state. */
public record StageActionResult(Status status, String evidence) {
    public enum Status { WAITING, SUCCEEDED, FAILED }

    public StageActionResult {
        Objects.requireNonNull(status, "status");
        evidence = evidence == null ? "" : evidence;
        if (status == Status.FAILED && evidence.isBlank()) {
            throw new IllegalArgumentException("FAILED requires evidence/reason");
        }
    }

    public static StageActionResult waiting(String evidence) { return new StageActionResult(Status.WAITING, evidence); }
    public static StageActionResult success(String evidence) { return new StageActionResult(Status.SUCCEEDED, evidence); }
    public static StageActionResult failure(String reason) { return new StageActionResult(Status.FAILED, reason); }
}
