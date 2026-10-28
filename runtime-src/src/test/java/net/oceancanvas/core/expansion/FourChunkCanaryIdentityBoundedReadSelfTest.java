package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

            FourChunkCanaryPlan plan = FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32));
            CoreConfig core = CoreConfig.defaults();
            Path symlinkTarget = dir.resolve("external-four-chunk.identity");
            Path symlinkAuthority = dir.resolve("symlink-four-chunk.identity");
            Files.write(symlinkTarget, expected);
            boolean symlinkSupported = true;
            try { Files.createSymbolicLink(symlinkAuthority, symlinkTarget.getFileName()); }
            catch (UnsupportedOperationException | IOException | SecurityException e) { symlinkSupported = false; }
            if (symlinkSupported) {
                require(!FourChunkCanaryIdentityStore.matchesExactBounded(symlinkAuthority, expected), "symlink authority must never satisfy bounded four-chunk identity"); checks++;
                expectIOException(() -> FourChunkCanaryIdentityStore.ensureExact(symlinkAuthority, plan, core));
                require(Files.isSymbolicLink(symlinkAuthority), "symlink authority must survive refusal"); checks++;
                require(Arrays.equals(expected, Files.readAllBytes(symlinkTarget)), "external symlink target bytes must survive refusal"); checks++;

                Files.deleteIfExists(file);
                Path lock = file.resolveSibling(file.getFileName() + ".lock");
                Path externalLock = dir.resolve("external-four-chunk.lock");
                byte[] lockBytes = "external-lock-authority".getBytes(StandardCharsets.UTF_8);
                Files.write(externalLock, lockBytes);
                Files.deleteIfExists(lock);
                Files.createSymbolicLink(lock, externalLock.getFileName());
                expectIOException(() -> FourChunkCanaryIdentityStore.ensureExact(file, plan, core));
                require(Files.isSymbolicLink(lock), "symlink lock authority must survive refusal"); checks++;
                require(Arrays.equals(lockBytes, Files.readAllBytes(externalLock)), "external lock target must remain unchanged"); checks++;
                require(!Files.exists(file, LinkOption.NOFOLLOW_LINKS), "lock refusal must not publish canonical authority"); checks++;
                Files.delete(lock);

                Path stage = file.resolveSibling(file.getFileName() + ".tmp");
                Path externalStage = dir.resolve("external-four-chunk.tmp");
                byte[] stageBytes = "external-stage-authority".getBytes(StandardCharsets.UTF_8);
                Files.write(externalStage, stageBytes);
                Files.deleteIfExists(stage);
                Files.createSymbolicLink(stage, externalStage.getFileName());
                expectIOException(() -> FourChunkCanaryIdentityStore.ensureExact(file, plan, core));
                require(Files.isSymbolicLink(stage), "symlink stage authority must survive refusal"); checks++;
                require(Arrays.equals(stageBytes, Files.readAllBytes(externalStage)), "external stage target must remain unchanged"); checks++;
                require(!Files.exists(file, LinkOption.NOFOLLOW_LINKS), "stage refusal must not publish canonical authority"); checks++;
                Files.delete(stage);
            } else {
                require(!Files.exists(symlinkAuthority, LinkOption.NOFOLLOW_LINKS), "unsupported symlink setup must not create authority"); checks++;
            }

            Files.deleteIfExists(file);
            Path raced = dir.resolve("raced-four-chunk.identity");
            byte[] competing = "competing-four-chunk-authority".getBytes(StandardCharsets.UTF_8);
            expectIOException(() -> FourChunkCanaryIdentityStore.ensureExact(raced, plan, core, channel -> { channel.force(true); Files.write(raced, competing); }));
            Path racedStage = raced.resolveSibling(raced.getFileName().toString() + ".tmp");
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "competing canonical four-chunk authority must be preserved byte-for-byte"); checks++;
            require(Files.isRegularFile(racedStage) && Files.size(racedStage) > 0, "staged four-chunk identity must survive publish-race refusal for forensics"); checks++;
            expectIOException(() -> FourChunkCanaryIdentityStore.ensureExact(raced, plan, core));
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "ambiguous restart must not replace competing canonical four-chunk authority"); checks++;
            require(Files.isRegularFile(racedStage), "ambiguous restart must preserve staged four-chunk identity evidence"); checks++;
            return checks;
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }

    private static void expectIOException(IoAction action) throws Exception { try { action.run(); throw new AssertionError("expected IOException"); } catch (IOException expected) {} }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
