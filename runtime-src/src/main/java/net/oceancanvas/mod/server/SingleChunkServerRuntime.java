package net.oceancanvas.mod.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.acceptance.AcceptanceHarness;
import net.oceancanvas.core.acceptance.PostCompleteRecoveryProof;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.OperationManifestStore;
import net.oceancanvas.core.pipeline.StartupAuthorityGuard;
import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.pipeline.SingleChunkPipeline;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.restore.BlockEntityRecoveryAdmission;
import net.oceancanvas.core.restore.BlockStateRegistryIdentityStore;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.file.Path;

/**
 * Inert-by-default server adapter for the one-chunk acceptance pipeline.
 *
 * v0.2.15 deliberately reloads the config exactly once per new server instance.
 * The client may therefore remain open at the title screen while the external
 * one-click harness arms the test. The next integrated server instance sees the
 * new config, revalidates the complete destructive gate, and only then opens a
 * mutating session. Mid-session config edits are ignored.
 */
public final class SingleChunkServerRuntime {
    private static Path configDir;
    private static Session session;
    private static MinecraftServer evaluatedServer;
    private static boolean registered;

    private SingleChunkServerRuntime() {}

    public static synchronized void register(Path suppliedConfigDir) {
        if (registered) return;
        if (suppliedConfigDir == null) throw new IllegalArgumentException("configDir");
        configDir = suppliedConfigDir;
        ServerTickEvents.END_SERVER_TICK.register(SingleChunkServerRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(SingleChunkServerRuntime::stop);
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) SERVER-ADAPTER-REGISTERED build={} authority=NONE-until-server-start-config-revalidation", OceanCanvas.VERSION);
    }

    private static synchronized void tick(MinecraftServer server) {
        if (evaluatedServer != server) {
            closeSession();
            evaluatedServer = server;
            try {
                CoreConfig current = CoreConfig.loadOrCreate(configDir);
                if (current.singleChunkAuthorityEnabled()) {
                    OceanCanvas.LOGGER.warn("(Ocean Canvas Core) SERVER-START-GATE-PASS build={} configuredMode={} target={},{} acceptanceHarness={} action=open-single-confirmed-chunk-session",
                            OceanCanvas.VERSION, current.mode(), current.singleChunkX(), current.singleChunkZ(), current.acceptanceHarnessEnabled());
                    session = Session.open(server, current);
                } else {
                    OceanCanvas.LOGGER.warn("(Ocean Canvas Core) SERVER-START-GATE-HOLD build={} configuredMode={} singleChunkEnabled={} confirmationExpected={} action=no-world-mutation-for-this-server-instance",
                            OceanCanvas.VERSION, current.mode(), current.singleChunkEnabled(), current.expectedSingleChunkConfirm());
                }
            } catch (Throwable t) {
                OceanCanvas.LOGGER.error("(Ocean Canvas Core) SINGLE-CHUNK-INIT-FAILED build={} action=fail-closed-no-authoring", OceanCanvas.VERSION, t);
                session = null;
            }
        }
        if (session != null) session.tick();
    }

    private static synchronized void stop(MinecraftServer server) {
        if (evaluatedServer == server) {
            closeSession();
            evaluatedServer = null;
        }
    }

