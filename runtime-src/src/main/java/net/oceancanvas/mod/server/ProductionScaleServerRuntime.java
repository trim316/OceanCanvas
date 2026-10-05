package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.expansion.BoundedCampaignAdmission;
import net.oceancanvas.core.expansion.FourChunkCanaryAdmission;
import net.oceancanvas.core.expansion.NineChunkCanaryAdmission;
import net.oceancanvas.core.expansion.SixteenChunkCanaryAdmission;
import net.oceancanvas.core.expansion.TwoChunkCanaryAdmission;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.geometry.OceanFloorProfile;
import net.oceancanvas.core.pipeline.OperationMode;
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
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Production-scale Ocean Canvas runtime.
 *
 * <p>This is intentionally separate from {@link FullCanvasServerRuntime}. The
 * v0.2.28 full-canvas path is a correctness/recovery canary whose exact
 * per-chunk preimages and per-chunk whole-server save barriers are deliberately
 * conservative. This production lane follows the mature v253.125 scheduler
 * shape instead: bounded multi-chunk terrain admission, raw chunk mutation,
 * decoupled lighting finalization, coarse crash checkpoints, and native
 * seed-bound regeneration for Restore.</p>
 *
 * <p>Crash contract: only the contiguous prefix stored in operation.properties
 * is authoritative. Work after that prefix may have reached disk, but is safe
 * to replay because terrain authoring is canonical/idempotent and Restore
 * regeneration is deterministic for the bound world seed. A checkpoint first
 * flushes Minecraft world data and only then advances the durable prefix.</p>
 */
public final class ProductionScaleServerRuntime {
    public static final String ENGINE_BASELINE = "v253.125.54";
    public static final String ENGINE_SOURCE_SHA256 = "b96e263e6cd74a4f8188e2b00b5dce2f6d8722c825174d98b6929b4c70e29d8f";

    private static final String DIR = "production-scale";
    private static final String PREGEN_STATE = "pregen.properties";
    private static final String RESTORE_STATE = "restore.properties";
    private static final int STATE_SCHEMA = 1;

    // v253 production-shape bounds. The wall-clock budget is still the hard
    // per-tick governor; these limits prevent unbounded residency/backlogs.
    private static final int TERRAIN_ACTIVE_LIMIT = 128;
    private static final int LIGHT_ACTIVE_LIMIT = 64;
    private static final int LIGHT_BACKLOG_LIMIT = 512;
    private static final int WORK_CELL_SLICE = 8192;
    private static final int RESTORE_CHECKPOINT_INTERVAL = 64;
    private static final long PREGEN_CHECKPOINT_INTERVAL = 2048L;
    private static final long TICK_WALL_BUDGET_NANOS = 20_000_000L;
    private static final int LIGHT_QUIET_TICKS = 4;
    private static final int MAX_RESIDENCY_RETRIES = 8;
    private static final int MAX_REPAIR_PASSES = 3;

    private static Path configDir;
    private static MinecraftServer evaluatedServer;
    private static PregenSession pregen;
    private static RestoreSession restore;
    private static boolean registered;

