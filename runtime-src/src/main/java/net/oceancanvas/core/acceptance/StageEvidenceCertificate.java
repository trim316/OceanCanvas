package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.journal.JournalEntry;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.pipeline.OperationManifestStore;
import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only stage-bound evidence certificate.
 *
 * <p>PASS is never inferred from later lifecycle progress. A normal stage is
 * PASS only when its authoritative journal transition and its stage-specific
 * receipt are both present. A durable transition to FAILED marks only the
 * attempted next normal stage FAIL. All other states remain UNKNOWN.</p>
 */
public final class StageEvidenceCertificate {
    private static final long MAX_EVIDENCE_FILE_BYTES = 8L * 1024L * 1024L;

    public enum EvidenceStatus {
        PASS,
        FAIL,
        UNKNOWN
    }

    private StageEvidenceCertificate() {}

    public static Certificate reconstruct(
            Path manifestPath, Path journalPath, Path receiptPath,
            SingleChunkOperationSpec expected) throws IOException {
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(journalPath, "journalPath");
        Objects.requireNonNull(receiptPath, "receiptPath");
        Objects.requireNonNull(expected, "expected");

        SingleChunkOperationSpec manifestBefore = OperationManifestStore.readVerified(manifestPath);
        if (!manifestBefore.equals(expected)) {
            throw new IOException("stage evidence manifest does not match expected operation");
        }

        boolean journalPresentBefore = Files.exists(journalPath);
        boolean receiptPresentBefore = Files.exists(receiptPath);
        CoreJournal journal = new CoreJournal(journalPath);
        List<JournalEntry> journalBefore = journal.readVerified();
        CoreJournal.ReplayState replayBefore = journal.replaySingleChunk(expected.chunk());

        RuntimeReceiptLog receiptLog = new RuntimeReceiptLog(receiptPath);
        List<RuntimeReceipt> receiptsBefore = receiptLog.readVerified();
        for (RuntimeReceipt receipt : receiptsBefore) {
            if (!expected.chunk().equals(receipt.chunk())) {
                throw new IOException("stage evidence receipt log contains another chunk");
            }
        }

        String manifestSha = stableSha256Required(manifestPath);
        String journalSha = stableSha256Optional(journalPath);
        String receiptSha = stableSha256Optional(receiptPath);

        SingleChunkOperationSpec manifestAfter = OperationManifestStore.readVerified(manifestPath);
        boolean journalPresentAfter = Files.exists(journalPath);
        boolean receiptPresentAfter = Files.exists(receiptPath);
        List<JournalEntry> journalAfter = journal.readVerified();
        CoreJournal.ReplayState replayAfter = journal.replaySingleChunk(expected.chunk());
        List<RuntimeReceipt> receiptsAfter = receiptLog.readVerified();
        if (!manifestBefore.equals(manifestAfter)
                || journalPresentBefore != journalPresentAfter
                || receiptPresentBefore != receiptPresentAfter
                || !journalBefore.equals(journalAfter)
                || !replayBefore.equals(replayAfter)
                || !receiptsBefore.equals(receiptsAfter)) {
            throw new IOException("stage evidence changed during reconstruction");
        }

        Map<ChunkStage, EvidenceStatus> statuses =
                statuses(expected, journalAfter, receiptsAfter);
        Certificate unsigned = new Certificate(
                1,
                expected.operationId(),
                expected.chunk(),
                replayAfter.record().stage(),
                manifestSha,
                journalSha,
                receiptSha,
                statuses,
                "");
        String certificateSha = sha256Hex(unsigned.canonicalPayload());
        return new Certificate(
                unsigned.schemaVersion(), unsigned.operationId(), unsigned.chunk(),
                unsigned.observedStage(), unsigned.manifestSha256(),
                unsigned.journalSha256(), unsigned.receiptSha256(),
                unsigned.statuses(), certificateSha);
    }

