package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Proves a failed preimage fsync cannot publish recovery authority. */
public final class BlockStatePreimageFsyncFailureSelfTest {
    private BlockStatePreimageFsyncFailureSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("BlockStatePreimageFsyncFailureSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-preimage-fsync");
        try {
            Path canonical = dir.resolve("preimage.bin");
            Path stage = dir.resolve("preimage.bin.tmp");
            ChunkKey chunk = new ChunkKey(32, 32);
            int[] states = new int[256];
            Arrays.fill(states, 7);
            BlockStatePreimageStore.Preimage preimage =
                    new BlockStatePreimageStore.Preimage("fsync-preimage", chunk, 0, 0, states);

            boolean rejected = false;
            try {
                BlockStatePreimageStore.writeExact(canonical, preimage,
                        (source, target) -> Files.move(source, target),
                        channel -> { throw new IOException("simulated disk full at preimage fsync"); });
            } catch (IOException expected) {
                rejected = expected.getMessage() != null
                        && expected.getMessage().contains("simulated disk full");
            }
            check(rejected, "injected preimage fsync failure is propagated"); checks++;
            check(!Files.exists(canonical), "failed fsync cannot publish canonical preimage"); checks++;
            check(Files.isRegularFile(stage) && Files.size(stage) > 0,
                    "failed preimage stage is preserved for diagnosis"); checks++;
            byte[] stagedBeforeRetry = Files.readAllBytes(stage);

            boolean retryRejected = false;
            try {
                BlockStatePreimageStore.writeExact(canonical, preimage);
            } catch (IOException expected) {
                retryRejected = true;
            }
            check(retryRejected, "surviving ambiguous preimage stage blocks automatic retry"); checks++;
            check(!Files.exists(canonical), "blocked retry cannot invent canonical preimage authority"); checks++;
            check(Arrays.equals(stagedBeforeRetry, Files.readAllBytes(stage)),
                    "blocked retry preserves staged preimage byte-for-byte"); checks++;
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
