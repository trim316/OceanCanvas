package net.oceancanvas.core.journal;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Adversarial bounded-journal checks run from CoreSelfTest on every hosted build. */
public final class CoreJournalBoundedReadSelfTest {
    private CoreJournalBoundedReadSelfTest() {}

    public static int run() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-bounded-journal");
        int checks = 0;
        try {
            Path good = dir.resolve("good.journal");
            CoreJournal journal = new CoreJournal(good);
            String detail = "saved \\u2713";
            journal.append(new JournalEntry(0L, 1L, new ChunkKey(7, -3),
                    ChunkStage.DISCOVERED, ChunkStage.LOADED, 1L, 1L, detail));
            if (!detail.equals(journal.readVerified().get(0).reason())) {
                throw new AssertionError("bounded streaming replay must retain valid UTF-8");
            }
            checks++;
            byte[] original = Files.readAllBytes(good);

            Path huge = dir.resolve("oversized-sparse.journal");
            try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
                file.setLength(8L * 1024L * 1024L + 1L);
            }
            rejects(new CoreJournal(huge), "safe total size bound");
            checks++;
            if (Files.size(huge) != 8L * 1024L * 1024L + 1L) {
                throw new AssertionError("oversized evidence must be preserved");
            }
            checks++;

            Path oversizedRecord = dir.resolve("oversized-record.journal");
            byte[] longLine = new byte[16 * 1024 + 2];
            Arrays.fill(longLine, (byte) 'x');
            longLine[longLine.length - 1] = (byte) '\\n';
            Files.write(oversizedRecord, longLine);
            rejects(new CoreJournal(oversizedRecord), "journal record exceeds safe size bound");
            checks++;

            Path torn = dir.resolve("unterminated.journal");
            Files.write(torn, Arrays.copyOf(original, original.length - 1));
            rejects(new CoreJournal(torn), "unterminated final record");
            checks++;

            Path tooLongToAppend = dir.resolve("refuse-large-append.journal");
            boolean refused = false;
            try {
                new CoreJournal(tooLongToAppend).append(new JournalEntry(0L, 1L,
                        new ChunkKey(7, -3), ChunkStage.DISCOVERED, ChunkStage.LOADED,
                        1L, 1L, "x".repeat(17_000)));
            } catch (IOException expected) {
                refused = expected.getMessage().contains("record exceeds safe size bound");
            }
            if (!refused || Files.exists(tooLongToAppend)) {
                throw new AssertionError("oversized append must refuse without creating authority");
            }
            checks++;

            if (!Arrays.equals(original, Files.readAllBytes(good))) {
                throw new AssertionError("adversarial reads must not mutate healthy journal evidence");
            }
            checks++;
            return checks;
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void rejects(CoreJournal journal, String expected) throws IOException {
        try {
            journal.readVerified();
            throw new AssertionError("corrupt journal unexpectedly passed: " + expected);
        } catch (IOException exception) {
            if (!exception.getMessage().contains(expected)) throw exception;
        }
    }
}
