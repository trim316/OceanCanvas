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

            provePublicationAuthoritySymlinks(root);

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

    private static void provePublicationAuthoritySymlinks(Path root) throws Exception {
        Path externalLock = root.resolve("external-lock-target");
        byte[] lockBytes = "external-lock-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(externalLock, lockBytes);
        Path lockIdentity = root.resolve("lock-redirection.identity");
        Path lock = lockIdentity.resolveSibling(lockIdentity.getFileName().toString() + ".lock");
        try {
            Files.createSymbolicLink(lock, externalLock.getFileName());
            expectIOException(() -> BlockStateRegistryIdentityStore.ensureExact(lockIdentity, FINGERPRINT, 4096));
            require(Files.isSymbolicLink(lock), "registry lock symlink must survive refusal");
            require(java.util.Arrays.equals(lockBytes, Files.readAllBytes(externalLock)),
                    "registry lock refusal must preserve external target bytes");
            require(!Files.exists(lockIdentity), "registry lock refusal must not publish canonical identity");
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        } catch (IOException e) {
            if (Files.exists(lock, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw e;
            return;
        }

        Path externalStage = root.resolve("external-stage-target");
        byte[] stageBytes = "external-stage-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(externalStage, stageBytes);
        Path stageIdentity = root.resolve("stage-redirection.identity");
        Path stage = stageIdentity.resolveSibling(stageIdentity.getFileName().toString() + ".tmp");
        Files.createSymbolicLink(stage, externalStage.getFileName());
        expectIOException(() -> BlockStateRegistryIdentityStore.ensureExact(stageIdentity, FINGERPRINT, 4096));
        require(Files.isSymbolicLink(stage), "registry stage symlink must survive refusal");
        require(java.util.Arrays.equals(stageBytes, Files.readAllBytes(externalStage)),
                "registry stage refusal must preserve external target bytes");
        require(!Files.exists(stageIdentity), "registry stage refusal must not publish canonical identity");
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
