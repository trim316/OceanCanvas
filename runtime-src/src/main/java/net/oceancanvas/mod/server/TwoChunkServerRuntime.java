package net.oceancanvas.mod.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.acceptance.PostCompleteRecoveryProof;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.expansion.SequentialChunkCoordinator;
import net.oceancanvas.core.expansion.TwoChunkCanaryAdmission;
import net.oceancanvas.core.expansion.TwoChunkCanaryPlan;
import net.oceancanvas.core.expansion.TwoChunkCanaryIdentityStore;
import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.pipeline.OperationManifestStore;
import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.pipeline.SingleChunkPipeline;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Separately gated and inert-by-default disposable two-chunk integration lane.
 * It reuses the exact one-chunk mutation adapter and never holds two live
 * tickets: the coordinator closes the first before opening the second.
 */
public final class TwoChunkServerRuntime {
    private static Path configDir;
    private static MinecraftServer evaluatedServer;
    private static Session active;
    private static boolean registered;

    private TwoChunkServerRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        ServerTickEvents.END_SERVER_TICK.register(TwoChunkServerRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(TwoChunkServerRuntime::stop);
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) TWO-CHUNK-CANARY-ADAPTER-REGISTERED authority=NONE");
    }

    private static synchronized void tick(MinecraftServer server) {
        if (evaluatedServer != server) {
            close();
            evaluatedServer = server;
            try {
                CoreConfig core = CoreConfig.loadOrCreate(configDir);
                var admitted = TwoChunkCanaryAdmission.load(configDir, core);
                if (admitted.isPresent()) {
                    active = Session.open(server, core, admitted.get());
                }
            } catch (Throwable t) {
                OceanCanvas.LOGGER.error("(Ocean Canvas Core) TWO-CHUNK-INIT-FAILED "
                        + "action=fail-closed-no-expansion", t);
                active = null;
            }
        }
        if (active != null) active.tick();
    }

    private static synchronized void stop(MinecraftServer server) {
        if (evaluatedServer == server) {
            close();
            evaluatedServer = null;
        }
    }

    private static void close() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    private static final class Session implements AutoCloseable {
        private final SequentialChunkCoordinator coordinator;
        private boolean fatal;

        private Session(SequentialChunkCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        static Session open(MinecraftServer server, CoreConfig core, TwoChunkCanaryPlan plan) throws Exception {
            ServerLevel world = server.overworld();
            if (world == null) throw new IllegalStateException("overworld unavailable");
            Path root = server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core")
                    .resolve("two-chunk-canary");
            // Bind BOTH configured targets before either chunk can be opened.
            // Changing the second target after first COMPLETE now fails closed.
            TwoChunkCanaryIdentityStore.ensureExact(
                    root.resolve("pair-operation.identity"), plan, core);
            Set<ChunkKey> completionVerifiedThisServer = new HashSet<>();
            SequentialChunkCoordinator.PipelineOpener opener = key -> {
                Path chunkRoot = root.resolve(TwoChunkCanaryAdmission.isolatedChunkDirectory(key));
                SingleChunkOperationSpec spec = spec(core, key);
                OperationManifestStore.ensureExact(chunkRoot.resolve("operation.properties"), spec);
                CoreJournal journal = new CoreJournal(chunkRoot.resolve("transitions.journal"));
                SingleChunkPipeline pipeline = SingleChunkPipeline.open(journal, key);
                if (pipeline.record().stage() == ChunkStage.COMPLETE
                        && !completionVerifiedThisServer.contains(key)) {
                    PostCompleteRecoveryProof.verify(
                            chunkRoot.resolve("preimage-blockstates.bin.completed.archive"),
                            spec.operationId(), key,
                            new RuntimeReceiptLog(chunkRoot.resolve("runtime-receipts.log")).readVerified());
                    completionVerifiedThisServer.add(key);
                }
                return pipeline;
            };
            SequentialChunkCoordinator coordinator = new SequentialChunkCoordinator(
                    plan.orderedChunks(), opener, key -> {
                        Path chunkRoot = root.resolve(TwoChunkCanaryAdmission.isolatedChunkDirectory(key));
                        SingleChunkOperationSpec spec = spec(core, key);
                        // Independent manifest/journal/preimage for each chunk;
                        // second adapter cannot exist before first COMPLETE.
                        OperationManifestStore.ensureExact(chunkRoot.resolve("operation.properties"), spec);
                        RuntimeReceiptLog receipts = new RuntimeReceiptLog(
                                chunkRoot.resolve("runtime-receipts.log"));
                        return new SingleChunkWorldPorts(world, core, key, receipts,
                                spec.operationId(), chunkRoot.resolve("preimage-blockstates.bin"));
                    });
            // Initial replay checks any already-complete chunk's archive before
            // returning a session; a failed second chunk remains terminal.
            var snapshot = coordinator.snapshot();
            OceanCanvas.LOGGER.warn("(Ocean Canvas Core) TWO-CHUNK-CANARY-OPEN "
                    + "first={} second={} active={} resumed={} completeCount={} "
                    + "scope=EXACTLY-TWO-EXPLICIT-CHUNKS ticketLimit=ONE radius=ZERO",
                    plan.first(), plan.second(), snapshot.activeChunk(), snapshot.activeStage(),
                    snapshot.completeCount());
            return new Session(coordinator);
        }

        private static SingleChunkOperationSpec spec(CoreConfig core, ChunkKey key) {
            return new SingleChunkOperationSpec(1, key, core.canvasSize(),
                    core.centerX(), core.centerZ(), core.waterSurfaceY(),
                    core.oceanFloorY(), core.oceanFloorVariation());
        }

        void tick() {
            if (fatal) return;
            try {
                var before = coordinator.snapshot();
                if (before.complete() || before.failed()) return;
                if (coordinator.tick(System.currentTimeMillis())) {
                    var after = coordinator.snapshot();
                    OceanCanvas.LOGGER.info("(Ocean Canvas Core) TWO-CHUNK-CANARY-TRANSITION "
                            + "active={} from={} next={} completeCount={} "
                            + "complete={} failed={}", before.activeChunk(),
                            before.activeStage(), after.activeStage(), after.completeCount(),
                            after.complete(), after.failed());
                    if (after.failed()) {
                        fatal = true;
                        coordinator.close();
                        OceanCanvas.LOGGER.error("(Ocean Canvas Core) TWO-CHUNK-CANARY-FAILED "
                                + "active={} no-later-chunk-authority", after.activeChunk());
                    }
                }
            } catch (Throwable t) {
                fatal = true;
                coordinator.close();
                OceanCanvas.LOGGER.error("(Ocean Canvas Core) TWO-CHUNK-CANARY-FATAL "
                        + "action=halt-and-retain-separate-journals", t);
            }
        }

        @Override public void close() { coordinator.close(); }
    }
}
