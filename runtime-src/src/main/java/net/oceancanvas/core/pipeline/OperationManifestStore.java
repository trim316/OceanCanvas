package net.oceancanvas.core.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Properties;

/** Fail-closed manifest that prevents a half-finished journal being resumed under changed geometry. */
public final class OperationManifestStore {
    private OperationManifestStore() {}

    static final int MAX_MANIFEST_BYTES = 64 * 1024;

    @FunctionalInterface
    interface ForceOperation {
        void force(FileChannel channel) throws IOException;
    }

    private static final ForceOperation NIO_FORCE = channel -> channel.force(true);

    public static void ensureExact(Path file, SingleChunkOperationSpec expected) throws IOException {
        ensureExact(file, expected, NIO_FORCE);
    }

    static void ensureExact(Path file, SingleChunkOperationSpec expected, ForceOperation forceOperation) throws IOException {
        Objects.requireNonNull(forceOperation, "forceOperation");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path lockPath = file.resolveSibling(file.getFileName().toString() + ".lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("operation manifest lock is not a direct regular file");
        }
        try (FileChannel lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (FileLock lease = lockChannel.tryLock()) {
                if (lease == null) {
                    throw new IOException("operation manifest already has an active writer");
                }
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    Properties p = encode(expected);
                    Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
                    if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("surviving operation manifest stage makes publication authority ambiguous");
                    }
                    try (FileChannel stageChannel = FileChannel.open(stage,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                         OutputStream out = Channels.newOutputStream(stageChannel)) {
                        p.store(out, "Ocean Canvas Core single-chunk operation identity. Do not edit during an active operation.");
                        out.flush();
                        forceOperation.force(stageChannel);
                    }
                    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("operation manifest authority appeared during staged publication; staged evidence preserved");
                    }
                    if (!Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("operation manifest stage is not a direct regular file; staged evidence preserved");
                    }
                    try {
                        Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        throw new IOException("atomic manifest commit unavailable; staged evidence preserved", e);
                    }
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("published operation manifest is not a direct regular file");
                    }
                    return;
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("existing operation manifest is not a direct regular file");
                }
                Properties p = readPropertiesBounded(file);
                SingleChunkOperationSpec actual = decode(p);
                if (!actual.equals(expected) || !expected.operationId().equals(p.getProperty("operationId", ""))) {
                    throw new IOException("single-chunk manifest mismatch: existing=" + actual + " expected=" + expected);
                }
            }
        } catch (OverlappingFileLockException e) {
            throw new IOException("operation manifest already has an active writer", e);
        }
    }

    public static SingleChunkOperationSpec readVerified(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("operation manifest is missing or is not a direct regular file");
        }
        Properties p = readPropertiesBounded(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("operation manifest authority changed while being verified");
        }
        SingleChunkOperationSpec actual = decode(p);
        if (!actual.operationId().equals(p.getProperty("operationId", ""))) {
            throw new IOException("operation manifest operationId disagrees with canonical geometry identity");
        }
        return actual;
    }

    private static Properties readPropertiesBounded(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("operation manifest is not a direct regular file");
        }
        byte[] bytes;
        long sizeBefore;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var in = Channels.newInputStream(channel)) {
            sizeBefore = channel.size();
            if (sizeBefore < 1 || sizeBefore > MAX_MANIFEST_BYTES) {
                throw new IOException("operation manifest size outside canonical bound: " + sizeBefore);
            }
            bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (channel.size() != sizeBefore || bytes.length != sizeBefore) {
                throw new IOException("operation manifest changed while being verified");
            }
        }
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("operation manifest exceeds canonical bound");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("operation manifest authority changed while being verified");
        }
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("duplicate operation manifest property: " + key);
                }
                return super.put(key, value);
            }
        };
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            try {
                p.load(in);
            } catch (IllegalArgumentException e) {
                throw new IOException("operation manifest contains conflicting or duplicate fields", e);
            }
        }
        return p;
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
