package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Deterministic regression for bounded immutable four-chunk identity replay and publication races. */
public final class FourChunkCanaryIdentityBoundedReadSelfTest {
    private FourChunkCanaryIdentityBoundedReadSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("FourChunkCanaryIdentityBoundedReadSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-four-chunk-identity-read");
        try {
            Path file = dir.resolve("four-chunk.identity");
            byte[] expected = "canonical-four-chunk-identity".getBytes(StandardCharsets.UTF_8);
            Files.write(file, expected);
            require(FourChunkCanaryIdentityStore.matchesExactBounded(file, expected), "exact immutable identity must match"); checks++;
            byte[] changed = expected.clone(); changed[changed.length / 2] ^= 1;
            Files.write(file, changed, StandardOpenOption.TRUNCATE_EXISTING);
            require(!FourChunkCanaryIdentityStore.matchesExactBounded(file, expected), "same-length changed identity must fail closed"); checks++;
            Files.write(file, expected, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(file, new byte[]{0}, StandardOpenOption.APPEND);
            require(!FourChunkCanaryIdentityStore.matchesExactBounded(file, expected), "oversized identity must fail before replay acceptance"); checks++;
            Files.write(file, Arrays.copyOf(expected, expected.length - 1), StandardOpenOption.TRUNCATE_EXISTING);
            require(!FourChunkCanaryIdentityStore.matchesExactBounded(file, expected), "truncated identity must fail closed"); checks++;

            Files.delete(file);
            Path raced = dir.resolve("raced-four-chunk.identity");
            byte[] competing = "competing-four-chunk-authority".getBytes(StandardCharsets.UTF_8);
            FourChunkCanaryPlan plan = FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32));
            CoreConfig core = CoreConfig.defaults();
            expectIOException(() -> FourChunkCanaryIdentityStore.ensureExact(raced, plan, core, channel -> {
                channel.force(true);
                Files.write(raced, competing);
            }));
            Path racedStage = raced.resolveSibling(raced.getFileName().toString() + ".tmp");
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "competing canonical four-chunk authority must be preserved byte-for-byte"); checks++;
            require(Files.isRegularFile(racedStage) && Files.size(racedStage) > 0, "staged four-chunk identity must survive publish-race refusal for forensics"); checks++;
            expectIOException(() -> FourChunkCanaryIdentityStore.ensureExact(raced, plan, core));
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "ambiguous restart must not replace competing canonical four-chunk authority"); checks++;
            require(Files.isRegularFile(racedStage), "ambiguous restart must preserve staged four-chunk identity evidence"); checks++;
            return checks;
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void expectIOException(IoAction action) throws Exception {
        try { action.run(); throw new AssertionError("expected IOException"); }
        catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
