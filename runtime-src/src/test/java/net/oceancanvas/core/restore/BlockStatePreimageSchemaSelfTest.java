package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Regression proof that checksum-valid future preimage formats fail closed without evidence mutation. */
public final class BlockStatePreimageSchemaSelfTest {
    private static final int MAGIC = 0x4F435031;

    private BlockStatePreimageSchemaSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("BlockStatePreimageSchemaSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-preimage-schema");
        try {
            Path canonical = dir.resolve("preimage.bin");
            ChunkKey chunk = new ChunkKey(12, -7);
            byte[] future = checksumValidFutureSchema("schema-proof", chunk, 3);
            Files.write(canonical, future);
            byte[] before = Files.readAllBytes(canonical);

            boolean readRejected = false;
            try {
                BlockStatePreimageStore.readVerified(canonical, "schema-proof", chunk);
            } catch (IOException expected) {
                readRejected = expected.getMessage() != null
                        && expected.getMessage().contains("unsupported preimage schema 3");
            }
            check(readRejected, "checksum-valid unknown schema is explicitly refused"); checks++;
            check(Arrays.equals(before, Files.readAllBytes(canonical)),
                    "read refusal preserves authoritative future-format bytes"); checks++;

            int[] ids = new int[256];
            Arrays.fill(ids, 1);
            boolean writeRejected = false;
            try {
                BlockStatePreimageStore.writeExact(canonical,
                        new BlockStatePreimageStore.Preimage("schema-proof", chunk, 0, 0, ids));
            } catch (IOException expected) {
                writeRejected = expected.getMessage() != null
                        && expected.getMessage().contains("unsupported preimage schema 3");
            }
            check(writeRejected, "current writer cannot replace checksum-valid unknown schema"); checks++;
            check(Arrays.equals(before, Files.readAllBytes(canonical)),
                    "failed current-format write preserves future-format bytes exactly"); checks++;
            check(!Files.exists(canonical.resolveSibling(canonical.getFileName() + ".tmp")),
                    "schema refusal occurs before any replacement stage is created"); checks++;
            return checks;
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
            }
        }
    }

    private static byte[] checksumValidFutureSchema(String operationId, ChunkKey chunk, int schema)
            throws Exception {
        byte[] operation = operationId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payloadBytes)) {
            out.writeInt(MAGIC);
            out.writeInt(schema);
            out.writeInt(operation.length);
            out.write(operation);
            out.writeInt(chunk.x());
            out.writeInt(chunk.z());
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(256);
            for (int i = 0; i < 256; i++) out.writeInt(1);
        }
        byte[] payload = payloadBytes.toByteArray();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        ByteArrayOutputStream complete = new ByteArrayOutputStream(payload.length + digest.length);
        complete.write(payload);
        complete.write(digest);
        return complete.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
