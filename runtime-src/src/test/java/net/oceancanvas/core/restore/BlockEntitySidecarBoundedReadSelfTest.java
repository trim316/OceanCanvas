package net.oceancanvas.core.restore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

        Path oversized = Files.createTempFile("oceancanvas-sidecar-oversized", ".bin");
        try (var channel = java.nio.channels.FileChannel.open(oversized, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(BlockEntityBackupContract.MAX_SIDECAR_BYTES + 32L);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] { 1 }));
        }
        boolean refused = false;
        try {
            BlockEntitySidecarStore.readVerified(oversized, "op", new net.oceancanvas.core.pipeline.ChunkKey(0, 0),
                    "0".repeat(64));
        } catch (java.io.IOException expected) { refused = true; }
        require(refused, "oversized sparse sidecar must fail closed before payload parsing");

        Path nonRegular = Files.createTempDirectory("oceancanvas-sidecar-directory");
        refused = false;
        try {
            BlockEntitySidecarStore.readVerified(nonRegular, "op", new net.oceancanvas.core.pipeline.ChunkKey(0, 0),
                    "0".repeat(64));
        } catch (java.io.IOException expected) { refused = true; }
        require(refused && Files.isDirectory(nonRegular),
                "non-regular canonical sidecar authority must fail closed and remain preserved");

        System.out.println("BlockEntitySidecarBoundedReadSelfTest PASS (6 checks)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
