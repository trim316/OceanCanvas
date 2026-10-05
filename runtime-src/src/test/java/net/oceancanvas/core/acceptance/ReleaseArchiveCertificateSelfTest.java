package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.*;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Deterministic release-archive binding regression for R1-55. */
public final class ReleaseArchiveCertificateSelfTest {
    private ReleaseArchiveCertificateSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("ReleaseArchiveCertificate self-test PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-release-archive-cert");
        ChunkKey key = new ChunkKey(7, -11);
        SingleChunkOperationSpec spec = new SingleChunkOperationSpec(
                1, key, 20_000, 0, 0, 62, -25, 3);
        Path manifest = dir.resolve("operation.properties");
        Path journalPath = dir.resolve("transitions.journal");
        Path receiptPath = dir.resolve("runtime-receipts.log");
        Path archivePath = dir.resolve("preimage.bin.completed.archive");
        OperationManifestStore.ensureExact(manifest, spec);

        SingleChunkPorts success = new SingleChunkPorts() {
            private StageActionResult ok() { return StageActionResult.success("release certificate fixture"); }
            @Override public StageActionResult load(ChunkRecord r) { return ok(); }
            @Override public StageActionResult capturePreimage(ChunkRecord r) { return ok(); }
            @Override public StageActionResult authorPhysical(ChunkRecord r) { return ok(); }
            @Override public StageActionResult settlePhysical(ChunkRecord r) { return ok(); }
            @Override public StageActionResult persist(ChunkRecord r) { return ok(); }
            @Override public StageActionResult settleLighting(ChunkRecord r) { return ok(); }
            @Override public StageActionResult verify(ChunkRecord r) { return ok(); }
            @Override public StageActionResult restore(ChunkRecord r) { return ok(); }
            @Override public StageActionResult verifyRestore(ChunkRecord r) { return ok(); }
            @Override public StageActionResult release(ChunkRecord r) { return ok(); }
        };
        SingleChunkPipeline pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
        for (int i = 0; i < 10; i++) pipeline.tick(success, 1000L + i);
        if (pipeline.record().stage() != ChunkStage.COMPLETE) throw new AssertionError("fixture did not complete");
        checks++;

        int[] states = new int[256 * 2];
        for (int i = 0; i < states.length; i++) states[i] = i % 17;
        BlockStatePreimageStore.writeExact(archivePath,
                new BlockStatePreimageStore.Preimage(spec.operationId(), key, -1, 0, states));
        String archiveSha = BlockStatePreimageStore.sha256Hex(archivePath);

        RuntimeReceiptLog receipts = new RuntimeReceiptLog(receiptPath);
        receipts.append(ReceiptKind.PREIMAGE_CAPTURED, key,
                "operation=" + spec.operationId() + ";preimageSha256=" + archiveSha);
        receipts.append(ReceiptKind.RESTORE_COMPLETE, key,
                "operation=" + spec.operationId() + ";preimageSha256=" + archiveSha);
        receipts.append(ReceiptKind.RESTORE_VERIFIED, key,
                "operation=" + spec.operationId() + ";preimageSha256=" + archiveSha);
        receipts.append(ReceiptKind.TICKET_RELEASED, key,
                "forced radius=0;restoreVerified=true;preimageArchiveSha256=" + archiveSha);

        var first = ReleaseArchiveCertificate.reconstruct(
                manifest, journalPath, receiptPath, archivePath, spec);
        var second = ReleaseArchiveCertificate.reconstruct(
                manifest, journalPath, receiptPath, archivePath, spec);
        if (!first.equals(second) || !first.selfVerifies()) throw new AssertionError("certificate not deterministic");
        checks++;
        if (!archiveSha.equals(first.archiveSha256())) throw new AssertionError("archive SHA not bound");
        checks++;

        Path missing = dir.resolve("missing.completed.archive");
        boolean missingRejected = false;
        try { ReleaseArchiveCertificate.reconstruct(manifest, journalPath, receiptPath, missing, spec); }
        catch (java.io.IOException expected) { missingRejected = true; }
        if (!missingRejected) throw new AssertionError("missing archive certified");
        checks++;

        Files.writeString(archivePath, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean corruptRejected = false;
        try { ReleaseArchiveCertificate.reconstruct(manifest, journalPath, receiptPath, archivePath, spec); }
        catch (java.io.IOException expected) { corruptRejected = true; }
        if (!corruptRejected) throw new AssertionError("corrupt archive certified");
        checks++;

        return checks;
    }
}
