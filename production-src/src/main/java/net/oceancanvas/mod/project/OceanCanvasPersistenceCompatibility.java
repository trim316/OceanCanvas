package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;

/**
 * Central conservative gate for terrain-mutating Ocean Canvas operations.
 *
 * <p>World loading remains available when metadata was written by a newer Ocean
 * Canvas version. Older code, however, must not reinterpret that future schema
 * and then Pregen/Rewipe/Restore terrain from assumptions it does not understand.
 * Callers receive a user-facing reason instead of mutating the world.</p>
 */
public final class OceanCanvasPersistenceCompatibility {
    private OceanCanvasPersistenceCompatibility() {}

    public static String destructiveOperationBlockReason(ServerLevel world) {
        OceanCanvasProjectData project = OceanCanvasProjectData.get(world);
        if (!project.schemaSupportedForMutation()) {
            return "Ocean Canvas project metadata schema " + project.schema()
                    + " is newer than this build supports (" + OceanCanvasProjectData.CURRENT_SCHEMA
                    + "). The world can remain open, but Pregen/Rewipe/Restore are disabled to prevent metadata loss or incorrect terrain changes. "
                    + "Use an Ocean Canvas version that supports this world.";
        }

        OceanCanvasTerrainStateData terrain = OceanCanvasTerrainStateData.get(world);
        if (!terrain.schemaSupportedForMutation()) {
            return "Ocean Canvas terrain-state schema " + terrain.schema()
                    + " is newer than this build supports (" + OceanCanvasTerrainStateData.CURRENT_SCHEMA
                    + "). The world can remain open, but Pregen/Rewipe/Restore are disabled to prevent unsafe interpretation of terrain state. "
                    + "Use an Ocean Canvas version that supports this world.";
        }
        return "";
    }

    public static boolean destructiveOperationsAllowed(ServerLevel world) {
        return destructiveOperationBlockReason(world).isEmpty();
    }
}