    private static void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private static final class Session implements AutoCloseable {
        final MinecraftServer server;
        final SingleChunkPipeline pipeline;
        final SingleChunkWorldPorts ports;
        final RuntimeReceiptLog receipts;
        final AcceptanceHarness acceptance;
        boolean fatal;

        private Session(MinecraftServer server, SingleChunkPipeline pipeline, SingleChunkWorldPorts ports,
                        RuntimeReceiptLog receipts, AcceptanceHarness acceptance) {
            this.server = server; this.pipeline = pipeline; this.ports = ports;
            this.receipts = receipts; this.acceptance = acceptance;
        }

        static Session open(MinecraftServer server, CoreConfig config) throws Exception {
            ServerLevel world = server.overworld();
            if (world == null) throw new IllegalStateException("overworld unavailable");
            ChunkKey key = new ChunkKey(config.singleChunkX(), config.singleChunkZ());
            // Checked before manifest publication, ticket acquisition, or any
            // physical world write. Int overflow must never wrap authority.
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                    config.canvasSize(), config.centerX(), config.centerZ());
            if (!bounds.contains(key.x(), key.z())) {
                throw new IllegalStateException("confirmed single-chunk target " + key + " is outside Canvas bounds " + bounds);
            }

            Path root = server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core").resolve("single-chunk");
            SingleChunkOperationSpec spec = new SingleChunkOperationSpec(1, key, config.canvasSize(), config.centerX(), config.centerZ(),
                    config.waterSurfaceY(), config.oceanFloorY(), config.oceanFloorVariation());
            // Establish exact operation/target authority before any other
            // durable startup evidence is created or updated. A redirected
            // config must fail before registry identity, journal, receipts,
            // chunk residency, or physical authoring can begin.
            StartupAuthorityGuard.runAfterManifestAuthority(
                    root.resolve("operation.properties"), spec, () -> {
                        BlockStateRegistryFingerprint.Identity registry = BlockStateRegistryFingerprint.compute();
                        BlockStateRegistryIdentityStore.ensureExact(
                                root.resolve("block-state-registry.identity"),
                                registry.sha256(), registry.stateCount());
                    });
            CoreJournal journal = new CoreJournal(root.resolve("transitions.journal"));
            RuntimeReceiptLog receipts = new RuntimeReceiptLog(root.resolve("runtime-receipts.log"));
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(journal, key);
            // A journaled COMPLETE is not itself proof that the original
            // backup survived release. Check the immutable archive's actual
            // bytes and its capture/restore receipt chain BEFORE the acceptance
            // harness can persist or announce final-restart credit.
            if (PostCompleteRecoveryProof.requiresArchiveOnReopen(pipeline.record().stage())) {
                Path archive = root.resolve("preimage-blockstates.bin.completed.archive");
                PostCompleteRecoveryProof.verify(
                        archive, spec.operationId(), key, receipts.readVerified());
            }
            AcceptanceHarness acceptance = null;
            if (config.acceptanceHarnessEnabled()) {
                AcceptanceHarness.OpenResult opened = AcceptanceHarness.open(
                        root.resolve("acceptance-state.properties"), spec.operationId(), key, pipeline.record().stage());
                acceptance = opened.harness();
                if (opened.restartVerified()) {
                    ReceiptKind kind = opened.finalRestartVerifiedNow()
                            ? ReceiptKind.ACCEPTANCE_FINAL_RESTART_VERIFIED
                            : ReceiptKind.ACCEPTANCE_RESTART_VERIFIED;
                    receipts.append(kind, key, "stage=" + pipeline.record().stage()
                            + ";verifiedRestarts=" + acceptance.verifiedRestarts());
                    if (opened.finalRestartVerifiedNow()) {
                        OceanCanvas.LOGGER.warn("(Ocean Canvas Core) ACCEPTANCE-FINAL-RESTART-PASS build={} chunk={},{} stage=COMPLETE verifiedRestarts={} action=collect-runtime-evidence",
                                OceanCanvas.VERSION, key.x(), key.z(), acceptance.verifiedRestarts());
                    } else {
                        OceanCanvas.LOGGER.info("(Ocean Canvas Core) ACCEPTANCE-RESTART-VERIFIED build={} chunk={},{} resumedStage={} verifiedRestarts={} action=allow-exactly-one-next-durable-transition",
                                OceanCanvas.VERSION, key.x(), key.z(), pipeline.record().stage(), acceptance.verifiedRestarts());
                    }
                }
            }
            boolean blockEntityRecoveryEnabled =
                    BlockEntityRecoveryAdmission.load(configDir, config).filter(key::equals).isPresent();
            SingleChunkWorldPorts ports = new SingleChunkWorldPorts(
                    world, config, key, receipts, spec.operationId(),
                    root.resolve("preimage-blockstates.bin"),
                    root.resolve("preimage-blockentities.ocbe"),
                    blockEntityRecoveryEnabled);
            OceanCanvas.LOGGER.warn("(Ocean Canvas Core) SINGLE-CHUNK-OPEN build={} operation={} target={},{} resumedStage={} attempt={} authority=CORE_AUTHORING scope=ONE-EXPLICITLY-CONFIRMED-CHUNK acceptanceHarness={}",
                    OceanCanvas.VERSION, spec.operationId(), key.x(), key.z(), pipeline.record().stage(), pipeline.record().attempt(), config.acceptanceHarnessEnabled());
            return new Session(server, pipeline, ports, receipts, acceptance);
        }

        void tick() {
            if (fatal || pipeline.terminal()) return;
            if (acceptance != null && acceptance.shouldHold()) return;
            try {
                var before = pipeline.record().stage();
                boolean advanced = pipeline.tick(ports, System.currentTimeMillis());
                if (advanced) {
                    var r = pipeline.record();
                    if (r.stage() == net.oceancanvas.core.pipeline.ChunkStage.FAILED) {
                        OceanCanvas.LOGGER.error("(Ocean Canvas Core) SINGLE-CHUNK-TRANSITION build={} chunk={},{} from={} to={} attempt={} revision={} evidence={} failureReason={}",
                                OceanCanvas.VERSION, r.chunk().x(), r.chunk().z(), before, r.stage(), r.attempt(), r.revision(), r.evidence(), r.failureReason());
                    } else {
                        OceanCanvas.LOGGER.info("(Ocean Canvas Core) SINGLE-CHUNK-TRANSITION build={} chunk={},{} from={} to={} attempt={} revision={} evidence={}",
                                OceanCanvas.VERSION, r.chunk().x(), r.chunk().z(), before, r.stage(), r.attempt(), r.revision(), r.evidence());
                    }
                    if (r.stage().terminal()) ports.close();
                    if (acceptance != null && r.stage() != net.oceancanvas.core.pipeline.ChunkStage.FAILED) {
                        acceptance.holdAfterTransition(r.stage());
                        receipts.append(ReceiptKind.ACCEPTANCE_HOLD, r.chunk(),
                                "stage=" + r.stage() + ";verifiedRestarts=" + acceptance.verifiedRestarts());
                        OceanCanvas.LOGGER.warn("(Ocean Canvas Core) ACCEPTANCE-HOLD build={} chunk={},{} stage={} verifiedRestarts={} action=SAVE-AND-QUIT-THEN-REOPEN-WORLD",
                                OceanCanvas.VERSION, r.chunk().x(), r.chunk().z(), r.stage(), acceptance.verifiedRestarts());
                    }
                }
            } catch (Throwable t) {
                fatal = true;
                ports.close();
                OceanCanvas.LOGGER.error("(Ocean Canvas Core) SINGLE-CHUNK-FATAL build={} stage={} action=halt-runtime-and-preserve-journal-for-restart",
                        OceanCanvas.VERSION, pipeline.record().stage(), t);
            }
        }

        @Override public void close() { ports.close(); }
    }
}
