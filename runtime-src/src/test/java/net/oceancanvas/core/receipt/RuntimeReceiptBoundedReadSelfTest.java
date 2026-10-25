package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Deterministic regression for bounded fail-closed forensic receipt replay. */
public final class RuntimeReceiptBoundedReadSelfTest {
    private RuntimeReceiptBoundedReadSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-receipt-bounded-read-");
        try {
            Path receipt = dir.resolve("runtime.receipts");
            RuntimeReceiptLog log = new RuntimeReceiptLog(receipt);
            log.append(ReceiptKind.PREIMAGE_CAPTURED, new ChunkKey(7, -3), "bounded-read-regression");
            var replay = new RuntimeReceiptLog(receipt).readVerified();
            require(replay.size() == 1, "ordinary canonical receipt must replay");
            require(replay.get(0).chunk().equals(new ChunkKey(7, -3)), "ordinary replay must preserve chunk identity");

            Path oversized = dir.resolve("oversized.receipts");
            try (FileChannel channel = FileChannel.open(oversized,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position((8L * 1024L * 1024L));
                channel.write(java.nio.ByteBuffer.wrap(new byte[] { '\n' }));
            }
            expectIOException(() -> new RuntimeReceiptLog(oversized).readVerified(),
                    "oversized receipt must fail closed before payload allocation");

            Path truncated = dir.resolve("truncated.receipts");
            Files.writeString(truncated, "0\t1\tPREIMAGE_CAPTURED\t0\t0\tdetail");
            expectIOException(() -> new RuntimeReceiptLog(truncated).readVerified(),
                    "unterminated receipt must fail closed");

            System.out.println("RuntimeReceiptBoundedReadSelfTest PASS");
        } finally {
            deleteTree(dir);
        }
    }

    private static void expectIOException(ThrowingRunnable action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
