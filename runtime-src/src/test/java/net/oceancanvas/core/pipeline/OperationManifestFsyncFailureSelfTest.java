package net.oceancanvas.core.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Proves a failed manifest fsync cannot publish operation authority. */
public final class OperationManifestFsyncFailureSelfTest {
    private OperationManifestFsyncFailureSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("OperationManifestFsyncFailureSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-manifest-fsync");
        try {
            Path canonical = dir.resolve("operation.properties");
            Path stage = dir.resolve("operation.properties.tmp");
            SingleChunkOperationSpec spec = new SingleChunkOperationSpec(
                    1, new ChunkKey(32, 32), 512, 0, 0, 63, 40, 3);

            boolean rejected = false;
            try {
                OperationManifestStore.ensureExact(canonical, spec,
                        channel -> { throw new IOException("simulated disk full at manifest fsync"); });
            } catch (IOException expected) {
                rejected = expected.getMessage() != null
                        && expected.getMessage().contains("simulated disk full");
            }
            check(rejected, "injected manifest fsync failure is propagated"); checks++;
            check(!Files.exists(canonical), "failed fsync cannot publish canonical manifest"); checks++;
            check(Files.isRegularFile(stage) && Files.size(stage) > 0,
                    "failed manifest stage is preserved for diagnosis"); checks++;
            byte[] stagedBeforeRetry = Files.readAllBytes(stage);

            boolean retryRejected = false;
            try {
                OperationManifestStore.ensureExact(canonical, spec);
            } catch (IOException expected) {
                retryRejected = true;
            }
            check(retryRejected, "surviving ambiguous manifest stage blocks automatic retry"); checks++;
            check(!Files.exists(canonical), "blocked retry still cannot invent canonical authority"); checks++;
            check(Arrays.equals(stagedBeforeRetry, Files.readAllBytes(stage)),
                    "blocked retry preserves original staged evidence byte-for-byte"); checks++;
            return checks;
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
