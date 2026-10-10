package net.oceancanvas.mod.worldgen;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Neutral sink for recording block before-states during undoable terrain work.
 *
 * <p>Terrain mutation code should not depend on the concrete undo manager or
 * its history/session implementation. The undo subsystem installs its recorder
 * once during mod initialization. Before installation this sink is deliberately
 * a no-op, matching the ordinary "no active recording session" behavior.</p>
 */
public final class OceanCanvasUndoRecorderBridge {
    @FunctionalInterface
    public interface Recorder {
        void record(ServerLevel world, BlockPos pos, BlockState before);
    }

    private static final Recorder NOOP = (world, pos, before) -> { };
    private static volatile Recorder recorder = NOOP;

    private OceanCanvasUndoRecorderBridge() { }

    public static void install(Recorder newRecorder) {
        recorder = Objects.requireNonNull(newRecorder, "newRecorder");
    }

    public static void record(ServerLevel world, BlockPos pos, BlockState before) {
        recorder.record(world, pos, before);
    }
}
