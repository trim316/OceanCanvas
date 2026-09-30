package net.oceancanvas.core.journal;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Tiny append-only journal used by the restarted core contract.
 *
 * <p>Each line carries a CRC over its payload and append() forces the file to
 * stable storage. This is intentionally boring. The runtime adapter may batch
 * fsyncs later, but it may not weaken the distinction between submitted work
 * and durably committed work.</p>
 */
public final class CoreJournal {
    // Recovery journals are tiny. Reject abnormal growth and an oversized
    // individual record before they can exhaust the recovery process heap.
    private static final long MAX_JOURNAL_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_RECORD_BYTES = 16 * 1024;
    private final Path path;

    public CoreJournal(Path path) { this.path = path; }

    public synchronized void append(JournalEntry entry) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        String payload = encodePayload(entry);
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        String line = payload + "\t" + Long.toUnsignedString(crc.getValue()) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("journal record exceeds safe size bound");
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (ch.size() > MAX_JOURNAL_BYTES - bytes.length) {
                throw new IOException("journal exceeds safe total size bound");
            }
            ByteBuffer pending = ByteBuffer.wrap(bytes);
            while (pending.hasRemaining()) {
                if (ch.write(pending) <= 0) throw new IOException("journal append made no progress");
            }
            ch.force(true);
        }
    }

    public synchronized List<JournalEntry> readVerified() throws IOException {
        if (!Files.exists(path)) return List.of();
        long initialSize = Files.size(path);
        if (initialSize > MAX_JOURNAL_BYTES) throw new IOException("journal exceeds safe total size bound");
        ArrayList<JournalEntry> out = new ArrayList<>();
        byte[] record = new byte[MAX_RECORD_BYTES];
        int used = 0;
        long bytesRead = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int next;
            while ((next = in.read()) != -1) {
                if (++bytesRead > MAX_JOURNAL_BYTES) {
                    throw new IOException("journal grew beyond safe total size bound during verification");
                }
                if (next == '\\n') {
                    // Files.readAllLines previously admitted CRLF; retain that
                    // format without accepting an unterminated final record.
                    int length = used > 0 && record[used - 1] == '\\r' ? used - 1 : used;
                    String line;
                    try {
                        line = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(record, 0, length)).toString();
                    } catch (CharacterCodingException e) {
                        throw new IOException("journal line " + (out.size() + 1) + " malformed UTF-8", e);
                    }
                    int split = line.lastIndexOf('\\t');
                    if (split <= 0) throw new IOException("journal line " + (out.size() + 1) + " missing checksum");
                    String payload = line.substring(0, split);
                    long expectedCrc;
                    try { expectedCrc = Long.parseUnsignedLong(line.substring(split + 1)); }
                    catch (NumberFormatException e) {
                        throw new IOException("journal line " + (out.size() + 1) + " bad checksum", e);
                    }
                    CRC32 crc = new CRC32();
                    crc.update(payload.getBytes(StandardCharsets.UTF_8));
                    if (crc.getValue() != expectedCrc) {
                        throw new IOException("journal line " + (out.size() + 1) + " checksum mismatch");
                    }
                    JournalEntry entry = decodePayload(payload, out.size() + 1);
                    if (entry.sequence() != out.size()) {
                        throw new IOException("journal sequence discontinuity at line " + (out.size() + 1)
                                + ": expected " + out.size() + " got " + entry.sequence());
                    }
                    out.add(entry);
                    used = 0;
                } else {
                    if (used == MAX_RECORD_BYTES) {
                        throw new IOException("journal record exceeds safe size bound");
                    }
                    record[used++] = (byte) next;
                }
            }
        }
        if (used != 0) throw new IOException("journal has unterminated final record");
        // Another writer must not make a prefix appear to be a complete
        // stable replay while validation is still reading this same file.
        if (Files.size(path) != initialSize) throw new IOException("journal changed during verification");
        return List.copyOf(out);
    }

    /** Reconstruct the sole managed chunk from durable transitions and fail closed on any inconsistency. */
    public synchronized ReplayState replaySingleChunk(net.oceancanvas.core.pipeline.ChunkKey expectedChunk) throws IOException {
        if (expectedChunk == null) throw new IllegalArgumentException("expectedChunk required");
        net.oceancanvas.core.pipeline.ChunkRecord record = net.oceancanvas.core.pipeline.ChunkRecord.discovered(expectedChunk);
        List<JournalEntry> entries = readVerified();
        for (int i = 0; i < entries.size(); i++) {
            JournalEntry e = entries.get(i);
            if (!expectedChunk.equals(e.chunk())) throw new IOException("single-chunk journal contains unexpected chunk at entry " + i + ": " + e.chunk());
            if (record.stage() != e.from()) throw new IOException("journal stage discontinuity at entry " + i + ": expected from=" + record.stage() + " got " + e.from());
            net.oceancanvas.core.pipeline.ChunkRecord next;
            if (e.to() == net.oceancanvas.core.pipeline.ChunkStage.FAILED) next = record.fail(e.reason());
            else next = record.advance(e.to(), e.reason());
            if (next.attempt() != e.attempt() || next.revision() != e.revision()) {
                throw new IOException("journal attempt/revision mismatch at entry " + i);
            }
            record = next;
        }
        return new ReplayState(record, entries.size());
    }

    public record ReplayState(net.oceancanvas.core.pipeline.ChunkRecord record, long nextSequence) {}

    private static String encodePayload(JournalEntry e) {
        return e.sequence() + "\t" + e.epochMillis() + "\t" + e.chunk().x() + "\t" + e.chunk().z()
                + "\t" + e.from().name() + "\t" + e.to().name() + "\t" + e.attempt() + "\t" + e.revision()
                + "\t" + escape(e.reason());
    }

    private static JournalEntry decodePayload(String payload, int lineNo) throws IOException {
        String[] p = payload.split("\\t", -1);
        if (p.length != 9) throw new IOException("journal line " + lineNo + " has " + p.length + " fields");
        try {
            return new JournalEntry(Long.parseLong(p[0]), Long.parseLong(p[1]),
                    new ChunkKey(Integer.parseInt(p[2]), Integer.parseInt(p[3])),
                    ChunkStage.valueOf(p[4]), ChunkStage.valueOf(p[5]),
                    Long.parseLong(p[6]), Long.parseLong(p[7]), unescape(p[8]));
        } catch (RuntimeException e) {
            throw new IOException("journal line " + lineNo + " cannot be decoded", e);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }
    private static String unescape(String s) throws IOException {
        StringBuilder out = new StringBuilder(s.length());
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                switch (c) {
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case '\\' -> out.append('\\');
                    default -> throw new IOException("journal contains invalid reason escape sequence");
                }
                escaped = false;
            } else if (c == '\\') escaped = true;
            else out.append(c);
        }
        if (escaped) throw new IOException("journal contains truncated reason escape sequence");
        return out.toString();
    }
}
