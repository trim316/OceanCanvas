package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("recovery preimage archive lock is not a direct regular file");
        }
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lease = channel.tryLock()) {
            if (lease == null) throw new IOException("recovery preimage archive already has a writer");
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectRegular(archive, "archive");
                BlockStatePreimageStore.readVerified(archive, operationId, chunk);
                String archivedHash = BlockStatePreimageStore.sha256Hex(archive);
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    requireDirectRegular(live, "live backup");
                    BlockStatePreimageStore.readVerified(live, operationId, chunk);
                    String candidateHash = BlockStatePreimageStore.sha256Hex(live);
                    if (!archivedHash.equals(candidateHash)) {
                        throw new IOException("refusing to replace immutable recovery archive with differing preimage");
                    }
                    // Duplicate is now redundant only after the exact archive
                    // has been fully reverified. Recheck direct authority before
                    // deleting the redundant live path; archive remains untouched.
                    requireDirectRegular(archive, "archive");
                    requireDirectRegular(live, "live backup");
                    Files.delete(live);
                }
                return archivedHash;
            }

            requireDirectRegular(live, "live backup");
            BlockStatePreimageStore.readVerified(live, operationId, chunk);
            String originalHash = BlockStatePreimageStore.sha256Hex(live);
            // Bind the path-level atomic move to a fresh direct-file check after
            // verification. Any link/directory replacement observed here refuses
            // completion rather than redirecting archival authority.
            requireDirectRegular(live, "live backup");
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("recovery preimage archive appeared during verification; preserve ambiguous authority");
            }
            try {
                Files.move(live, archive, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic recovery preimage archival unavailable; live backup retained", e);
            }
            requireDirectRegular(archive, "archive");
            BlockStatePreimageStore.readVerified(archive, operationId, chunk);
            if (!originalHash.equals(BlockStatePreimageStore.sha256Hex(archive))) {
                throw new IOException("recovery preimage changed during archival; refuse completion");
            }
            return originalHash;
        } catch (OverlappingFileLockException e) {
            throw new IOException("recovery preimage archive already has a writer", e);
        }
    }

    private static void requireDirectRegular(Path path, String role) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("recovery preimage " + role + " is not a direct regular file");
        }
    }
}
