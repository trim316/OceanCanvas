package net.oceancanvas.core.journal;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Proves an fsync failure cannot expose a new authoritative journal transition. */
public final class CoreJournalFsyncFailureSelfTest {
    private CoreJournalFsyncFailureSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("CoreJournalFsyncFailureSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-journal-fsync");
        try {
            Path canonical = dir.resolve("journal.log");
            Path stage = dir.resolve("journal.log.append.tmp");
            ChunkKey chunk = new ChunkKey(32, 32);
            CoreJournal journal = new CoreJournal(canonical);
            journal.append(new JournalEntry(0, 1, chunk,
                    ChunkStage.DISCOVERED, ChunkStage.LOADED, 0, 1, "resident"));
            byte[] canonicalBefore = Files.readAllBytes(canonical);
            check(journal.readVerified().size() == 1, "baseline journal prefix verifies"); checks++;

            boolean rejected = false;
            try {
                journal.append(new JournalEntry(1, 2, chunk,
                                ChunkStage.LOADED, ChunkStage.PREIMAGE_CAPTURED, 0, 2, "captured"),
                        channel -> { throw new IOException("simulated disk full at journal fsync"); },
                        (source, target) -> Files.move(source, target));
            } catch (IOException expected) {
                rejected = expected.getMessage() != null
                        && expected.getMessage().contains("simulated disk full");
            }
            check(rejected, "injected journal fsync failure is propagated"); checks++;
            check(Arrays.equals(canonicalBefore, Files.readAllBytes(canonical)),
                    "failed journal fsync preserves canonical prefix byte-for-byte"); checks++;
            check(journal.readVerified().size() == 1,
                    "failed fsync grants no authoritative transition credit"); checks++;
            check(Files.isRegularFile(stage) && Files.size(stage) > canonicalBefore.length,
                    "unpublished journal candidate survives as forensic stage"); checks++;
            byte[] stagedBeforeRetry = Files.readAllBytes(stage);

            boolean retryRejected = false;
            try {
                journal.append(new JournalEntry(1, 3, chunk,
                        ChunkStage.LOADED, ChunkStage.PREIMAGE_CAPTURED, 0, 2, "captured"));
            } catch (IOException expected) {
                retryRejected = true;
            }
            check(retryRejected, "surviving ambiguous journal stage blocks automatic retry"); checks++;
            check(Arrays.equals(canonicalBefore, Files.readAllBytes(canonical)),
                    "blocked retry preserves canonical journal authority"); checks++;
            check(Arrays.equals(stagedBeforeRetry, Files.readAllBytes(stage)),
                    "blocked retry preserves first unpublished journal bytes"); checks++;
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
