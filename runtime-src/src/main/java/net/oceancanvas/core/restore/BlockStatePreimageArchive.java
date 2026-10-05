package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Retain the exact recovery snapshot before granting ticket-release credit.
 * A crash between archiving and COMPLETE must preserve a verifiable preimage
 * for post-completion inspection and must be safe to replay idempotently.
 */
public final class BlockStatePreimageArchive {
    private BlockStatePreimageArchive() {}

    /** Returns SHA-256 of the immutable archived bytes after verification. */
    public static String archiveExact(Path live, Path archive, String operationId, ChunkKey chunk)
            throws IOException {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(chunk, "chunk");
        if (live.toAbsolutePath().normalize().equals(archive.toAbsolutePath().normalize())) {
            throw new IOException("live recovery backup and archive cannot share the same path");
        }
        Path parent = archive.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        // Lease coordinates identical/restarted writers without deleting any
        // crash evidence. Keep the lock file itself across restarts.
        Path lockPath = archive.resolveSibling(archive.getFileName().toString() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lease = channel.tryLock()) {
            if (lease == null) throw new IOException("recovery preimage archive already has a writer");
            if (Files.exists(archive)) {
                BlockStatePreimageStore.readVerified(archive, operationId, chunk);
                String archivedHash = BlockStatePreimageStore.sha256Hex(archive);
                if (Files.exists(live)) {
                    BlockStatePreimageStore.readVerified(live, operationId, chunk);
                    String candidateHash = BlockStatePreimageStore.sha256Hex(live);
                    if (!archivedHash.equals(candidateHash)) {
                        throw new IOException("refusing to replace immutable recovery archive with differing preimage");
                    }
                    // Duplicate is now redundant only after the exact archive
                    // has been fully reverified. Archive remains untouched.
                    Files.delete(live);
                }
                return archivedHash;
            }

            BlockStatePreimageStore.readVerified(live, operationId, chunk);
            String originalHash = BlockStatePreimageStore.sha256Hex(live);
            try {
                Files.move(live, archive, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic recovery preimage archival unavailable; live backup retained", e);
            }
            BlockStatePreimageStore.readVerified(archive, operationId, chunk);
            if (!originalHash.equals(BlockStatePreimageStore.sha256Hex(archive))) {
                throw new IOException("recovery preimage changed during archival; refuse completion");
            }
            return originalHash;
        } catch (OverlappingFileLockException e) {
            throw new IOException("recovery preimage archive already has a writer", e);
        }
    }
}
