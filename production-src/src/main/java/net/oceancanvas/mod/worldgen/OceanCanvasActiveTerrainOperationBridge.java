package net.oceancanvas.mod.worldgen;

import java.util.Objects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Dependency-neutral bridge between terrain/worldgen code and the currently
 * active terrain-operation controller.
 *
 * <p>The flattener and biome masker need a small set of read-only scope
 * questions plus three recovery/publication events, but they must not depend
 * directly on the concrete Pregen manager. The installed controller owns all
 * job state and ordering. This bridge owns no scheduler state and cannot start,
 * advance, pause, cancel or persist an operation.</p>
 *
 * <p>Before installation, behavior exactly matches the historical "no active
 * job" answers: mutation scope is permissive, there is no active Pregen chunk,
 * no chunk can still mutate because of Pregen, and lifecycle events are no-ops.</p>
 */
public final class OceanCanvasActiveTerrainOperationBridge {
    public interface Controller {
        boolean coversStructureFootprint(BoundingBox box);
        boolean columnInMutationScope(int chunkX, int chunkZ, int blockX, int blockZ);
        boolean fullyCoversChunkBlocks(int chunkX, int chunkZ);
        boolean pregenIncludesChunk(int chunkX, int chunkZ);
        boolean pregenChunkMayStillMutate(int chunkX, int chunkZ);
        void authoritativeCommit(ServerLevel world, ChunkPos pos);
        void recoveryExemptionCommitted(ServerLevel world, ChunkPos pos);
        void recoveryLightOnlyEnteredFinalizer(ServerLevel world, ChunkPos pos);
    }

    private static final Controller INACTIVE = new Controller() {
        @Override public boolean coversStructureFootprint(BoundingBox box) { return true; }
        @Override public boolean columnInMutationScope(int chunkX, int chunkZ, int blockX, int blockZ) { return true; }
        @Override public boolean fullyCoversChunkBlocks(int chunkX, int chunkZ) { return true; }
        @Override public boolean pregenIncludesChunk(int chunkX, int chunkZ) { return false; }
        @Override public boolean pregenChunkMayStillMutate(int chunkX, int chunkZ) { return false; }
        @Override public void authoritativeCommit(ServerLevel world, ChunkPos pos) { }
        @Override public void recoveryExemptionCommitted(ServerLevel world, ChunkPos pos) { }
        @Override public void recoveryLightOnlyEnteredFinalizer(ServerLevel world, ChunkPos pos) { }
    };

    private static volatile Controller controller = INACTIVE;

    private OceanCanvasActiveTerrainOperationBridge() { }

    public static void install(Controller newController) {
        controller = Objects.requireNonNull(newController, "newController");
    }

    public static boolean coversStructureFootprint(BoundingBox box) {
        return controller.coversStructureFootprint(box);
    }

    public static boolean columnInMutationScope(int chunkX, int chunkZ, int blockX, int blockZ) {
        return controller.columnInMutationScope(chunkX, chunkZ, blockX, blockZ);
    }

    public static boolean fullyCoversChunkBlocks(int chunkX, int chunkZ) {
        return controller.fullyCoversChunkBlocks(chunkX, chunkZ);
    }

    public static boolean pregenIncludesChunk(int chunkX, int chunkZ) {
        return controller.pregenIncludesChunk(chunkX, chunkZ);
    }

    public static boolean pregenChunkMayStillMutate(int chunkX, int chunkZ) {
        return controller.pregenChunkMayStillMutate(chunkX, chunkZ);
    }

    public static void authoritativeCommit(ServerLevel world, ChunkPos pos) {
        controller.authoritativeCommit(world, pos);
    }

    public static void recoveryExemptionCommitted(ServerLevel world, ChunkPos pos) {
        controller.recoveryExemptionCommitted(world, pos);
    }

    public static void recoveryLightOnlyEnteredFinalizer(ServerLevel world, ChunkPos pos) {
        controller.recoveryLightOnlyEnteredFinalizer(world, pos);
    }
}
