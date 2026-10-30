package net.oceancanvas.core.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Deterministic regression for direct-file stage-evidence digest and receipt identity authority. */
public final class StageEvidenceCertificateDigestSelfTest {
    private StageEvidenceCertificateDigestSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-stage-evidence-digest-");
        try {
            byte[] evidence = "direct-stage-evidence".getBytes(StandardCharsets.UTF_8);
            Path direct = dir.resolve("direct.evidence");
            Files.write(direct, evidence);
            require(expectedSha256(evidence).equals(StageEvidenceCertificate.stableSha256Required(direct)),
                    "direct required evidence must digest exactly");
            require(expectedSha256(evidence).equals(StageEvidenceCertificate.stableSha256Optional(direct)),
                    "direct optional evidence must digest exactly");
            require("ABSENT".equals(StageEvidenceCertificate.stableSha256Optional(dir.resolve("absent.evidence"))),
                    "absent optional evidence must remain explicitly absent");

            String expectedOperation = "operation-a";
            require(StageEvidenceCertificate.operationIdentityCompatible("cells=1", expectedOperation),
                    "legacy receipt without operation token must remain compatible");
            require(StageEvidenceCertificate.operationIdentityCompatible(
                            "cells=1;operation=operation-a", expectedOperation),
                    "matching explicit operation identity must remain compatible");
            require(!StageEvidenceCertificate.operationIdentityCompatible(
                            "cells=1;operation=operation-b", expectedOperation),
                    "explicit cross-operation receipt must be rejected");
            require(!StageEvidenceCertificate.operationIdentityCompatible(
                            "operation=operation-a;operation=operation-a", expectedOperation),
                    "duplicate operation tokens must be rejected as ambiguous");
            require(!StageEvidenceCertificate.operationIdentityCompatible("operation=", expectedOperation),
                    "empty explicit operation identity must be rejected");

            Path external = dir.resolve("external.evidence");
            Files.write(external, evidence);
            Path linked = dir.resolve("linked.evidence");
            try {
                Files.createSymbolicLink(linked, external.getFileName());
                expectIOException(() -> StageEvidenceCertificate.stableSha256Required(linked));
                expectIOException(() -> StageEvidenceCertificate.stableSha256Optional(linked));
                require(Files.isSymbolicLink(linked), "symlink evidence authority must survive refusal");
                require(Arrays.equals(evidence, Files.readAllBytes(external)),
                        "external evidence target bytes must survive refusal");
            } catch (UnsupportedOperationException | SecurityException e) {
                // Symbolic links unavailable in this environment.
            } catch (IOException e) {
                if (Files.exists(linked, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw e;
                // Symlink creation itself may be unavailable, notably on unprivileged Windows.
            }

            System.out.println("StageEvidenceCertificateDigestSelfTest PASS");
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
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
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
