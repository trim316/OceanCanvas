package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.ChunkKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Deterministic regression for bounded immutable nine-chunk identity replay and publication races. */
public final class NineChunkCanaryIdentityBoundedReadSelfTest {
    private NineChunkCanaryIdentityBoundedReadSelfTest() {}
    public static void main(String[] args) throws Exception { int checks = run(); System.out.println("NineChunkCanaryIdentityBoundedReadSelfTest PASS (" + checks + " checks)"); }
    public static int run() throws Exception {
        int checks = 0; Path dir = Files.createTempDirectory("oceancanvas-nine-chunk-identity-read");
        try {
            Path file = dir.resolve("nine-chunk.identity"); byte[] expected = "canonical-nine-chunk-identity".getBytes(StandardCharsets.UTF_8); Files.write(file, expected);
            require(NineChunkCanaryIdentityStore.matchesExactBounded(file, expected), "exact immutable identity must match"); checks++;
            byte[] changed = expected.clone(); changed[changed.length / 2] ^= 1; Files.write(file, changed, StandardOpenOption.TRUNCATE_EXISTING);
            require(!NineChunkCanaryIdentityStore.matchesExactBounded(file, expected), "same-length changed identity must fail closed"); checks++;
            Files.write(file, expected, StandardOpenOption.TRUNCATE_EXISTING); Files.write(file, new byte[]{0}, StandardOpenOption.APPEND);
            require(!NineChunkCanaryIdentityStore.matchesExactBounded(file, expected), "oversized identity must fail before replay acceptance"); checks++;
            Files.write(file, Arrays.copyOf(expected, expected.length - 1), StandardOpenOption.TRUNCATE_EXISTING);
            require(!NineChunkCanaryIdentityStore.matchesExactBounded(file, expected), "truncated identity must fail closed"); checks++;
            Files.delete(file);

            Path external = dir.resolve("external-nine-chunk.identity");
            Files.write(external, expected);
            Path linked = dir.resolve("linked-nine-chunk.identity");
            boolean symlinkSupported = true;
            try { Files.createSymbolicLink(linked, external); }
            catch (UnsupportedOperationException | IOException | SecurityException unsupported) { symlinkSupported = false; }
            if (symlinkSupported) {
                require(!NineChunkCanaryIdentityStore.matchesExactBounded(linked, expected), "symlink identity authority must fail closed even when target bytes match"); checks++;
                require(Files.isSymbolicLink(linked), "symlink identity authority must survive refusal"); checks++;
                require(Arrays.equals(expected, Files.readAllBytes(external)), "external symlink target bytes must survive refusal unchanged"); checks++;
            }

            Path raced = dir.resolve("raced-nine-chunk.identity"); byte[] competing = "competing-nine-chunk-authority".getBytes(StandardCharsets.UTF_8);
            NineChunkCanaryPlan plan = NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32)); CoreConfig core = CoreConfig.defaults();
            expectIOException(() -> NineChunkCanaryIdentityStore.ensureExact(raced, plan, core, channel -> { channel.force(true); Files.write(raced, competing); }));
            Path stage = raced.resolveSibling(raced.getFileName().toString() + ".tmp");
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "competing canonical nine-chunk authority must be preserved byte-for-byte"); checks++;
            require(Files.isRegularFile(stage) && Files.size(stage) > 0, "staged nine-chunk identity must survive publish-race refusal for forensics"); checks++;
            expectIOException(() -> NineChunkCanaryIdentityStore.ensureExact(raced, plan, core));
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "ambiguous restart must not replace competing canonical nine-chunk authority"); checks++;
            require(Files.isRegularFile(stage), "ambiguous restart must preserve staged nine-chunk identity evidence"); checks++;

            if (symlinkSupported) {
                Path lockGuarded = dir.resolve("lock-guarded-nine-chunk.identity");
                Path lock = lockGuarded.resolveSibling(lockGuarded.getFileName().toString() + ".lock");
                Path externalLock = dir.resolve("external-nine-chunk.lock"); byte[] externalLockBytes = "external-lock-authority".getBytes(StandardCharsets.UTF_8); Files.write(externalLock, externalLockBytes);
                Files.createSymbolicLink(lock, externalLock);
                expectIOException(() -> NineChunkCanaryIdentityStore.ensureExact(lockGuarded, plan, core));
                require(Files.isSymbolicLink(lock), "symlinked nine-chunk lock must survive refusal"); checks++;
                require(Arrays.equals(externalLockBytes, Files.readAllBytes(externalLock)), "external nine-chunk lock target must remain unchanged"); checks++;
                require(!Files.exists(lockGuarded), "symlinked lock refusal must not publish canonical nine-chunk authority"); checks++;

                Path stageGuarded = dir.resolve("stage-guarded-nine-chunk.identity");
                Path guardedStage = stageGuarded.resolveSibling(stageGuarded.getFileName().toString() + ".tmp");
                Path externalStage = dir.resolve("external-nine-chunk.stage"); byte[] externalStageBytes = "external-stage-authority".getBytes(StandardCharsets.UTF_8); Files.write(externalStage, externalStageBytes);
                Files.createSymbolicLink(guardedStage, externalStage);
                expectIOException(() -> NineChunkCanaryIdentityStore.ensureExact(stageGuarded, plan, core));
                require(Files.isSymbolicLink(guardedStage), "symlinked nine-chunk stage must survive refusal"); checks++;
                require(Arrays.equals(externalStageBytes, Files.readAllBytes(externalStage)), "external nine-chunk stage target must remain unchanged"); checks++;
                require(!Files.exists(stageGuarded), "symlinked stage refusal must not publish canonical nine-chunk authority"); checks++;
            }
            return checks;
        } finally { try (var walk = Files.walk(dir)) { walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); } }
    }
    private static void expectIOException(IoAction action) throws Exception { try { action.run(); throw new AssertionError("expected IOException"); } catch (IOException expected) {} }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}