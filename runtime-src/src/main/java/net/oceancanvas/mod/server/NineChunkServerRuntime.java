package net.oceancanvas.mod.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.acceptance.PostCompleteRecoveryProof;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.expansion.NineChunkCanaryAdmission;
import net.oceancanvas.core.expansion.NineChunkCanaryIdentityStore;
import net.oceancanvas.core.expansion.NineChunkCanaryPlan;
import net.oceancanvas.core.expansion.SequentialChunkCoordinator;
import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.pipeline.OperationManifestStore;
import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.pipeline.SingleChunkPipeline;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.restore.BlockStateRegistryIdentityStore;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Separately gated disposable nine-chunk integration lane.
 *
 * The validated one-chunk mutation adapter is reused strictly sequentially:
 * all nine targets and geometry are immutably bound before the first opens,
 * and at most one radius-zero ticket may exist at any time.
 */
public final class NineChunkServerRuntime {
    private static Path configDir;
    private static MinecraftServer evaluatedServer;
    private static Session active;
    private static boolean registered;

    private NineChunkServerRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        ServerTickEvents.END_SERVER_TICK.register(NineChunkServerRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(NineChunkServerRuntime::stop);
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) NINE-CHUNK-CANARY-ADAPTER-REGISTERED authority=NONE");
    }

    private static synchronized void tick(MinecraftServer server) {
        if (evaluatedServer != server) {
            close();
            evaluatedServer = server;
            try {
                CoreConfig core = CoreConfig.loadOrCreate(configDir);
                var admitted = NineChunkCanaryAdmission.load(configDir, core);
                if (admitted.isPresent()) {
                    active = Session.open(server, core, admitted.get());
                }
            } catch (Throwable t) {
                OceanCanvas.LOGGER.error("(Ocean Canvas Core) NINE-CHUNK-INIT-FAILED "
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

        static Session open(MinecraftServer server, CoreConfig core, NineChunkCanaryPlan plan)
                throws Exception {
            ServerLevel world = server.overworld();
            if (world == null) throw new IllegalStateException("overworld unavailable");

            Path root = server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core")
                    .resolve("nine-chunk-canary");
            BlockStateRegistryFingerprint.Identity registry = BlockStateRegistryFingerprint.compute();
            BlockStateRegistryIdentityStore.ensureExact(
                    root.resolve("block-state-registry.identity"),
                    registry.sha256(), registry.stateCount());
            NineChunkCanaryIdentityStore.ensureExact(
                    root.resolve("nine-operation.identity"), plan, core);

            Set<ChunkKey> completionVerifiedThisServer = new HashSet<>();
            SequentialChunkCoordinator.PipelineOpener opener = key -> {
                Path chunkRoot = root.resolve(NineChunkCanaryAdmission.isolatedChunkDirectory(key));
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
                        Path chunkRoot = root.resolve(NineChunkCanaryAdmission.isolatedChunkDirectory(key));
                        SingleChunkOperationSpec spec = spec(core, key);
                        OperationManifestStore.ensureExact(chunkRoot.resolve("operation.properties"), spec);
                        RuntimeReceiptLog receipts = new RuntimeReceiptLog(
                                chunkRoot.resolve("runtime-receipts.log"));
                        return new SingleChunkWorldPorts(world, core, key, receipts,
                                spec.operationId(),
                                chunkRoot.resolve("preimage-blockstates.bin"),
                                chunkRoot.resolve("preimage-blockentities.ocbe"),
                                false);
                    });

            var snapshot = coordinator.snapshot();
            OceanCanvas.LOGGER.warn("(Ocean Canvas Core) NINE-CHUNK-CANARY-OPEN "
                            + "targets={} active={} resumed={} completeCount={} "
                            + "scope=EXACTLY-NINE-EXPLICIT-CHUNKS ticketLimit=ONE radius=ZERO",
                    plan.orderedChunks(), snapshot.activeChunk(), snapshot.activeStage(),
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
                    OceanCanvas.LOGGER.info("(Ocean Canvas Core) NINE-CHUNK-CANARY-TRANSITION "
                                    + "active={} from={} next={} completeCount={} complete={} failed={}",
                            before.activeChunk(), before.activeStage(), after.activeStage(),
                            after.completeCount(), after.complete(), after.failed());
                    if (after.failed()) {
                        fatal = true;
                        coordinator.close();
                        OceanCanvas.LOGGER.error("(Ocean Canvas Core) NINE-CHUNK-CANARY-FAILED "
                                + "active={} no-later-chunk-authority", after.activeChunk());
                    }
                }
            } catch (Throwable t) {
                fatal = true;
                coordinator.close();
                OceanCanvas.LOGGER.error("(Ocean Canvas Core) NINE-CHUNK-CANARY-FATAL "
                        + "action=halt-and-retain-separate-journals", t);
            }
        }

        @Override public void close() {
            coordinator.close();
        }
    }
}
