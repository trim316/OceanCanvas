package net.oceancanvas.core.acceptance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;

/** Regression for direct-file authority in release archive hashing. */
public final class ReleaseArchiveSymlinkDigestSelfTest {
    private ReleaseArchiveSymlinkDigestSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-release-archive-symlink");
        Path target = dir.resolve("target.archive");
        byte[] bytes = new byte[] {7, 4, 2, 9, 1};
        Files.write(target, bytes);
        Path linked = dir.resolve("linked.archive");

        try {
            Files.createSymbolicLink(linked, target.getFileName());
            boolean rejected = false;
            try {
                ReleaseArchiveCertificate.stableSha256(linked);
            } catch (IOException expected) {
                rejected = true;
            }
            if (!rejected) throw new AssertionError("symlink release archive digest authority accepted");
            if (!Files.exists(linked, LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(linked)) {
                throw new AssertionError("symlink release archive authority was replaced");
            }
            if (!Arrays.equals(bytes, Files.readAllBytes(target))) {
                throw new AssertionError("symlink release archive target bytes changed during refusal");
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            // Symbolic links unavailable in this environment.
        } catch (IOException e) {
            if (Files.exists(linked, LinkOption.NOFOLLOW_LINKS)) throw e;
            // Symlink creation itself may be unavailable, notably on unprivileged Windows.
        }

        System.out.println("ReleaseArchiveSymlinkDigestSelfTest PASS");
    }
}
