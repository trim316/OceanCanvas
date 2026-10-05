package net.oceancanvas.mod.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * A coarse notification that Ocean Canvas replaced terrain in a chunk.
 *
 * <p>This is deliberately owned by Ocean Canvas rather than any optional
 * renderer/cache mod. Terrain mutation code emits this event and knows nothing
 * about Voxy (or any future compatibility target). One notification is emitted
 * after a successful chunk mutation instead of per changed block so large
 * Pregen/Rewipe/Restore jobs do not create millions of compatibility calls.</p>
 */
public record OceanCanvasTerrainChange(ServerLevel world, ChunkPos chunkPos, Kind kind) {
    public enum Kind {
        CANVAS_WRITE,
        VISIBLE_LIGHT_REPAIR,
        RESTORE_TO_VANILLA,
        CUSTOM_IMPORT
    }
}
