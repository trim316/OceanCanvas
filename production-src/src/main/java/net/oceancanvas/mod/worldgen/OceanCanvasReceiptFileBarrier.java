package net.oceancanvas.mod.worldgen;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.*;

/** Independent error-propagating authority barrier. Minecraft SavedDataStorage logs and swallows
 * write IOExceptions, so its successful future is insufficient authority for a destructive receipt.
 * No caller may block the tick thread on this future. No failed/cancelled completion permits repair.
 * This forces file bytes and atomically publishes an immutable record; it is not a directory-fsync
 * or universal power-loss guarantee. A cold reader must verify exact bytes again before use. */
public final class OceanCanvasReceiptFileBarrier {
    @FunctionalInterface public interface Writer { void write(Path path, byte[] bytes) throws IOException; }
    private final Executor executor;
    private final Writer writer;
    public OceanCanvasReceiptFileBarrier(Executor executor) { this(executor, OceanCanvasReceiptFileBarrier::writeImmutable); }
    OceanCanvasReceiptFileBarrier(Executor executor, Writer writer) { this.executor=executor; this.writer=writer; }
    public CompletableFuture<Path> publish(Path root, String world, String operation, byte[] canonicalPayload) {
        UUID.fromString(world); UUID.fromString(operation);
        byte[] snapshot=canonicalPayload.clone();
        Path path=root.toAbsolutePath().normalize().resolve(world).resolve(operation).resolve(sha(snapshot)+".json");
        return CompletableFuture.supplyAsync(() -> {
            try { writer.write(path,snapshot); verify(path,snapshot); return path; }
            catch(IOException e) { throw new CompletionException(e); }
        }, executor);
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static void verify(Path path, byte[] expected) throws IOException {
        if (!java.util.Arrays.equals(Files.readAllBytes(path),expected)) throw new IOException("Receipt bytes differ: "+path.getFileName());
    }
    static void writeImmutable(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) { verify(path,bytes); return; }
        Path temporary=path.resolveSibling(path.getFileName()+".pending-"+UUID.randomUUID());
        // Failed temporaries are deliberately retained as forensic evidence, never mistaken for authority.
        try (FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
        }
        try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE); }
        catch(FileAlreadyExistsException race) { verify(path,bytes); }
        verify(path,bytes);
    }
    public static boolean ready(CompletableFuture<?> future) {
        return future!=null && future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
    }
}
