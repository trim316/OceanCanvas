package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.pipeline.OperationManifestStore;
import net.oceancanvas.core.pipeline.RowMajorCursor;
import net.oceancanvas.core.pipeline.SingleChunkOperationSpec;
import net.oceancanvas.core.pipeline.SingleChunkPipeline;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.restore.BlockStateRegistryIdentityStore;
import net.oceancanvas.mod.OceanCanvas;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Production full-Canvas flatten runtime.
 *
 * <p>Unlike the acceptance/canary pipelines, this operation intentionally stops
 * after the authored chunk reaches VERIFIED. The captured preimage remains in
 * place for future restore authority. Exactly one radius-zero chunk adapter is
 * active at a time, and progress is durably advanced only after VERIFIED.</p>
 */
public final class FullCanvasServerRuntime {
    private static final String DIR = "full-canvas";
    private static final String STATE_FILE = "operation.properties";
    private static final String RESTORE_STATE_FILE = "restore.properties";
    private static final int STATE_SCHEMA = 1;

    private static Path configDir;
    private static MinecraftServer evaluatedServer;
    private static Session active;
    private static boolean registered;

    private FullCanvasServerRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        ServerTickEvents.END_SERVER_TICK.register(FullCanvasServerRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(FullCanvasServerRuntime::stop);
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                registerCommands(dispatcher));
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) FULL-CANVAS-ADAPTER-REGISTERED authority=COMMAND-ONLY");
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/flatten"), PermissionLevel.ADMINS))
                .then(Commands.literal("flatten")
                        .then(Commands.literal("start")
                                .then(Commands.literal("ERASE_CONFIGURED_CANVAS")
                                        .executes(context -> start(context.getSource()))))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("pause")
                                .executes(context -> pause(context.getSource())))
                        .then(Commands.literal("resume")
                                .executes(context -> resume(context.getSource())))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancel(context.getSource()))))
                .then(Commands.literal("restore")
                        .then(Commands.literal("start")
                                .then(Commands.literal("RESTORE_CONFIGURED_CANVAS")
                                        .executes(context -> restoreStart(context.getSource()))))
                        .then(Commands.literal("status")
                                .executes(context -> restoreStatus(context.getSource())))
                        .then(Commands.literal("pause")
                                .executes(context -> restorePause(context.getSource())))
                        .then(Commands.literal("resume")
                                .executes(context -> restoreResume(context.getSource())))
                        .then(Commands.literal("cancel")
                                .executes(context -> restoreCancel(context.getSource())))));
    }

    private static int start(CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            if (!core.expansionEnabled()) {
                source.sendFailure(Component.literal("OceanCanvas flatten refused: expansionEnabled=false."));
                return 0;
            }
            if (core.singleChunkEnabled() || core.acceptanceHarnessEnabled()) {
                source.sendFailure(Component.literal(
                        "OceanCanvas flatten refused: disable singleChunkEnabled and acceptanceHarnessEnabled first."));
                return 0;
            }
            Path root = operationRoot(server);
            State restore = State.load(root.resolve(RESTORE_STATE_FILE));
            if (restore != null) {
                source.sendFailure(Component.literal(
                        "OceanCanvas flatten refused: a durable restore lifecycle already exists for this Canvas. "
                                + "Preserve that evidence; do not re-arm authoring over it."));
                return 0;
            }
            State existing = State.load(root.resolve(STATE_FILE));
            State desired = State.initial(core);
            if (existing != null) {
                existing.requireSameGeometry(desired);
                if (existing.status.equals("COMPLETE")) {
                    source.sendSuccess(() -> Component.literal(
                            "OceanCanvas flatten is already COMPLETE for this configured Canvas."), false);
                    return 1;
                }
                State resumed = existing.withStatus("RUNNING");
                resumed.write(root.resolve(STATE_FILE));
                closeActive();
                source.sendSuccess(() -> Component.literal(
                        "OceanCanvas flatten resumed at chunk " + resumed.nextIndex + "/" + resumed.totalChunks + "."), true);
                return 1;
            }

            Files.createDirectories(root);
            desired.write(root.resolve(STATE_FILE));
            closeActive();
            source.sendSuccess(() -> Component.literal(
                    "OceanCanvas flatten started: " + desired.totalChunks + " chunks, one active chunk at a time. "
                            + "Use /oceancanvas flatten status for progress."), true);
            return 1;
        } catch (Exception e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) FULL-CANVAS-START-FAILED", e);
            source.sendFailure(Component.literal("OceanCanvas flatten could not start: " + safe(e)));
            return 0;
        }
    }

    private static int status(CommandSourceStack source) {
        try {
            State state = State.load(operationRoot(source.getServer()).resolve(STATE_FILE));
            if (state == null) {
                source.sendSuccess(() -> Component.literal("OceanCanvas flatten: IDLE."), false);
                return 1;
            }
            long remaining = Math.max(0L, state.totalChunks - state.nextIndex);
            double pct = state.totalChunks == 0 ? 100.0 : (100.0 * state.nextIndex / state.totalChunks);
            source.sendSuccess(() -> Component.literal(String.format(
                    "OceanCanvas flatten: %s %,d/%,d chunks (%.2f%%), %,d remaining.",
                    state.status, state.nextIndex, state.totalChunks, pct, remaining)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas flatten status failed: " + safe(e)));
            return 0;
        }
    }

    private static int pause(CommandSourceStack source) {
        try {
            Path path = operationRoot(source.getServer()).resolve(STATE_FILE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal("OceanCanvas flatten is not configured."));
                return 0;
            }
            if (!state.status.equals("COMPLETE")) state.withStatus("PAUSED").write(path);
            closeActive();
            source.sendSuccess(() -> Component.literal("OceanCanvas flatten paused at the next durable boundary."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas flatten pause failed: " + safe(e)));
            return 0;
        }
    }

    private static int resume(CommandSourceStack source) {
        try {
            Path path = operationRoot(source.getServer()).resolve(STATE_FILE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal(
                        "No prior full-Canvas operation exists. Start with /oceancanvas flatten start ERASE_CONFIGURED_CANVAS."));
                return 0;
            }
            if (state.status.equals("COMPLETE")) {
                source.sendSuccess(() -> Component.literal("OceanCanvas flatten is already COMPLETE."), false);
                return 1;
            }
            state.withStatus("RUNNING").write(path);
            closeActive();
            source.sendSuccess(() -> Component.literal("OceanCanvas flatten resumed."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas flatten resume failed: " + safe(e)));
            return 0;
        }
    }

    private static int cancel(CommandSourceStack source) {
        try {
            Path path = operationRoot(source.getServer()).resolve(STATE_FILE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal("OceanCanvas flatten is not configured."));
                return 0;
            }
            if (!state.status.equals("COMPLETE")) state.withStatus("PAUSED").write(path);
            closeActive();
            source.sendSuccess(() -> Component.literal(
                    "OceanCanvas flatten cancelled at the current durable boundary; /oceancanvas flatten resume can continue it."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas flatten cancel failed: " + safe(e)));
            return 0;
        }
    }

    private static int restoreStart(CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            Path root = operationRoot(server);
            State flatten = State.load(root.resolve(STATE_FILE));
            State desired = State.initial(core);
            if (flatten == null) {
                source.sendFailure(Component.literal(
                        "OceanCanvas restore refused: no durable full-Canvas flatten operation exists."));
                return 0;
            }
            flatten.requireSameGeometry(desired);
            if (!flatten.status.equals("COMPLETE") || flatten.nextIndex != flatten.totalChunks) {
                source.sendFailure(Component.literal(
                        "OceanCanvas restore refused: flatten must be fully COMPLETE before restore can begin."));
                return 0;
            }

            Path restorePath = root.resolve(RESTORE_STATE_FILE);
            State existing = State.load(restorePath);
            if (existing != null) {
                existing.requireSameGeometry(desired);
                if (existing.status.equals("COMPLETE")) {
                    source.sendSuccess(() -> Component.literal(
                            "OceanCanvas restore is already COMPLETE for this configured Canvas."), false);
                    return 1;
                }
                existing.withStatus("RUNNING").write(restorePath);
                closeActive();
                source.sendSuccess(() -> Component.literal(
                        "OceanCanvas restore resumed at chunk " + existing.nextIndex + "/" + existing.totalChunks + "."), true);
                return 1;
            }

            desired.write(restorePath);
            closeActive();
            source.sendSuccess(() -> Component.literal(
                    "OceanCanvas restore started: " + desired.totalChunks
                            + " chunks, one active chunk at a time, using the immutable captured preimages."), true);
            return 1;
        } catch (Exception e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) FULL-CANVAS-RESTORE-START-FAILED", e);
            source.sendFailure(Component.literal("OceanCanvas restore could not start: " + safe(e)));
            return 0;
        }
    }

    private static int restoreStatus(CommandSourceStack source) {
        try {
            State state = State.load(operationRoot(source.getServer()).resolve(RESTORE_STATE_FILE));
            if (state == null) {
                source.sendSuccess(() -> Component.literal("OceanCanvas restore: IDLE."), false);
                return 1;
            }
            long remaining = Math.max(0L, state.totalChunks - state.nextIndex);
            double pct = state.totalChunks == 0 ? 100.0 : (100.0 * state.nextIndex / state.totalChunks);
            source.sendSuccess(() -> Component.literal(String.format(
                    "OceanCanvas restore: %s %,d/%,d chunks (%.2f%%), %,d remaining.",
                    state.status, state.nextIndex, state.totalChunks, pct, remaining)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas restore status failed: " + safe(e)));
            return 0;
        }
    }

    private static int restorePause(CommandSourceStack source) {
        return setRestorePaused(source, "paused");
    }

    private static int restoreCancel(CommandSourceStack source) {
        return setRestorePaused(source, "cancelled");
    }

    private static int setRestorePaused(CommandSourceStack source, String verb) {
        try {
            Path path = operationRoot(source.getServer()).resolve(RESTORE_STATE_FILE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal("OceanCanvas restore is not configured."));
                return 0;
            }
            if (!state.status.equals("COMPLETE")) state.withStatus("PAUSED").write(path);
            closeActive();
            source.sendSuccess(() -> Component.literal(
                    "OceanCanvas restore " + verb
                            + " at the current durable boundary; /oceancanvas restore resume can continue it."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas restore " + verb + " failed: " + safe(e)));
            return 0;
        }
    }

    private static int restoreResume(CommandSourceStack source) {
        try {
            Path path = operationRoot(source.getServer()).resolve(RESTORE_STATE_FILE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal(
                        "No prior full-Canvas restore exists. Start with /oceancanvas restore start RESTORE_CONFIGURED_CANVAS."));
                return 0;
            }
            if (state.status.equals("COMPLETE")) {
                source.sendSuccess(() -> Component.literal("OceanCanvas restore is already COMPLETE."), false);
                return 1;
            }
            state.withStatus("RUNNING").write(path);
            closeActive();
            source.sendSuccess(() -> Component.literal("OceanCanvas restore resumed."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas restore resume failed: " + safe(e)));
            return 0;
        }
    }

    private static synchronized void tick(MinecraftServer server) {
        if (evaluatedServer != server) {
            closeActive();
            evaluatedServer = server;
        }
        try {
            Path root = operationRoot(server);
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            State desired = State.initial(core);

            State restore = State.load(root.resolve(RESTORE_STATE_FILE));
            if (restore != null) {
                restore.requireSameGeometry(desired);
                if (!restore.status.equals("RUNNING")) {
                    closeActive();
                    return;
                }
                tickOperation(server, core, root, root.resolve(RESTORE_STATE_FILE), restore, true);
                return;
            }

            State flatten = State.load(root.resolve(STATE_FILE));
            if (flatten == null || !flatten.status.equals("RUNNING")) {
                closeActive();
                return;
            }
            flatten.requireSameGeometry(desired);
            tickOperation(server, core, root, root.resolve(STATE_FILE), flatten, false);
        } catch (Throwable t) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) FULL-CANVAS-FATAL action=halt-preserve-progress", t);
            try {
                Path root = operationRoot(server);
                Path restorePath = root.resolve(RESTORE_STATE_FILE);
                State restore = State.load(restorePath);
                if (restore != null && !restore.status.equals("COMPLETE")) {
                    restore.withStatus("PAUSED").write(restorePath);
                } else {
                    Path flattenPath = root.resolve(STATE_FILE);
                    State flatten = State.load(flattenPath);
                    if (flatten != null && !flatten.status.equals("COMPLETE")) {
                        flatten.withStatus("PAUSED").write(flattenPath);
                    }
                }
            } catch (Throwable ignored) {}
            closeActive();
        }
    }

    private static void tickOperation(MinecraftServer server, CoreConfig core, Path root,
                                      Path statePath, State state, boolean restoreMode) throws Exception {
        if (state.nextIndex >= state.totalChunks) {
            state.withStatus("COMPLETE").write(statePath);
            closeActive();
            return;
        }
        if (active == null || active.index != state.nextIndex || active.restoreMode != restoreMode) {
            closeActive();
            active = Session.open(server, core, state, root, restoreMode);
        }
        if (active.tick()) {
            State advanced = state.withNextIndex(state.nextIndex + 1L);
            if (advanced.nextIndex >= advanced.totalChunks) advanced = advanced.withStatus("COMPLETE");
            advanced.write(statePath);
            OceanCanvas.LOGGER.info(
                    restoreMode
                            ? "(Ocean Canvas Core) FULL-CANVAS-RESTORE-PROGRESS completed={}/{} current={} status={}"
                            : "(Ocean Canvas Core) FULL-CANVAS-PROGRESS completed={}/{} current={} status={}",
                    advanced.nextIndex, advanced.totalChunks, active.key, advanced.status);
            closeActive();
        }
    }

    private static synchronized void stop(MinecraftServer server) {
        if (evaluatedServer == server) {
            closeActive();
            evaluatedServer = null;
        }
    }

    private static void closeActive() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    private static Path operationRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core").resolve(DIR);
    }

    private static String safe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static final class Session implements AutoCloseable {
        private final long index;
        private final ChunkKey key;
        private final SingleChunkPipeline pipeline;
        private final SingleChunkWorldPorts ports;
        private final boolean restoreMode;

        private Session(long index, ChunkKey key, SingleChunkPipeline pipeline,
                        SingleChunkWorldPorts ports, boolean restoreMode) {
            this.index = index;
            this.key = key;
            this.pipeline = pipeline;
            this.ports = ports;
            this.restoreMode = restoreMode;
        }

        static Session open(MinecraftServer server, CoreConfig core, State state, Path root,
                            boolean restoreMode) throws Exception {
            ServerLevel world = server.overworld();
            if (world == null) throw new IllegalStateException("overworld unavailable");

            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                    core.canvasSize(), core.centerX(), core.centerZ());
            RowMajorCursor cursor = new RowMajorCursor(bounds, state.nextIndex);
            ChunkKey key = cursor.peek();
            Path chunkRoot = root.resolve("chunks").resolve("chunk_" + key.x() + "_" + key.z());

            BlockStateRegistryFingerprint.Identity registry = BlockStateRegistryFingerprint.compute();
            BlockStateRegistryIdentityStore.ensureExact(
                    root.resolve("block-state-registry.identity"), registry.sha256(), registry.stateCount());

            SingleChunkOperationSpec spec = new SingleChunkOperationSpec(1, key, core.canvasSize(),
                    core.centerX(), core.centerZ(), core.waterSurfaceY(),
                    core.oceanFloorY(), core.oceanFloorVariation());
            OperationManifestStore.ensureExact(chunkRoot.resolve("operation.properties"), spec);
            RuntimeReceiptLog receipts = new RuntimeReceiptLog(chunkRoot.resolve("runtime-receipts.log"));
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(
                    new CoreJournal(chunkRoot.resolve("transitions.journal")), key);
            SingleChunkWorldPorts ports = new SingleChunkWorldPorts(
                    world, core, key, receipts, spec.operationId(),
                    chunkRoot.resolve("preimage-blockstates.bin"),
                    chunkRoot.resolve("preimage-blockentities.ocbe"),
                    true);
            ChunkStage stage = pipeline.record().stage();
            if (restoreMode && stage != ChunkStage.VERIFIED && stage != ChunkStage.RESTORED
                    && stage != ChunkStage.RESTORE_VERIFIED && stage != ChunkStage.COMPLETE) {
                ports.close();
                throw new IOException("restore refused: chunk " + key
                        + " is not durably VERIFIED; current stage=" + stage);
            }
            if (!restoreMode && (stage == ChunkStage.RESTORED
                    || stage == ChunkStage.RESTORE_VERIFIED || stage == ChunkStage.COMPLETE)) {
                ports.close();
                throw new IOException("flatten refused: chunk " + key
                        + " already entered restore lifecycle stage=" + stage);
            }
            return new Session(state.nextIndex, key, pipeline, ports, restoreMode);
        }

        /**
         * @return true only when this chunk is durably VERIFIED and may advance.
         */
        boolean tick() throws Exception {
            ChunkStage stage = pipeline.record().stage();
            if (stage == ChunkStage.FAILED) {
                throw new IOException("chunk " + key + " is FAILED: " + pipeline.record().failureReason());
            }

            if (restoreMode) {
                if (stage == ChunkStage.COMPLETE) {
                    ports.close();
                    return true;
                }
                if (stage != ChunkStage.VERIFIED && stage != ChunkStage.RESTORED
                        && stage != ChunkStage.RESTORE_VERIFIED) {
                    throw new IOException("restore chunk " + key + " entered invalid stage " + stage);
                }
                pipeline.tick(ports, System.currentTimeMillis());
                if (pipeline.record().stage() == ChunkStage.COMPLETE) {
                    ports.close();
                    return true;
                }
                return false;
            }

            if (stage == ChunkStage.VERIFIED) {
                // Production flatten stops here. Do NOT call restore/verifyRestore/release.
                // close() releases the runtime ticket but deliberately retains the exact
                // preimage and block-entity sidecar for a future restore operation.
                ports.close();
                return true;
            }
            if (stage == ChunkStage.RESTORED || stage == ChunkStage.RESTORE_VERIFIED || stage == ChunkStage.COMPLETE) {
                throw new IOException("production chunk unexpectedly entered acceptance restore stage " + stage);
            }
            pipeline.tick(ports, System.currentTimeMillis());
            if (pipeline.record().stage() == ChunkStage.VERIFIED) {
                ports.close();
                return true;
            }
            return false;
        }

        @Override public void close() { ports.close(); }
    }

    private record State(
            int schema,
            String status,
            int canvasSize,
            int centerX,
            int centerZ,
            int waterSurfaceY,
            int oceanFloorY,
            int oceanFloorVariation,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            long totalChunks,
            long nextIndex) {

        static State initial(CoreConfig core) {
            var b = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                    core.canvasSize(), core.centerX(), core.centerZ());
            return new State(STATE_SCHEMA, "RUNNING", core.canvasSize(), core.centerX(), core.centerZ(),
                    core.waterSurfaceY(), core.oceanFloorY(), core.oceanFloorVariation(),
                    b.minX(), b.maxX(), b.minZ(), b.maxZ(), b.count(), 0L);
        }

        State withStatus(String next) {
            return new State(schema, next, canvasSize, centerX, centerZ, waterSurfaceY, oceanFloorY,
                    oceanFloorVariation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, nextIndex);
        }

        State withNextIndex(long next) {
            if (next < 0 || next > totalChunks) throw new IllegalArgumentException("cursor outside Canvas");
            return new State(schema, status, canvasSize, centerX, centerZ, waterSurfaceY, oceanFloorY,
                    oceanFloorVariation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, next);
        }

        void requireSameGeometry(State other) throws IOException {
            if (schema != other.schema || canvasSize != other.canvasSize || centerX != other.centerX
                    || centerZ != other.centerZ || waterSurfaceY != other.waterSurfaceY
                    || oceanFloorY != other.oceanFloorY || oceanFloorVariation != other.oceanFloorVariation
                    || minChunkX != other.minChunkX || maxChunkX != other.maxChunkX
                    || minChunkZ != other.minChunkZ || maxChunkZ != other.maxChunkZ
                    || totalChunks != other.totalChunks) {
                throw new IOException("configured Canvas geometry differs from durable flatten operation; "
                        + "restore the original configuration before resuming");
            }
        }

        static State load(Path path) throws IOException {
            if (!Files.isRegularFile(path)) return null;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(path)) { p.load(in); }
            try {
                State s = new State(
                        Integer.parseInt(req(p, "schema")),
                        req(p, "status"),
                        Integer.parseInt(req(p, "canvasSize")),
                        Integer.parseInt(req(p, "centerX")),
                        Integer.parseInt(req(p, "centerZ")),
                        Integer.parseInt(req(p, "waterSurfaceY")),
                        Integer.parseInt(req(p, "oceanFloorY")),
                        Integer.parseInt(req(p, "oceanFloorVariation")),
                        Integer.parseInt(req(p, "minChunkX")),
                        Integer.parseInt(req(p, "maxChunkX")),
                        Integer.parseInt(req(p, "minChunkZ")),
                        Integer.parseInt(req(p, "maxChunkZ")),
                        Long.parseLong(req(p, "totalChunks")),
                        Long.parseLong(req(p, "nextIndex")));
                if (s.schema != STATE_SCHEMA) throw new IOException("unsupported full-Canvas state schema " + s.schema);
                if (!s.status.equals("RUNNING") && !s.status.equals("PAUSED") && !s.status.equals("COMPLETE")) {
                    throw new IOException("invalid full-Canvas status " + s.status);
                }
                if (s.nextIndex < 0 || s.nextIndex > s.totalChunks) throw new IOException("invalid full-Canvas cursor");
                return s;
            } catch (NumberFormatException e) {
                throw new IOException("invalid numeric full-Canvas state", e);
            }
        }

        void write(Path path) throws IOException {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Properties p = new Properties();
            p.setProperty("schema", Integer.toString(schema));
            p.setProperty("status", status);
            p.setProperty("canvasSize", Integer.toString(canvasSize));
            p.setProperty("centerX", Integer.toString(centerX));
            p.setProperty("centerZ", Integer.toString(centerZ));
            p.setProperty("waterSurfaceY", Integer.toString(waterSurfaceY));
            p.setProperty("oceanFloorY", Integer.toString(oceanFloorY));
            p.setProperty("oceanFloorVariation", Integer.toString(oceanFloorVariation));
            p.setProperty("minChunkX", Integer.toString(minChunkX));
            p.setProperty("maxChunkX", Integer.toString(maxChunkX));
            p.setProperty("minChunkZ", Integer.toString(minChunkZ));
            p.setProperty("maxChunkZ", Integer.toString(maxChunkZ));
            p.setProperty("totalChunks", Long.toString(totalChunks));
            p.setProperty("nextIndex", Long.toString(nextIndex));

            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                p.store(out, "OceanCanvas full-Canvas flatten operation");
                out.flush();
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) { ch.force(true); }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic full-Canvas state publication unsupported; staged state preserved at " + tmp, e);
            }
        }

        private static String req(Properties p, String key) throws IOException {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IOException("missing full-Canvas state property " + key);
            return value.trim();
        }
    }
}
