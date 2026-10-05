package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Proves an fsync failure cannot expose or consume a forensic receipt record. */
public final class RuntimeReceiptFsyncFailureSelfTest {
    private RuntimeReceiptFsyncFailureSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("RuntimeReceiptFsyncFailureSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-receipt-fsync");
        try {
            Path canonical = dir.resolve("receipts.log");
            Path stage = dir.resolve("receipts.log.append.tmp");
            ChunkKey chunk = new ChunkKey(32, 32);
            RuntimeReceiptLog log = new RuntimeReceiptLog(canonical);
            RuntimeReceipt first = log.append(ReceiptKind.TICKET_INSTALLED, chunk, "radius=0");
            check(first.sequence() == 0, "baseline receipt sequence starts at zero"); checks++;
            byte[] canonicalBefore = Files.readAllBytes(canonical);
            check(log.readVerified().size() == 1, "baseline receipt prefix verifies"); checks++;

            boolean rejected = false;
            try {
                log.append(ReceiptKind.CHUNK_RESIDENT, chunk, "resident=true",
                        channel -> { throw new IOException("simulated disk full at receipt fsync"); },
                        (source, target) -> Files.move(source, target));
            } catch (IOException expected) {
                rejected = expected.getMessage() != null
                        && expected.getMessage().contains("simulated disk full");
            }
            check(rejected, "injected receipt fsync failure is propagated"); checks++;
            check(Arrays.equals(canonicalBefore, Files.readAllBytes(canonical)),
                    "failed receipt fsync preserves canonical prefix byte-for-byte"); checks++;
            check(log.readVerified().size() == 1,
                    "failed fsync grants no forensic receipt credit"); checks++;
            check(Files.isRegularFile(stage) && Files.size(stage) > canonicalBefore.length,
                    "unpublished receipt candidate survives as forensic stage"); checks++;
            byte[] stagedBeforeRetry = Files.readAllBytes(stage);

            boolean retryRejected = false;
            try {
                log.append(ReceiptKind.CHUNK_RESIDENT, chunk, "resident=true");
            } catch (IOException expected) {
                retryRejected = true;
            }
            check(retryRejected, "surviving ambiguous receipt stage blocks automatic retry"); checks++;
            check(Arrays.equals(canonicalBefore, Files.readAllBytes(canonical)),
                    "blocked retry preserves canonical receipt evidence"); checks++;
            check(Arrays.equals(stagedBeforeRetry, Files.readAllBytes(stage)),
                    "blocked retry preserves first unpublished receipt bytes"); checks++;
            return checks;
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
