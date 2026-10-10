package net.oceancanvas.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * v253.69.4: exposes only the persisted-but-not-yet-promoted block-entity NBT map.
 * In Minecraft 26.2 the field is declared on ChunkAccess, not LevelChunk. Targeting
 * LevelChunk compiled but failed at runtime because Mixin accessors resolve fields on
 * the declared target class and do not walk up to inherited fields. Target ChunkAccess
 * directly so LevelChunk inherits the generated accessor implementation.
 * Raw LevelChunk#setBlockState writes bypass Level's normal block-entity lifecycle;
 * removing the exact pending entry before replacement avoids promoting stale chest /
 * beehive / brushable-block NBT after the block array already contains the new state.
 */
@Mixin(ChunkAccess.class)
public interface LevelChunkBlockEntityAccessor {
    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> oceancanvas$getPendingBlockEntities();
}
