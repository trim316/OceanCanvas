package net.oceancanvas.core.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Deterministic regression for direct-file completion-evidence digest authority. */
public final class CompletionEvidenceCertificateDigestSelfTest {
    private CompletionEvidenceCertificateDigestSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-completion-evidence-digest-");
        try {
            byte[] evidence = "direct-completion-evidence".getBytes(StandardCharsets.UTF_8);
            Path direct = dir.resolve("direct.evidence");
            Files.write(direct, evidence);
            require(expectedSha256(evidence).equals(CompletionEvidenceCertificate.stableSha256(direct)),
                    "direct regular evidence must digest exactly");

            Path external = dir.resolve("external.evidence");
            Files.write(external, evidence);
            Path linked = dir.resolve("linked.evidence");
            try {
                Files.createSymbolicLink(linked, external.getFileName());
                expectIOException(() -> CompletionEvidenceCertificate.stableSha256(linked));
                require(Files.isSymbolicLink(linked),
                        "symlink evidence authority must survive refusal");
                require(Arrays.equals(evidence, Files.readAllBytes(external)),
                        "external evidence target bytes must survive refusal");
            } catch (UnsupportedOperationException | SecurityException e) {
                // Filesystem does not support symbolic links in this environment.
            } catch (IOException e) {
                if (Files.exists(linked)) throw e;
                // Symlink creation itself may be unavailable, notably on unprivileged Windows.
            }

            System.out.println("CompletionEvidenceCertificateDigestSelfTest PASS");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static String expectedSha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            // Expected fail-closed refusal.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    @FunctionalInterface
    private interface IoAction { void run() throws Exception; }
}
