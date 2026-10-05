package net.oceancanvas.core.restore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deterministic regression for bounded streaming archive attestation. */
public final class BlockEntitySidecarArchiveDigestSelfTest {
    private BlockEntitySidecarArchiveDigestSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-sidecar-digest-");
        Path file = dir.resolve("sidecar.bin");
        byte[] bytes = new byte[8192 * 3 + 137];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        Files.write(file, bytes);

        String oracle = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String actual = BlockEntitySidecarArchive.sha256Hex(file);
        if (!oracle.equals(actual)) throw new AssertionError("streaming sidecar digest differs from independent oracle");
        if (!actual.equals(BlockEntitySidecarArchive.sha256Hex(file))) {
            throw new AssertionError("streaming sidecar digest is not deterministic");
        }

        bytes[bytes.length - 1] ^= 0x5a;
        Files.write(file, bytes);
        String changed = BlockEntitySidecarArchive.sha256Hex(file);
        if (actual.equals(changed)) throw new AssertionError("changed sidecar bytes retained old digest");

        System.out.println("BlockEntitySidecarArchiveDigestSelfTest PASS (3 checks)");
    }
}
