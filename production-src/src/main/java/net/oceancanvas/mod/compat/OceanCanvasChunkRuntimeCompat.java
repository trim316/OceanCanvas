package net.oceancanvas.mod.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.oceancanvas.mod.mixin.ChunkMapAccessor;

import java.util.concurrent.CompletableFuture;

/**
 * Narrow compatibility boundary for Minecraft chunk-runtime operations whose
 * names/signatures have changed across mappings or whose visibility requires a
 * mixin bridge. Callers own policy and lifecycle; this class only translates
 * that policy into the current Minecraft 26.2 runtime calls.
 *
 * <p>Keep this surface deliberately small. In particular, ticket radius,
 * ownership bookkeeping, retry policy and release ordering remain in the
 * Pregen/Restore/lighting owners rather than moving into this adapter.</p>
 */
public final class OceanCanvasChunkRuntimeCompat {
    private OceanCanvasChunkRuntimeCompat() {}

    /** Delete one stored chunk record and wait until the chunk map has synchronized it. */
    public static CompletableFuture<Void> pruneStoredChunk(ServerLevel world, ChunkPos pos) {
        ChunkMap map = world.getChunkSource().chunkMap;
        return map.write(pos, (CompoundTag) null).thenCompose(ignored -> map.synchronize(true));
    }

    /** Install the exact vanilla FORCED ticket requested by the owning subsystem. */
    public static void addForcedTicket(ServerLevel world, ChunkPos pos, int radius) {
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, radius);
    }

    /** Remove the exact vanilla FORCED ticket requested by the owning subsystem. */
    public static void removeForcedTicket(ServerLevel world, ChunkPos pos, int radius) {
        world.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, radius);
    }

    /**
     * Flush holder-side block/entity deltas after Ocean Canvas has already sent
     * any explicit authoritative packet required by its lighting contract.
     */
    public static void broadcastHolderChanges(ServerLevel world, LevelChunk chunk) {
        ChunkMap map = world.getChunkSource().chunkMap;
        long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        ChunkHolder holder = ((ChunkMapAccessor) (Object) map).oceancanvas$invokeGetVisibleChunkIfPresent(key);
        if (holder == null) holder = map.getUpdatingChunkIfPresent(key);
        if (holder != null) holder.broadcastChanges(chunk);
    }
}
