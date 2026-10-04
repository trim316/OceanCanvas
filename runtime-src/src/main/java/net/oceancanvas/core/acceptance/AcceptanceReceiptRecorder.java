package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;

import java.io.IOException;
import java.util.Objects;

/**
 * Best-effort bridge from the durable acceptance restart gate to non-authoritative
 * forensic receipts.
 *
 * <p>The acceptance-state file is the test harness restart gate. Receipt I/O must
 * never grant or revoke that gate. In particular, a missing receipt file may be
 * created, while a torn/corrupt existing receipt file is preserved byte-for-byte
 * and reported as refused rather than repaired or allowed to abort an otherwise
 * valid acceptance restart. Release evidence reconstruction still reads receipts
 * strictly and therefore cannot certify a corrupt log.</p>
 */
public final class AcceptanceReceiptRecorder {
    private AcceptanceReceiptRecorder() {}

    public record Result(boolean recorded, long sequence, String refusalType) {
        public Result {
            if (recorded) {
                if (sequence < 0 || !refusalType.isEmpty()) {
                    throw new IllegalArgumentException("recorded acceptance receipt result is inconsistent");
                }
            } else if (sequence != -1 || refusalType.isBlank()) {
                throw new IllegalArgumentException("refused acceptance receipt result is inconsistent");
            }
        }
    }

    public static Result appendBestEffort(
            RuntimeReceiptLog receipts, ReceiptKind kind, ChunkKey chunk, String detail) {
        Objects.requireNonNull(receipts, "receipts");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(detail, "detail");
        if (kind != ReceiptKind.ACCEPTANCE_HOLD
                && kind != ReceiptKind.ACCEPTANCE_RESTART_VERIFIED
                && kind != ReceiptKind.ACCEPTANCE_FINAL_RESTART_VERIFIED) {
            throw new IllegalArgumentException("not an acceptance forensic receipt kind: " + kind);
        }
        try {
            RuntimeReceipt receipt = receipts.append(kind, chunk, detail);
            return new Result(true, receipt.sequence(), "");
        } catch (IOException refused) {
            // Do not truncate, repair, replace, or otherwise mutate corrupt
            // evidence. RuntimeReceiptLog performs strict verification before
            // append, so an IOException is a safe forensic refusal.
            return new Result(false, -1, refused.getClass().getSimpleName());
        }
    }
}
