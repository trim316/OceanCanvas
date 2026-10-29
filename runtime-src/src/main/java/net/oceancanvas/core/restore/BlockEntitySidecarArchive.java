package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable post-completion archival for the block-entity recovery sidecar.
 * Mirrors block-state preimage archival without granting runtime mutation authority.
 */
public final class BlockEntitySidecarArchive {
    private static final int DIGEST_BUFFER_BYTES = 8192;

    private BlockEntitySidecarArchive() {}

    public static String archiveExact(Path live, Path archive, String operationId,
            ChunkKey chunk, String sourcePreimageSha256) throws IOException {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(sourcePreimageSha256, "sourcePreimageSha256");
        if (live.toAbsolutePath().normalize().equals(archive.toAbsolutePath().normalize())) {
            throw new IOException("live block-entity sidecar and archive cannot share the same path");
        }
        Path parent = archive.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path lockPath = archive.resolveSibling(archive.getFileName().toString() + ".lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-entity sidecar archive lock is not a direct regular file");
        }
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lease = channel.tryLock()) {
            if (lease == null) throw new IOException("block-entity sidecar archive already has a writer");
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                BlockEntitySidecarStore.readVerified(
                        archive, operationId, chunk, sourcePreimageSha256);
                String archivedHash = sha256Hex(archive);
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    BlockEntitySidecarStore.readVerified(
                            live, operationId, chunk, sourcePreimageSha256);
                    String candidate = sha256Hex(live);
                    if (!archivedHash.equals(candidate)) {
                        throw new IOException("refusing to replace immutable block-entity archive with differing sidecar");
                    }
                    if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("live block-entity sidecar authority changed before cleanup");
                    }
                    Files.delete(live);
                }
                return archivedHash;
            }

            BlockEntitySidecarStore.readVerified(
                    live, operationId, chunk, sourcePreimageSha256);
            String originalHash = sha256Hex(live);
            if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("block-entity sidecar archive authority changed before publication");
            }
            try {
                Files.move(live, archive, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic block-entity sidecar archival unavailable; live sidecar retained", e);
            }
            BlockEntitySidecarStore.readVerified(
                    archive, operationId, chunk, sourcePreimageSha256);
            if (!originalHash.equals(sha256Hex(archive))) {
                throw new IOException("block-entity sidecar changed during archival; refuse completion");
            }
            return originalHash;
        } catch (OverlappingFileLockException e) {
            throw new IOException("block-entity sidecar archive already has a writer", e);
        }
    }

    static String sha256Hex(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-entity sidecar archive digest authority is not a direct regular file");
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable for block-entity sidecar archive", e);
        }
        long observed = 0L;
        byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream in = Channels.newInputStream(channel)) {
            long expectedSize = channel.size();
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read == 0) continue;
                observed = Math.addExact(observed, read);
                digest.update(buffer, 0, read);
            }
            if (observed != expectedSize || channel.size() != expectedSize) {
                throw new IOException("block-entity sidecar archive changed while hashing");
            }
        } catch (ArithmeticException e) {
            throw new IOException("block-entity sidecar archive byte count overflow", e);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-entity sidecar archive digest authority changed while hashing");
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
