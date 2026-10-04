package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Non-authoritative forensic receipts. Stage truth remains in CoreJournal. */
public final class RuntimeReceiptLog {
    // Forensic receipts are bounded metadata, never an unlimited heap-backed
    // append log. A pathological file is evidence of corruption and must be
    // rejected before readAllBytes allocates or further records are appended.
    private static final long MAX_RECEIPT_BYTES = 8L * 1024L * 1024L;
    private final Path path;
    private long nextSequence = -1L;

    public enum WorldMutationState {
        NOT_STARTED,
        POSSIBLE,
        CONFIRMED
    }

    public RuntimeReceiptLog(Path path) { this.path = path; }

    /**
     * Classify mutation state from durable pipeline authority only. PREIMAGE_CAPTURED
     * is deliberately POSSIBLE because a crash/failure can occur either before the
     * first authoring write or after a partial write but before PHYSICAL_AUTHORED is
     * journaled. Later stages prove that destructive authoring completed at least once.
     */
    public static WorldMutationState classifyWorldMutation(ChunkStage failingStage) {
        Objects.requireNonNull(failingStage, "failingStage");
        return switch (failingStage) {
            case DISCOVERED, LOADED -> WorldMutationState.NOT_STARTED;
            case PREIMAGE_CAPTURED -> WorldMutationState.POSSIBLE;
            case PHYSICAL_AUTHORED, PHYSICAL_SETTLED, PERSISTED, LIGHTING_SETTLED,
                    VERIFIED, RESTORED, RESTORE_VERIFIED -> WorldMutationState.CONFIRMED;
            case COMPLETE, FAILED -> throw new IllegalArgumentException(
                    "first-failure diagnostic cannot originate from terminal stage " + failingStage);
        };
    }

    /**
     * Persist a path-free first-failure identity. The raw failure text is never
     * written to the receipt because adapter exceptions may contain mutable
     * world paths. Its SHA-256 still binds this diagnostic to the exact failure.
     *
     * worldMutation is a conservative tri-state derived only from durable stage
     * authority: NOT_STARTED, POSSIBLE, or CONFIRMED. It never guesses that an
     * in-flight PREIMAGE_CAPTURED authoring failure was mutation-free.
     *
     * The independent failure snapshot is deliberately best-effort and runs even
     * if the forensic receipt append itself fails. It can preserve earlier durable
     * evidence, but can never make a failed append or pipeline transition succeed.
     */
    public synchronized RuntimeReceipt appendFirstFailure(
            ChunkStage stage, ChunkKey chunk, String operationId,
            String sourceCandidate, String failureReason) throws IOException {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(chunk, "chunk");
        WorldMutationState worldMutation = classifyWorldMutation(stage);
        String operation = stableIdentityToken(operationId, "operationId");
        String candidate = stableIdentityToken(sourceCandidate, "sourceCandidate");
        String failureSha256 = sha256Hex(failureReason == null ? "" : failureReason);
        String canonical = "schema=2\n"
                + "stage=" + stage.name() + "\n"
                + "chunk=" + chunk.x() + "," + chunk.z() + "\n"
                + "operation=" + operation + "\n"
                + "sourceCandidate=" + candidate + "\n"
                + "worldMutation=" + worldMutation.name() + "\n"
                + "failureSha256=" + failureSha256;
        String contextSha256 = sha256Hex(canonical);
        String detail = "schema=2"
                + ";stage=" + stage.name()
                + ";chunk=" + chunk.x() + "," + chunk.z()
                + ";operation=" + operation
                + ";sourceCandidate=" + candidate
                + ";worldMutation=" + worldMutation.name()
                + ";failureSha256=" + failureSha256
                + ";contextSha256=" + contextSha256;
        try {
            return append(ReceiptKind.FIRST_FAILURE_DIAGNOSTIC, chunk, detail);
        } finally {
            FailureEvidenceSnapshot.captureBestEffort(path.toAbsolutePath().getParent(), stage);
        }
    }

