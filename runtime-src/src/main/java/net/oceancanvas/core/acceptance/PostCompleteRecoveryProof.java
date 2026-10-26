package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.receipt.PreimageReceiptContinuity;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.restore.BlockEntityBackupContract;
import net.oceancanvas.core.restore.BlockEntitySidecarStore;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    private static final long MAX_SIDECAR_FILE_BYTES = BlockEntityBackupContract.MAX_SIDECAR_BYTES + 32L;

    private PostCompleteRecoveryProof() {}

    /** Completion is authoritative regardless of optional test instrumentation. */
    public static boolean requiresArchiveOnReopen(ChunkStage persistedStage) {
        return Objects.requireNonNull(persistedStage, "persistedStage") == ChunkStage.COMPLETE;
    }

    public static PreimageReceiptContinuity.Verified verify(
            Path archive, String operationId, ChunkKey chunk,
            List<RuntimeReceipt> checksummedReceipts) throws IOException {
        Objects.requireNonNull(archive, "archive");
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
            if (!expectedSidecarHash.equals(boundedStableSha256(sidecarArchive))) {
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

    /**
     * Hash a sidecar without allocating the entire evidence file. The evidence
     * path itself must remain a direct regular file: following a symbolic link
     * would let mutable external bytes masquerade as canonical archive authority.
     */
    static String boundedStableSha256(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-entity archive is missing or not a direct regular file");
        }
        long expectedSize = Files.size(path);
        if (expectedSize < 1L || expectedSize > MAX_SIDECAR_FILE_BYTES) {
            throw new IOException("invalid or excessive completed block-entity archive size");
        }
        String first = streamingSha256(path, expectedSize);
        String second = streamingSha256(path, expectedSize);
        if (!first.equals(second)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) != expectedSize) {
            throw new IOException("completed block-entity archive changed during digest");
        }
        return first;
    }

    private static String streamingSha256(Path path, long expectedSize) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long count = 0L;
            try (InputStream in = Files.newInputStream(path)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    count += n;
                    if (count > MAX_SIDECAR_FILE_BYTES || count > expectedSize) {
                        throw new IOException("completed block-entity archive grew during digest");
                    }
                    digest.update(buffer, 0, n);
                }
            }
            if (count != expectedSize
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) != expectedSize) {
                throw new IOException("completed block-entity archive size changed during digest");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable for block-entity post-complete proof", e);
        }
    }
}
