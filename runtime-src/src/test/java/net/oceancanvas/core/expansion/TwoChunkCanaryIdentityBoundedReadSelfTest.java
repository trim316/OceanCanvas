package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Deterministic regression for bounded immutable two-chunk identity replay and publication races. */
public final class TwoChunkCanaryIdentityBoundedReadSelfTest {
    private TwoChunkCanaryIdentityBoundedReadSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("TwoChunkCanaryIdentityBoundedReadSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-two-chunk-identity-read");
        try {
            Path file = dir.resolve("two-chunk.identity");
            byte[] expected = "canonical-two-chunk-identity".getBytes(StandardCharsets.UTF_8);
            Files.write(file, expected);
            require(TwoChunkCanaryIdentityStore.matchesExactBounded(file, expected),
                    "exact immutable identity must match");
            checks++;

            byte[] changed = expected.clone();
            changed[changed.length / 2] ^= 1;
            Files.write(file, changed, StandardOpenOption.TRUNCATE_EXISTING);
            require(!TwoChunkCanaryIdentityStore.matchesExactBounded(file, expected),
                    "same-length changed identity must fail closed");
            checks++;

            Files.write(file, expected, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(file, new byte[]{0}, StandardOpenOption.APPEND);
            require(!TwoChunkCanaryIdentityStore.matchesExactBounded(file, expected),
                    "oversized identity must fail before replay acceptance");
            checks++;

            Files.write(file, Arrays.copyOf(expected, expected.length - 1),
                    StandardOpenOption.TRUNCATE_EXISTING);
            require(!TwoChunkCanaryIdentityStore.matchesExactBounded(file, expected),
                    "truncated identity must fail closed");
            checks++;

            Files.delete(file);
            Files.createDirectory(file);
            require(!TwoChunkCanaryIdentityStore.matchesExactBounded(file, expected),
                    "non-regular identity path must fail closed");
            checks++;

            Files.delete(file);
            Path target = dir.resolve("two-chunk-target.identity");
            Files.write(target, expected);
            try {
                Files.createSymbolicLink(file, target.getFileName());
                require(!TwoChunkCanaryIdentityStore.matchesExactBounded(file, expected),
                        "symlink identity authority must fail closed");
                checks++;
                require(Files.isSymbolicLink(file),
                        "symlink identity evidence must survive refusal");
                checks++;
                require(Arrays.equals(expected, Files.readAllBytes(target)),
                        "symlink target bytes must survive refusal");
                checks++;
            } catch (UnsupportedOperationException | SecurityException e) {
                // Symbolic links unavailable in this environment.
            } catch (IOException e) {
                if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw e;
                // Symlink creation itself may be unavailable, notably on unprivileged Windows.
            }
            Files.deleteIfExists(file);

            TwoChunkCanaryPlan plan = TwoChunkCanaryPlan.eastOf(new ChunkKey(32, 32));
            CoreConfig core = CoreConfig.defaults();
            Path publish = dir.resolve("publish-two-chunk.identity");
            Path publishLock = publish.resolveSibling(publish.getFileName().toString() + ".lock");
            Path externalLock = dir.resolve("external-two-chunk-lock.txt");
            byte[] externalLockBytes = "external-lock-authority".getBytes(StandardCharsets.UTF_8);
            Files.write(externalLock, externalLockBytes);
            try {
                Files.createSymbolicLink(publishLock, externalLock.getFileName());
                expectIOException(() -> TwoChunkCanaryIdentityStore.ensureExact(publish, plan, core));
                require(Files.isSymbolicLink(publishLock), "symlinked lock evidence must survive refusal");
                checks++;
                require(Arrays.equals(externalLockBytes, Files.readAllBytes(externalLock)),
                        "symlinked lock target must remain unchanged");
                checks++;
                require(!Files.exists(publish), "symlinked lock authority must not publish canonical identity");
                checks++;
                Files.delete(publishLock);
            } catch (UnsupportedOperationException | SecurityException e) {
                // Symbolic links unavailable in this environment.
            } catch (IOException e) {
                if (Files.exists(publishLock, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw e;
            }

            Path publishStage = publish.resolveSibling(publish.getFileName().toString() + ".tmp");
            Path externalStage = dir.resolve("external-two-chunk-stage.txt");
            byte[] externalStageBytes = "external-stage-authority".getBytes(StandardCharsets.UTF_8);
            Files.write(externalStage, externalStageBytes);
            try {
                Files.createSymbolicLink(publishStage, externalStage.getFileName());
                expectIOException(() -> TwoChunkCanaryIdentityStore.ensureExact(publish, plan, core));
                require(Files.isSymbolicLink(publishStage), "symlinked staged evidence must survive refusal");
                checks++;
                require(Arrays.equals(externalStageBytes, Files.readAllBytes(externalStage)),
                        "symlinked staged target must remain unchanged");
                checks++;
                require(!Files.exists(publish), "symlinked staged authority must not publish canonical identity");
                checks++;
                Files.delete(publishStage);
            } catch (UnsupportedOperationException | SecurityException e) {
                // Symbolic links unavailable in this environment.
            } catch (IOException e) {
                if (Files.exists(publishStage, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw e;
            }

            Path raced = dir.resolve("raced-two-chunk.identity");
            byte[] competing = "competing-two-chunk-authority".getBytes(StandardCharsets.UTF_8);
            expectIOException(() -> TwoChunkCanaryIdentityStore.ensureExact(
                    raced, plan, core, channel -> {
                        channel.force(true);
                        Files.write(raced, competing);
                    }));
            Path racedStage = raced.resolveSibling(raced.getFileName().toString() + ".tmp");
            require(Arrays.equals(competing, Files.readAllBytes(raced)),
                    "competing canonical two-chunk authority must be preserved byte-for-byte");
            checks++;
            require(Files.isRegularFile(racedStage) && Files.size(racedStage) > 0,
                    "staged two-chunk identity must survive publish-race refusal for forensics");
            checks++;
            expectIOException(() -> TwoChunkCanaryIdentityStore.ensureExact(raced, plan, core));
            require(Arrays.equals(competing, Files.readAllBytes(raced)),
                    "ambiguous restart must not replace competing canonical two-chunk authority");
            checks++;
            require(Files.isRegularFile(racedStage),
                    "ambiguous restart must preserve staged two-chunk identity evidence");
            checks++;

            checks += BoundedCampaignIdentityRegularFileSelfTest.run();
            checks += FourChunkCanaryIdentityBoundedReadSelfTest.run();
            return checks;
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception ignored) {}
                });
            }
        }
    }

    private static void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            // expected fail-closed refusal
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
