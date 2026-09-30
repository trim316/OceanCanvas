package net.oceancanvas.core.restore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
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
        Objects.requireNonNull(file, "file");
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
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lease = channel.tryLock()) {
            if (lease == null) throw new IOException("block-state registry identity already has a writer");
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            if (Files.exists(file) && Files.exists(stage)) {
                throw new IOException("ambiguous canonical and staged block-state registry identity");
            }
            if (Files.exists(file)) {
                byte[] actual = Files.readAllBytes(file);
                if (!java.security.MessageDigest.isEqual(expected, actual)) {
                    throw new IOException("block-state registry identity changed; refuse runtime-ID preimage replay");
                }
                return;
            }
            Files.write(stage, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel staged = FileChannel.open(stage, StandardOpenOption.WRITE)) {
                staged.force(true);
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
}