    public synchronized RuntimeReceipt append(ReceiptKind kind, ChunkKey chunk, String detail) throws IOException {
        if (nextSequence < 0) nextSequence = readVerified().size();
        // Do not consume a sequence until the full record is fsynced. Failed
        // opens/writes must not fabricate a gap on the next append attempt.
        RuntimeReceipt receipt = new RuntimeReceipt(nextSequence, System.currentTimeMillis(), kind, chunk, detail);
        Files.createDirectories(path.toAbsolutePath().getParent());
        String payload = encode(receipt);
        CRC32 crc = new CRC32(); crc.update(payload.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = (payload + "\t" + Long.toUnsignedString(crc.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
        // A refusal must not mutate an oversized forensic file or consume a
        // sequence; metadata alone is sufficient to enforce this bound.
        if (bytes.length > MAX_RECEIPT_BYTES
                || (Files.exists(path) && Files.size(path) > MAX_RECEIPT_BYTES - bytes.length)) {
            throw new IOException("forensic receipt exceeds safe serialized size bound");
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer pending = ByteBuffer.wrap(bytes);
            while (pending.hasRemaining()) {
                if (ch.write(pending) <= 0) throw new IOException("receipt append made no progress");
            }
            ch.force(true);
        }
        nextSequence++;
        return receipt;
    }

    public synchronized List<RuntimeReceipt> readVerified() throws IOException {
        if (!Files.exists(path)) return List.of();
        if (Files.size(path) > MAX_RECEIPT_BYTES) {
            throw new IOException("forensic receipt exceeds safe serialized size bound");
        }
        byte[] raw = Files.readAllBytes(path);
        if (raw.length > 0 && raw[raw.length - 1] != (byte) 10) {
            throw new IOException("receipt has unterminated final record");
        }
        // A replacement-character decoder can normalize malformed bytes and
        // mistakenly accept an attacker-recomputed CRC on altered evidence.
        // Decode the exact bounded bytes with REPORT, never REPLACE.
        final List<String> lines;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            lines = decoded.lines().toList();
        } catch (CharacterCodingException e) {
            throw new IOException("forensic receipt contains malformed UTF-8", e);
        }
        ArrayList<RuntimeReceipt> out = new ArrayList<>(lines.size());
        long expected = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i); int split = line.lastIndexOf('\t');
            if (split <= 0) throw new IOException("receipt line " + (i + 1) + " missing checksum");
            String payload = line.substring(0, split); long stored;
            try { stored = Long.parseUnsignedLong(line.substring(split + 1)); }
            catch (RuntimeException e) { throw new IOException("receipt line " + (i + 1) + " bad checksum", e); }
            CRC32 crc = new CRC32(); crc.update(payload.getBytes(StandardCharsets.UTF_8));
            if (crc.getValue() != stored) throw new IOException("receipt line " + (i + 1) + " checksum mismatch");
            RuntimeReceipt r = decode(payload, i + 1);
            if (r.sequence() != expected++) throw new IOException("receipt sequence discontinuity at line " + (i + 1));
            out.add(r);
        }
        return List.copyOf(out);
    }

    private static String stableIdentityToken(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isEmpty() || value.length() > 256) {
            throw new IllegalArgumentException(label + " length invalid");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '+';
            if (!allowed) {
                throw new IllegalArgumentException(label + " must be a stable path-free identity token");
            }
        }
        return value;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String encode(RuntimeReceipt r) {
        return r.sequence() + "\t" + r.epochMillis() + "\t" + r.kind().name() + "\t" + r.chunk().x() + "\t" + r.chunk().z() + "\t" + escape(r.detail());
    }
    private static RuntimeReceipt decode(String payload, int lineNo) throws IOException {
        String[] p = payload.split("\\t", -1);
        if (p.length != 6) throw new IOException("receipt line " + lineNo + " field count " + p.length);
        try { return new RuntimeReceipt(Long.parseLong(p[0]), Long.parseLong(p[1]), ReceiptKind.valueOf(p[2]), new ChunkKey(Integer.parseInt(p[3]), Integer.parseInt(p[4])), unescape(p[5])); }
        catch (RuntimeException e) { throw new IOException("receipt line " + lineNo + " cannot be decoded", e); }
    }
    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r"); }
    private static String unescape(String s) throws IOException {
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                switch (c) {
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case '\\' -> out.append('\\');
                    default -> throw new IOException("receipt contains invalid detail escape sequence");
                }
                escaped = false;
            } else if (c == '\\') escaped = true;
            else out.append(c);
        }
        if (escaped) throw new IOException("receipt contains truncated detail escape sequence");
        return out.toString();
    }
}
