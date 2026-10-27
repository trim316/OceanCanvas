package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public final class BlockStatePreimageBoundedReadSelfTest {
    private BlockStatePreimageBoundedReadSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("oc-preimage-bounded-");
        ChunkKey chunk = new ChunkKey(4, -3);
        int[] states = new int[256];
        for (int i = 0; i < states.length; i++) states[i] = i * 17;
        BlockStatePreimageStore.Preimage expected = new BlockStatePreimageStore.Preimage("op-r1-275", chunk, 64, 64, states);

        Path exact = root.resolve("exact.bin");
        BlockStatePreimageStore.writeExact(exact, expected);
        BlockStatePreimageStore.Preimage replay = BlockStatePreimageStore.readVerified(exact, "op-r1-275", chunk);
        require(replay.count() == 256 && replay.stateIdAt(255) == states[255], "exact replay changed");
        require(BlockStatePreimageStore.sha256Hex(exact).length() == 64, "exact digest missing");

        Path oversized = root.resolve("oversized.bin");
        try (var out = Files.newOutputStream(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(new byte[8192]);
            long remaining = 16L * 1024L * 1024L + 1L - 8192L;
            byte[] zero = new byte[8192];
            while (remaining > 0) {
                int n = (int) Math.min(zero.length, remaining);
                out.write(zero, 0, n);
                remaining -= n;
            }
        }
        expectIo(() -> BlockStatePreimageStore.readVerified(oversized, "op-r1-275", chunk), "oversized replay accepted");
        require(Files.size(oversized) == 16L * 1024L * 1024L + 1L, "oversized evidence changed");

        Path truncated = root.resolve("truncated.bin");
        Files.write(truncated, new byte[31], StandardOpenOption.CREATE_NEW);
        expectIo(() -> BlockStatePreimageStore.readVerified(truncated, "op-r1-275", chunk), "truncated replay accepted");
        require(Files.size(truncated) == 31L, "truncated evidence changed");

        Path directory = root.resolve("authority-dir");
        Files.createDirectory(directory);
        expectIo(() -> BlockStatePreimageStore.readVerified(directory, "op-r1-275", chunk), "directory replay accepted");
        expectIo(() -> BlockStatePreimageStore.sha256Hex(directory), "directory digest accepted");
        require(Files.isDirectory(directory), "non-regular evidence changed");

        Path target = root.resolve("preimage-target.bin");
        byte[] exactBytes = Files.readAllBytes(exact);
        Files.write(target, exactBytes);
        Path linked = root.resolve("preimage-linked.bin");
        try {
            Files.createSymbolicLink(linked, target.getFileName());
            expectIo(() -> BlockStatePreimageStore.readVerified(linked, "op-r1-275", chunk),
                    "symlink preimage replay accepted");
            expectIo(() -> BlockStatePreimageStore.sha256Hex(linked),
                    "symlink preimage digest accepted");
            require(Files.isSymbolicLink(linked), "symlink preimage evidence was replaced");
            require(Arrays.equals(exactBytes, Files.readAllBytes(target)),
                    "symlink preimage target bytes changed during refusal");
        } catch (UnsupportedOperationException | SecurityException e) {
            // Symbolic links unavailable in this environment.
        } catch (IOException e) {
            if (Files.exists(linked, LinkOption.NOFOLLOW_LINKS)) throw e;
            // Symlink creation itself may be unavailable, notably on unprivileged Windows.
        }

        System.out.println("BlockStatePreimageBoundedReadSelfTest PASS");
    }

    private static void expectIo(IoAction action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
