package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Deterministic regression for R1-07 independent best-effort failed-stage evidence preservation. */
public final class FailureEvidenceSnapshotSelfTest {
    private FailureEvidenceSnapshotSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("FailureEvidenceSnapshotSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path root = Files.createTempDirectory("oceancanvas-failure-evidence");
        try {
            Path manifest = root.resolve("operation.properties");
            Path journal = root.resolve("transitions.journal");
            Path receipts = root.resolve("runtime-receipts.log");
            Files.writeString(manifest, "manifest-v1\n", StandardCharsets.UTF_8);
            Files.writeString(journal, "journal-v1\n", StandardCharsets.UTF_8);
            Files.writeString(receipts, "receipt-v1\n", StandardCharsets.UTF_8);

            var first = FailureEvidenceSnapshot.captureBestEffort(root, ChunkStage.LOADED);
            checks += check(first.copied() == 3, "all present bounded evidence copied");
            checks += check(first.alreadyPreserved() == 0, "first snapshot has no prior copies");
            checks += check(first.absent() == 4, "optional absent evidence does not fail snapshot");
            checks += check(first.failed() == 0, "first snapshot has no failures");
            Path firstDir = root.resolve("failure-evidence/stage-loaded");
            byte[] frozenManifest = Files.readAllBytes(firstDir.resolve("operation.properties.snapshot"));
            checks += check(Arrays.equals(frozenManifest, Files.readAllBytes(manifest)),
                    "snapshot preserves exact source bytes");

            Files.writeString(manifest, "manifest-v2-drift\n", StandardCharsets.UTF_8);
            var replay = FailureEvidenceSnapshot.captureBestEffort(root, ChunkStage.LOADED);
            checks += check(replay.failed() == 1,
                    "changed source cannot overwrite immutable prior snapshot");
            checks += check(replay.alreadyPreserved() == 2,
                    "unchanged prior snapshots are recognized idempotently");
            checks += check(Arrays.equals(frozenManifest,
                            Files.readAllBytes(firstDir.resolve("operation.properties.snapshot"))),
                    "conflicting replay preserves original failure evidence byte-for-byte");

            Path oversized = root.resolve("preimage-blockstates.bin");
            try (RandomAccessFile raf = new RandomAccessFile(oversized.toFile(), "rw")) {
                raf.setLength(16L * 1024L * 1024L + 1L);
            }
            var bounded = FailureEvidenceSnapshot.captureBestEffort(root, ChunkStage.PHYSICAL_AUTHORED);
            checks += check(bounded.failed() == 1,
                    "oversized evidence is refused rather than copied unbounded");
            checks += check(bounded.copied() == 3,
                    "one refused source does not suppress independent evidence copies");
            Path boundedDir = root.resolve("failure-evidence/stage-physical_authored");
            checks += check(Files.isRegularFile(boundedDir.resolve("operation.properties.snapshot"))
                            && Files.isRegularFile(boundedDir.resolve("transitions.journal.snapshot"))
                            && Files.isRegularFile(boundedDir.resolve("runtime-receipts.log.snapshot")),
                    "bounded failure still preserves all other available evidence");
            checks += check(!Files.exists(boundedDir.resolve("preimage-blockstates.bin.snapshot")),
                    "oversized source never publishes a partial canonical snapshot");

            // Prove the production first-failure path still attempts the independent
            // evidence snapshot if the receipt log itself is unwriteable/corrupt.
            Path brokenRoot = root.resolve("broken-receipt-case");
            Files.createDirectories(brokenRoot);
            Path brokenManifest = brokenRoot.resolve("operation.properties");
            Files.writeString(brokenManifest, "authority-survives\n", StandardCharsets.UTF_8);
            Files.createDirectory(brokenRoot.resolve("runtime-receipts.log"));
            RuntimeReceiptLog brokenLog = new RuntimeReceiptLog(brokenRoot.resolve("runtime-receipts.log"));
            boolean appendFailed = false;
            try {
                brokenLog.appendFirstFailure(
                        ChunkStage.LOADED, new ChunkKey(7, -2),
                        "operation-7--2", "candidate-v1", "fixture failure");
            } catch (IOException expected) {
                appendFailed = true;
            }
            checks += check(appendFailed,
                    "broken forensic receipt remains a real append failure");
            Path rescued = brokenRoot.resolve(
                    "failure-evidence/stage-loaded/operation.properties.snapshot");
            checks += check(Files.isRegularFile(rescued)
                            && Arrays.equals(Files.readAllBytes(brokenManifest), Files.readAllBytes(rescued)),
                    "receipt append failure still preserves independent durable authority evidence");

            boolean terminalRejected = false;
            try {
                FailureEvidenceSnapshot.captureBestEffort(root, ChunkStage.FAILED);
            } catch (IllegalArgumentException expected) {
                terminalRejected = true;
            }
            checks += check(terminalRejected,
                    "terminal FAILED cannot invent an attempted-stage snapshot namespace");
            return checks;
        } finally {
            deleteTree(root);
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
