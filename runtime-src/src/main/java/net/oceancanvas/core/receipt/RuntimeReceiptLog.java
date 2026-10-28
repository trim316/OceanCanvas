package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Non-authoritative forensic receipts. Stage truth remains in CoreJournal. */
public final class RuntimeReceiptLog {
    private static final long MAX_RECEIPT_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_RECEIPT_READ_BYTES = Math.toIntExact(MAX_RECEIPT_BYTES);
    private final Path path;
    private long nextSequence = -1L;

    @FunctionalInterface interface ForceOperation { void force(FileChannel channel) throws IOException; }
    @FunctionalInterface interface AtomicMoveOperation { void move(Path source, Path target) throws IOException; }
    private static final ForceOperation NIO_FORCE = channel -> channel.force(true);
    private static final AtomicMoveOperation NIO_ATOMIC_REPLACE = (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

    public enum WorldMutationState { NOT_STARTED, POSSIBLE, CONFIRMED }
    public RuntimeReceiptLog(Path path) { this.path = path; }

    public static WorldMutationState classifyWorldMutation(ChunkStage failingStage) {
        Objects.requireNonNull(failingStage, "failingStage");
        return switch (failingStage) {
            case DISCOVERED, LOADED -> WorldMutationState.NOT_STARTED;
            case PREIMAGE_CAPTURED -> WorldMutationState.POSSIBLE;
            case PHYSICAL_AUTHORED, PHYSICAL_SETTLED, PERSISTED, LIGHTING_SETTLED, VERIFIED, RESTORED, RESTORE_VERIFIED -> WorldMutationState.CONFIRMED;
            case COMPLETE, FAILED -> throw new IllegalArgumentException("first-failure diagnostic cannot originate from terminal stage " + failingStage);
        };
    }

    public synchronized RuntimeReceipt appendFirstFailure(ChunkStage stage, ChunkKey chunk, String operationId, String sourceCandidate, String failureReason) throws IOException {
        Objects.requireNonNull(stage, "stage"); Objects.requireNonNull(chunk, "chunk");
        WorldMutationState worldMutation = classifyWorldMutation(stage);
        String operation = stableIdentityToken(operationId, "operationId");
        String candidate = stableIdentityToken(sourceCandidate, "sourceCandidate");
        String failureSha256 = sha256Hex(failureReason == null ? "" : failureReason);
        String canonical = "schema=2\n" + "stage=" + stage.name() + "\n" + "chunk=" + chunk.x() + "," + chunk.z() + "\n" + "operation=" + operation + "\n" + "sourceCandidate=" + candidate + "\n" + "worldMutation=" + worldMutation.name() + "\n" + "failureSha256=" + failureSha256;
        String contextSha256 = sha256Hex(canonical);
        String detail = "schema=2;stage=" + stage.name() + ";chunk=" + chunk.x() + "," + chunk.z() + ";operation=" + operation + ";sourceCandidate=" + candidate + ";worldMutation=" + worldMutation.name() + ";failureSha256=" + failureSha256 + ";contextSha256=" + contextSha256;
        try { return append(ReceiptKind.FIRST_FAILURE_DIAGNOSTIC, chunk, detail); }
        finally { FailureEvidenceSnapshot.captureBestEffort(path.toAbsolutePath().getParent(), stage); }
    }

    public synchronized RuntimeReceipt append(ReceiptKind kind, ChunkKey chunk, String detail) throws IOException { return append(kind, chunk, detail, NIO_FORCE, NIO_ATOMIC_REPLACE); }

    synchronized RuntimeReceipt append(ReceiptKind kind, ChunkKey chunk, String detail, ForceOperation forceOperation, AtomicMoveOperation atomicMove) throws IOException {
        Objects.requireNonNull(forceOperation, "forceOperation"); Objects.requireNonNull(atomicMove, "atomicMove");
        if (nextSequence < 0) nextSequence = readVerified().size();
        RuntimeReceipt receipt = new RuntimeReceipt(nextSequence, System.currentTimeMillis(), kind, chunk, detail);
        Files.createDirectories(path.toAbsolutePath().getParent());
        String payload = encode(receipt); CRC32 crc = new CRC32(); crc.update(payload.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = (payload + "\t" + Long.toUnsignedString(crc.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
        boolean canonicalExists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (canonicalExists && !directRegular(path)) throw new IOException("forensic receipt path is not a direct regular file");
        long canonicalSize = canonicalExists ? Files.size(path) : 0L;
        if (bytes.length > MAX_RECEIPT_BYTES || canonicalSize > MAX_RECEIPT_BYTES - bytes.length) throw new IOException("forensic receipt exceeds safe serialized size bound");

        Path stage = path.resolveSibling(path.getFileName() + ".append.tmp");
        if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
            if (!directRegular(stage)) throw new IOException("forensic receipt staging path is not a direct regular file");
            throw new IOException("surviving forensic receipt stage makes append authority ambiguous");
        }
        if (canonicalExists) copyCanonicalToStageDirect(stage, canonicalSize);
        else {
            try (FileChannel ignored = FileChannel.open(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { }
        }
        try (FileChannel ch = FileChannel.open(stage, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer pending = ByteBuffer.wrap(bytes);
            while (pending.hasRemaining()) if (ch.write(pending) <= 0) throw new IOException("receipt append made no progress");
            forceOperation.force(ch);
        }
        requireDirectRegular(stage);
        boolean currentExists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (currentExists != canonicalExists || (currentExists && !directRegular(path))) throw new IOException("forensic receipt authority changed before staged append publication");
        long currentCanonicalSize = currentExists ? Files.size(path) : 0L;
        if (currentCanonicalSize != canonicalSize) throw new IOException("forensic receipt changed before staged append publication");
        if (canonicalSize > 0 && !prefixMatchesCanonical(stage, canonicalSize)) throw new IOException("forensic receipt authority changed before staged append publication");
        try { atomicMove.move(stage, path); }
        catch (AtomicMoveNotSupportedException e) { throw new IOException("atomic forensic receipt publication unavailable; staged evidence preserved", e); }
        requireDirectRegular(path);
        nextSequence++; return receipt;
    }

    private void copyCanonicalToStageDirect(Path stage, long canonicalSize) throws IOException {
        requireDirectRegular(path);
        try (FileChannel source = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel target = FileChannel.open(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long copied = 0L;
            while (copied < canonicalSize) {
                long count = source.transferTo(copied, canonicalSize - copied, target);
                if (count <= 0L) throw new IOException("forensic receipt canonical copy made no progress");
                copied += count;
            }
            if (source.position() > canonicalSize || source.size() != canonicalSize || target.size() != canonicalSize) throw new IOException("forensic receipt changed while staging append");
        }
        requireDirectRegular(path); requireDirectRegular(stage);
        if (Files.size(path) != canonicalSize || Files.size(stage) != canonicalSize) throw new IOException("forensic receipt changed while staging append");
    }

    private boolean prefixMatchesCanonical(Path stage, long canonicalSize) throws IOException {
        if (!directRegular(path) || !directRegular(stage)) return false;
        if (Files.size(path) != canonicalSize || Files.size(stage) < canonicalSize) return false;
        try (FileChannel canonical = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); FileChannel staged = FileChannel.open(stage, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer left = ByteBuffer.allocate(8192), right = ByteBuffer.allocate(8192); long remaining = canonicalSize;
            while (remaining > 0) {
                int wanted = (int)Math.min(left.capacity(), remaining); left.clear().limit(wanted); right.clear().limit(wanted);
                if (!readFully(canonical, left) || !readFully(staged, right)) return false;
                left.flip(); right.flip(); if (!left.equals(right)) return false; remaining -= wanted;
            }
        }
        return directRegular(path) && directRegular(stage) && Files.size(path) == canonicalSize;
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) { int read = channel.read(buffer); if (read < 0) return false; if (read == 0) continue; } return true;
    }

    public synchronized List<RuntimeReceipt> readVerified() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
        requireDirectRegular(path);
        long expectedSize = Files.size(path);
        if (expectedSize > MAX_RECEIPT_BYTES) throw new IOException("forensic receipt exceeds safe serialized size bound");
        byte[] raw = readBoundedStable(expectedSize);
        if (raw.length > 0 && raw[raw.length - 1] != (byte)10) throw new IOException("receipt has unterminated final record");
        final List<String> lines;
        try { lines = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().lines().toList(); }
        catch (CharacterCodingException e) { throw new IOException("forensic receipt contains malformed UTF-8", e); }
        ArrayList<RuntimeReceipt> out = new ArrayList<>(lines.size()); long expected = 0;
        for (int i=0;i<lines.size();i++) {
            String line=lines.get(i); int split=line.lastIndexOf('\t'); if(split<=0) throw new IOException("receipt line "+(i+1)+" missing checksum");
            String payload=line.substring(0,split); long stored; try { stored=Long.parseUnsignedLong(line.substring(split+1)); } catch(RuntimeException e){ throw new IOException("receipt line "+(i+1)+" bad checksum",e); }
            CRC32 crc=new CRC32(); crc.update(payload.getBytes(StandardCharsets.UTF_8)); if(crc.getValue()!=stored) throw new IOException("receipt line "+(i+1)+" checksum mismatch");
            RuntimeReceipt r=decode(payload,i+1); if(r.sequence()!=expected++) throw new IOException("receipt sequence discontinuity at line "+(i+1)); out.add(r);
        }
        return List.copyOf(out);
    }

    private byte[] readBoundedStable(long expectedSize) throws IOException {
        requireDirectRegular(path); int expected=Math.toIntExact(expectedSize); byte[] bounded=new byte[Math.min(MAX_RECEIPT_READ_BYTES+1,expected+1)]; int total=0;
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            while(total<bounded.length){ int read=channel.read(ByteBuffer.wrap(bounded,total,bounded.length-total)); if(read<0)break; if(read==0)continue; total+=read; }
        }
        if(!directRegular(path) || total!=expected || Files.size(path)!=expectedSize) throw new IOException("forensic receipt changed while reading");
        if(total>MAX_RECEIPT_READ_BYTES) throw new IOException("forensic receipt exceeds safe serialized size bound");
        return total==bounded.length?bounded:Arrays.copyOf(bounded,total);
    }

    private static boolean directRegular(Path file) { return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS); }
    private static void requireDirectRegular(Path file) throws IOException { if(!directRegular(file)) throw new IOException("forensic receipt path is not a direct regular file"); }

    private static String stableIdentityToken(String value,String label){ Objects.requireNonNull(value,label); if(value.isEmpty()||value.length()>256)throw new IllegalArgumentException(label+" length invalid"); for(int i=0;i<value.length();i++){char c=value.charAt(i);boolean allowed=(c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.'||c=='+';if(!allowed)throw new IllegalArgumentException(label+" must be a stable path-free identity token");}return value; }
    private static String sha256Hex(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}}
    private static String encode(RuntimeReceipt r){return r.sequence()+"\t"+r.epochMillis()+"\t"+r.kind().name()+"\t"+r.chunk().x()+"\t"+r.chunk().z()+"\t"+escape(r.detail());}
    private static RuntimeReceipt decode(String payload,int lineNo)throws IOException{String[]p=payload.split("\\t",-1);if(p.length!=6)throw new IOException("receipt line "+lineNo+" field count "+p.length);try{return new RuntimeReceipt(Long.parseLong(p[0]),Long.parseLong(p[1]),ReceiptKind.valueOf(p[2]),new ChunkKey(Integer.parseInt(p[3]),Integer.parseInt(p[4])),unescape(p[5]));}catch(RuntimeException e){throw new IOException("receipt line "+lineNo+" cannot be decoded",e);}}
    private static String escape(String s){return s.replace("\\","\\\\").replace("\t","\\t").replace("\n","\\n").replace("\r","\\r");}
    private static String unescape(String s)throws IOException{StringBuilder out=new StringBuilder();boolean escaped=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(escaped){switch(c){case't'->out.append('\t');case'n'->out.append('\n');case'r'->out.append('\r');case'\\'->out.append('\\');default->throw new IOException("receipt contains invalid detail escape sequence");}escaped=false;}else if(c=='\\')escaped=true;else out.append(c);}if(escaped)throw new IOException("receipt contains truncated detail escape sequence");return out.toString();}
}
