package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deterministic path-free first-failure evidence regression for R1-80/R1-08. */
public final class RuntimeReceiptFirstFailureSelfTest {
    private RuntimeReceiptFirstFailureSelfTest() {}

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-first-failure-receipt");
        try {
            Path file = dir.resolve("runtime-receipts.log");
            RuntimeReceiptLog log = new RuntimeReceiptLog(file);
            ChunkKey chunk = new ChunkKey(32, -7);
            String operation = "single-chunk-32--7-acde0123";
            String candidate = "core-v0.2.26-recovery.3";
            String rawFailure = "capture failed beside /mutable/world/save/oceancanvas-core/preimage.bin";

            RuntimeReceipt written = log.appendFirstFailure(
                    ChunkStage.LOADED, chunk, operation, candidate, rawFailure);
            checks += check(written.kind() == ReceiptKind.FIRST_FAILURE_DIAGNOSTIC,
                    "first-failure append uses dedicated forensic receipt kind");

            RuntimeReceipt verified = log.readVerified().get(0);
            checks += check(verified.chunk().equals(chunk),
                    "verified first-failure receipt preserves exact chunk");
            String detail = verified.detail();
            checks += check(detail.contains("schema=2"),
                    "first-failure receipt uses mutation-aware schema");
            checks += check(detail.contains("stage=LOADED"),
                    "first-failure detail identifies exact failing stage");
            checks += check(detail.contains("chunk=32,-7"),
                    "first-failure detail independently identifies chunk");
            checks += check(detail.contains("operation=" + operation),
                    "first-failure detail identifies immutable operation");
            checks += check(detail.contains("sourceCandidate=" + candidate),
                    "first-failure detail identifies source candidate/runtime identity");
            checks += check(detail.contains("worldMutation=NOT_STARTED"),
                    "pre-authoring failure states that world mutation had not begun");
            checks += check(!detail.contains("/mutable/world")
                            && !Files.readString(file, StandardCharsets.UTF_8).contains("/mutable/world"),
                    "mutable world path is never persisted in first-failure receipt");

            String failureSha = sha256(rawFailure);
            checks += check(detail.contains("failureSha256=" + failureSha),
                    "raw path-bearing failure is represented only by stable SHA-256");
            String canonical = "schema=2\n"
                    + "stage=LOADED\n"
                    + "chunk=32,-7\n"
                    + "operation=" + operation + "\n"
                    + "sourceCandidate=" + candidate + "\n"
                    + "worldMutation=NOT_STARTED\n"
                    + "failureSha256=" + failureSha;
            String expectedContextSha = sha256(canonical);
            checks += check(detail.endsWith("contextSha256=" + expectedContextSha),
                    "independent digest reproduces checksum-bound mutation-aware context");

            RuntimeReceipt possible = log.appendFirstFailure(
                    ChunkStage.PREIMAGE_CAPTURED, chunk, operation, candidate,
                    "authoring failed with no durable PHYSICAL_AUTHORED transition");
            checks += check(possible.detail().contains("worldMutation=POSSIBLE"),
                    "in-flight authoring failure never guesses mutation-free state");

            RuntimeReceipt confirmed = log.appendFirstFailure(
                    ChunkStage.PHYSICAL_AUTHORED, chunk, operation, candidate,
                    "settlement failed after durable authoring credit");
            checks += check(confirmed.detail().contains("worldMutation=CONFIRMED"),
                    "post-authoring failure records confirmed prior world mutation");

            checks += check(RuntimeReceiptLog.classifyWorldMutation(ChunkStage.DISCOVERED)
                            == RuntimeReceiptLog.WorldMutationState.NOT_STARTED,
                    "discovery/load failures classify as mutation not started");
            checks += check(RuntimeReceiptLog.classifyWorldMutation(ChunkStage.PREIMAGE_CAPTURED)
                            == RuntimeReceiptLog.WorldMutationState.POSSIBLE,
                    "preimage-captured authoring boundary classifies conservatively");
            checks += check(RuntimeReceiptLog.classifyWorldMutation(ChunkStage.RESTORED)
                            == RuntimeReceiptLog.WorldMutationState.CONFIRMED,
                    "restore-stage failure retains confirmed historical mutation truth");
            for (ChunkStage terminal : new ChunkStage[] {ChunkStage.COMPLETE, ChunkStage.FAILED}) {
                boolean terminalRejected = false;
                try {
                    RuntimeReceiptLog.classifyWorldMutation(terminal);
                } catch (IllegalArgumentException expected) {
                    terminalRejected = true;
                }
                checks += check(terminalRejected,
                        "terminal stage cannot fabricate a first-failure mutation classification");
            }

            byte[] beforeRefusal = Files.readAllBytes(file);
            for (String invalid : new String[] {
                    "/mutable/world/save",
                    "C:\\mutable\\world\\save",
                    "../relative-world"
            }) {
                boolean rejected = false;
                try {
                    log.appendFirstFailure(
                            ChunkStage.LOADED, chunk, invalid, candidate, "must not persist");
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                checks += check(rejected,
                        "path-like operation identity rejected before forensic append");
            }
            checks += check(java.util.Arrays.equals(beforeRefusal, Files.readAllBytes(file)),
                    "rejected mutable-path identities leave forensic bytes unchanged");
        } finally {
            deleteTree(dir);
        }
        return checks;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static int check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        return 1;
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
