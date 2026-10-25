package net.oceancanvas.core.restore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic refusal proof for non-regular immutable registry authority. */
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
