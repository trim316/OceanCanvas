package net.oceancanvas.core.journal;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.CRC32;

/** Adversarial bounded-journal checks run from CoreSelfTest on every hosted build. */
public final class CoreJournalBoundedReadSelfTest {
    private CoreJournalBoundedReadSelfTest() {}

    public static int run() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-bounded-journal");
        int checks = 0;
        try {
            Path good = dir.resolve("good.journal");
            CoreJournal journal = new CoreJournal(good);
            String detail = "saved ✓";
            journal.append(new JournalEntry(0L, 1L, new ChunkKey(7, -3),
                    ChunkStage.DISCOVERED, ChunkStage.LOADED, 1L, 1L, detail));
            if (!detail.equals(journal.readVerified().get(0).reason())) {
                throw new AssertionError("bounded streaming replay must retain valid UTF-8");
            }
            checks++;
            byte[] original = Files.readAllBytes(good);

            Path nonRegular = dir.resolve("directory-as-journal");
            Files.createDirectory(nonRegular);
            rejects(new CoreJournal(nonRegular), "journal path is not a direct regular file");
            checks++;
            if (!Files.isDirectory(nonRegular)) {
                throw new AssertionError("non-regular journal evidence must remain untouched");
            }
            checks++;

            Path symlinkTarget = dir.resolve("symlink-target.journal");
            Files.write(symlinkTarget, original);
            Path symlinkJournal = dir.resolve("symlink.journal");
            try {
                Files.createSymbolicLink(symlinkJournal, symlinkTarget.getFileName());
                rejects(new CoreJournal(symlinkJournal), "journal path is not a direct regular file");
                checks++;
                boolean appendRefused = false;
                try {
                    new CoreJournal(symlinkJournal).append(new JournalEntry(1L, 2L,
                            new ChunkKey(7, -3), ChunkStage.LOADED,
                            ChunkStage.PREIMAGE_CAPTURED, 1L, 2L, "symlink append"));
                } catch (IOException expected) {
                    appendRefused = expected.getMessage().contains("journal path is not a direct regular file");
                }
                if (!appendRefused) {
                    throw new AssertionError("journal append through symlink authority must fail closed");
                }
                checks++;
                if (!Files.isSymbolicLink(symlinkJournal)) {
                    throw new AssertionError("symlink journal evidence must survive refusal");
                }
                checks++;
                if (!Arrays.equals(original, Files.readAllBytes(symlinkTarget))) {
                    throw new AssertionError("symlink journal target bytes must survive refusal");
                }
                checks++;
            } catch (UnsupportedOperationException | SecurityException e) {
                // Symbolic links unavailable in this environment.
            } catch (IOException e) {
                if (Files.exists(symlinkJournal, LinkOption.NOFOLLOW_LINKS)) throw e;
                // Symlink creation itself may be unavailable, notably on unprivileged Windows.
            }

            Path zero = dir.resolve("zero-byte.journal");
            Files.createFile(zero);
            rejects(new CoreJournal(zero), "zero-byte evidence");
            checks++;
            if (Files.size(zero) != 0L) {
                throw new AssertionError("zero-byte journal evidence must remain untouched");
            }
            checks++;

            Path invalidUtf8 = dir.resolve("invalid-utf8.journal");
            byte[] invalidPrefix = "0\t1\t7\t-3\tDISCOVERED\tLOADED\t1\t1\t".getBytes(StandardCharsets.UTF_8);
            byte[] invalidLine = Arrays.copyOf(invalidPrefix, invalidPrefix.length + 4);
            invalidLine[invalidPrefix.length] = (byte) 0xC3;
            invalidLine[invalidPrefix.length + 1] = (byte) '\t';
            invalidLine[invalidPrefix.length + 2] = (byte) '0';
            invalidLine[invalidPrefix.length + 3] = (byte) '\n';
            Files.write(invalidUtf8, invalidLine);
            byte[] invalidOriginal = Files.readAllBytes(invalidUtf8);
            rejects(new CoreJournal(invalidUtf8), "malformed UTF-8");
            checks++;
            if (!Arrays.equals(invalidOriginal, Files.readAllBytes(invalidUtf8))) {
                throw new AssertionError("malformed UTF-8 evidence must remain byte-for-byte intact");
            }
            checks++;

            Path extraField = dir.resolve("extra-field.journal");
            String extraPayload = "0\t1\t7\t-3\tDISCOVERED\tLOADED\t1\t1\treason\textra";
            CRC32 extraCrc = new CRC32();
            extraCrc.update(extraPayload.getBytes(StandardCharsets.UTF_8));
            Files.writeString(extraField,
                    extraPayload + "\t" + Long.toUnsignedString(extraCrc.getValue()) + "\n",
                    StandardCharsets.UTF_8);
            byte[] extraOriginal = Files.readAllBytes(extraField);
            rejects(new CoreJournal(extraField), "has 10 fields");
            checks++;
            if (!Arrays.equals(extraOriginal, Files.readAllBytes(extraField))) {
                throw new AssertionError("extraneous-field evidence must remain byte-for-byte intact");
            }
            checks++;

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
            longLine[longLine.length - 1] = (byte) '\n';
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
