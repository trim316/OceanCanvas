package net.oceancanvas.mod.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Mixin-safe bridge for the one ChunkMap member Ocean Canvas still needs that
 * is not exposed by a stable public API. All callers go through
 * OceanCanvasChunkRuntimeCompat so mapping drift is isolated to one boundary.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder oceancanvas$invokeGetVisibleChunkIfPresent(long chunkKey);
}
