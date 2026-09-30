package net.oceancanvas.core.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Fail-closed manifest that prevents a half-finished journal being resumed under changed geometry. */
public final class OperationManifestStore {
    private OperationManifestStore() {}

    public static void ensureExact(Path file, SingleChunkOperationSpec expected) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (!Files.exists(file)) {
            Properties p = encode(expected);
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (OutputStream out = Files.newOutputStream(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                p.store(out, "Ocean Canvas Core single-chunk operation identity. Do not edit during an active operation.");
            }
            try (FileChannel channel = FileChannel.open(stage, StandardOpenOption.WRITE)) { channel.force(true); }
            try {
                Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic manifest commit unavailable; staged evidence preserved", e);
            }
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        SingleChunkOperationSpec actual = decode(p);
        if (!actual.equals(expected) || !expected.operationId().equals(p.getProperty("operationId", ""))) {
            throw new IOException("single-chunk manifest mismatch: existing=" + actual + " expected=" + expected);
        }
    }

    private static Properties encode(SingleChunkOperationSpec s) {
        Properties p = new Properties();
        p.setProperty("schemaVersion", Integer.toString(s.schemaVersion()));
        p.setProperty("operationId", s.operationId());
        p.setProperty("chunkX", Integer.toString(s.chunk().x()));
        p.setProperty("chunkZ", Integer.toString(s.chunk().z()));
        p.setProperty("canvasSize", Integer.toString(s.canvasSize()));
        p.setProperty("centerX", Integer.toString(s.centerX()));
        p.setProperty("centerZ", Integer.toString(s.centerZ()));
        p.setProperty("waterSurfaceY", Integer.toString(s.waterSurfaceY()));
        p.setProperty("oceanFloorY", Integer.toString(s.oceanFloorY()));
        p.setProperty("oceanFloorVariation", Integer.toString(s.oceanFloorVariation()));
        return p;
    }

    private static SingleChunkOperationSpec decode(Properties p) throws IOException {
        try {
            return new SingleChunkOperationSpec(
                    Integer.parseInt(p.getProperty("schemaVersion")),
                    new ChunkKey(Integer.parseInt(p.getProperty("chunkX")), Integer.parseInt(p.getProperty("chunkZ"))),
                    Integer.parseInt(p.getProperty("canvasSize")),
                    Integer.parseInt(p.getProperty("centerX")),
                    Integer.parseInt(p.getProperty("centerZ")),
                    Integer.parseInt(p.getProperty("waterSurfaceY")),
                    Integer.parseInt(p.getProperty("oceanFloorY")),
                    Integer.parseInt(p.getProperty("oceanFloorVariation")));
        } catch (RuntimeException e) {
            throw new IOException("single-chunk manifest cannot be decoded", e);
        }
    }
}
