package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, size-bounded design contract for future block-entity NBT backups.
 * This has NO world-mutation authority. The existing block-state-only capture
 * must continue refusing any block entity until a versioned durable sidecar,
 * Minecraft codec, exact cold-restart tests, and migration gate are completed.
 *
 * A sidecar is bound to the exact existing canonical schema-2 preimage SHA:
 * a captured chest cannot accidentally be replayed into a different chunk
 * or operation even when its coordinates happen to match.
 */
public final class BlockEntityBackupContract {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_OPERATION_ID_BYTES = 4096;
    public static final int MAX_ENTRY_NBT_BYTES = 1 * 1024 * 1024;
    public static final long MAX_SIDECAR_BYTES = 16L * 1024 * 1024;
    private static final String IDENTIFIER = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    private static final String SHA_HEX = "[0-9a-f]{64}";

    private BlockEntityBackupContract() {}

    /** Each entry binds an exact chunk-local vertical scan index to a type. */
    public record Entry(int stateIndex, String typeId, byte[] nbt) {
        public Entry {
            if (stateIndex < 0) throw new IllegalArgumentException("negative block-entity state index");
            if (typeId == null || !typeId.matches(IDENTIFIER))
                throw new IllegalArgumentException("invalid block-entity registry type");
            Objects.requireNonNull(nbt, "nbt");
            if (nbt.length == 0 || nbt.length > MAX_ENTRY_NBT_BYTES)
                throw new IllegalArgumentException("block-entity NBT exceeds exact bounded payload");
            nbt = nbt.clone();
        }
        @Override public byte[] nbt() { return nbt.clone(); }
    }

    /**
     * Empty entries are a positive proof of an entity-free snapshot ONLY when
     * a future durable writer actually publishes and verifies this envelope.
     */
    public record Envelope(String operationId, ChunkKey chunk, String blockStatePreimageSha256,
                           int stateCount, List<Entry> entries) {
        public Envelope {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(entries, "entries");
            int opBytes = operationId.getBytes(StandardCharsets.UTF_8).length;
            if (opBytes == 0 || opBytes > MAX_OPERATION_ID_BYTES)
                throw new IllegalArgumentException("invalid operation identity size");
            if (blockStatePreimageSha256 == null || !blockStatePreimageSha256.matches(SHA_HEX))
                throw new IllegalArgumentException("invalid immutable source preimage SHA-256");
            if (stateCount <= 0 || stateCount > 256 * 4096)
                throw new IllegalArgumentException("invalid backed-up state count");

            List<Entry> canonical = new ArrayList<>(entries.size());
            HashSet<Integer> seen = new HashSet<>();
            long bytes = 92L + opBytes; // full version/identity/chunk/hash/count header
            for (Entry item : entries) {
                if (item == null || item.stateIndex() >= stateCount || !seen.add(item.stateIndex()))
                    throw new IllegalArgumentException("missing, duplicate or out-of-range block entity");
                Entry retained = new Entry(item.stateIndex(), item.typeId(), item.nbt());
                canonical.add(retained);
                bytes += 12L + retained.typeId().getBytes(StandardCharsets.UTF_8).length
                        + retained.nbt().length;
                if (bytes > MAX_SIDECAR_BYTES)
                    throw new IllegalArgumentException("block-entity backup exceeds sidecar bound");
            }
            canonical.sort(Comparator.comparingInt(Entry::stateIndex));
            entries = List.copyOf(canonical);
        }
        /** Prevent callers from mutating retained NBT through record getters. */
        @Override public List<Entry> entries() {
            ArrayList<Entry> copy = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                copy.add(new Entry(entry.stateIndex(), entry.typeId(), entry.nbt()));
            }
            return List.copyOf(copy);
        }
    }

    /** Canonical digest over operation, chunk, source backup and sorted NBT. */
    public static String canonicalSha256(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(SCHEMA_VERSION);
                writeString(out, envelope.operationId());
                out.writeInt(envelope.chunk().x());
                out.writeInt(envelope.chunk().z());
                writeString(out, envelope.blockStatePreimageSha256());
                out.writeInt(envelope.stateCount());
                out.writeInt(envelope.entries.size());
                for (Entry entry : envelope.entries) {
                    out.writeInt(entry.stateIndex());
                    writeString(out, entry.typeId());
                    out.writeInt(entry.nbt.length);
                    out.write(entry.nbt);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("canonical block-entity contract hashing failed", e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }
}
