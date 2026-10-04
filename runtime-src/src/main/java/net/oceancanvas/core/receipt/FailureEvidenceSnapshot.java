package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Best-effort immutable copy of bounded recovery evidence after a journaled runtime failure.
 *
 * <p>This store is forensic only. It never changes pipeline authority, never deletes or
 * replaces source evidence, and never overwrites an already-preserved snapshot. Each copy
 * is accepted only if the source was stable across a before/copy/after digest check and the
 * copied bytes have the same SHA-256. Canonical publication uses create-new hard-link
 * semantics so a pre-existing failure snapshot can never be replaced by a racing writer.
 * Unsupported publication leaves prior evidence untouched and is reported as a snapshot
 * failure rather than weakening durability.</p>
 */
public final class FailureEvidenceSnapshot {
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    private static final List<String> EVIDENCE_FILES = List.of(
            "operation.properties",
            "transitions.journal",
            "runtime-receipts.log",
            "acceptance-state.properties",
            "block-state-registry.identity",
            "preimage-blockstates.bin",
            "preimage-blockentities.ocbe");

    private FailureEvidenceSnapshot() {}

    public record Result(
            Path directory,
            int copied,
            int alreadyPreserved,
            int absent,
            int failed,
            List<String> failures) {
        public Result {
            Objects.requireNonNull(directory, "directory");
            if (copied < 0 || alreadyPreserved < 0 || absent < 0 || failed < 0) {
                throw new IllegalArgumentException("negative failure snapshot count");
            }
            failures = List.copyOf(failures);
            if (failed != failures.size()) {
                throw new IllegalArgumentException("failure snapshot count/detail mismatch");
            }
        }

        public boolean preservedAnything() {
            return copied + alreadyPreserved > 0;
        }
    }

    /**
     * Capture every present bounded evidence file independently. A failure copying one file
     * does not suppress attempts for the remaining files. Synchronization prevents two
     * in-process failure paths from competing for the same immutable namespace.
     */
    public static synchronized Result captureBestEffort(Path operationRoot, ChunkStage failingStage) {
        Objects.requireNonNull(operationRoot, "operationRoot");
        Objects.requireNonNull(failingStage, "failingStage");
        if (failingStage == ChunkStage.COMPLETE || failingStage == ChunkStage.FAILED) {
            throw new IllegalArgumentException("failure snapshot requires the attempted non-terminal stage");
        }

        Path destination = operationRoot.resolve("failure-evidence")
                .resolve("stage-" + failingStage.name().toLowerCase(Locale.ROOT));
        int copied = 0;
        int already = 0;
        int absent = 0;
        ArrayList<String> failures = new ArrayList<>();

        try {
            Files.createDirectories(destination);
        } catch (Throwable t) {
            failures.add("snapshot-directory:" + t.getClass().getSimpleName());
            return new Result(destination, 0, 0, 0, failures.size(), failures);
        }

        for (String name : EVIDENCE_FILES) {
            Path source = operationRoot.resolve(name);
            if (!Files.exists(source)) {
                absent++;
                continue;
            }
            try {
                CopyOutcome outcome = copyStableImmutable(source, destination.resolve(name + ".snapshot"));
                if (outcome == CopyOutcome.COPIED) copied++;
                else already++;
            } catch (Throwable t) {
                failures.add(name + ":" + t.getClass().getSimpleName());
            }
        }
        return new Result(destination, copied, already, absent, failures.size(), failures);
    }

    private enum CopyOutcome { COPIED, ALREADY_PRESERVED }

    private static CopyOutcome copyStableImmutable(Path source, Path destination) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("source evidence is not a regular file");
        }
        long sourceSize = Files.size(source);
        if (sourceSize > MAX_FILE_BYTES) {
            throw new IOException("source evidence exceeds bounded snapshot size");
        }
        String sourceBefore = sha256Bounded(source, sourceSize);

        if (Files.exists(destination)) {
            return verifyExisting(destination, sourceSize, sourceBefore);
        }

        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
        if (Files.exists(temp)) {
            // A previous interrupted publication is evidence, not permission to truncate it.
            throw new IOException("staged failure snapshot already exists");
        }

        boolean canonicalPublished = false;
        try {
            Files.copy(source, temp);
            if (!Files.isRegularFile(temp) || Files.size(temp) != sourceSize) {
                throw new IOException("failure snapshot copy size mismatch");
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            String copiedSha = sha256Bounded(temp, sourceSize);
            String sourceAfter = sha256Bounded(source, sourceSize);
            if (Files.size(source) != sourceSize
                    || !sourceBefore.equals(sourceAfter)
                    || !sourceBefore.equals(copiedSha)) {
                throw new IOException("source evidence changed during failure snapshot copy");
            }
            try {
                // createLink is an atomic create-new namespace operation: it cannot
                // replace an existing canonical snapshot. The staged inode is already
                // forced, and removing the temporary name cannot remove the new link.
                Files.createLink(destination, temp);
                canonicalPublished = true;
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
                return CopyOutcome.COPIED;
            } catch (FileAlreadyExistsException race) {
                return verifyExisting(destination, sourceSize, sourceBefore);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("create-new failure snapshot publication unavailable", unsupported);
            }
        } finally {
            if (!canonicalPublished) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    private static CopyOutcome verifyExisting(Path destination, long expectedSize, String expectedSha)
            throws IOException {
        if (!Files.isRegularFile(destination)
                || Files.size(destination) != expectedSize
                || !expectedSha.equals(sha256Bounded(destination, expectedSize))) {
            throw new IOException("existing immutable failure snapshot differs from current evidence");
        }
        return CopyOutcome.ALREADY_PRESERVED;
    }

    private static String sha256Bounded(Path path, long expectedSize) throws IOException {
        if (expectedSize < 0 || expectedSize > MAX_FILE_BYTES) {
            throw new IOException("evidence size outside failure snapshot bound");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long count = 0;
            try (InputStream in = Files.newInputStream(path)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    count += read;
                    if (count > expectedSize || count > MAX_FILE_BYTES) {
                        throw new IOException("evidence grew while hashing failure snapshot");
                    }
                    digest.update(buffer, 0, read);
                }
            }
            if (count != expectedSize) {
                throw new IOException("evidence size changed while hashing failure snapshot");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot hash failure snapshot evidence", e);
        }
    }
}
