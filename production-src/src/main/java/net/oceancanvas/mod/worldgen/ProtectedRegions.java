package net.oceancanvas.mod.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Thin static facade over {@link OceanCanvasProtectedData}, the level's
 * own persistent (survives-a-restart) record of bounding boxes the
 * flattener must never carve into.
 *
 * <p>This used to hold its own plain in-memory static list. That was the
 * actual root cause of the shipwreck-chest-destroyed bug recurring after
 * every restart - see {@link OceanCanvasProtectedData}'s class doc for
 * the full story. Kept as a static facade (now delegating to per-world
 * persistent data instead of holding state itself) so every existing
 * call site only needed the now-required {@code ServerLevel} parameter
 * added, not a restructure.</p>
 */
public final class ProtectedRegions {

	private ProtectedRegions() {
	}

	/** Registers a region the flattener must leave completely untouched, persisted across restarts. */
	public static void protect(ServerLevel world, BoundingBox box) {
		OceanCanvasProtectedData.get(world).protect(box);
	}

	/** True if the given block position falls inside any protected region. */
	public static boolean isProtected(ServerLevel world, int x, int y, int z) {
		return OceanCanvasProtectedData.get(world).isProtected(x, y, z);
	}
}