    public record Certificate(
            int schemaVersion,
            String operationId,
            ChunkKey chunk,
            ChunkStage observedStage,
            String manifestSha256,
            String journalSha256,
            String receiptSha256,
            Map<ChunkStage, EvidenceStatus> statuses,
            String certificateSha256) {

        public Certificate {
            if (schemaVersion != 1) throw new IllegalArgumentException("unsupported stage evidence schema");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(observedStage, "observedStage");
            requireSha(manifestSha256, "manifestSha256");
            requireShaOrAbsent(journalSha256, "journalSha256");
            requireShaOrAbsent(receiptSha256, "receiptSha256");
            Objects.requireNonNull(statuses, "statuses");
            EnumMap<ChunkStage, EvidenceStatus> copy = new EnumMap<>(ChunkStage.class);
            for (ChunkStage stage : normalStages()) {
                EvidenceStatus status = statuses.get(stage);
                if (status == null) throw new IllegalArgumentException("missing stage evidence status for " + stage);
                copy.put(stage, status);
            }
            statuses = Collections.unmodifiableMap(copy);
            if (!certificateSha256.isEmpty()) requireSha(certificateSha256, "certificateSha256");
        }

        public EvidenceStatus status(ChunkStage stage) {
            Objects.requireNonNull(stage, "stage");
            if (stage == ChunkStage.FAILED) {
                throw new IllegalArgumentException("FAILED is an observed terminal state, not a normal stage evidence target");
            }
            return statuses.get(stage);
        }

        public String canonicalPayload() {
            StringBuilder out = new StringBuilder();
            out.append("schemaVersion=").append(schemaVersion).append('\n');
            out.append("operationId=").append(operationId).append('\n');
            out.append("chunkX=").append(chunk.x()).append('\n');
            out.append("chunkZ=").append(chunk.z()).append('\n');
            out.append("observedStage=").append(observedStage.name()).append('\n');
            out.append("manifestSha256=").append(manifestSha256).append('\n');
            out.append("journalSha256=").append(journalSha256).append('\n');
            out.append("receiptSha256=").append(receiptSha256).append('\n');
            for (ChunkStage stage : normalStages()) {
                out.append("stage.").append(stage.name()).append('=')
                        .append(statuses.get(stage).name()).append('\n');
            }
            return out.toString();
        }

        public boolean selfVerifies() {
            return !certificateSha256.isEmpty()
                    && certificateSha256.equals(sha256Hex(canonicalPayload()));
        }
    }

    private static Map<ChunkStage, EvidenceStatus> statuses(
            SingleChunkOperationSpec expected,
            List<JournalEntry> journal,
            List<RuntimeReceipt> receipts) throws IOException {
        EnumMap<ChunkStage, EvidenceStatus> result = new EnumMap<>(ChunkStage.class);
        for (ChunkStage stage : normalStages()) result.put(stage, EvidenceStatus.UNKNOWN);
        result.put(ChunkStage.DISCOVERED, EvidenceStatus.PASS);

        EnumMap<ChunkStage, Boolean> journalPass = new EnumMap<>(ChunkStage.class);
        ChunkStage failedTarget = null;
        for (JournalEntry entry : journal) {
            if (!expected.chunk().equals(entry.chunk())) {
                throw new IOException("stage evidence journal contains another chunk");
            }
            if (entry.to() == ChunkStage.FAILED) {
                failedTarget = nextNormalStage(entry.from());
                continue;
            }
            journalPass.put(entry.to(), true);
        }

        for (ChunkStage stage : normalStages()) {
            if (stage == ChunkStage.DISCOVERED) continue;
            if (stage == failedTarget) {
                result.put(stage, EvidenceStatus.FAIL);
                continue;
            }
            if (Boolean.TRUE.equals(journalPass.get(stage))
                    && hasRequiredReceipt(stage, expected.operationId(), receipts)) {
                result.put(stage, EvidenceStatus.PASS);
            }
        }
        return result;
    }

