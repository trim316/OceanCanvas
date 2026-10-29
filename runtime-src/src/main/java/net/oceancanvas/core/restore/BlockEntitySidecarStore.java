package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Crash-conservative, checksummed durable block-entity NBT sidecar. */
public final class BlockEntitySidecarStore {
    private static final int MAGIC = 0x4F434245;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_FILE_BYTES = Math.toIntExact(BlockEntityBackupContract.MAX_SIDECAR_BYTES + DIGEST_BYTES);

    private BlockEntitySidecarStore() {}

    @FunctionalInterface
    interface ForceOperation {
        void force(FileChannel channel) throws IOException;
    }

    public static String writeExact(Path file, BlockEntityBackupContract.Envelope envelope) throws IOException {
        return writeExact(file, envelope, channel -> channel.force(true));
    }

    static String writeExact(Path file, BlockEntityBackupContract.Envelope envelope, ForceOperation forceOperation)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(forceOperation, "forceOperation");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-entity sidecar lock is not a direct regular file");
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
             FileLock held = channel.tryLock()) {
            if (held == null) throw new IOException("block-entity sidecar already has a writer");
            byte[] bytes = serialize(envelope);
            String exactHash = hex(sha256(Arrays.copyOf(bytes, bytes.length - DIGEST_BYTES)));
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                BlockEntityBackupContract.Envelope existing = readVerified(file, envelope.operationId(),
                        envelope.chunk(), envelope.blockStatePreimageSha256());
                if (!Arrays.equals(bytes, serialize(existing))) {
                    throw new IOException("refusing overwrite of different immutable block-entity sidecar");
                }
                return exactHash;
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(temp.toString(), null,
                        "surviving block-entity sidecar stage makes publication authority ambiguous");
            }
            try (FileChannel staged = FileChannel.open(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    int written = staged.write(buffer);
                    if (written <= 0) {
                        throw new IOException("block-entity sidecar staged write made no forward progress");
                    }
                }
                forceOperation.force(staged);
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("refusing block-entity sidecar publication after competing canonical authority appeared");
            }
            if (!Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("block-entity sidecar stage is not a direct regular file; staged evidence preserved");
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic block-entity sidecar publication unsupported", e);
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("published block-entity sidecar is not a direct regular file");
            }
            BlockEntityBackupContract.Envelope confirmed = readVerified(file, envelope.operationId(),
                    envelope.chunk(), envelope.blockStatePreimageSha256());
            if (!Arrays.equals(serialize(confirmed), bytes)) {
                throw new IOException("block-entity sidecar changed during publication");
            }
            return exactHash;
        } catch (OverlappingFileLockException e) {
            throw new IOException("block-entity sidecar already has a writer", e);
        }
    }

    public static BlockEntityBackupContract.Envelope readVerified(
            Path file, String operationId, ChunkKey chunk, String sourcePreimageSha256) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-entity sidecar authority is not a direct regular file");
        }
        byte[] entire = readBounded(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("block-entity sidecar changed while reading");
        }
        int payloadLength = entire.length - DIGEST_BYTES;
        if (payloadLength < 0 || !MessageDigest.isEqual(
                sha256(Arrays.copyOf(entire, payloadLength)),
                Arrays.copyOfRange(entire, payloadLength, entire.length))) {
            throw new IOException("block-entity sidecar checksum mismatch");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(entire, 0, payloadLength))) {
            if (in.readInt() != MAGIC || in.readInt() != BlockEntityBackupContract.SCHEMA_VERSION) {
                throw new IOException("unknown block-entity sidecar schema; refuse unsafe migration");
            }
            String storedOperation = readString(in, BlockEntityBackupContract.MAX_OPERATION_ID_BYTES);
            ChunkKey storedChunk = new ChunkKey(in.readInt(), in.readInt());
            String source = readString(in, 64);
            int stateCount = in.readInt();
            int entryCount = in.readInt();
            if (!storedOperation.equals(operationId) || !storedChunk.equals(chunk) || !source.equals(sourcePreimageSha256)) {
                throw new IOException("block-entity sidecar operation, chunk or source preimage mismatch");
            }
            if (stateCount <= 0 || stateCount > 256 * 4096 || entryCount < 0 || entryCount > stateCount
                    || entryCount > payloadLength / 12) {
                throw new IOException("invalid block-entity sidecar bounds");
            }
            ArrayList<BlockEntityBackupContract.Entry> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                int index = in.readInt();
                String type = readString(in, 1024);
                int nbtLength = in.readInt();
                if (nbtLength < 1 || nbtLength > BlockEntityBackupContract.MAX_ENTRY_NBT_BYTES || nbtLength > in.available()) {
                    throw new IOException("invalid block-entity NBT payload bound");
                }
                entries.add(new BlockEntityBackupContract.Entry(index, type, in.readNBytes(nbtLength)));
            }
            if (in.available() != 0) throw new IOException("block-entity sidecar contains extra trailing payload");
            BlockEntityBackupContract.Envelope result = new BlockEntityBackupContract.Envelope(
                    storedOperation, storedChunk, source, stateCount, entries);
            if (!Arrays.equals(serialize(result), entire)) {
                throw new IOException("noncanonical block-entity sidecar refused");
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid block-entity sidecar record", e);
        }
    }

    private static byte[] readBounded(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream in = Channels.newInputStream(channel)) {
            long observedSize = channel.size();
            if (observedSize < DIGEST_BYTES + 32L || observedSize > MAX_FILE_BYTES) {
                throw new IOException("invalid or excessive block-entity sidecar size");
            }
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES || in.read() != -1) {
                throw new IOException("block-entity sidecar exceeds global bound while reading");
            }
            if (bytes.length != observedSize || channel.size() != observedSize) {
                throw new IOException("block-entity sidecar changed while reading");
            }
            return bytes;
        }
    }

    private static byte[] serialize(BlockEntityBackupContract.Envelope source) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(BlockEntityBackupContract.SCHEMA_VERSION);
            writeString(out, source.operationId());
            out.writeInt(source.chunk().x());
            out.writeInt(source.chunk().z());
            writeString(out, source.blockStatePreimageSha256());
            out.writeInt(source.stateCount());
            List<BlockEntityBackupContract.Entry> entries = source.entries();
            out.writeInt(entries.size());
            for (BlockEntityBackupContract.Entry item : entries) {
                if (item.typeId().getBytes(StandardCharsets.UTF_8).length > 1024) {
                    throw new IOException("block-entity registry identity exceeds sidecar bound");
                }
                out.writeInt(item.stateIndex());
                writeString(out, item.typeId());
                byte[] nbt = item.nbt();
                out.writeInt(nbt.length);
                out.write(nbt);
            }
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length > BlockEntityBackupContract.MAX_SIDECAR_BYTES) {
            throw new IOException("block-entity sidecar exceeds global bound");
        }
        buffer.write(sha256(payload));
        return buffer.toByteArray();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int count = in.readInt();
        if (count < 1 || count > maxBytes || count > in.available()) {
            throw new IOException("invalid sidecar identity or registry string length");
        }
        byte[] value = in.readNBytes(count);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("malformed UTF-8 block-entity sidecar identity", e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
}
