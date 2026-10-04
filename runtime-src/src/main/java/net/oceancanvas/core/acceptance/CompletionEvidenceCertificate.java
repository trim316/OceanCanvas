package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.pipeline.OperationManifestStore;
import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.receipt.PreimageReceiptContinuity;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Read-only, deterministic completion identity reconstructed from the three
 * durable evidence roots: operation manifest, authoritative journal and
 * forensic receipt log.
 *
 * <p>This certificate does not grant mutation authority and does not replace
 * post-restart archive/world verification. It makes the exact evidence set
 * independently identifiable without trusting a pre-generated report.</p>
 */
public final class CompletionEvidenceCertificate {
    private static final long MAX_EVIDENCE_FILE_BYTES = 8L * 1024L * 1024L;

    private CompletionEvidenceCertificate() {}

    public static Certificate reconstruct(
            Path manifestPath, Path journalPath, Path receiptPath,
            SingleChunkOperationSpec expected) throws IOException {
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(journalPath, "journalPath");
        Objects.requireNonNull(receiptPath, "receiptPath");
        Objects.requireNonNull(expected, "expected");

        SingleChunkOperationSpec manifestBefore = OperationManifestStore.readVerified(manifestPath);
        if (!manifestBefore.equals(expected)) {
            throw new IOException("completion certificate manifest does not match expected operation");
        }

        CoreJournal journal = new CoreJournal(journalPath);
        CoreJournal.ReplayState replayBefore = journal.replaySingleChunk(expected.chunk());
        if (replayBefore.record().stage() != ChunkStage.COMPLETE) {
            throw new IOException("completion certificate requires authoritative journal stage COMPLETE");
        }

        RuntimeReceiptLog receiptLog = new RuntimeReceiptLog(receiptPath);
        List<RuntimeReceipt> receiptsBefore = receiptLog.readVerified();
        PreimageReceiptContinuity.Verified continuity =
                PreimageReceiptContinuity.verify(
                        receiptsBefore, expected.operationId(), expected.chunk(), true);

        String manifestSha = stableSha256(manifestPath);
        String journalSha = stableSha256(journalPath);
        String receiptSha = stableSha256(receiptPath);

        // Re-parse after digesting so a concurrent append/replacement cannot be
        // certified from one semantic snapshot and a different byte snapshot.
        SingleChunkOperationSpec manifestAfter = OperationManifestStore.readVerified(manifestPath);
        CoreJournal.ReplayState replayAfter = journal.replaySingleChunk(expected.chunk());
        List<RuntimeReceipt> receiptsAfter = receiptLog.readVerified();
        if (!manifestBefore.equals(manifestAfter)
                || !replayBefore.equals(replayAfter)
                || !receiptsBefore.equals(receiptsAfter)) {
            throw new IOException("completion evidence changed during reconstruction");
        }

        Certificate unsigned = new Certificate(
                1,
                expected.operationId(),
                expected.chunk(),
                ChunkStage.COMPLETE,
                manifestSha,
                journalSha,
                receiptSha,
                continuity.preimageSha256(),
                Math.toIntExact(replayAfter.nextSequence()),
                receiptsAfter.size(),
                "");
        String certificateSha = sha256Hex(unsigned.canonicalPayload());
        return new Certificate(
                unsigned.schemaVersion(), unsigned.operationId(), unsigned.chunk(),
                unsigned.stage(), unsigned.manifestSha256(), unsigned.journalSha256(),
                unsigned.receiptSha256(), unsigned.preimageSha256(),
                unsigned.journalEntries(), unsigned.receiptEntries(), certificateSha);
    }

    public record Certificate(
            int schemaVersion,
            String operationId,
            ChunkKey chunk,
            ChunkStage stage,
            String manifestSha256,
            String journalSha256,
            String receiptSha256,
            String preimageSha256,
            int journalEntries,
            int receiptEntries,
            String certificateSha256) {

        public Certificate {
            if (schemaVersion != 1) throw new IllegalArgumentException("unsupported certificate schema");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(stage, "stage");
            requireSha(manifestSha256, "manifestSha256");
            requireSha(journalSha256, "journalSha256");
            requireSha(receiptSha256, "receiptSha256");
            requireSha(preimageSha256, "preimageSha256");
            if (journalEntries < 0 || receiptEntries < 0) {
                throw new IllegalArgumentException("negative evidence count");
            }
            if (!certificateSha256.isEmpty()) requireSha(certificateSha256, "certificateSha256");
        }

        public String canonicalPayload() {
            return "schemaVersion=" + schemaVersion + "\n"
                    + "operationId=" + operationId + "\n"
                    + "chunkX=" + chunk.x() + "\n"
                    + "chunkZ=" + chunk.z() + "\n"
                    + "stage=" + stage.name() + "\n"
                    + "manifestSha256=" + manifestSha256 + "\n"
                    + "journalSha256=" + journalSha256 + "\n"
                    + "receiptSha256=" + receiptSha256 + "\n"
                    + "preimageSha256=" + preimageSha256 + "\n"
                    + "journalEntries=" + journalEntries + "\n"
                    + "receiptEntries=" + receiptEntries + "\n";
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

    private static String stableSha256(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("evidence file is missing: " + path.getFileName());
        long size = Files.size(path);
        if (size > MAX_EVIDENCE_FILE_BYTES) {
            throw new IOException("evidence file exceeds safe digest bound: " + path.getFileName());
        }
        String first = sha256Hex(path, size);
        String second = sha256Hex(path, size);
        if (!first.equals(second) || Files.size(path) != size) {
            throw new IOException("evidence file changed during digest: " + path.getFileName());
        }
        return first;
    }

    private static String sha256Hex(Path path, long expectedSize) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long count = 0;
            try (InputStream in = Files.newInputStream(path)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    count += n;
                    if (count > MAX_EVIDENCE_FILE_BYTES) {
                        throw new IOException("evidence file grew beyond safe digest bound: " + path.getFileName());
                    }
                    digest.update(buffer, 0, n);
                }
            }
            if (count != expectedSize || Files.size(path) != expectedSize) {
                throw new IOException("evidence file size changed during digest: " + path.getFileName());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
