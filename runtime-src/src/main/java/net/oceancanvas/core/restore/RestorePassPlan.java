package net.oceancanvas.core.restore;

/**
 * Bounded two-pass exact restoration. Reapply original block states only after
 * a full first pass has restored neighboring support throughout the chunk.
 * Completion remains gated by the existing durable save and cold-restart
 * block-state verification; this policy never authorizes skipping that proof.
 */
public final class RestorePassPlan {
    private RestorePassPlan() {}

    public record AfterPass(int nextPass, int nextCursor, boolean readyToPersist) {}

    public static AfterPass afterFullPass(int pass, int cursor, int total) {
        if (total <= 0 || cursor != total || pass < 0 || pass > 1) {
            throw new IllegalArgumentException("cannot credit an incomplete or unknown restore pass");
        }
        if (pass == 0) {
            return new AfterPass(1, 0, false);
        }
        return new AfterPass(2, total, true);
    }
}
