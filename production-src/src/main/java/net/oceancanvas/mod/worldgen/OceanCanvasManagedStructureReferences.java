package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinStructure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckStructure;

/** Canonical managed-structure classification and referenced-owner readiness policy. */
public final class OceanCanvasManagedStructureReferences {
    private OceanCanvasManagedStructureReferences() {}

    @FunctionalInterface
    public interface MissingOwnerRequester { void request(ServerLevel world, int chunkX, int chunkZ); }

    /** One canonical list shared by readiness, mutation discovery and health inspection. */
    public static boolean isManaged(Structure structure) {
        return structure instanceof ShipwreckStructure
                || structure instanceof BuriedTreasureStructure
                || structure instanceof OceanRuinStructure
                || structure instanceof OceanMonumentStructure
                || structure instanceof RuinedPortalStructure;
    }

    /** Read-only owner residency check used by Pregen/flatten readiness. */
    public static boolean ownersReady(ServerLevel world, LevelChunk chunk) {
        return !hasMissingOwner(world, chunk);
    }

    public static boolean hasMissingOwner(ServerLevel world, LevelChunk chunk) {
        for (var entry : chunk.getAllReferences().entrySet()) {
            if (!isManaged(entry.getKey())) continue;
            LongIterator it = entry.getValue().iterator();
            while (it.hasNext()) {
                long owner = it.nextLong();
                if (!world.hasChunk(ChunkPos.getX(owner), ChunkPos.getZ(owner))) return true;
            }
        }
        return false;
    }

    /**
     * Explicit destructive operations may request the exact missing owner chunks needed to decide
     * structure safety. The caller supplies the non-blocking request implementation; this service
     * never reaches into the flattener's ticket/load machinery.
     */
    public static boolean ownersReadyOrRequest(ServerLevel world, LevelChunk chunk,
            MissingOwnerRequester requester) {
        boolean ready = true;
        for (var entry : chunk.getAllReferences().entrySet()) {
            if (!isManaged(entry.getKey())) continue;
            LongIterator it = entry.getValue().iterator();
            while (it.hasNext()) {
                long owner = it.nextLong();
                int ox = ChunkPos.getX(owner), oz = ChunkPos.getZ(owner);
                if (world.hasChunk(ox, oz)) continue;
                ready = false;
                requester.request(world, ox, oz);
            }
        }
        return ready;
    }
}
