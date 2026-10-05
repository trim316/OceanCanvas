package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.receipt.PreimageReceiptContinuity;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * R1-40 regression: a hard process boundary after RESTORE_VERIFIED is durably
 * journaled but before final ticket-release evidence must resume at release,
 * produce the missing archive/release evidence exactly once, then commit COMPLETE.
 */
public final class RestoreVerifiedReleaseRestartSelfTest {
    private RestoreVerifiedReleaseRestartSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("RestoreVerifiedReleaseRestartSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-r1-40-");
        try {
            ChunkKey key = new ChunkKey(-6, 13);
            String operation = "r1-40-release-boundary";
            String preimageSha = "4".repeat(64);
            Path journalPath = dir.resolve("transitions.journal");
            Path receiptPath = dir.resolve("runtime-receipts.log");
            RuntimeReceiptLog receipts = new RuntimeReceiptLog(receiptPath);
            Counts counts = new Counts();

            SingleChunkPipeline beforeCrash = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            ReleaseBoundaryPorts firstProcess = new ReleaseBoundaryPorts(
                    receipts, key, operation, preimageSha, counts);

            long now = 1_000L;
            while (beforeCrash.record().stage() != ChunkStage.RESTORE_VERIFIED) {
                check(beforeCrash.tick(firstProcess, now++),
                        "each pre-release stage must commit durably");
            }

            check(beforeCrash.record().stage() == ChunkStage.RESTORE_VERIFIED,
                    "fixture reaches durable RESTORE_VERIFIED boundary");
            check(counts.release == 0,
                    "ticket release is not dispatched before the simulated crash boundary");
            CoreJournal.ReplayState durableBoundary =
                    new CoreJournal(journalPath).replaySingleChunk(key);
            check(durableBoundary.record().stage() == ChunkStage.RESTORE_VERIFIED,
                    "journal independently replays RESTORE_VERIFIED before release");
            check(durableBoundary.nextSequence() == 9L,
                    "exactly nine pre-release transitions are durable");

            boolean prematureCompletionProofRejected = false;
            try {
                PreimageReceiptContinuity.verify(
                        receipts.readVerified(), operation, key, true);
            } catch (IOException expected) {
                prematureCompletionProofRejected = true;
            }
            check(prematureCompletionProofRejected,
                    "missing final release/archive receipt cannot certify completion");

            // Hard process boundary: reconstruct both the pipeline and receipt-log
            // reader from disk. The new adapter owns no inherited in-memory ticket.
            SingleChunkPipeline afterRestart =
                    SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            RuntimeReceiptLog reopenedReceipts = new RuntimeReceiptLog(receiptPath);
            ReleaseBoundaryPorts secondProcess = new ReleaseBoundaryPorts(
                    reopenedReceipts, key, operation, preimageSha, counts);

            check(afterRestart.record().stage() == ChunkStage.RESTORE_VERIFIED,
                    "restart resumes exactly at release boundary");
            check(afterRestart.tick(secondProcess, 2_000L),
                    "post-restart release commits COMPLETE");
            check(afterRestart.record().stage() == ChunkStage.COMPLETE,
                    "release acknowledgement advances only to COMPLETE");
            check(counts.release == 1,
                    "release stage dispatches exactly once after restart");

            PreimageReceiptContinuity.Verified proof = PreimageReceiptContinuity.verify(
                    reopenedReceipts.readVerified(), operation, key, true);
            check(proof.immutableArchiveVerified(),
                    "post-restart release receipt closes immutable archive proof");
            check(preimageSha.equals(proof.preimageSha256()),
                    "release proof preserves exact preimage SHA continuity");

            CoreJournal.ReplayState completed = new CoreJournal(journalPath).replaySingleChunk(key);
            check(completed.record().stage() == ChunkStage.COMPLETE,
                    "authoritative journal replays COMPLETE after release");
            check(completed.nextSequence() == 10L,
                    "exactly one release transition was appended");

            SingleChunkPipeline coldComplete =
                    SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            check(!coldComplete.tick(new ReleaseBoundaryPorts(
                            new RuntimeReceiptLog(receiptPath), key, operation, preimageSha, counts),
                    3_000L),
                    "cold COMPLETE reopen dispatches no release work");
            check(counts.release == 1,
                    "COMPLETE reopen cannot duplicate final release acknowledgement");

            return 14;
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class Counts {
        int release;
    }

    private static final class ReleaseBoundaryPorts implements SingleChunkPorts {
        private final RuntimeReceiptLog receipts;
        private final ChunkKey key;
        private final String operation;
        private final String preimageSha;
        private final Counts counts;

        private ReleaseBoundaryPorts(RuntimeReceiptLog receipts, ChunkKey key,
                                     String operation, String preimageSha, Counts counts) {
            this.receipts = receipts;
            this.key = key;
            this.operation = operation;
            this.preimageSha = preimageSha;
            this.counts = counts;
        }

        @Override public StageActionResult load(ChunkRecord record) { return ok("load"); }

        @Override public StageActionResult capturePreimage(ChunkRecord record) {
            try {
                receipts.append(ReceiptKind.PREIMAGE_CAPTURED, key,
                        "operation=" + operation + ";preimageSha256=" + preimageSha);
                return ok("capture");
            } catch (IOException e) {
                return StageActionResult.failure("capture receipt failed: " + e.getMessage());
            }
        }

        @Override public StageActionResult authorPhysical(ChunkRecord record) { return ok("author"); }
        @Override public StageActionResult settlePhysical(ChunkRecord record) { return ok("settle"); }
        @Override public StageActionResult persist(ChunkRecord record) { return ok("persist"); }
        @Override public StageActionResult settleLighting(ChunkRecord record) { return ok("light"); }
        @Override public StageActionResult verify(ChunkRecord record) { return ok("verify"); }

        @Override public StageActionResult restore(ChunkRecord record) {
            try {
                receipts.append(ReceiptKind.RESTORE_COMPLETE, key,
                        "operation=" + operation + ";preimageSha256=" + preimageSha);
                return ok("restore");
            } catch (IOException e) {
                return StageActionResult.failure("restore receipt failed: " + e.getMessage());
            }
        }

        @Override public StageActionResult verifyRestore(ChunkRecord record) {
            try {
                receipts.append(ReceiptKind.RESTORE_VERIFIED, key,
                        "operation=" + operation + ";preimageSha256=" + preimageSha);
                return ok("verify-restore");
            } catch (IOException e) {
                return StageActionResult.failure("verify-restore receipt failed: " + e.getMessage());
            }
        }

        @Override public StageActionResult release(ChunkRecord record) {
            counts.release++;
            try {
                receipts.append(ReceiptKind.TICKET_RELEASED, key,
                        "no live ticket after restart;restoreVerified=true;preimageArchiveSha256="
                                + preimageSha);
                return ok("release-after-restart");
            } catch (IOException e) {
                return StageActionResult.failure("release receipt failed: " + e.getMessage());
            }
        }

        private static StageActionResult ok(String evidence) {
            return StageActionResult.success(evidence);
        }
    }
}
