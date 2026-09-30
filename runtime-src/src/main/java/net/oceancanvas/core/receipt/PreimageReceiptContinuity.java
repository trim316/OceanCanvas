package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Independent consistency check over CRC-verified forensic receipts. This
 * validates evidence continuity, not restored Minecraft block states or the
 * authoritative journal; those must be proven by separate runtime gates.
 */
public final class PreimageReceiptContinuity {
    private PreimageReceiptContinuity() {}

    public record Verified(String operationId, ChunkKey chunk, String preimageSha256,
                           int captures, int restoreFlushes, int restoreVerifications,
                           boolean immutableArchiveVerified) {}

    public static Verified verify(List<RuntimeReceipt> receipts, String operationId,
                                  ChunkKey chunk, boolean requireArchive) throws IOException {
        Objects.requireNonNull(receipts, "receipts");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(chunk, "chunk");
        if (operationId.isBlank()) throw new IOException("missing expected operation identity");
        String hash = null;
        int captures = 0, flushes = 0, verifications = 0;
        boolean archiveVerified = false;
        long lastSequence = -1;
        for (RuntimeReceipt receipt : receipts) {
            if (!chunk.equals(receipt.chunk())) {
                throw new IOException("mixed chunk forensic receipt at sequence " + receipt.sequence());
            }
            if (receipt.sequence() != lastSequence + 1) {
                throw new IOException("forensic receipt sequence discontinuity");
            }
            lastSequence = receipt.sequence();

            ReceiptKind kind = receipt.kind();
            boolean relevant = kind == ReceiptKind.PREIMAGE_CAPTURED
                    || kind == ReceiptKind.RESTORE_COMPLETE
                    || kind == ReceiptKind.RESTORE_VERIFIED
                    || kind == ReceiptKind.TICKET_RELEASED;
            if (!relevant) continue;
            // Ordinary session-close ticket receipts are free-form diagnostic
            // messages, not final archival claims. Ignore them, but require
            // a strict proof whenever the archive field is actually present.
            if (kind == ReceiptKind.TICKET_RELEASED
                    && !receipt.detail().contains("preimageArchiveSha256")) continue;
            Map<String, String> fields = parseFields(receipt.detail());
            if (kind == ReceiptKind.TICKET_RELEASED) {
                String archiveHash = fields.get("preimageArchiveSha256");
                if (archiveHash != null) {
                    if (verifications == 0 || !validHash(archiveHash)
                            || !archiveHash.equals(hash)
                            || !"true".equals(fields.get("restoreVerified"))) {
                        throw new IOException("ticket-release archival proof does not match verified restore");
                    }
                    if (fields.containsKey("operation")
                            && !operationId.equals(fields.get("operation"))) {
                        throw new IOException("archive receipt operation identity mismatch");
                    }
                    archiveVerified = true;
                }
                continue;
            }
            if (!operationId.equals(fields.get("operation"))) {
                throw new IOException("preimage receipt operation identity mismatch at sequence "
                        + receipt.sequence());
            }
            String stageHash = fields.get("preimageSha256");
            if (!validHash(stageHash)) throw new IOException("missing or invalid preimage SHA-256");
            if (hash == null) hash = stageHash;
            else if (!hash.equals(stageHash)) throw new IOException("preimage SHA-256 continuity mismatch");

            switch (kind) {
                case PREIMAGE_CAPTURED -> {
                    if (flushes > 0 || verifications > 0 || archiveVerified)
                        throw new IOException("preimage capture after restoration");
                    captures++;
                }
                case RESTORE_COMPLETE -> {
                    if (captures == 0 || verifications > 0 || archiveVerified)
                        throw new IOException("restore flush missing capture or out of order");
                    flushes++;
                }
                case RESTORE_VERIFIED -> {
                    if (flushes == 0 || archiveVerified)
                        throw new IOException("restore verification missing durable flush");
                    verifications++;
                }
                default -> throw new IOException("unhandled preimage receipt state");
            }
        }
        if (captures == 0 || flushes == 0 || verifications == 0
                || (requireArchive && !archiveVerified)) {
            throw new IOException("incomplete preimage receipt continuity proof");
        }
        return new Verified(operationId, chunk, hash, captures, flushes, verifications,
                archiveVerified);
    }

    private static boolean validHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    private static Map<String, String> parseFields(String detail) throws IOException {
        Map<String, String> result = new HashMap<>();
        for (String field : detail.split(";", -1)) {
            int split = field.indexOf('=');
            if (split <= 0 || split == field.length() - 1) {
                throw new IOException("malformed forensic receipt detail field");
            }
            String key = field.substring(0, split);
            String value = field.substring(split + 1);
            if (result.putIfAbsent(key, value) != null) {
                throw new IOException("duplicate forensic receipt detail key " + key);
            }
        }
        return result;
    }
}
