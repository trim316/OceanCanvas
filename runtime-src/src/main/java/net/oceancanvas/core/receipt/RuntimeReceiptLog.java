package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Non-authoritative forensic receipts. Stage truth remains in CoreJournal. */
public final class RuntimeReceiptLog {
    private final Path path;
    private long nextSequence = -1L;

    public RuntimeReceiptLog(Path path) { this.path = path; }

    public synchronized RuntimeReceipt append(ReceiptKind kind, ChunkKey chunk, String detail) throws IOException {
        if (nextSequence < 0) nextSequence = readVerified().size();
        RuntimeReceipt receipt = new RuntimeReceipt(nextSequence++, System.currentTimeMillis(), kind, chunk, detail);
        Files.createDirectories(path.toAbsolutePath().getParent());
        String payload = encode(receipt);
        CRC32 crc = new CRC32(); crc.update(payload.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = (payload + "\t" + Long.toUnsignedString(crc.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(bytes)); ch.force(true);
        }
        return receipt;
    }

    public synchronized List<RuntimeReceipt> readVerified() throws IOException {
        if (!Files.exists(path)) return List.of();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
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
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(); boolean escaped = false;
        for (int i=0;i<s.length();i++) { char c=s.charAt(i); if (escaped) { out.append(switch(c){case 't'->'\t';case 'n'->'\n';case 'r'->'\r';default->c;}); escaped=false; } else if(c=='\\') escaped=true; else out.append(c); }
        if (escaped) out.append('\\'); return out.toString();
    }
}
