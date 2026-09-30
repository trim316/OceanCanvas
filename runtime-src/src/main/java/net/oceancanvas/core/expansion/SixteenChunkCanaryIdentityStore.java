package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable authority for the exact sixteen-chunk 4x4 canary and geometry. */
public final class SixteenChunkCanaryIdentityStore {
    private SixteenChunkCanaryIdentityStore() {}

    public static void ensureExact(Path file, SixteenChunkCanaryPlan plan, CoreConfig core) throws IOException {
        byte[] expected = canonicalBytes(plan, core);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path leasePath = file.resolveSibling(file.getFileName().toString() + ".lock");
        try (FileChannel lockChannel = FileChannel.open(leasePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lease = lockChannel.tryLock()) {
            if (lease == null) throw new IOException("another sixteen-chunk plan publisher is active");
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            if (Files.exists(file) && Files.exists(stage)) {
                throw new IOException("ambiguous canonical and staged sixteen-chunk plans; preserve evidence");
            }
            if (Files.exists(file)) {
                if (Files.size(file) != expected.length
                        || !MessageDigest.isEqual(expected, Files.readAllBytes(file))) {
                    throw new IOException("immutable sixteen-chunk plan changed; refuse additional authoring");
                }
                return;
            }
            Files.write(stage, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(stage, StandardOpenOption.WRITE)) {
                channel.force(true);
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
}
