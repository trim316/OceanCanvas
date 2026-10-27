package net.oceancanvas.core.restore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic refusal proof for non-regular and competing immutable registry authority. */
public final class BlockStateRegistryIdentityRegularFileSelfTest {
    private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private BlockStateRegistryIdentityRegularFileSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("oceancanvas-registry-identity-regular-");
        try {
            Path regular = root.resolve("regular.identity");
            BlockStateRegistryIdentityStore.ensureExact(regular, FINGERPRINT, 4096);
            byte[] before = Files.readAllBytes(regular);
            BlockStateRegistryIdentityStore.ensureExact(regular, FINGERPRINT, 4096);
            require(java.util.Arrays.equals(before, Files.readAllBytes(regular)),
                    "exact regular replay must preserve immutable registry authority");

            Path nonRegular = root.resolve("directory.identity");
            Files.createDirectory(nonRegular);
            Path marker = nonRegular.resolve("preserve.marker");
            Files.writeString(marker, "preserve");
            expectIOException(() -> BlockStateRegistryIdentityStore.ensureExact(
                    nonRegular, FINGERPRINT, 4096));
            require(Files.isDirectory(nonRegular),
                    "non-regular registry authority must remain preserved");
            require("preserve".equals(Files.readString(marker)),
                    "refusal must not mutate evidence under non-regular authority path");

            Path symlinkTarget = root.resolve("external-registry.identity");
            BlockStateRegistryIdentityStore.ensureExact(symlinkTarget, FINGERPRINT, 4096);
            byte[] externalBefore = Files.readAllBytes(symlinkTarget);
            Path symlink = root.resolve("symlink.identity");
            try {
                Files.createSymbolicLink(symlink, symlinkTarget.getFileName());
                expectIOException(() -> BlockStateRegistryIdentityStore.ensureExact(symlink, FINGERPRINT, 4096));
                require(Files.isSymbolicLink(symlink),
                        "registry authority symlink must survive refusal");
                require(java.util.Arrays.equals(externalBefore, Files.readAllBytes(symlinkTarget)),
                        "registry authority refusal must preserve external target bytes");
            } catch (UnsupportedOperationException | SecurityException e) {
                // Platform does not permit symbolic-link construction; direct-file production guard still applies.
            } catch (IOException e) {
                if (Files.exists(symlink, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw e;
                // Windows hosted environments may deny symlink creation without developer privilege.
            }

            Path raced = root.resolve("raced.identity");
            byte[] competing = "competing-registry-authority".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            expectIOException(() -> BlockStateRegistryIdentityStore.ensureExact(
                    raced,
                    FINGERPRINT,
                    4096,
                    channel -> {
                        channel.force(true);
                        Files.write(raced, competing);
                    }));
            Path racedStage = raced.resolveSibling(raced.getFileName().toString() + ".tmp");
            require(java.util.Arrays.equals(competing, Files.readAllBytes(raced)),
                    "competing canonical registry authority must be preserved byte-for-byte");
            require(Files.isRegularFile(racedStage),
                    "staged registry identity must survive publish-race refusal for forensics");
            require(Files.size(racedStage) > 0,
                    "staged registry identity evidence must remain non-empty");
            expectIOException(() -> BlockStateRegistryIdentityStore.ensureExact(raced, FINGERPRINT, 4096));
            require(java.util.Arrays.equals(competing, Files.readAllBytes(raced)),
                    "ambiguous restart must not replace competing canonical registry authority");
            require(Files.isRegularFile(racedStage),
                    "ambiguous restart must preserve staged registry identity evidence");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
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
