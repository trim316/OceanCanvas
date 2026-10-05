package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.*;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.restore.BlockEntityBackupContract;
import net.oceancanvas.core.restore.BlockEntitySidecarStore;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Deterministic release-archive binding regression for R1-55/R1-57/R1-58. */
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
        Path sidecarPath = dir.resolve("block-entities.bin.completed.archive");
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

        BlockEntityBackupContract.Envelope sidecar = new BlockEntityBackupContract.Envelope(
                spec.operationId(), key, archiveSha, states.length,
                List.of(new BlockEntityBackupContract.Entry(19, "minecraft:chest", new byte[] {10, 0, 0, 0})));
        BlockEntitySidecarStore.writeExact(sidecarPath, sidecar);
        String sidecarSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sidecarPath)));
        String envelopeSha = BlockEntityBackupContract.canonicalSha256(sidecar);

        RuntimeReceiptLog receipts = new RuntimeReceiptLog(receiptPath);
        appendEvidence(receipts, key, spec.operationId(), archiveSha,
                sidecarSha, envelopeSha, 1);

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

        var sidecarFirst = ReleaseArchiveCertificate.reconstructWithBlockEntityArchive(
                manifest, journalPath, receiptPath, archivePath, sidecarPath, spec);
        var sidecarSecond = ReleaseArchiveCertificate.reconstructWithBlockEntityArchive(
                manifest, journalPath, receiptPath, archivePath, sidecarPath, spec);
        if (!sidecarFirst.equals(sidecarSecond) || !sidecarFirst.selfVerifies()) {
            throw new AssertionError("block-entity release certificate not deterministic");
        }
        if (sidecarFirst.blockEntityEntries() != 1) throw new AssertionError("block-entity entry count not bound");
        checks += 2;

        Path unboundReceipt = dir.resolve("unbound-runtime-receipts.log");
        RuntimeReceiptLog unbound = new RuntimeReceiptLog(unboundReceipt);
        unbound.append(ReceiptKind.PREIMAGE_CAPTURED, key,
                "operation=" + spec.operationId() + ";preimageSha256=" + archiveSha);
        unbound.append(ReceiptKind.RESTORE_COMPLETE, key,
                "operation=" + spec.operationId() + ";preimageSha256=" + archiveSha);
        unbound.append(ReceiptKind.RESTORE_VERIFIED, key,
                "operation=" + spec.operationId() + ";preimageSha256=" + archiveSha);
        unbound.append(ReceiptKind.TICKET_RELEASED, key,
                "forced radius=0;restoreVerified=true;preimageArchiveSha256=" + archiveSha);
        boolean unboundRejected = false;
        try {
            ReleaseArchiveCertificate.reconstructWithBlockEntityArchive(
                    manifest, journalPath, unboundReceipt, archivePath, sidecarPath, spec);
        } catch (java.io.IOException expected) { unboundRejected = true; }
        if (!unboundRejected) throw new AssertionError("unreleased block-entity archive certified");
        checks++;

        Path wrongReceipt = dir.resolve("wrong-sidecar-runtime-receipts.log");
        RuntimeReceiptLog wrong = new RuntimeReceiptLog(wrongReceipt);
        appendEvidence(wrong, key, spec.operationId(), archiveSha,
                "0".repeat(64), envelopeSha, 1);
        boolean wrongReceiptRejected = false;
        try {
            ReleaseArchiveCertificate.reconstructWithBlockEntityArchive(
                    manifest, journalPath, wrongReceipt, archivePath, sidecarPath, spec);
        } catch (java.io.IOException expected) { wrongReceiptRejected = true; }
        if (!wrongReceiptRejected) throw new AssertionError("mismatched block-entity release receipt certified");
        checks++;

        boolean missingSidecarRejected = false;
        try {
            ReleaseArchiveCertificate.reconstructWithBlockEntityArchive(
                    manifest, journalPath, receiptPath, archivePath, dir.resolve("missing-sidecar.archive"), spec);
        } catch (java.io.IOException expected) { missingSidecarRejected = true; }
        if (!missingSidecarRejected) throw new AssertionError("missing block-entity archive certified");
        checks++;

        Path wrongSidecar = dir.resolve("wrong-source-sidecar.archive");
        String wrongSource = "0".repeat(64);
        BlockEntitySidecarStore.writeExact(wrongSidecar, new BlockEntityBackupContract.Envelope(
                spec.operationId(), key, wrongSource, states.length,
                List.of(new BlockEntityBackupContract.Entry(19, "minecraft:chest", new byte[] {10, 0, 0, 0}))));
        boolean wrongSourceRejected = false;
        try {
            ReleaseArchiveCertificate.reconstructWithBlockEntityArchive(
                    manifest, journalPath, receiptPath, archivePath, wrongSidecar, spec);
        } catch (java.io.IOException expected) { wrongSourceRejected = true; }
        if (!wrongSourceRejected) throw new AssertionError("sidecar for different block-state archive certified");
        checks++;

        Files.writeString(sidecarPath, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean corruptSidecarRejected = false;
        try {
            ReleaseArchiveCertificate.reconstructWithBlockEntityArchive(
                    manifest, journalPath, receiptPath, archivePath, sidecarPath, spec);
        } catch (java.io.IOException expected) { corruptSidecarRejected = true; }
        if (!corruptSidecarRejected) throw new AssertionError("corrupt block-entity archive certified");
        checks++;

        Files.writeString(archivePath, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean corruptRejected = false;
        try { ReleaseArchiveCertificate.reconstruct(manifest, journalPath, receiptPath, archivePath, spec); }
        catch (java.io.IOException expected) { corruptRejected = true; }
        if (!corruptRejected) throw new AssertionError("corrupt archive certified");
        checks++;

        return checks;
    }

    private static void appendEvidence(RuntimeReceiptLog receipts, ChunkKey key, String operationId,
            String archiveSha, String sidecarSha, String envelopeSha, int entries) throws Exception {
        receipts.append(ReceiptKind.PREIMAGE_CAPTURED, key,
                "operation=" + operationId + ";preimageSha256=" + archiveSha);
        receipts.append(ReceiptKind.RESTORE_COMPLETE, key,
                "operation=" + operationId + ";preimageSha256=" + archiveSha);
        receipts.append(ReceiptKind.RESTORE_VERIFIED, key,
                "operation=" + operationId + ";preimageSha256=" + archiveSha);
        receipts.append(ReceiptKind.TICKET_RELEASED, key,
                "forced radius=0;restoreVerified=true;preimageArchiveSha256=" + archiveSha
                        + ";blockEntities=" + entries
                        + ";blockEntityArchiveSha256=" + sidecarSha
                        + ";blockEntityEnvelopeSha256=" + envelopeSha);
    }
}
