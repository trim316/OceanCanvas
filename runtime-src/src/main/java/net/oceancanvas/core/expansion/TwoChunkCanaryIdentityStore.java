package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Independent immutable two-chunk plan authority. Both chunks and all world
 * geometry must match the first published plan on every server restart.
 * A new config cannot silently redirect the unstarted second chunk.
 */
public final class TwoChunkCanaryIdentityStore {
    private TwoChunkCanaryIdentityStore() {}

    public static void ensureExact(Path file, TwoChunkCanaryPlan plan, CoreConfig core) throws IOException {
        ensureExact(file, plan, core, channel -> channel.force(true));
    }

    static void ensureExact(Path file, TwoChunkCanaryPlan plan, CoreConfig core,
                            ForceOperation forceOperation) throws IOException {
        byte[] expected = canonicalBytes(plan, core);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path leasePath = file.resolveSibling(file.getFileName().toString() + ".lock");
        if (Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(leasePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("two-chunk plan lock authority is not a direct regular file; preserve evidence");
        }
        try (FileChannel lockChannel = FileChannel.open(leasePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lease = lockChannel.tryLock()) {
            if (lease == null) throw new IOException("another two-chunk plan publisher is active");
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(stage.toString(), null,
                        "surviving staged two-chunk plan is ambiguous; preserve evidence");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !matchesExactBounded(file, expected)) {
                    throw new IOException("immutable two-chunk plan changed; refuse additional authoring");
                }
                return;
            }
            try (FileChannel channel = FileChannel.open(stage,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(expected);
                while (bytes.hasRemaining()) {
                    if (channel.write(bytes) <= 0) {
                        throw new IOException("two-chunk plan stage write made no progress; preserve evidence");
                    }
                }
                forceOperation.force(channel);
            }
            if (!Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("staged two-chunk plan authority changed before publication; preserve evidence");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("competing two-chunk plan authority appeared during publication; preserve evidence");
            }
            try {
                Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic two-chunk plan publication unavailable; stage retained", e);
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !matchesExactBounded(file, expected)) {
                throw new IOException("published two-chunk plan lost direct exact authority; refuse authoring");
            }
        } catch (OverlappingFileLockException e) {
            throw new IOException("another two-chunk plan publisher is active", e);
        }
    }

    static boolean matchesExactBounded(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        byte[] actual = new byte[expected.length];
        int offset = 0;
        long before;
        long after;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            before = channel.size();
            if (before != expected.length) return false;
            ByteBuffer buffer = ByteBuffer.wrap(actual);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) return false;
                if (read == 0) return false;
                offset += read;
            }
            if (offset != expected.length) return false;
            ByteBuffer trailing = ByteBuffer.allocate(1);
            if (channel.read(trailing) != -1) return false;
            after = channel.size();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        return before == after && after == expected.length
                && MessageDigest.isEqual(expected, actual);
    }

    private static byte[] canonicalBytes(TwoChunkCanaryPlan plan, CoreConfig core) throws IOException {
        String identity = "OCEANCANVAS_TWO_CHUNK_PLAN_V1\n"
                + "firstX=" + plan.first().x() + "\nfirstZ=" + plan.first().z()
                + "\nsecondX=" + plan.second().x() + "\nsecondZ=" + plan.second().z()
                + "\ncanvasSize=" + core.canvasSize()
                + "\ncenterX=" + core.centerX() + "\ncenterZ=" + core.centerZ()
                + "\nwaterSurfaceY=" + core.waterSurfaceY()
                + "\noceanFloorY=" + core.oceanFloorY()
                + "\noceanFloorVariation=" + core.oceanFloorVariation() + "\n";
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            return (identity + "sha256=" + digest + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable for durable two-chunk identity", e);
        }
    }

    @FunctionalInterface
    interface ForceOperation {
        void force(FileChannel channel) throws IOException;
    }
}
