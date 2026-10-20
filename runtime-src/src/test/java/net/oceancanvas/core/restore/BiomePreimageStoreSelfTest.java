package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;

/** Standalone storage test; no claim of Minecraft biome capture or restore. */
public final class BiomePreimageStoreSelfTest {
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static int checks;
    private static void require(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
    private static void refuses(Action action) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            checks++;
            return;
        }
        throw new AssertionError("expected refusal");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("oceancanvas-biome-preimage-");
        ChunkKey chunk = new ChunkKey(-1, 2);
        String hash = "ab".repeat(32);
        List<String> keys = new ArrayList<>(Collections.nCopies(32, "minecraft:ocean"));
        keys.set(0, "minecraft:plains");
        keys.set(31, "minecraft:deep_cold_ocean");
        var original = new BiomePreimageStore.Preimage("operation-1", chunk, hash, -1, 0, keys);
        keys.set(0, "minecraft:desert");
        require(original.biomeAt(0, -1, 0).equals("minecraft:plains"));
        require(original.biomeAt(3, 0, 3).equals("minecraft:deep_cold_ocean"));
        refuses(() -> original.biomeAt(-1, -1, 0));
        refuses(() -> original.biomeAt(0, 1, 0));
        Path file = root.resolve("chunk.biomes");
        BiomePreimageStore.writeExact(file, original);
        byte[] pristine = Files.readAllBytes(file);
        var timestamp = Files.getLastModifiedTime(file);
        require(BiomePreimageStore.readVerified(file, "operation-1", chunk, hash, -1, 0).equals(original));
        BiomePreimageStore.writeExact(file, original);
        require(Arrays.equals(pristine, Files.readAllBytes(file)));
        require(timestamp.equals(Files.getLastModifiedTime(file)));
        refuses(() -> BiomePreimageStore.readVerified(file, "other-operation", chunk, hash, -1, 0));
        refuses(() -> BiomePreimageStore.readVerified(file, "operation-1", new ChunkKey(0, 2), hash, -1, 0));
        refuses(() -> BiomePreimageStore.readVerified(file, "operation-1", chunk, "cd".repeat(32), -1, 0));
        refuses(() -> BiomePreimageStore.readVerified(file, "operation-1", chunk, hash, 0, 1));
        var changed = new BiomePreimageStore.Preimage("operation-1", chunk, hash, -1, 0, keys);
        refuses(() -> BiomePreimageStore.writeExact(file, changed));
        require(Arrays.equals(pristine, Files.readAllBytes(file)));
        Path damaged = root.resolve("damaged.biomes");
        byte[] corrupt = pristine.clone(); corrupt[12] ^= 1;
        Files.write(damaged, corrupt);
        refuses(() -> BiomePreimageStore.readVerified(damaged, "operation-1", chunk, hash, -1, 0));
        refuses(() -> BiomePreimageStore.writeExact(damaged, original));
        require(Arrays.equals(corrupt, Files.readAllBytes(damaged)));
        Path interrupted = root.resolve("interrupted.biomes");
        Files.write(interrupted.resolveSibling("interrupted.biomes.tmp"), new byte[]{1, 2, 3});
        refuses(() -> BiomePreimageStore.writeExact(interrupted, original));
        require(!Files.exists(interrupted));
        require(Arrays.equals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("interrupted.biomes.tmp"))));
        Path locked = root.resolve("locked.biomes");
        try (FileChannel channel = FileChannel.open(root.resolve("locked.biomes.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lease = channel.lock()) {
            refuses(() -> BiomePreimageStore.writeExact(locked, original));
        }
        require(!Files.exists(locked));
        refuses(() -> new BiomePreimageStore.Preimage("op", chunk, hash, -1, 1, keys));
        refuses(() -> new BiomePreimageStore.Preimage("op", chunk, hash, Integer.MIN_VALUE, Integer.MAX_VALUE, keys));
        List<String> invalid = new ArrayList<>(keys); invalid.set(0, "not a registry key");
        refuses(() -> new BiomePreimageStore.Preimage("op", chunk, hash, -1, 0, invalid));
        System.out.println("Biome preimage storage: " + checks + " checks PASS; " + root);
    }
}
