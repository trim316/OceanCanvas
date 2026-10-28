package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Retained regression for the immutable sidecar read allocation and authority boundary. */
public final class BlockEntitySidecarBoundedReadSelfTest {
    private BlockEntitySidecarBoundedReadSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/main/java/net/oceancanvas/core/restore/BlockEntitySidecarStore.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        require(!text.contains("Files.readAllBytes(file)"),
                "canonical sidecar paths must not allocate from mutable file length");
        require(text.contains("readNBytes(MAX_FILE_BYTES + 1)"),
                "sidecar read must retain an explicit one-byte-over-bound sentinel");
        require(text.contains("entire.length != observedSize") && text.contains("Files.size(file) != observedSize"),
                "sidecar read must fail closed when file size changes around bounded read");
        require(text.contains("serialize(existing)"),
                "immutable replay comparison must use the already verified canonical envelope");
        require(text.contains("FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)"),
                "verified sidecar bytes must be consumed from direct non-link authority");
        require(!text.contains("Files.write(temp, bytes"),
                "staged sidecar publication must not write through a path-following helper");
        require(text.contains("FileChannel.open(temp, StandardOpenOption.CREATE_NEW")
                        && text.contains("StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS"),
                "staged sidecar bytes must be created and written through direct non-link authority");

        Path oversized = Files.createTempFile("oceancanvas-sidecar-oversized", ".bin");
        try (var channel = java.nio.channels.FileChannel.open(oversized, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(BlockEntityBackupContract.MAX_SIDECAR_BYTES + 32L);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] { 1 }));
        }
        boolean refused = false;
        try {
            BlockEntitySidecarStore.readVerified(oversized, "op", new ChunkKey(0, 0), "0".repeat(64));
        } catch (java.io.IOException expected) { refused = true; }
        require(refused, "oversized sparse sidecar must fail closed before payload parsing");

        Path nonRegular = Files.createTempDirectory("oceancanvas-sidecar-directory");
        refused = false;
        try {
            BlockEntitySidecarStore.readVerified(nonRegular, "op", new ChunkKey(0, 0), "0".repeat(64));
        } catch (java.io.IOException expected) { refused = true; }
        require(refused && Files.isDirectory(nonRegular),
                "non-regular canonical sidecar authority must fail closed and remain preserved");

        Path symlinkDir = Files.createTempDirectory("oceancanvas-sidecar-symlink");
        Path external = symlinkDir.resolve("external.sidecar");
        Path linked = symlinkDir.resolve("linked.sidecar");
        BlockEntityBackupContract.Envelope linkedEnvelope = new BlockEntityBackupContract.Envelope(
                "linked-op", new ChunkKey(7, 9), "b".repeat(64), 1, List.of());
        BlockEntitySidecarStore.writeExact(external, linkedEnvelope);
        byte[] externalBefore = Files.readAllBytes(external);
        boolean symlinkSupported = true;
        try {
            Files.createSymbolicLink(linked, external.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            symlinkSupported = false;
        }
        if (symlinkSupported) {
            refused = false;
            try {
                BlockEntitySidecarStore.readVerified(linked, "linked-op", new ChunkKey(7, 9), "b".repeat(64));
            } catch (java.io.IOException expected) { refused = true; }
            require(refused && Files.isSymbolicLink(linked),
                    "symlink canonical sidecar authority must fail closed even when target bytes are valid");
            require(java.util.Arrays.equals(externalBefore, Files.readAllBytes(external)),
                    "refusing symlink sidecar authority must preserve external target bytes");

            Path lockCanonical = symlinkDir.resolve("lock-test.sidecar");
            Path lockPath = symlinkDir.resolve("lock-test.sidecar.lock");
            Path lockTarget = symlinkDir.resolve("external.lock-target");
            byte[] lockSentinel = "external-lock-authority".getBytes(StandardCharsets.UTF_8);
            Files.write(lockTarget, lockSentinel);
            Files.createSymbolicLink(lockPath, lockTarget.getFileName());
            refused = false;
            try {
                BlockEntitySidecarStore.writeExact(lockCanonical, linkedEnvelope);
            } catch (java.io.IOException expected) { refused = true; }
            require(refused && Files.isSymbolicLink(lockPath) && !Files.exists(lockCanonical),
                    "symlinked sidecar lock authority must fail closed before canonical publication");
            require(java.util.Arrays.equals(lockSentinel, Files.readAllBytes(lockTarget)),
                    "refusing symlinked sidecar lock authority must preserve external target bytes");

            Path stageCanonical = symlinkDir.resolve("stage-test.sidecar");
            Path stagePath = symlinkDir.resolve("stage-test.sidecar.tmp");
            Path stageTarget = symlinkDir.resolve("external.stage-target");
            byte[] stageSentinel = "external-staged-evidence".getBytes(StandardCharsets.UTF_8);
            Files.write(stageTarget, stageSentinel);
            Files.createSymbolicLink(stagePath, stageTarget.getFileName());
            refused = false;
            try {
                BlockEntitySidecarStore.writeExact(stageCanonical, linkedEnvelope);
            } catch (java.io.IOException expected) { refused = true; }
            require(refused && Files.isSymbolicLink(stagePath) && !Files.exists(stageCanonical),
                    "symlinked staged sidecar evidence must fail closed before canonical publication");
            require(java.util.Arrays.equals(stageSentinel, Files.readAllBytes(stageTarget)),
                    "refusing symlinked staged sidecar evidence must preserve external target bytes");
        }

        Path raceDir = Files.createTempDirectory("oceancanvas-sidecar-publish-race");
        Path canonical = raceDir.resolve("block-entities.sidecar");
        Path staged = raceDir.resolve("block-entities.sidecar.tmp");
        byte[] competing = "competing-canonical-authority".getBytes(StandardCharsets.UTF_8);
        BlockEntityBackupContract.Envelope envelope = new BlockEntityBackupContract.Envelope(
                "race-op", new ChunkKey(4, -2), "a".repeat(64), 1, List.of());
        refused = false;
        try {
            BlockEntitySidecarStore.writeExact(canonical, envelope, channel -> {
                channel.force(true);
                Files.write(canonical, competing);
            });
        } catch (java.io.IOException expected) {
            refused = expected.getMessage().contains("competing canonical authority");
        }
        require(refused, "sidecar publication must refuse authority that appears after staged fsync");
        require(java.util.Arrays.equals(competing, Files.readAllBytes(canonical)),
                "competing canonical sidecar authority must remain byte-for-byte preserved");
        require(Files.isRegularFile(staged) && Files.size(staged) > 0,
                "staged sidecar candidate must survive refused publication for forensic recovery");
        boolean restartRefused = false;
        try {
            BlockEntitySidecarStore.writeExact(canonical, envelope);
        } catch (java.io.IOException expected) { restartRefused = true; }
        require(restartRefused
                        && java.util.Arrays.equals(competing, Files.readAllBytes(canonical))
                        && Files.isRegularFile(staged),
                "restart must remain fail-closed without replacing competing or staged sidecar evidence");

        System.out.println("BlockEntitySidecarBoundedReadSelfTest PASS (19 checks)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
