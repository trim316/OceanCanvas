package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.restore.BlockEntityBackupContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic bounded-authority regression for release sidecar hashing. */
public final class ReleaseArchiveBoundedDigestSelfTest {
    private ReleaseArchiveBoundedDigestSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-release-sidecar-digest");
        Path exact = dir.resolve("exact.archive");
        Files.write(exact, new byte[] {1, 2, 3, 4});
        String first = ReleaseArchiveCertificate.stableSha256(exact);
        String second = ReleaseArchiveCertificate.stableSha256(exact);
        if (!first.equals(second) || !first.matches("[0-9a-f]{64}")) throw new AssertionError("exact digest unstable");

        Path directory = dir.resolve("directory.archive");
        Files.createDirectory(directory);
        expectRejected(directory, "non-regular authority");

        Path oversized = dir.resolve("oversized.archive");
        try (var out = Files.newOutputStream(oversized)) {
            out.write(1);
            out.flush();
        }
        try (var channel = java.nio.channels.FileChannel.open(oversized, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(BlockEntityBackupContract.MAX_SIDECAR_BYTES + 32L);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        expectRejected(oversized, "oversized authority");
        if (!Files.exists(directory) || !Files.exists(oversized)) throw new AssertionError("rejected evidence was modified");
        System.out.println("ReleaseArchiveBoundedDigest self-test PASS (5 checks)");
    }

    private static void expectRejected(Path path, String label) throws Exception {
        boolean rejected = false;
        try { ReleaseArchiveCertificate.stableSha256(path); }
        catch (IOException expected) { rejected = true; }
        if (!rejected) throw new AssertionError(label + " accepted");
    }
}
