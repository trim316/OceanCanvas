package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.restore.BlockEntityBackupContract;
import net.oceancanvas.core.restore.BlockEntitySidecarStore;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

    /**
     * Stronger release proof for operations that have separately backed block
     * entities. The sidecar is mandatory here: callers cannot silently fall
     * back to the block-state-only certificate after block-entity support was
     * exercised. The terminal release receipt must also bind the exact sidecar
     * archive SHA, canonical envelope SHA and entry count; an independently
     * valid sidecar that was never the archive released by this operation is
     * not sufficient release evidence.
     */
    public static BlockEntityCertificate reconstructWithBlockEntityArchive(
            Path manifestPath, Path journalPath, Path receiptPath, Path archivePath,
            Path blockEntityArchivePath, SingleChunkOperationSpec expected) throws IOException {
        Objects.requireNonNull(blockEntityArchivePath, "blockEntityArchivePath");
        Objects.requireNonNull(expected, "expected");

        Certificate releaseBefore = reconstruct(
                manifestPath, journalPath, receiptPath, archivePath, expected);
        BlockEntityBackupContract.Envelope sidecarBefore = BlockEntitySidecarStore.readVerified(
                blockEntityArchivePath, expected.operationId(), expected.chunk(), releaseBefore.archiveSha256());
        String sidecarSha = stableSha256(blockEntityArchivePath);
        String envelopeSha = BlockEntityBackupContract.canonicalSha256(sidecarBefore);
        verifyBlockEntityReleaseReceipt(receiptPath, releaseBefore, expected,
                sidecarSha, envelopeSha, sidecarBefore.entries().size());
        BlockEntityBackupContract.Envelope sidecarAfter = BlockEntitySidecarStore.readVerified(
                blockEntityArchivePath, expected.operationId(), expected.chunk(), releaseBefore.archiveSha256());
        if (!sameEnvelope(sidecarBefore, sidecarAfter)) {
            throw new IOException("block-entity archive changed during release certification");
        }

        Certificate releaseAfter = reconstruct(
                manifestPath, journalPath, receiptPath, archivePath, expected);
        if (!releaseBefore.equals(releaseAfter)) {
            throw new IOException("release evidence changed during block-entity archive certification");
        }

        BlockEntityCertificate unsigned = new BlockEntityCertificate(
                1, releaseAfter.certificateSha256(), sidecarSha, expected.operationId(),
                expected.chunk().x(), expected.chunk().z(), sidecarAfter.entries().size(), "");
        String certificateSha = sha256Hex(unsigned.canonicalPayload());
        return new BlockEntityCertificate(unsigned.schemaVersion(),
                unsigned.releaseArchiveCertificateSha256(), unsigned.blockEntityArchiveSha256(),
                unsigned.operationId(), unsigned.chunkX(), unsigned.chunkZ(), unsigned.blockEntityEntries(),
                certificateSha);
    }

    private static void verifyBlockEntityReleaseReceipt(
            Path receiptPath, Certificate release, SingleChunkOperationSpec expected,
            String sidecarSha, String envelopeSha, int entryCount) throws IOException {
        List<RuntimeReceipt> receipts = new RuntimeReceiptLog(receiptPath).readVerified();
        int matchingTerminalReceipts = 0;
        for (RuntimeReceipt receipt : receipts) {
            if (receipt.kind() != ReceiptKind.TICKET_RELEASED
                    || !receipt.detail().contains("blockEntityArchiveSha256=")) {
                continue;
            }
            if (!receipt.chunk().equals(expected.chunk())) {
                throw new IOException("block-entity release receipt chunk mismatch");
            }
            Map<String, String> fields = parseReceiptFields(receipt.detail());
            if (!"true".equals(fields.get("restoreVerified"))
                    || !release.archiveSha256().equals(fields.get("preimageArchiveSha256"))
                    || !sidecarSha.equals(fields.get("blockEntityArchiveSha256"))
                    || !envelopeSha.equals(fields.get("blockEntityEnvelopeSha256"))) {
                throw new IOException("block-entity release receipt does not match certified archives");
            }
            final int recordedEntries;
            try {
                recordedEntries = Integer.parseInt(fields.getOrDefault("blockEntities", ""));
            } catch (NumberFormatException e) {
                throw new IOException("block-entity release receipt has invalid entry count", e);
            }
            if (recordedEntries != entryCount) {
                throw new IOException("block-entity release receipt entry count mismatch");
            }
            matchingTerminalReceipts++;
        }
        if (matchingTerminalReceipts != 1) {
            throw new IOException("block-entity release certification requires exactly one matching terminal receipt");
        }
    }

    private static Map<String, String> parseReceiptFields(String detail) throws IOException {
        Map<String, String> fields = new HashMap<>();
        for (String field : detail.split(";", -1)) {
            int split = field.indexOf('=');
            if (split <= 0 || split == field.length() - 1) {
                throw new IOException("malformed block-entity release receipt field");
            }
            String key = field.substring(0, split);
            String value = field.substring(split + 1);
            if (fields.putIfAbsent(key, value) != null) {
                throw new IOException("duplicate block-entity release receipt key " + key);
            }
        }
        return fields;
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
    }

    public record BlockEntityCertificate(
            int schemaVersion,
            String releaseArchiveCertificateSha256,
            String blockEntityArchiveSha256,
            String operationId,
            int chunkX,
            int chunkZ,
            int blockEntityEntries,
            String certificateSha256) {
        public BlockEntityCertificate {
            if (schemaVersion != 1) throw new IllegalArgumentException("unsupported block-entity release certificate schema");
            requireSha(releaseArchiveCertificateSha256, "releaseArchiveCertificateSha256");
            requireSha(blockEntityArchiveSha256, "blockEntityArchiveSha256");
            if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId");
            if (blockEntityEntries < 0) throw new IllegalArgumentException("negative blockEntityEntries");
            if (!certificateSha256.isEmpty()) requireSha(certificateSha256, "certificateSha256");
        }

        public String canonicalPayload() {
            return "schemaVersion=" + schemaVersion + "\n"
                    + "releaseArchiveCertificateSha256=" + releaseArchiveCertificateSha256 + "\n"
                    + "blockEntityArchiveSha256=" + blockEntityArchiveSha256 + "\n"
                    + "operationId=" + operationId + "\n"
                    + "chunkX=" + chunkX + "\n"
                    + "chunkZ=" + chunkZ + "\n"
                    + "blockEntityEntries=" + blockEntityEntries + "\n";
        }

        public boolean selfVerifies() {
            return !certificateSha256.isEmpty()
                    && certificateSha256.equals(sha256Hex(canonicalPayload()));
        }
    }

    private static boolean sameEnvelope(BlockEntityBackupContract.Envelope a,
                                        BlockEntityBackupContract.Envelope b) {
        return a.operationId().equals(b.operationId())
                && a.chunk().equals(b.chunk())
                && a.blockStatePreimageSha256().equals(b.blockStatePreimageSha256())
                && a.stateCount() == b.stateCount()
                && BlockEntityBackupContract.canonicalSha256(a)
                        .equals(BlockEntityBackupContract.canonicalSha256(b));
    }

    private static String stableSha256(Path path) throws IOException {
        long expectedSize = Files.size(path);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        long observed = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) continue;
                try { observed = Math.addExact(observed, n); }
                catch (ArithmeticException e) { throw new IOException("archive byte count overflow", e); }
                digest.update(buffer, 0, n);
            }
        }
        if (observed != expectedSize || Files.size(path) != expectedSize) {
            throw new IOException("archive changed while hashing");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireSha(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
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
