package net.oceancanvas.core.restore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Immutable identity binding runtime block-state IDs to the recovery operation.
 * Runtime-ID preimages must never be replayed under a different registry mapping.
 */
public final class BlockStateRegistryIdentityStore {
    private static final String MAGIC = "OCEANCANVAS_BLOCK_STATE_REGISTRY_V1\n";

    private BlockStateRegistryIdentityStore() {}

    public static void ensureExact(Path file, String fingerprintSha256, int stateCount)
            throws IOException {
        ensureExact(file, fingerprintSha256, stateCount, channel -> channel.force(true));
    }

    static void ensureExact(
            Path file,
            String fingerprintSha256,
            int stateCount,
            ForceOperation forceOperation) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(forceOperation, "forceOperation");
        if (fingerprintSha256 == null || !fingerprintSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("invalid block-state registry fingerprint");
        }
        if (stateCount <= 0 || stateCount > 1_000_000) {
            throw new IOException("invalid block-state registry state count");
        }
        byte[] expected = (MAGIC
                + "stateCount=" + stateCount + "\n"
                + "sha256=" + fingerprintSha256 + "\n").getBytes(StandardCharsets.UTF_8);

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path lockPath = file.resolveSibling(file.getFileName().toString() + ".lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-state registry identity lock is not a direct regular file");
        }
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lease = channel.tryLock()) {
            if (lease == null) throw new IOException("block-state registry identity already has a writer");
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ambiguous canonical and staged block-state registry identity");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] actual = readExactBounded(file, expected.length);
                if (!java.security.MessageDigest.isEqual(expected, actual)) {
                    throw new IOException("block-state registry identity changed; refuse runtime-ID preimage replay");
                }
                return;
            }
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(stage.toString(), null,
                        "surviving staged block-state registry identity is ambiguous; preserve evidence");
            }
            try (FileChannel staged = FileChannel.open(stage,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(expected);
                while (buffer.hasRemaining()) staged.write(buffer);
                forceOperation.force(staged);
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("block-state registry identity appeared during staged publication; preserve ambiguous authority");
            }
            try {
                Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic block-state registry identity publication unavailable", e);
            }
        } catch (OverlappingFileLockException e) {
            throw new IOException("block-state registry identity already has a writer", e);
        }
    }

    static byte[] readExactBounded(Path file, int expectedLength) throws IOException {
        if (expectedLength < 0) throw new IOException("invalid block-state registry identity length");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-state registry identity is not a direct regular file; refuse runtime-ID preimage replay");
        }
        long sizeBefore = Files.size(file);
        if (sizeBefore != expectedLength) {
            throw new IOException("block-state registry identity length changed; refuse runtime-ID preimage replay");
        }
        byte[] actual;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream in = Channels.newInputStream(channel)) {
            actual = in.readNBytes(expectedLength + 1);
        }
        if (actual.length != expectedLength
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) != sizeBefore) {
            throw new IOException("block-state registry identity changed while reading; refuse runtime-ID preimage replay");
        }
        return actual;
    }

    @FunctionalInterface
    interface ForceOperation {
        void force(FileChannel channel) throws IOException;
    }
}
