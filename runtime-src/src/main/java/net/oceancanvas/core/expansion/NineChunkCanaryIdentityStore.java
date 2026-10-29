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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable authority for the exact nine-chunk 3x3 canary and world geometry. */
public final class NineChunkCanaryIdentityStore {
    private NineChunkCanaryIdentityStore() {}

    public static void ensureExact(Path file, NineChunkCanaryPlan plan, CoreConfig core) throws IOException {
        ensureExact(file, plan, core, channel -> channel.force(true));
    }

    static void ensureExact(Path file, NineChunkCanaryPlan plan, CoreConfig core, ForceOperation forceOperation) throws IOException {
        byte[] expected = canonicalBytes(plan, core);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path leasePath = file.resolveSibling(file.getFileName().toString() + ".lock");
        if (Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(leasePath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("nine-chunk plan lock is not a direct regular file");
        try (FileChannel lockChannel = FileChannel.open(leasePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lease = lockChannel.tryLock()) {
            if (lease == null) throw new IOException("another nine-chunk plan publisher is active");
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("ambiguous canonical and staged nine-chunk plans; preserve evidence");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !matchesExactBounded(file, expected))
                    throw new IOException("immutable nine-chunk plan changed; refuse additional authoring");
                return;
            }
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
                throw new java.nio.file.FileAlreadyExistsException(stage.toString(), null,
                        "surviving staged nine-chunk plan is ambiguous; preserve evidence");
            try (FileChannel channel = FileChannel.open(stage,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(expected);
                while (buffer.hasRemaining()) channel.write(buffer);
                forceOperation.force(channel);
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("competing nine-chunk plan authority appeared during publication; preserve evidence");
            try { Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("atomic nine-chunk plan publication unavailable; stage retained", e); }
        } catch (OverlappingFileLockException e) { throw new IOException("another nine-chunk plan publisher is active", e); }
    }

    static boolean matchesExactBounded(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        byte[] observed = new byte[expected.length];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long beforeSize = channel.size();
            if (beforeSize != expected.length) return false;
            ByteBuffer buffer = ByteBuffer.wrap(observed);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) return false;
                if (read == 0) throw new IOException("nine-chunk plan replay made no forward progress");
            }
            ByteBuffer extra = ByteBuffer.allocate(1);
            if (channel.read(extra) != -1) return false;
            long afterSize = channel.size();
            if (beforeSize != afterSize || afterSize != expected.length) return false;
        }
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && MessageDigest.isEqual(expected, observed);
    }

    private static byte[] canonicalBytes(NineChunkCanaryPlan plan, CoreConfig core) throws IOException {
        var chunks = plan.orderedChunks(); StringBuilder identity = new StringBuilder("OCEANCANVAS_NINE_CHUNK_PLAN_V1\n");
        for (int i = 0; i < chunks.size(); i++) { identity.append("chunk").append(i).append("X=").append(chunks.get(i).x()).append("\n"); identity.append("chunk").append(i).append("Z=").append(chunks.get(i).z()).append("\n"); }
        identity.append("canvasSize=").append(core.canvasSize()).append("\n").append("centerX=").append(core.centerX()).append("\n").append("centerZ=").append(core.centerZ()).append("\n").append("waterSurfaceY=").append(core.waterSurfaceY()).append("\n").append("oceanFloorY=").append(core.oceanFloorY()).append("\n").append("oceanFloorVariation=").append(core.oceanFloorVariation()).append("\n");
        try { String body = identity.toString(); String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); return (body + "sha256=" + digest + "\n").getBytes(StandardCharsets.UTF_8); }
        catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable for durable nine-chunk identity", e); }
    }

    @FunctionalInterface interface ForceOperation { void force(FileChannel channel) throws IOException; }
}