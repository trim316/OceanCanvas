package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable recovery data only; this store does not authorize biome mutation. */
public final class BiomePreimageStore {
    private static final int MAGIC = 0x4f434249; // OCBI
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    /** Keys ordered by ascending quart Y, then local quart Z, then local quart X. */
    public record Preimage(String operationId, ChunkKey chunk, String blockPreimageSha256,
                           int minQuartY, int maxQuartY, List<String> biomeKeys) {
        public Preimage {
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(chunk);
            Objects.requireNonNull(blockPreimageSha256);
            Objects.requireNonNull(biomeKeys);
            if (!operationId.matches("[A-Za-z0-9_.:-]{1,256}"))
                throw new IllegalArgumentException("invalid operation identity");
            if (!blockPreimageSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid block preimage digest");
            long height = (long) maxQuartY - minQuartY + 1;
            if (height < 1 || height > 1024 || biomeKeys.size() != height * 16)
                throw new IllegalArgumentException("invalid quart geometry or cell count");
            biomeKeys = List.copyOf(biomeKeys);
            for (String key : biomeKeys) {
                if (key.length() > 256 || !key.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                    throw new IllegalArgumentException("invalid biome registry key");
            }
        }

        public String biomeAt(int localQuartX, int quartY, int localQuartZ) {
            if (localQuartX < 0 || localQuartX > 3 || localQuartZ < 0 || localQuartZ > 3
                    || quartY < minQuartY || quartY > maxQuartY)
                throw new IndexOutOfBoundsException("quart outside captured chunk");
            return biomeKeys.get((quartY - minQuartY) * 16 + localQuartZ * 4 + localQuartX);
        }
    }

    private BiomePreimageStore() {}

    public static void writeExact(Path file, Preimage preimage) throws IOException {
        byte[] bytes = encode(preimage);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lease = channel.tryLock()) {
            if (lease == null) throw new IOException("biome preimage writer active");
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            // A surviving staged publication is recovery evidence. Never silently accept a
            // canonical file while an orphan stage also exists: their ordering/authority is
            // ambiguous after interruption, even when the canonical bytes happen to match.
            if (Files.exists(file) && Files.exists(temporary))
                throw new IOException("ambiguous canonical and staged biome preimage");
            if (Files.exists(file)) {
                readVerified(file, preimage.operationId(), preimage.chunk(),
                        preimage.blockPreimageSha256(), preimage.minQuartY(), preimage.maxQuartY());
                if (!Arrays.equals(bytes, Files.readAllBytes(file)))
                    throw new IOException("refusing different immutable biome preimage");
                return;
            }
            // Preserve interrupted publications instead of overwriting recovery evidence.
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel staged = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                staged.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            Preimage confirmed = readVerified(file, preimage.operationId(), preimage.chunk(),
                    preimage.blockPreimageSha256(), preimage.minQuartY(), preimage.maxQuartY());
            if (!confirmed.equals(preimage)) throw new IOException("biome publication changed");
        } catch (OverlappingFileLockException e) {
            throw new IOException("biome preimage writer active", e);
        }
    }

    public static Preimage readVerified(Path file, String operationId, ChunkKey chunk,
            String blockPreimageSha256, int minQuartY, int maxQuartY) throws IOException {
        long length = Files.size(file);
        if (length < 64 || length > MAX_BYTES) throw new IOException("invalid biome preimage size");
        byte[] bytes = Files.readAllBytes(file);
        int payloadLength = bytes.length - 32;
        if (payloadLength < 0 || !MessageDigest.isEqual(digest(Arrays.copyOf(bytes, payloadLength)),
                Arrays.copyOfRange(bytes, payloadLength, bytes.length)))
            throw new IOException("biome preimage checksum mismatch");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadLength))) {
            if (in.readInt() != MAGIC || in.readInt() != SCHEMA)
                throw new IOException("unknown biome preimage schema");
            String operation = in.readUTF();
            ChunkKey storedChunk = new ChunkKey(in.readInt(), in.readInt());
            String source = in.readUTF();
            int min = in.readInt();
            int max = in.readInt();
            int count = in.readInt();
            long expected = ((long) max - min + 1) * 16;
            if (count < 16 || count > 16384 || count != expected || count > in.available() / 3)
                throw new IOException("invalid biome cell count");
            List<String> keys = new ArrayList<>(count);
            for (int i = 0; i < count; i++) keys.add(in.readUTF());
            if (in.available() != 0) throw new IOException("trailing biome preimage payload");
            Preimage result = new Preimage(operation, storedChunk, source, min, max, keys);
            if (!operation.equals(operationId) || !storedChunk.equals(chunk)
                    || !source.equals(blockPreimageSha256) || min != minQuartY || max != maxQuartY)
                throw new IOException("biome preimage identity or geometry mismatch");
            if (!Arrays.equals(bytes, encode(result))) throw new IOException("noncanonical biome preimage");
            return result;
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid biome preimage", e);
        }
    }

    private static byte[] encode(Preimage preimage) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA);
            out.writeUTF(preimage.operationId());
            out.writeInt(preimage.chunk().x());
            out.writeInt(preimage.chunk().z());
            out.writeUTF(preimage.blockPreimageSha256());
            out.writeInt(preimage.minQuartY());
            out.writeInt(preimage.maxQuartY());
            out.writeInt(preimage.biomeKeys().size());
            for (String key : preimage.biomeKeys()) out.writeUTF(key);
        }
        byte[] payload = buffer.toByteArray();
        buffer.write(digest(payload));
        return buffer.toByteArray();
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
