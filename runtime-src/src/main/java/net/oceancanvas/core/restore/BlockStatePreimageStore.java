package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** Durable, corruption-detecting block-state preimage for one authorized chunk. */
public final class BlockStatePreimageStore {
    private static final int MAGIC = 0x4F435031; // OCP1
    private static final int SCHEMA = 2;
    private static final int SHA256_BYTES = 32;

    public record Preimage(String operationId, ChunkKey chunk, int minY, int maxY, int[] stateIds) {
        public Preimage {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(stateIds, "stateIds");
            if (operationId.isBlank()) throw new IllegalArgumentException("operationId");
            if (maxY < minY) throw new IllegalArgumentException("maxY < minY");
            int expected = Math.multiplyExact(256, Math.addExact(Math.subtractExact(maxY, minY), 1));
            if (stateIds.length != expected) {
                throw new IllegalArgumentException("stateIds length " + stateIds.length + " != expected " + expected);
            }
            stateIds = stateIds.clone();
        }

        @Override public int[] stateIds() { return stateIds.clone(); }
        public int stateIdAt(int index) { return stateIds[index]; }
        public int count() { return stateIds.length; }
    }

    private BlockStatePreimageStore() {}

    public static void writeExact(Path path, Preimage preimage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(preimage, "preimage");

        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payloadBytes)) {
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA);
            byte[] operationBytes = preimage.operationId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(operationBytes.length);
            out.write(operationBytes);
            out.writeInt(preimage.chunk().x());
            out.writeInt(preimage.chunk().z());
            out.writeInt(preimage.minY());
            out.writeInt(preimage.maxY());
            out.writeInt(preimage.count());
            for (int id : preimage.stateIds) out.writeInt(id);
        }

        byte[] payload = payloadBytes.toByteArray();
        byte[] digest = sha256(payload);
        ByteArrayOutputStream complete = new ByteArrayOutputStream(payload.length + SHA256_BYTES);
        complete.write(payload);
        complete.write(digest);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, complete.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Preimage readVerified(Path path, String expectedOperationId, ChunkKey expectedChunk) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expectedOperationId, "expectedOperationId");
        Objects.requireNonNull(expectedChunk, "expectedChunk");
        byte[] all = Files.readAllBytes(path);
        if (all.length < 8 * Integer.BYTES + SHA256_BYTES) throw new IOException("preimage truncated");

        byte[] payload = Arrays.copyOf(all, all.length - SHA256_BYTES);
        byte[] storedDigest = Arrays.copyOfRange(all, all.length - SHA256_BYTES, all.length);
        if (!MessageDigest.isEqual(storedDigest, sha256(payload))) throw new IOException("preimage checksum mismatch");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw new IOException("preimage magic mismatch");
            int schema = in.readInt();
            if (schema != SCHEMA) throw new IOException("unsupported preimage schema " + schema);
            int operationLength = in.readInt();
            if (operationLength < 1 || operationLength > 4096 || operationLength > in.available()) {
                throw new IOException("preimage operation id length invalid");
            }
            byte[] operationBytes = in.readNBytes(operationLength);
            if (operationBytes.length != operationLength) throw new IOException("preimage operation id truncated");
            String operationId = new String(operationBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!operationId.equals(expectedOperationId)) {
                throw new IOException("preimage operation mismatch: existing=" + operationId + " expected=" + expectedOperationId);
            }
            ChunkKey chunk = new ChunkKey(in.readInt(), in.readInt());
            if (!chunk.equals(expectedChunk)) {
                throw new IOException("preimage chunk mismatch: existing=" + chunk + " expected=" + expectedChunk);
            }
            int minY = in.readInt();
            int maxY = in.readInt();
            int count = in.readInt();
            int expectedCount;
            try {
                expectedCount = Math.multiplyExact(256, Math.addExact(Math.subtractExact(maxY, minY), 1));
            } catch (ArithmeticException e) {
                throw new IOException("preimage dimensions overflow", e);
            }
            if (count != expectedCount || count < 0) throw new IOException("preimage count mismatch");
            if (in.available() != count * Integer.BYTES) throw new IOException("preimage payload length mismatch");
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) ids[i] = in.readInt();
            return new Preimage(operationId, chunk, minY, maxY, ids);
        }
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}