    private static boolean hasRequiredReceipt(
            ChunkStage stage, String operationId, List<RuntimeReceipt> receipts) {
        ReceiptKind kind = switch (stage) {
            case LOADED -> ReceiptKind.CHUNK_RESIDENT;
            case PREIMAGE_CAPTURED -> ReceiptKind.PREIMAGE_CAPTURED;
            case PHYSICAL_AUTHORED -> ReceiptKind.PHYSICAL_AUTHORING_COMPLETE;
            case PHYSICAL_SETTLED -> ReceiptKind.PHYSICAL_SETTLEMENT_VERIFIED;
            case PERSISTED -> ReceiptKind.SAVE_FLUSH_COMPLETE;
            case LIGHTING_SETTLED -> ReceiptKind.LIGHT_REQUEST_COMPLETE;
            case VERIFIED -> ReceiptKind.SERVER_VERIFICATION_COMPLETE;
            case RESTORED -> ReceiptKind.RESTORE_COMPLETE;
            case RESTORE_VERIFIED -> ReceiptKind.RESTORE_VERIFIED;
            case COMPLETE -> ReceiptKind.TICKET_RELEASED;
            case DISCOVERED, FAILED -> null;
        };
        if (kind == null) return stage == ChunkStage.DISCOVERED;
        for (RuntimeReceipt receipt : receipts) {
            if (receipt.kind() != kind) continue;
            if ((stage == ChunkStage.PREIMAGE_CAPTURED
                    || stage == ChunkStage.RESTORED
                    || stage == ChunkStage.RESTORE_VERIFIED)
                    && !operationId.equals(token(receipt.detail(), "operation"))) {
                continue;
            }
            if (stage == ChunkStage.COMPLETE
                    && !"true".equals(token(receipt.detail(), "restoreVerified"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String token(String detail, String key) {
        if (detail == null) return null;
        String prefix = key + "=";
        String found = null;
        for (String item : detail.split(";")) {
            if (!item.startsWith(prefix)) continue;
            if (found != null) return null;
            found = item.substring(prefix.length());
        }
        return found;
    }

    private static ChunkStage nextNormalStage(ChunkStage from) throws IOException {
        return switch (from) {
            case DISCOVERED -> ChunkStage.LOADED;
            case LOADED -> ChunkStage.PREIMAGE_CAPTURED;
            case PREIMAGE_CAPTURED -> ChunkStage.PHYSICAL_AUTHORED;
            case PHYSICAL_AUTHORED -> ChunkStage.PHYSICAL_SETTLED;
            case PHYSICAL_SETTLED -> ChunkStage.PERSISTED;
            case PERSISTED -> ChunkStage.LIGHTING_SETTLED;
            case LIGHTING_SETTLED -> ChunkStage.VERIFIED;
            case VERIFIED -> ChunkStage.RESTORED;
            case RESTORED -> ChunkStage.RESTORE_VERIFIED;
            case RESTORE_VERIFIED -> ChunkStage.COMPLETE;
            case COMPLETE, FAILED -> throw new IOException("terminal stage cannot have attempted next-stage evidence");
        };
    }

    private static List<ChunkStage> normalStages() {
        return List.of(
                ChunkStage.DISCOVERED,
                ChunkStage.LOADED,
                ChunkStage.PREIMAGE_CAPTURED,
                ChunkStage.PHYSICAL_AUTHORED,
                ChunkStage.PHYSICAL_SETTLED,
                ChunkStage.PERSISTED,
                ChunkStage.LIGHTING_SETTLED,
                ChunkStage.VERIFIED,
                ChunkStage.RESTORED,
                ChunkStage.RESTORE_VERIFIED,
                ChunkStage.COMPLETE);
    }

    private static String stableSha256Required(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("required evidence file is missing: " + path.getFileName());
        return stableSha256(path);
    }

    private static String stableSha256Optional(Path path) throws IOException {
        if (!Files.exists(path)) return "ABSENT";
        if (!Files.isRegularFile(path)) throw new IOException("evidence path is not a regular file: " + path.getFileName());
        return stableSha256(path);
    }

    private static String stableSha256(Path path) throws IOException {
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

    private static void requireSha(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
    }

    private static void requireShaOrAbsent(String value, String label) {
        if (!"ABSENT".equals(value)) requireSha(value, label);
    }
}
