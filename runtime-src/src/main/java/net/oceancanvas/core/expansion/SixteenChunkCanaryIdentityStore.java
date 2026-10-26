package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;

import java.io.IOException;
import java.io.InputStream;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable authority for the exact sixteen-chunk 4x4 canary and geometry. */
public final class SixteenChunkCanaryIdentityStore {
    private SixteenChunkCanaryIdentityStore() {}

    public static void ensureExact(Path file, SixteenChunkCanaryPlan plan, CoreConfig core) throws IOException {
        ensureExact(file, plan, core, channel -> channel.force(true));
    }

    static void ensureExact(Path file, SixteenChunkCanaryPlan plan, CoreConfig core, ForceOperation forceOperation) throws IOException {
        byte[] expected = canonicalBytes(plan, core);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path leasePath = file.resolveSibling(file.getFileName().toString() + ".lock");
        try (FileChannel lockChannel = FileChannel.open(leasePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lease = lockChannel.tryLock()) {
            if (lease == null) throw new IOException("another sixteen-chunk plan publisher is active");
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ambiguous canonical and staged sixteen-chunk plans; preserve evidence");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !matchesExactBounded(file, expected)) {
                    throw new IOException("immutable sixteen-chunk plan changed; refuse additional authoring");
                }
                return;
            }
            Files.write(stage, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(stage, StandardOpenOption.WRITE)) {
                forceOperation.force(channel);
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("competing sixteen-chunk plan authority appeared during publication; preserve evidence");
            }
            try {
                Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic sixteen-chunk plan publication unavailable; stage retained", e);
            }
        } catch (OverlappingFileLockException e) {
            throw new IOException("another sixteen-chunk plan publisher is active", e);
        }
    }

    static boolean matchesExactBounded(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        long beforeSize = Files.size(file);
        if (beforeSize != expected.length) return false;

        byte[] observed = new byte[expected.length];
        int offset = 0;
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            while (offset < observed.length) {
                int read = in.read(observed, offset, observed.length - offset);
                if (read < 0) return false;
                if (read == 0) continue;
                offset += read;
            }
            if (in.read() != -1) return false;
        }
        long afterSize = Files.size(file);
        return afterSize == beforeSize && MessageDigest.isEqual(expected, observed);
    }

    private static byte[] canonicalBytes(SixteenChunkCanaryPlan plan, CoreConfig core) throws IOException {
        var chunks = plan.orderedChunks();
        StringBuilder identity = new StringBuilder("OCEANCANVAS_SIXTEEN_CHUNK_PLAN_V1\n");
        for (int i = 0; i < chunks.size(); i++) {
            identity.append("chunk").append(i).append("X=").append(chunks.get(i).x()).append("\n");
            identity.append("chunk").append(i).append("Z=").append(chunks.get(i).z()).append("\n");
        }
        identity.append("canvasSize=").append(core.canvasSize()).append("\n")
                .append("centerX=").append(core.centerX()).append("\n")
                .append("centerZ=").append(core.centerZ()).append("\n")
                .append("waterSurfaceY=").append(core.waterSurfaceY()).append("\n")
                .append("oceanFloorY=").append(core.oceanFloorY()).append("\n")
                .append("oceanFloorVariation=").append(core.oceanFloorVariation()).append("\n");
        try {
            String body = identity.toString();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
            return (body + "sha256=" + digest + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable for durable sixteen-chunk identity", e);
        }
    }

    @FunctionalInterface
    interface ForceOperation {
        void force(FileChannel channel) throws IOException;
    }
}