    private ProductionScaleServerRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        ServerTickEvents.END_SERVER_TICK.register(ProductionScaleServerRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(ProductionScaleServerRuntime::stop);
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas) PRODUCTION-SCALE-ADAPTER-REGISTERED baseline={} sourceSha256={} authority=COMMAND-ONLY",
                ENGINE_BASELINE, ENGINE_SOURCE_SHA256);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/production"), PermissionLevel.ADMINS))
                .then(Commands.literal("pregen")
                        .then(Commands.literal("start")
                                .then(Commands.literal("ERASE_CONFIGURED_CANVAS")
                                        .executes(context -> pregenStart(context.getSource()))))
                        .then(Commands.literal("status").executes(context -> pregenStatus(context.getSource())))
                        .then(Commands.literal("pause").executes(context -> pregenPause(context.getSource(), false)))
                        .then(Commands.literal("cancel").executes(context -> pregenPause(context.getSource(), true)))
                        .then(Commands.literal("resume").executes(context -> pregenResume(context.getSource()))))
                .then(Commands.literal("restoreseed")
                        .then(Commands.literal("start")
                                .then(Commands.literal("RESTORE_CONFIGURED_CANVAS_FROM_SEED")
                                        .executes(context -> restoreStart(context.getSource()))))
                        .then(Commands.literal("status").executes(context -> restoreStatus(context.getSource())))
                        .then(Commands.literal("pause").executes(context -> restorePause(context.getSource(), false)))
                        .then(Commands.literal("cancel").executes(context -> restorePause(context.getSource(), true)))
                        .then(Commands.literal("resume").executes(context -> restoreResume(context.getSource())))));
    }

    private static int pregenStart(CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            requireExclusiveAuthority(server, core);
            Path root = root(server);
            Files.createDirectories(root);
            if (Files.isRegularFile(root.resolve(RESTORE_STATE))) {
                source.sendFailure(Component.literal("OceanCanvas production pregen refused: a seed-restore lifecycle already exists."));
                return 0;
            }
            State desired = State.initial(core, server.overworld());
            State existing = State.load(root.resolve(PREGEN_STATE));
            if (existing != null) {
                existing.requireSameIdentity(desired);
                if (existing.status.equals("COMPLETE")) {
                    source.sendSuccess(() -> Component.literal("OceanCanvas production pregen is already COMPLETE."), false);
                    return 1;
                }
                existing.withStatus("RUNNING").write(root.resolve(PREGEN_STATE));
            } else {
                desired.write(root.resolve(PREGEN_STATE));
            }
            closePregen(false);
            source.sendSuccess(() -> Component.literal("OceanCanvas production pregen started/resumed for "
                    + desired.totalChunks + " chunks. Baseline=" + ENGINE_BASELINE
                    + ". Use /oceancanvas pregen status for progress."), true);
            return 1;
        } catch (Exception e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) PRODUCTION-PREGEN-START-FAILED", e);
            source.sendFailure(Component.literal("OceanCanvas production pregen could not start: " + safe(e)));
            return 0;
        }
    }

    private static int pregenStatus(CommandSourceStack source) {
        try {
            Path path = root(source.getServer()).resolve(PREGEN_STATE);
            State state = State.load(path);
            if (state == null) {
                source.sendSuccess(() -> Component.literal("OceanCanvas production pregen: IDLE."), false);
                return 1;
            }
            long frontier = pregen != null ? pregen.frontier : state.nextIndex;
            long admitted = pregen != null ? pregen.nextAdmission : state.nextIndex;
            long remaining = Math.max(0L, state.totalChunks - frontier);
            double pct = state.totalChunks == 0 ? 100.0 : 100.0 * frontier / state.totalChunks;
            int terrain = pregen == null ? 0 : pregen.terrain.size();
            int light = pregen == null ? 0 : pregen.pendingLight.size() + pregen.lighting.size();
            source.sendSuccess(() -> Component.literal(String.format(
                    "OceanCanvas production pregen: %s durable=%,d verified=%,d admitted=%,d/%,d (%.2f%%), %,d remaining, terrain=%d, lightBacklog=%d.",
                    state.status, state.nextIndex, frontier, admitted, state.totalChunks, pct, remaining, terrain, light)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas production pregen status failed: " + safe(e)));
            return 0;
        }
    }

    private static int pregenPause(CommandSourceStack source, boolean cancel) {
        try {
            Path path = root(source.getServer()).resolve(PREGEN_STATE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal("OceanCanvas production pregen is not configured."));
                return 0;
            }
            if (state.status.equals("COMPLETE")) {
                source.sendSuccess(() -> Component.literal("OceanCanvas production pregen is already COMPLETE."), false);
                return 1;
            }
            if (pregen != null) {
                pregen.checkpoint("PAUSED");
                pregen.close();
                pregen = null;
            } else {
                state.withStatus("PAUSED").write(path);
            }
            source.sendSuccess(() -> Component.literal("OceanCanvas production pregen "
                    + (cancel ? "cancelled" : "paused") + " at a durable checkpoint. Resume will replay only uncommitted tail work."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas production pregen pause failed: " + safe(e)));
            return 0;
        }
    }

    private static int pregenResume(CommandSourceStack source) {
        try {
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            requireExclusiveAuthority(source.getServer(), core);
            Path path = root(source.getServer()).resolve(PREGEN_STATE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal("No production pregen exists. Start with /oceancanvas pregen start ERASE_CONFIGURED_CANVAS."));
                return 0;
            }
            state.requireSameIdentity(State.initial(core, source.getServer().overworld()));
            if (!state.status.equals("COMPLETE")) state.withStatus("RUNNING").write(path);
            closePregen(false);
            source.sendSuccess(() -> Component.literal("OceanCanvas production pregen resumed from durable chunk " + state.nextIndex + "."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas production pregen resume failed: " + safe(e)));
            return 0;
        }
    }

    private static int restoreStart(CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            requireExclusiveAuthority(server, core);
            Path root = root(server);
            State pregenState = State.load(root.resolve(PREGEN_STATE));
            State desired = State.initial(core, server.overworld());
            if (pregenState == null || !pregenState.status.equals("COMPLETE") || pregenState.nextIndex != pregenState.totalChunks) {
                source.sendFailure(Component.literal("OceanCanvas seed Restore refused: production pregen must be fully COMPLETE first."));
                return 0;
            }
            pregenState.requireSameIdentity(desired);
            State existing = State.load(root.resolve(RESTORE_STATE));
            if (existing != null) {
                existing.requireSameIdentity(desired);
                if (existing.status.equals("COMPLETE")) {
                    source.sendSuccess(() -> Component.literal("OceanCanvas seed Restore is already COMPLETE."), false);
                    return 1;
                }
                existing.withStatus("RUNNING").write(root.resolve(RESTORE_STATE));
            } else {
                desired.withNextIndex(0L).write(root.resolve(RESTORE_STATE));
            }
            closeRestore();
            closePregen(false);
            source.sendSuccess(() -> Component.literal("OceanCanvas seed Restore started. Stored chunks are pruned only while unloaded, then regenerated natively from the bound original seed."), true);
            return 1;
        } catch (Exception e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) PRODUCTION-RESTORE-START-FAILED", e);
            source.sendFailure(Component.literal("OceanCanvas seed Restore could not start: " + safe(e)));
            return 0;
        }
    }

    private static int restoreStatus(CommandSourceStack source) {
        try {
            State state = State.load(root(source.getServer()).resolve(RESTORE_STATE));
            if (state == null) {
                source.sendSuccess(() -> Component.literal("OceanCanvas seed Restore: IDLE."), false);
                return 1;
            }
            long index = restore != null ? restore.nextIndex : state.nextIndex;
            long remaining = Math.max(0L, state.totalChunks - index);
            double pct = state.totalChunks == 0 ? 100.0 : 100.0 * index / state.totalChunks;
            String phase = restore == null ? "idle" : restore.phase();
            source.sendSuccess(() -> Component.literal(String.format(
                    "OceanCanvas seed Restore: %s %,d/%,d (%.2f%%), %,d remaining, phase=%s.",
                    state.status, index, state.totalChunks, pct, remaining, phase)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas seed Restore status failed: " + safe(e)));
            return 0;
        }
    }

    private static int restorePause(CommandSourceStack source, boolean cancel) {
        try {
            Path path = root(source.getServer()).resolve(RESTORE_STATE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal("OceanCanvas seed Restore is not configured."));
                return 0;
            }
            if (state.status.equals("COMPLETE")) {
                source.sendSuccess(() -> Component.literal("OceanCanvas seed Restore is already COMPLETE."), false);
                return 1;
            }
            if (restore != null) {
                restore.pauseRequested = true;
            } else {
                state.withStatus("PAUSED").write(path);
            }
            source.sendSuccess(() -> Component.literal("OceanCanvas seed Restore " + (cancel ? "cancel" : "pause")
                    + " requested; any active prune/regeneration transaction will finish before the durable pause."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas seed Restore pause failed: " + safe(e)));
            return 0;
        }
    }

    private static int restoreResume(CommandSourceStack source) {
        try {
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            requireExclusiveAuthority(source.getServer(), core);
            Path path = root(source.getServer()).resolve(RESTORE_STATE);
            State state = State.load(path);
            if (state == null) {
                source.sendFailure(Component.literal("No seed Restore exists. Start with /oceancanvas restoreseed start RESTORE_CONFIGURED_CANVAS_FROM_SEED."));
                return 0;
            }
            state.requireSameIdentity(State.initial(core, source.getServer().overworld()));
            if (!state.status.equals("COMPLETE")) state.withStatus("RUNNING").write(path);
            closeRestore();
            source.sendSuccess(() -> Component.literal("OceanCanvas seed Restore resumed from chunk " + state.nextIndex + "."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas seed Restore resume failed: " + safe(e)));
            return 0;
        }
    }

    private static synchronized void tick(MinecraftServer server) {
        if (evaluatedServer != server) {
            closePregen(false);
            closeRestore();
            evaluatedServer = server;
        }
        try {
            Path root = root(server);
            State restoreState = State.load(root.resolve(RESTORE_STATE));
            if (restoreState != null && restoreState.status.equals("RUNNING")) {
                closePregen(false);
                CoreConfig core = CoreConfig.loadOrCreate(configDir);
                requireExclusiveAuthority(server, core);
                restoreState.requireSameIdentity(State.initial(core, server.overworld()));
                if (restore == null) restore = new RestoreSession(server, root.resolve(RESTORE_STATE), restoreState);
                if (restore.tick()) restore = null;
                return;
            }
            if (restore != null) closeRestore();

            State pregenState = State.load(root.resolve(PREGEN_STATE));
            if (pregenState == null || !pregenState.status.equals("RUNNING")) {
                closePregen(false);
                return;
            }
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            requireExclusiveAuthority(server, core);
            pregenState.requireSameIdentity(State.initial(core, server.overworld()));
            if (pregen == null) pregen = new PregenSession(server, core, root.resolve(PREGEN_STATE), pregenState);
            if (pregen.tick()) pregen = null;
        } catch (Throwable t) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) PRODUCTION-SCALE-FATAL action=halt-preserve-durable-prefix", t);
            closePregen(false);
            closeRestore();
        }
    }

    private static synchronized void stop(MinecraftServer server) {
        if (evaluatedServer != server) return;
        closePregen(false); // durable prefix remains authoritative; tail is replayed
        closeRestore();
        evaluatedServer = null;
    }

    private static void requireExclusiveAuthority(MinecraftServer server, CoreConfig core) throws IOException {
        if (server.overworld() == null) throw new IOException("overworld unavailable");
        if (core.mode() != OperationMode.CORE_AUTHORING) throw new IOException("mode must be CORE_AUTHORING");
        if (!core.expansionEnabled()) throw new IOException("expansionEnabled=false");
        if (core.singleChunkEnabled() || core.acceptanceHarnessEnabled()) {
            throw new IOException("single-chunk or acceptance authority is enabled");
        }
        if (TwoChunkCanaryAdmission.explicitlyEnabled(configDir)
                || FourChunkCanaryAdmission.explicitlyEnabled(configDir)
                || NineChunkCanaryAdmission.explicitlyEnabled(configDir)
                || SixteenChunkCanaryAdmission.explicitlyEnabled(configDir)
                || BoundedCampaignAdmission.explicitlyEnabled(configDir)) {
            throw new IOException("another scale/campaign authority is enabled");
        }
        Path legacy = server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core").resolve("full-canvas");
        if (Files.isRegularFile(legacy.resolve("operation.properties"))
                || Files.isRegularFile(legacy.resolve("restore.properties"))) {
            throw new IOException("legacy exact-preimage full-canvas lifecycle exists; do not overlap production authority in the same world");
        }
    }

    private static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core").resolve(DIR);
    }

    private static void closePregen(boolean checkpoint) {
        if (pregen == null) return;
        try {
            if (checkpoint) pregen.checkpoint("PAUSED");
        } catch (Throwable t) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) production pregen checkpoint-on-close failed; old durable prefix retained", t);
        }
        pregen.close();
        pregen = null;
    }

    private static void closeRestore() {
        if (restore == null) return;
        restore.close();
        restore = null;
    }

    private static final class PregenSession implements AutoCloseable {
        final MinecraftServer server;
        final ServerLevel world;
        final CoreConfig core;
        final Path statePath;
        State state;
        final long startedNanos = System.nanoTime();
        final long startedFrontier;
        long durableIndex;
        long frontier;
        long nextAdmission;
        long lastTelemetryTick;
        final Map<Long, TerrainWork> terrain = new LinkedHashMap<>();
        final ArrayDeque<LightPending> pendingLight = new ArrayDeque<>();
        final Map<Long, LightWork> lighting = new LinkedHashMap<>();
        final Set<Long> completed = new HashSet<>();

        PregenSession(MinecraftServer server, CoreConfig core, Path statePath, State state) {
            this.server = server;
            this.world = server.overworld();
            this.core = core;
            this.statePath = statePath;
            this.state = state;
            this.durableIndex = state.nextIndex;
            this.frontier = state.nextIndex;
            this.nextAdmission = state.nextIndex;
            this.startedFrontier = state.nextIndex;
            OceanCanvas.LOGGER.warn("(Ocean Canvas) PRODUCTION-PREGEN-RESUME baseline={} durablePrefix={}/{} terrainLimit={} lightActiveLimit={} lightBacklogLimit={} checkpointInterval={}",
                    ENGINE_BASELINE, durableIndex, state.totalChunks, TERRAIN_ACTIVE_LIMIT, LIGHT_ACTIVE_LIMIT,
                    LIGHT_BACKLOG_LIMIT, PREGEN_CHECKPOINT_INTERVAL);
        }

        boolean tick() throws Exception {
            long deadline = System.nanoTime() + TICK_WALL_BUDGET_NANOS;
            serviceLighting(deadline);
            promoteLightWork();
            serviceTerrain(deadline);
            promoteLightWork();
            admitTerrain();
            advanceFrontier();

            if (frontier - durableIndex >= PREGEN_CHECKPOINT_INTERVAL) checkpoint("RUNNING");
            if (frontier >= state.totalChunks && terrain.isEmpty() && pendingLight.isEmpty() && lighting.isEmpty()) {
                checkpoint("COMPLETE");
                OceanCanvas.LOGGER.warn("(Ocean Canvas) PRODUCTION-PREGEN-COMPLETE baseline={} chunks={} elapsedSeconds={}",
                        ENGINE_BASELINE, state.totalChunks, elapsedSeconds());
                close();
                return true;
            }
            emitTelemetry();
            return false;
        }

        private void serviceTerrain(long deadline) throws Exception {
            Iterator<Map.Entry<Long, TerrainWork>> it = terrain.entrySet().iterator();
            while (it.hasNext() && System.nanoTime() < deadline) {
                Map.Entry<Long, TerrainWork> entry = it.next();
                TerrainWork work = entry.getValue();
                if (!work.step(deadline)) continue;
                work.close();
                pendingLight.addLast(new LightPending(entry.getKey(), work.pos));
                it.remove();
            }
        }

        private void serviceLighting(long deadline) throws Exception {
            Iterator<Map.Entry<Long, LightWork>> it = lighting.entrySet().iterator();
            while (it.hasNext() && System.nanoTime() < deadline) {
                Map.Entry<Long, LightWork> entry = it.next();
                LightWork work = entry.getValue();
                if (!work.step(deadline)) continue;
                work.close();
                completed.add(entry.getKey());
                it.remove();
            }
        }

        private void promoteLightWork() {
            while (lighting.size() < LIGHT_ACTIVE_LIMIT && !pendingLight.isEmpty()) {
                LightPending pending = pendingLight.removeFirst();
                lighting.put(pending.index, new LightWork(world, core, pending.pos));
            }
        }

        private void admitTerrain() {
            while (terrain.size() < TERRAIN_ACTIVE_LIMIT
                    && nextAdmission < state.totalChunks
                    && pendingLight.size() + lighting.size() < LIGHT_BACKLOG_LIMIT) {
                ChunkPos pos = state.chunkAt(nextAdmission);
                terrain.put(nextAdmission, new TerrainWork(world, core, pos));
                nextAdmission++;
            }
        }

        private void advanceFrontier() {
            while (completed.remove(frontier)) frontier++;
        }

        void checkpoint(String status) throws IOException {
            // World data first, cursor second. If either step fails, the older
            // cursor remains safe and all tail work is replayable.
            server.saveAllChunks(false, true, true);
            State next = state.withNextIndex(frontier).withStatus(status);
            next.write(statePath);
            state = next;
            durableIndex = frontier;
            OceanCanvas.LOGGER.info("(Ocean Canvas) PRODUCTION-PREGEN-CHECKPOINT status={} durablePrefix={}/{} admitted={} terrain={} lightPending={} lightActive={}",
                    status, durableIndex, state.totalChunks, nextAdmission, terrain.size(), pendingLight.size(), lighting.size());
        }

        private long elapsedSeconds() {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000_000L);
        }

        private void emitTelemetry() {
            long nowTick = world.getGameTime();
            if (nowTick - lastTelemetryTick < 200L) return;
            lastTelemetryTick = nowTick;
            double seconds = Math.max(0.001, (System.nanoTime() - startedNanos) / 1_000_000_000.0);
            double rate = (frontier - startedFrontier) / seconds;
            double required = Math.max(0.0, (state.totalChunks - frontier) / Math.max(1.0, 8.0 * 3600.0 - seconds));
            OceanCanvas.LOGGER.info("(Ocean Canvas) PRODUCTION-PREGEN-TELEMETRY baseline={} verified={}/{} durable={} admitted={} rate={}chunks/s requiredFor8h={} terrain={} lightPending={} lightActive={}",
                    ENGINE_BASELINE, frontier, state.totalChunks, durableIndex, nextAdmission,
                    String.format(java.util.Locale.ROOT, "%.2f", rate),
                    String.format(java.util.Locale.ROOT, "%.2f", required),
                    terrain.size(), pendingLight.size(), lighting.size());
        }

        @Override public void close() {
            for (TerrainWork work : terrain.values()) work.close();
            for (LightWork work : lighting.values()) work.close();
            terrain.clear();
            lighting.clear();
            pendingLight.clear();
            completed.clear();
        }
    }

    private record LightPending(long index, ChunkPos pos) {}

    private static final class TerrainWork implements AutoCloseable {
        final ServerLevel world;
        final CoreConfig core;
        final ChunkPos pos;
        final ResidentHandle resident;
        int cursor;

        TerrainWork(ServerLevel world, CoreConfig core, ChunkPos pos) {
            this.world = world;
            this.core = core;
            this.pos = pos;
            this.resident = new ResidentHandle(world, pos);
        }

        boolean step(long globalDeadline) throws Exception {
            LevelChunk chunk = resident.poll();
            if (chunk == null) return false;
            int minY = core.oceanFloorY() - core.oceanFloorVariation() - 1;
            int maxY = world.getMaxY() - 1;
            int height = maxY - minY + 1;
            int total = Math.multiplyExact(256, height);
            int processed = 0;
            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
            while (cursor < total && processed < WORK_CELL_SLICE && System.nanoTime() < globalDeadline) {
                int index = cursor++;
                int column = index / height;
                int y = minY + (index % height);
                int x = pos.getMinBlockX() + (column & 15);
                int z = pos.getMinBlockZ() + (column >>> 4);
                blockPos.set(x, y, z);
                BlockState current = chunk.getBlockState(blockPos);
                BlockState target = canonicalTarget(core, x, z, y);
                if (!authoredCanonical(core, current, x, z, y)) {
                    setBlockStateRawSafe(world, chunk, blockPos, current, target);
                }
                processed++;
            }
            if (cursor < total) return false;
            Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
            chunk.markUnsaved();
            return true;
        }

        @Override public void close() { resident.close(); }
    }

    private static final class LightWork implements AutoCloseable {
        final ServerLevel world;
        final CoreConfig core;
        final ChunkPos pos;
        final ResidentHandle resident;
        boolean propagated;
        int requestCursor;
        long settleUntil = Long.MIN_VALUE;
        int physicalCursor;
        int lightCursor;
        int physicalRepairs;
        int lightRepairs;

        LightWork(ServerLevel world, CoreConfig core, ChunkPos pos) {
            this.world = world;
            this.core = core;
            this.pos = pos;
            this.resident = new ResidentHandle(world, pos);
        }

        boolean step(long globalDeadline) throws Exception {
            LevelChunk chunk = resident.poll();
            if (chunk == null) return false;
            if (!propagated) {
                world.getChunkSource().getLightEngine().propagateLightSources(pos);
                propagated = true;
            }
            if (!submitLightRequests(globalDeadline)) return false;
            if (settleUntil == Long.MIN_VALUE) {
                settleUntil = world.getGameTime() + LIGHT_QUIET_TICKS;
                return false;
            }
            if (world.getGameTime() < settleUntil) return false;
            if (!verifyPhysical(chunk, globalDeadline)) return false;
            if (!verifyLight(chunk, globalDeadline)) return false;
            chunk.markUnsaved();
            return true;
        }

        private boolean submitLightRequests(long deadline) {
            int minY = core.oceanFloorY() - core.oceanFloorVariation();
            int maxY = core.waterSurfaceY() + 1;
            int height = maxY - minY + 1;
            int total = 256 * height;
            int processed = 0;
            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
            while (requestCursor < total && processed < WORK_CELL_SLICE && System.nanoTime() < deadline) {
                int index = requestCursor++;
                int column = index / height;
                int y = minY + index % height;
                int x = pos.getMinBlockX() + (column & 15);
                int z = pos.getMinBlockZ() + (column >>> 4);
                blockPos.set(x, y, z);
                world.getChunkSource().getLightEngine().checkBlock(blockPos);
                processed++;
            }
            return requestCursor >= total;
        }

        private boolean verifyPhysical(LevelChunk chunk, long deadline) throws IOException {
            int minY = core.oceanFloorY() - core.oceanFloorVariation() - 1;
            int maxY = world.getMaxY() - 1;
            int height = maxY - minY + 1;
            int total = 256 * height;
            int processed = 0;
            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
            while (physicalCursor < total && processed < WORK_CELL_SLICE && System.nanoTime() < deadline) {
                int index = physicalCursor++;
                int column = index / height;
                int y = minY + index % height;
                int x = pos.getMinBlockX() + (column & 15);
                int z = pos.getMinBlockZ() + (column >>> 4);
                blockPos.set(x, y, z);
                BlockState state = chunk.getBlockState(blockPos);
                if (!settledCanonical(core, state, x, z, y)) {
                    if (physicalRepairs >= MAX_REPAIR_PASSES) {
                        throw new IOException("production physical profile remained unstable at " + x + "," + y + "," + z);
                    }
                    setBlockStateRawSafe(world, chunk, blockPos, state, canonicalTarget(core, x, z, y));
                    world.getChunkSource().getLightEngine().checkBlock(blockPos);
                    physicalRepairs++;
                    physicalCursor = 0;
                    lightCursor = 0;
                    settleUntil = world.getGameTime() + LIGHT_QUIET_TICKS;
                    return false;
                }
                processed++;
            }
            return physicalCursor >= total;
        }

        private boolean verifyLight(LevelChunk chunk, long deadline) throws IOException {
            int minY = core.oceanFloorY() - core.oceanFloorVariation();
            int maxY = core.waterSurfaceY() + 1;
            int height = maxY - minY + 1;
            int total = 256 * height;
            int processed = 0;
            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
            while (lightCursor < total && processed < WORK_CELL_SLICE && System.nanoTime() < deadline) {
                int index = lightCursor++;
                int column = index / height;
                int y = minY + index % height;
                int x = pos.getMinBlockX() + (column & 15);
                int z = pos.getMinBlockZ() + (column >>> 4);
                blockPos.set(x, y, z);
                int floorY = floorY(core, x, z);
                if (y < floorY) { processed++; continue; }
                int actual = world.getBrightness(LightLayer.SKY, blockPos);
                int minimum = y == core.waterSurfaceY() + 1 ? 15
                        : Math.max(0, 14 - Math.max(0, core.waterSurfaceY() - y));
                boolean naturalCap = chunk.getBlockState(new BlockPos(x, core.waterSurfaceY(), z)).is(Blocks.ICE)
                        || chunk.getBlockState(new BlockPos(x, core.waterSurfaceY() + 1, z)).is(Blocks.SNOW);
                int maximum = (y == core.waterSurfaceY() + 1 || naturalCap) ? 15 : 14;
                if (actual < minimum || actual > maximum) {
                    if (lightRepairs >= MAX_REPAIR_PASSES) {
                        throw new IOException("production skylight invariant remained unstable at " + x + "," + y + "," + z
                                + " allowed=" + minimum + ".." + maximum + " actual=" + actual);
                    }
                    world.getChunkSource().getLightEngine().checkBlock(blockPos);
                    lightRepairs++;
                    lightCursor = 0;
                    settleUntil = world.getGameTime() + LIGHT_QUIET_TICKS;
                    return false;
                }
                processed++;
            }
            return lightCursor >= total;
        }

        @Override public void close() { resident.close(); }
    }

    private static final class ResidentHandle implements AutoCloseable {
        final ServerLevel world;
        final ChunkPos pos;
        boolean ticket;
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        LevelChunk chunk;
        int retries;

        ResidentHandle(ServerLevel world, ChunkPos pos) {
            this.world = world;
            this.pos = pos;
        }

        LevelChunk poll() throws IOException {
            if (chunk != null) {
                LevelChunk now = world.getChunkSource().getChunkNow(pos.x(), pos.z());
                if (now != null) { chunk = now; return now; }
                chunk = null;
                future = null;
            }
            if (!ticket) {
                world.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 0);
                ticket = true;
            }
            LevelChunk now = world.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (now != null) { chunk = now; retries = 0; return now; }
            if (future == null) {
                future = world.getChunkSource().getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, false);
                return null;
            }
            if (!future.isDone()) return null;
            ChunkResult<ChunkAccess> result = future.join();
            ChunkAccess access = result.orElse(null);
            if (access instanceof LevelChunk live) {
                chunk = live;
                future = null;
                retries = 0;
                return live;
            }
            future = null;
            retries++;
            if (retries > MAX_RESIDENCY_RETRIES) {
                throw new IOException("FULL chunk residency failed for " + pos + " after " + MAX_RESIDENCY_RETRIES
                        + " retries: " + result.getError());
            }
            return null;
        }

        @Override public void close() {
            if (ticket) {
                try { world.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0); }
                catch (Throwable ignored) {}
                ticket = false;
            }
            chunk = null;
            future = null;
        }
    }

    private static final class RestoreSession implements AutoCloseable {
        final MinecraftServer server;
        final ServerLevel world;
        final Path statePath;
        State state;
        long nextIndex;
        ChunkPos current;
        CompletableFuture<Void> pruneFuture;
        ResidentHandle regen;
        boolean pauseRequested;
        long durableIndex;

        RestoreSession(MinecraftServer server, Path statePath, State state) {
            this.server = server;
            this.world = server.overworld();
            this.statePath = statePath;
            this.state = state;
            this.nextIndex = state.nextIndex;
            this.durableIndex = state.nextIndex;
            OceanCanvas.LOGGER.warn("(Ocean Canvas) PRODUCTION-SEED-RESTORE-RESUME next={}/{} boundSeed={} baseline={}",
                    nextIndex, state.totalChunks, state.worldSeed, ENGINE_BASELINE);
        }

        boolean tick() throws Exception {
            if (nextIndex >= state.totalChunks) {
                checkpoint("COMPLETE");
                close();
                return true;
            }
            if (pauseRequested && current == null) {
                checkpoint("PAUSED");
                close();
                return true;
            }
            if (current == null) current = state.chunkAt(nextIndex);

            if (pruneFuture == null && regen == null) {
                // Native regeneration can only own a chunk after Minecraft has
                // naturally released it; never prune a live player-visible chunk.
                if (world.getChunkSource().getChunkNow(current.x(), current.z()) != null) return false;
                var chunkMap = world.getChunkSource().chunkMap;
                pruneFuture = chunkMap.write(current, (CompoundTag) null)
                        .thenCompose(ignored -> chunkMap.synchronize(true));
                return false;
            }
            if (pruneFuture != null) {
                if (!pruneFuture.isDone()) return false;
                pruneFuture.join();
                pruneFuture = null;
                regen = new ResidentHandle(world, current);
                return false;
            }
            LevelChunk regenerated = regen.poll();
            if (regenerated == null) return false;
            regenerated.markUnsaved();
            regen.close();
            regen = null;
            current = null;
            nextIndex++;

            if (nextIndex - durableIndex >= RESTORE_CHECKPOINT_INTERVAL) checkpoint("RUNNING");
            if (pauseRequested) {
                checkpoint("PAUSED");
                close();
                return true;
            }
            return false;
        }

        void checkpoint(String status) throws IOException {
            server.saveAllChunks(false, true, true);
            State next = state.withNextIndex(nextIndex).withStatus(status);
            next.write(statePath);
            state = next;
            durableIndex = nextIndex;
            OceanCanvas.LOGGER.info("(Ocean Canvas) PRODUCTION-SEED-RESTORE-CHECKPOINT status={} durable={}/{}",
                    status, durableIndex, state.totalChunks);
        }

        String phase() {
            if (current == null) return pauseRequested ? "pausing" : "select";
            if (pruneFuture != null) return "prune";
            if (regen != null) return "native-full-regeneration";
            return "wait-for-unload";
        }

        @Override public void close() {
            if (regen != null) regen.close();
            regen = null;
            pruneFuture = null;
            current = null;
        }
    }

    private static int floorY(CoreConfig core, int x, int z) {
        return core.oceanFloorY() + OceanFloorProfile.floorOffset(x, z, core.oceanFloorVariation());
    }

    private static BlockState canonicalTarget(CoreConfig core, int x, int z, int y) {
        int floor = floorY(core, x, z);
        if (y < floor) return Blocks.STONE.defaultBlockState();
        if (y <= core.waterSurfaceY()) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    private static boolean authoredCanonical(CoreConfig core, BlockState state, int x, int z, int y) {
        int floor = floorY(core, x, z);
        if (y < floor) return state.is(Blocks.STONE);
        if (y <= core.waterSurfaceY()) return state.is(Blocks.WATER) && state.getFluidState().isSource();
        return state.isAir();
    }

    private static boolean settledCanonical(CoreConfig core, BlockState state, int x, int z, int y) {
        int floor = floorY(core, x, z);
        if (y < floor) return state.is(Blocks.STONE);
        if (y < core.waterSurfaceY()) return state.is(Blocks.WATER) && state.getFluidState().isSource();
        if (y == core.waterSurfaceY()) {
            return (state.is(Blocks.WATER) && state.getFluidState().isSource()) || state.is(Blocks.ICE);
        }
        if (y == core.waterSurfaceY() + 1) return state.isAir() || state.is(Blocks.SNOW);
        return state.isAir();
    }

    /**
     * Mature-engine raw-write shape without a new mixin dependency. Normal cells
     * avoid Level#setBlock physics. Existing live block entities are explicitly
     * promoted/removed before the raw state write; this keeps ordinary ocean
     * cells on the cheap path while preventing visible NBT-bearing states from
     * surviving destructive authoring.
     */
    private static void setBlockStateRawSafe(ServerLevel world, LevelChunk chunk, BlockPos pos,
                                             BlockState current, BlockState target) {
        if (current.hasBlockEntity()) {
            world.getBlockEntity(pos);
            world.removeBlockEntity(pos);
        }
        chunk.setBlockState(pos, target, 0);
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
            long worldSeed,
            long nextIndex) {

        static State initial(CoreConfig core, ServerLevel world) {
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(core.canvasSize(), core.centerX(), core.centerZ());
            return new State(STATE_SCHEMA, "RUNNING", core.canvasSize(), core.centerX(), core.centerZ(),
                    core.waterSurfaceY(), core.oceanFloorY(), core.oceanFloorVariation(),
                    bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(), bounds.count(), world.getSeed(), 0L);
        }

        State withStatus(String value) {
            return new State(schema, value, canvasSize, centerX, centerZ, waterSurfaceY, oceanFloorY,
                    oceanFloorVariation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, worldSeed, nextIndex);
        }

        State withNextIndex(long value) {
            if (value < 0 || value > totalChunks) throw new IllegalArgumentException("cursor outside Canvas");
            return new State(schema, status, canvasSize, centerX, centerZ, waterSurfaceY, oceanFloorY,
                    oceanFloorVariation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, worldSeed, value);
        }

        ChunkPos chunkAt(long index) {
            if (index < 0 || index >= totalChunks) throw new IllegalArgumentException("chunk index outside Canvas: " + index);
            long width = (long) maxChunkX - minChunkX + 1L;
            int x = Math.toIntExact(minChunkX + index % width);
            int z = Math.toIntExact(minChunkZ + index / width);
            return new ChunkPos(x, z);
        }

        void requireSameIdentity(State other) throws IOException {
            if (schema != other.schema || canvasSize != other.canvasSize || centerX != other.centerX
                    || centerZ != other.centerZ || waterSurfaceY != other.waterSurfaceY
                    || oceanFloorY != other.oceanFloorY || oceanFloorVariation != other.oceanFloorVariation
                    || minChunkX != other.minChunkX || maxChunkX != other.maxChunkX
                    || minChunkZ != other.minChunkZ || maxChunkZ != other.maxChunkZ
                    || totalChunks != other.totalChunks || worldSeed != other.worldSeed) {
                throw new IOException("production Canvas identity differs from durable operation; original geometry and seed are required");
            }
        }

        static State load(Path path) throws IOException {
            if (!Files.isRegularFile(path)) return null;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(path)) { p.load(in); }
            try {
                State state = new State(
                        Integer.parseInt(req(p, "schema")), req(p, "status"),
                        Integer.parseInt(req(p, "canvasSize")), Integer.parseInt(req(p, "centerX")),
                        Integer.parseInt(req(p, "centerZ")), Integer.parseInt(req(p, "waterSurfaceY")),
                        Integer.parseInt(req(p, "oceanFloorY")), Integer.parseInt(req(p, "oceanFloorVariation")),
                        Integer.parseInt(req(p, "minChunkX")), Integer.parseInt(req(p, "maxChunkX")),
                        Integer.parseInt(req(p, "minChunkZ")), Integer.parseInt(req(p, "maxChunkZ")),
                        Long.parseLong(req(p, "totalChunks")), Long.parseLong(req(p, "worldSeed")),
                        Long.parseLong(req(p, "nextIndex")));
                if (state.schema != STATE_SCHEMA) throw new IOException("unsupported production state schema " + state.schema);
                if (!state.status.equals("RUNNING") && !state.status.equals("PAUSED") && !state.status.equals("COMPLETE")) {
                    throw new IOException("invalid production state status " + state.status);
                }
                if (state.nextIndex < 0 || state.nextIndex > state.totalChunks) throw new IOException("invalid production cursor");
                return state;
            } catch (NumberFormatException e) {
                throw new IOException("invalid production state numeric field", e);
            }
        }

        void write(Path path) throws IOException {
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
            p.setProperty("worldSeed", Long.toString(worldSeed));
            p.setProperty("nextIndex", Long.toString(nextIndex));
            p.setProperty("engineBaseline", ENGINE_BASELINE);
            p.setProperty("engineSourceSha256", ENGINE_SOURCE_SHA256);

            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                p.store(out, "Ocean Canvas production-scale durable state");
            }
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic production state publication unavailable", e);
            }
        }

        private static String req(Properties p, String key) throws IOException {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IOException("missing production state key " + key);
            return value.trim();
        }
    }

    private static String safe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }
}
