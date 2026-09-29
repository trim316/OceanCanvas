package net.oceancanvas.core.pipeline;

/** Immutable chunk coordinate that packs exactly like Minecraft ChunkPos. */
public record ChunkKey(int x, int z) {
    public long packed() {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static ChunkKey fromPacked(long packed) {
        return new ChunkKey((int) (packed & 0xFFFFFFFFL), (int) (packed >>> 32));
    }
}
