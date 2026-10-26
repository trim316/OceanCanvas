package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.restore.BlockEntityBackupContract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deterministic regression for bounded post-COMPLETE sidecar hashing. */
public final class PostCompleteRecoveryProofDigestSelfTest {
    private PostCompleteRecoveryProofDigestSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-post-complete-digest");
        try {
            Path ordinary = dir.resolve("ordinary.ocbe");
            byte[] bytes = "bounded-sidecar-evidence".getBytes(StandardCharsets.UTF_8);
            Files.write(ordinary, bytes);
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            require(expected.equals(PostCompleteRecoveryProof.boundedStableSha256(ordinary)),
                    "bounded digest must equal exact file SHA-256");

            Path excessive = dir.resolve("excessive.ocbe");
            long excessiveSize = BlockEntityBackupContract.MAX_SIDECAR_BYTES + 33L;
            try (FileChannel channel = FileChannel.open(excessive,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(excessiveSize - 1L);
                channel.write(ByteBuffer.wrap(new byte[] { 1 }));
            }
            boolean refused = false;
            try {
                PostCompleteRecoveryProof.boundedStableSha256(excessive);
            } catch (IOException expectedFailure) {
                refused = expectedFailure.getMessage().contains("excessive");
            }
            require(refused,
                    "post-COMPLETE digest must refuse a sidecar that grew beyond the canonical bound before reading it");

            Path empty = dir.resolve("empty.ocbe");
            Files.createFile(empty);
            boolean emptyRefused = false;
            try {
                PostCompleteRecoveryProof.boundedStableSha256(empty);
            } catch (IOException expectedFailure) {
                emptyRefused = true;
            }
            require(emptyRefused, "empty sidecar evidence must fail closed");

            Path symlinkTarget = dir.resolve("symlink-target.ocbe");
            byte[] targetBytes = "valid-external-sidecar-bytes".getBytes(StandardCharsets.UTF_8);
            Files.write(symlinkTarget, targetBytes);
            Path symlinkAuthority = dir.resolve("symlink-authority.ocbe");
            boolean symlinkCreated = false;
            try {
                Files.createSymbolicLink(symlinkAuthority, symlinkTarget.getFileName());
                symlinkCreated = true;
            } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
                // Windows runners may deny unprivileged symlink creation. The
                // regression remains deterministic where the filesystem exposes it.
            }
            if (symlinkCreated) {
                boolean symlinkRefused = false;
                try {
                    PostCompleteRecoveryProof.boundedStableSha256(symlinkAuthority);
                } catch (IOException expectedFailure) {
                    symlinkRefused = true;
                }
                require(symlinkRefused,
                        "post-COMPLETE digest must refuse symbolic-link archive authority");
                require(Files.isSymbolicLink(symlinkAuthority),
                        "refusal must preserve the symbolic-link authority path");
                require(java.util.Arrays.equals(targetBytes, Files.readAllBytes(symlinkTarget)),
                        "refusal must preserve external target evidence bytes");
            }

            System.out.println("PostCompleteRecoveryProofDigestSelfTest PASS");
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException ignored) { }
                });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
