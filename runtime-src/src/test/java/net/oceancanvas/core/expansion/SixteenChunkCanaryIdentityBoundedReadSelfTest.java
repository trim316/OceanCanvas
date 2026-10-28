package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.ChunkKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Deterministic regression for bounded immutable sixteen-chunk identity replay and publication races. */
public final class SixteenChunkCanaryIdentityBoundedReadSelfTest {
    private SixteenChunkCanaryIdentityBoundedReadSelfTest() {}
    public static void main(String[] args) throws Exception { int checks = run(); System.out.println("SixteenChunkCanaryIdentityBoundedReadSelfTest PASS (" + checks + " checks)"); }
    public static int run() throws Exception {
        int checks = 0; Path dir = Files.createTempDirectory("oceancanvas-sixteen-chunk-identity-read");
        try {
            Path file = dir.resolve("sixteen-chunk.identity"); byte[] expected = "canonical-sixteen-chunk-identity".getBytes(StandardCharsets.UTF_8); Files.write(file, expected);
            require(SixteenChunkCanaryIdentityStore.matchesExactBounded(file, expected), "exact immutable identity must match"); checks++;
            byte[] changed = expected.clone(); changed[changed.length / 2] ^= 1; Files.write(file, changed, StandardOpenOption.TRUNCATE_EXISTING);
            require(!SixteenChunkCanaryIdentityStore.matchesExactBounded(file, expected), "same-length changed identity must fail closed"); checks++;
            Files.write(file, expected, StandardOpenOption.TRUNCATE_EXISTING); Files.write(file, new byte[]{0}, StandardOpenOption.APPEND);
            require(!SixteenChunkCanaryIdentityStore.matchesExactBounded(file, expected), "oversized identity must fail before replay acceptance"); checks++;
            Files.write(file, Arrays.copyOf(expected, expected.length - 1), StandardOpenOption.TRUNCATE_EXISTING);
            require(!SixteenChunkCanaryIdentityStore.matchesExactBounded(file, expected), "truncated identity must fail closed"); checks++;
            Files.delete(file);
            Path external = dir.resolve("external-sixteen-chunk.identity"); Files.write(external, expected);
            Path linked = dir.resolve("linked-sixteen-chunk.identity");
            try {
                Files.createSymbolicLink(linked, external.getFileName());
                require(!SixteenChunkCanaryIdentityStore.matchesExactBounded(linked, expected), "symlink sixteen-chunk authority must fail closed"); checks++;
                expectIOException(() -> SixteenChunkCanaryIdentityStore.ensureExact(linked, SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32)), CoreConfig.defaults()));
                require(Files.isSymbolicLink(linked), "symlink sixteen-chunk authority must survive refusal"); checks++;
                require(Arrays.equals(expected, Files.readAllBytes(external)), "external sixteen-chunk target bytes must survive refusal"); checks++;
            } catch (UnsupportedOperationException | SecurityException e) {
                // Filesystem does not support symbolic links in this environment; other bounded-read checks still run.
            } catch (IOException e) {
                if (Files.exists(linked)) throw e;
                // Symlink creation itself may be unavailable (notably unprivileged Windows runners).
            }
            SixteenChunkCanaryPlan plan = SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32)); CoreConfig core = CoreConfig.defaults();
            Path publish = dir.resolve("publish-sixteen-chunk.identity");
            Path lock = publish.resolveSibling(publish.getFileName().toString() + ".lock");
            Path externalLock = dir.resolve("external-sixteen-lock"); Files.writeString(externalLock, "external-lock");
            try {
                Files.createSymbolicLink(lock, externalLock.getFileName());
                expectIOException(() -> SixteenChunkCanaryIdentityStore.ensureExact(publish, plan, core));
                require(Files.isSymbolicLink(lock), "symlink sixteen-chunk lock must survive refusal"); checks++;
                require("external-lock".equals(Files.readString(externalLock)), "external sixteen-chunk lock target must remain unchanged"); checks++;
                require(!Files.exists(publish), "symlink lock refusal must not publish canonical sixteen-chunk authority"); checks++;
                Files.delete(lock);
            } catch (UnsupportedOperationException | SecurityException e) {
                // Symlinks unavailable.
            } catch (IOException e) {
                if (Files.exists(lock)) throw e;
            }
            Path stagePublish = dir.resolve("stage-publish-sixteen-chunk.identity");
            Path stage = stagePublish.resolveSibling(stagePublish.getFileName().toString() + ".tmp");
            Path externalStage = dir.resolve("external-sixteen-stage"); Files.writeString(externalStage, "external-stage");
            try {
                Files.createSymbolicLink(stage, externalStage.getFileName());
                expectIOException(() -> SixteenChunkCanaryIdentityStore.ensureExact(stagePublish, plan, core));
                require(Files.isSymbolicLink(stage), "symlink sixteen-chunk stage must survive refusal"); checks++;
                require("external-stage".equals(Files.readString(externalStage)), "external sixteen-chunk stage target must remain unchanged"); checks++;
                require(!Files.exists(stagePublish), "symlink stage refusal must not publish canonical sixteen-chunk authority"); checks++;
            } catch (UnsupportedOperationException | SecurityException e) {
                // Symlinks unavailable.
            } catch (IOException e) {
                if (Files.exists(stage)) throw e;
            }
            Path raced = dir.resolve("raced-sixteen-chunk.identity"); byte[] competing = "competing-sixteen-chunk-authority".getBytes(StandardCharsets.UTF_8);
            expectIOException(() -> SixteenChunkCanaryIdentityStore.ensureExact(raced, plan, core, channel -> { channel.force(true); Files.write(raced, competing); }));
            Path racedStage = raced.resolveSibling(raced.getFileName().toString() + ".tmp");
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "competing canonical sixteen-chunk authority must be preserved byte-for-byte"); checks++;
            require(Files.isRegularFile(racedStage) && Files.size(racedStage) > 0, "staged sixteen-chunk identity must survive publish-race refusal for forensics"); checks++;
            expectIOException(() -> SixteenChunkCanaryIdentityStore.ensureExact(raced, plan, core));
            require(Arrays.equals(competing, Files.readAllBytes(raced)), "ambiguous restart must not replace competing canonical sixteen-chunk authority"); checks++;
            require(Files.isRegularFile(racedStage), "ambiguous restart must preserve staged sixteen-chunk identity evidence"); checks++;
            return checks;
        } finally { try (var walk = Files.walk(dir)) { walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); } }
    }
    private static void expectIOException(IoAction action) throws Exception { try { action.run(); throw new AssertionError("expected IOException"); } catch (IOException expected) {} }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
