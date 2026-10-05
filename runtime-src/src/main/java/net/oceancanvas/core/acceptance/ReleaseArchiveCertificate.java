package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Release-facing completion proof that binds the normal durable completion
 * evidence to the actual immutable restored preimage archive.
 *
 * <p>Hosted core CI may reconstruct this certificate, but doing so does not
 * certify Minecraft runtime, scale, or overnight behavior. The certificate's
 * purpose is narrower: a COMPLETE journal/receipt chain cannot be presented as
 * release evidence when the immutable archive it names is absent, corrupt, or
 * belongs to another operation/chunk.</p>
 */
public final class ReleaseArchiveCertificate {
    private ReleaseArchiveCertificate() {}

    public static Certificate reconstruct(
            Path manifestPath, Path journalPath, Path receiptPath, Path archivePath,
            SingleChunkOperationSpec expected) throws IOException {
        Objects.requireNonNull(archivePath, "archivePath");
        Objects.requireNonNull(expected, "expected");

        CompletionEvidenceCertificate.Certificate before =
                CompletionEvidenceCertificate.reconstruct(manifestPath, journalPath, receiptPath, expected);

        BlockStatePreimageStore.readVerified(
                archivePath, expected.operationId(), expected.chunk());
        String archiveSha = BlockStatePreimageStore.sha256Hex(archivePath);
        if (!archiveSha.equals(before.preimageSha256())) {
            throw new IOException("release archive SHA does not match completed restore evidence");
        }

        // Reconstruct the semantic roots again after archive verification. This
        // prevents a concurrent evidence replacement from pairing an archive
        // checked against one completion snapshot with a different certificate.
        CompletionEvidenceCertificate.Certificate after =
                CompletionEvidenceCertificate.reconstruct(manifestPath, journalPath, receiptPath, expected);
        if (!before.equals(after)) {
            throw new IOException("completion evidence changed during release archive certification");
        }

        Certificate unsigned = new Certificate(
                1, after.certificateSha256(), archiveSha, expected.operationId(),
                expected.chunk().x(), expected.chunk().z(), "");
        String certificateSha = sha256Hex(unsigned.canonicalPayload());
        return new Certificate(unsigned.schemaVersion(), unsigned.completionCertificateSha256(),
                unsigned.archiveSha256(), unsigned.operationId(), unsigned.chunkX(), unsigned.chunkZ(),
                certificateSha);
    }

    public record Certificate(
            int schemaVersion,
            String completionCertificateSha256,
            String archiveSha256,
            String operationId,
            int chunkX,
            int chunkZ,
            String certificateSha256) {
        public Certificate {
            if (schemaVersion != 1) throw new IllegalArgumentException("unsupported release archive certificate schema");
            requireSha(completionCertificateSha256, "completionCertificateSha256");
            requireSha(archiveSha256, "archiveSha256");
            if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId");
            if (!certificateSha256.isEmpty()) requireSha(certificateSha256, "certificateSha256");
        }

        public String canonicalPayload() {
            return "schemaVersion=" + schemaVersion + "\n"
                    + "completionCertificateSha256=" + completionCertificateSha256 + "\n"
                    + "archiveSha256=" + archiveSha256 + "\n"
                    + "operationId=" + operationId + "\n"
                    + "chunkX=" + chunkX + "\n"
                    + "chunkZ=" + chunkZ + "\n";
        }

        public boolean selfVerifies() {
            return !certificateSha256.isEmpty()
                    && certificateSha256.equals(sha256Hex(canonicalPayload()));
        }

        private static void requireSha(String value, String label) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(label + " must be lowercase SHA-256");
            }
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
