package net.oceancanvas.mod.mixin;

import java.util.List;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLoadCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only progress snapshot; native pendingChunks remains the readiness authority. */
@Mixin(ChunkLoadCounter.class)
public interface ChunkLoadCounterAccessor {
    @Accessor("pendingChunks")
    List<ChunkHolder> oceancanvas$pendingProgressSnapshot();
}
