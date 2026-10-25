package net.oceancanvas.core.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic regression for bounded immutable operation-authority replay. */
public final class OperationManifestBoundedReadSelfTest {
    private OperationManifestBoundedReadSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-manifest-bound-");
        Path manifest = dir.resolve("operation.properties");
        SingleChunkOperationSpec expected = new SingleChunkOperationSpec(
                1, new ChunkKey(32, 32), 512, 512, 512, 63, -32, 3);

        OperationManifestStore.ensureExact(manifest, expected);
        if (!expected.equals(OperationManifestStore.readVerified(manifest))) {
            throw new AssertionError("canonical bounded manifest replay changed identity");
        }

        byte[] oversized = new byte[OperationManifestStore.MAX_MANIFEST_BYTES + 1];
        oversized[0] = 'x';
        Files.write(manifest, oversized);
        expectRefusal(manifest, "oversized manifest must fail closed");

        Files.writeString(manifest, "schemaVersion=1\n");
        expectRefusal(manifest, "truncated manifest must fail closed");

        System.out.println("OperationManifestBoundedReadSelfTest PASS");
    }

    private static void expectRefusal(Path manifest, String message) throws Exception {
        try {
            OperationManifestStore.readVerified(manifest);
            throw new AssertionError(message);
        } catch (IOException expected) {
            // fail-closed is the contract
        }
    }
}
