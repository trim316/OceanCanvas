package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Deterministic R1-20 restart-gate/forensic-receipt independence regression. */
public final class AcceptanceReceiptRecorderSelfTest {
    private AcceptanceReceiptRecorderSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("AcceptanceReceiptRecorderSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-acceptance-receipt-independence");
        try {
            ChunkKey key = new ChunkKey(14, -9);
            String operation = "r1-20-acceptance-receipts";

            // Missing forensic receipts are not authority. A valid persisted hold
            // is verified from acceptance state and the missing receipt log can be
            // created afterwards without changing restart truth.
            Path absentState = dir.resolve("absent-acceptance.properties");
            Path absentReceipts = dir.resolve("absent-runtime-receipts.log");
            AcceptanceHarness.OpenResult absentFirst = AcceptanceHarness.open(
                    absentState, operation, key, ChunkStage.DISCOVERED);
            absentFirst.harness().holdAfterTransition(ChunkStage.LOADED);
            checks += check(absentFirst.harness().shouldHold(),
                    "missing-receipt fixture persists acceptance hold before restart");
            checks += check(!Files.exists(absentReceipts),
                    "acceptance hold does not require a forensic receipt file");

            AcceptanceHarness.OpenResult absentRestart = AcceptanceHarness.open(
                    absentState, operation, key, ChunkStage.LOADED);
            checks += check(absentRestart.restartVerified(),
                    "restart hold verifies with receipt log absent");
            checks += check(!absentRestart.harness().shouldHold(),
                    "verified restart clears hold independently of receipts");
            var absentRecorded = AcceptanceReceiptRecorder.appendBestEffort(
                    new RuntimeReceiptLog(absentReceipts),
                    ReceiptKind.ACCEPTANCE_RESTART_VERIFIED, key,
                    "stage=LOADED;verifiedRestarts=1");
            checks += check(absentRecorded.recorded() && absentRecorded.sequence() == 0,
                    "missing forensic log is safely created after restart verification");
            checks += check(new RuntimeReceiptLog(absentReceipts).readVerified().size() == 1,
                    "created restart receipt verifies normally");

            // A partial/torn forensic file must remain forensic evidence. It must
            // neither repair itself nor revoke/consume acceptance restart truth.
            Path tornState = dir.resolve("torn-acceptance.properties");
            Path tornReceipts = dir.resolve("torn-runtime-receipts.log");
            AcceptanceHarness.OpenResult tornFirst = AcceptanceHarness.open(
                    tornState, operation + "-torn", key, ChunkStage.LOADED);
            tornFirst.harness().holdAfterTransition(ChunkStage.PREIMAGE_CAPTURED);
            byte[] tornBytes = "partial-forensic-record-without-newline".getBytes(StandardCharsets.UTF_8);
            Files.write(tornReceipts, tornBytes);

            AcceptanceHarness.OpenResult tornRestart = AcceptanceHarness.open(
                    tornState, operation + "-torn", key, ChunkStage.PREIMAGE_CAPTURED);
            checks += check(tornRestart.restartVerified(),
                    "valid acceptance hold verifies despite torn forensic log");
            checks += check(tornRestart.harness().verifiedRestarts() == 1,
                    "torn receipt cannot alter durable restart count");
            var refusedRestartReceipt = AcceptanceReceiptRecorder.appendBestEffort(
                    new RuntimeReceiptLog(tornReceipts),
                    ReceiptKind.ACCEPTANCE_RESTART_VERIFIED, key,
                    "stage=PREIMAGE_CAPTURED;verifiedRestarts=1");
            checks += check(!refusedRestartReceipt.recorded()
                            && refusedRestartReceipt.sequence() == -1,
                    "torn forensic log refuses append without aborting acceptance truth");
            checks += check(Arrays.equals(tornBytes, Files.readAllBytes(tornReceipts)),
                    "refused restart receipt leaves torn evidence byte-for-byte unchanged");

            tornRestart.harness().holdAfterTransition(ChunkStage.PHYSICAL_AUTHORED);
            var refusedHoldReceipt = AcceptanceReceiptRecorder.appendBestEffort(
                    new RuntimeReceiptLog(tornReceipts), ReceiptKind.ACCEPTANCE_HOLD, key,
                    "stage=PHYSICAL_AUTHORED;verifiedRestarts=1");
            checks += check(!refusedHoldReceipt.recorded(),
                    "torn forensic log also refuses hold receipt best-effort");
            checks += check(tornRestart.harness().shouldHold(),
                    "forensic hold-receipt failure cannot clear persisted restart gate");
            checks += check(Arrays.equals(tornBytes, Files.readAllBytes(tornReceipts)),
                    "hold-receipt refusal preserves original torn forensic bytes");

            AcceptanceHarness.OpenResult nextRestart = AcceptanceHarness.open(
                    tornState, operation + "-torn", key, ChunkStage.PHYSICAL_AUTHORED);
            checks += check(nextRestart.restartVerified()
                            && nextRestart.harness().verifiedRestarts() == 2,
                    "next real restart verifies from acceptance state despite persistent receipt corruption");

            boolean nonAcceptanceRejected = false;
            try {
                AcceptanceReceiptRecorder.appendBestEffort(
                        new RuntimeReceiptLog(dir.resolve("wrong-kind.log")),
                        ReceiptKind.PREIMAGE_CAPTURED, key, "must-not-write");
            } catch (IllegalArgumentException expected) {
                nonAcceptanceRejected = true;
            }
            checks += check(nonAcceptanceRejected,
                    "best-effort bridge cannot swallow non-acceptance receipt failures");
            checks += check(!Files.exists(dir.resolve("wrong-kind.log")),
                    "wrong receipt kind is rejected before forensic file creation");
            return checks;
        } finally {
            deleteTree(dir);
        }
    }

    private static int check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        return 1;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
