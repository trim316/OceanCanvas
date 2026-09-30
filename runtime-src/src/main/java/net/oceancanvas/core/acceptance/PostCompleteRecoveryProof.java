package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.receipt.PreimageReceiptContinuity;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.restore.BlockEntitySidecarStore;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Read-only independent evidence gate before a restarted COMPLETE operation
 * may receive final-restart acceptance credit. This does not replace live
 * block-state verification or independently prove terrain after reboot.
 */
public final class PostCompleteRecoveryProof {
    private PostCompleteRecoveryProof() {}

    /** Completion is authoritative regardless of optional test instrumentation. */
    public static boolean requiresArchiveOnReopen(ChunkStage persistedStage) {
        return Objects.requireNonNull(persistedStage, "persistedStage") == ChunkStage.COMPLETE;
    }

    public static PreimageReceiptContinuity.Verified verify(
            Path archive, String operationId, ChunkKey chunk,
            List<RuntimeReceipt> checksummedReceipts) throws IOException {
        Objects.requireNonNull(archive, "archive");
        // Prove original backup bytes remain available and operation-bound
        // before trusting the forensic digest chain.
        BlockStatePreimageStore.readVerified(archive, operationId, chunk);
        String archiveHash = BlockStatePreimageStore.sha256Hex(archive);
        var continuity = PreimageReceiptContinuity.verify(
                checksummedReceipts, operationId, chunk, true);
        if (!archiveHash.equals(continuity.preimageSha256())) {
            throw new IOException("completed archive bytes disagree with capture/restore receipts");
        }

        boolean blockEntityRecovery = false;
        RuntimeReceipt release = null;
        RuntimeReceipt restoreVerified = null;
        for (RuntimeReceipt receipt : checksummedReceipts) {
            if (!receipt.chunk().equals(chunk)) continue;
            if (receipt.kind() == ReceiptKind.PREIMAGE_CAPTURED
                    && "true".equals(token(receipt.detail(), "blockEntityRecoveryEnabled"))) {
                blockEntityRecovery = true;
            }
            if (receipt.kind() == ReceiptKind.RESTORE_VERIFIED) {
                restoreVerified = receipt;
            }
            if (receipt.kind() == ReceiptKind.TICKET_RELEASED
                    && "true".equals(token(receipt.detail(), "restoreVerified"))) {
                release = receipt;
            }
        }
        if (blockEntityRecovery) {
            if (release == null) {
                throw new IOException("completed block-entity operation lacks verified ticket release receipt");
            }
            String expectedSidecarHash = token(release.detail(), "blockEntityArchiveSha256");
            String expectedEnvelopeHash = token(release.detail(), "blockEntityEnvelopeSha256");
            String countText = token(release.detail(), "blockEntities");
            String verifiedEnvelopeHash = restoreVerified == null ? null
                    : token(restoreVerified.detail(), "blockEntityEnvelopeSha256");
            if (expectedSidecarHash == null || !expectedSidecarHash.matches("[0-9a-f]{64}")
                    || expectedEnvelopeHash == null || !expectedEnvelopeHash.matches("[0-9a-f]{64}")
                    || verifiedEnvelopeHash == null || !verifiedEnvelopeHash.equals(expectedEnvelopeHash)
                    || countText == null || !countText.matches("[0-9]+")) {
                throw new IOException("completed block-entity operation lacks continuous archived sidecar identity");
            }
            Path sidecarArchive = archive.resolveSibling(
                    "preimage-blockentities.ocbe.completed.archive");
            var envelope = BlockEntitySidecarStore.readVerified(
                    sidecarArchive, operationId, chunk, archiveHash);
            if (envelope.entries().size() != Integer.parseInt(countText)) {
                throw new IOException("completed block-entity archive count disagrees with release receipt");
            }
            if (!expectedEnvelopeHash.equals(
                    net.oceancanvas.core.restore.BlockEntityBackupContract.canonicalSha256(envelope))) {
                throw new IOException("completed block-entity archive semantics disagree with restore/release receipts");
            }
            if (!expectedSidecarHash.equals(sha256Hex(sidecarArchive))) {
                throw new IOException("completed block-entity archive bytes disagree with release receipt");
            }
        }
        return continuity;
    }

    private static String token(String detail, String key) {
        if (detail == null) return null;
        String prefix = key + "=";
        for (String item : detail.split(";")) {
            if (item.startsWith(prefix)) return item.substring(prefix.length());
        }
        return null;
    }

    private static String sha256Hex(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable for block-entity post-complete proof", e);
        }
    }
}
