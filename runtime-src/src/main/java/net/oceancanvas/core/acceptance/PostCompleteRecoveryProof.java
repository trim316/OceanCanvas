package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.receipt.PreimageReceiptContinuity;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.io.IOException;
import java.nio.file.Path;
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
        return continuity;
    }
}
