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
import java.util.Objects;

/** Immutable authority for one exact bounded square campaign and geometry. */
public final class BoundedCampaignIdentityStore {
    private BoundedCampaignIdentityStore() {}

    public static void ensureExact(Path file, BoundedCampaignPlan plan, CoreConfig core)
            throws IOException {
        ensureExact(file, plan, core, channel -> channel.force(true));
    }

    static void ensureExact(
            Path file,
            BoundedCampaignPlan plan,
            CoreConfig core,
            ForceOperation forceOperation) throws IOException {
        Objects.requireNonNull(forceOperation, "forceOperation");
        byte[] expected = canonicalBytes(plan, core);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path leasePath = file.resolveSibling(file.getFileName().toString() + ".lock");
        if (Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(leasePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("campaign plan lock is not a direct regular file");
        }
        try (FileChannel lockChannel = FileChannel.open(leasePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lease = lockChannel.tryLock()) {
            if (lease == null) throw new IOException("another campaign plan publisher is active");
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            boolean canonicalExists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            boolean stageExists = Files.exists(stage, LinkOption.NOFOLLOW_LINKS);
            if (canonicalExists && stageExists) {
                throw new IOException("ambiguous canonical and staged campaign plans; preserve evidence");
            }
            if (canonicalExists) {
                if (!matchesExactBounded(file, expected)) {
                    throw new IOException("immutable campaign plan changed; refuse additional authoring");
                }
                return;
            }
            if (stageExists) {
                throw new java.nio.file.FileAlreadyExistsException(stage.toString(), null,
                        "surviving staged campaign plan is ambiguous; preserve evidence");
            }
            try (FileChannel channel = FileChannel.open(stage,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(expected);
                while (buffer.hasRemaining()) channel.write(buffer);
                forceOperation.force(channel);
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("campaign plan authority appeared during staged publication; preserve ambiguous authority");
            }
            try {
                Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic campaign plan publication unavailable; stage retained", e);
            }
        } catch (OverlappingFileLockException e) {
            throw new IOException("another campaign plan publisher is active", e);
        }
    }

    static boolean matchesExactBounded(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        byte[] actual = new byte[expected.length];
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long before = channel.size();
            if (before != expected.length) return false;
            ByteBuffer buffer = ByteBuffer.wrap(actual);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) return false;
                if (read == 0) throw new IOException("campaign plan replay made no forward progress");
            }
            ByteBuffer extra = ByteBuffer.allocate(1);
            if (channel.read(extra) != -1) return false;
            long after = channel.size();
            if (before != after || after != expected.length) return false;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] canonicalBytes(BoundedCampaignPlan plan, CoreConfig core)
            throws IOException {
        StringBuilder identity = new StringBuilder("OCEANCANVAS_BOUNDED_CAMPAIGN_PLAN_V1\n")
                .append("sideChunks=").append(plan.sideChunks()).append("\n")
                .append("chunkCount=").append(plan.orderedChunks().size()).append("\n");
        for (int i = 0; i < plan.orderedChunks().size(); i++) {
            var key = plan.orderedChunks().get(i);
            identity.append("chunk").append(i).append("X=").append(key.x()).append("\n");
            identity.append("chunk").append(i).append("Z=").append(key.z()).append("\n");
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
            throw new IOException("SHA-256 unavailable for durable campaign identity", e);
        }
    }

    @FunctionalInterface
    interface ForceOperation {
        void force(FileChannel channel) throws IOException;
    }
}
