package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.geometry.OceanFloorProfile;
import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.pipeline.OperationManifestStore;
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
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;

/**
 * Authoritative implementation behind the required P0-P2 operator commands.
 *
 * <p>This runtime is registered after the compatibility command layers so its
 * command executors replace the earlier scaffolding at the same Brigadier paths.
 * Destructive bounded selections are normalized to whole chunk boundaries before
 * they become authoritative, which makes the displayed selection exactly match
 * the mutation footprint. Selected Pregen reuses the same immutable per-chunk
 * evidence as the full-Canvas runtime. Rewipe preserves those original preimages
 * and only reapplies the canonical ocean surface.</p>
 */
public final class AuthoritativeOperatorRuntime {
    private static final String ROOT_DIR = "oceancanvas-core";
    private static final String CONTROL_DIR = "operator-control";
    private static final String SELECTION_FILE = "selection.properties";
    private static final String PROTECTION_FILE = "protection.properties";
    private static final String WILDLIFE_FILE = "wildlife.properties";
    private static final String OVERNIGHT_FILE = "overnight.properties";
    private static final String STRUCTURE_RULES_FILE = "oceancanvas-structure-rules.properties";
    private static final String TERRAIN_DIR = "authoritative-terrain";
    private static final String TERRAIN_STATE = "operation.properties";
    private static final String HEALTH_DIR = "health-scan";
    private static final String HEALTH_STATE = "operation.properties";
    private static final String FULL_CANVAS_DIR = "full-canvas";
    private static final String FULL_FLATTEN_STATE = "operation.properties";
    private static final String FULL_RESTORE_STATE = "restore.properties";
    private static final String SHIPWRECK_DIR = "shipwreck-population";
    private static final String MONUMENT_DIR = "monument-population";

    private static Path configDir;
    private static MinecraftServer evaluatedServer;
    private static PregenSession pregenSession;
    private static RewipeSession rewipeSession;
    private static boolean registered;

    private AuthoritativeOperatorRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(AuthoritativeOperatorRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(AuthoritativeOperatorRuntime::stop);
        ServerEntityEvents.ENTITY_LOAD.register(AuthoritativeOperatorRuntime::onEntityLoad);
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) AUTHORITATIVE-OPERATOR-RUNTIME-REGISTERED p0p1p2=true");
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/operator"), PermissionLevel.ADMINS))
                .then(Commands.literal("selection")
                        .then(Commands.literal("radius")
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(16))
                                        .executes(c -> selectionRadius(c.getSource(), IntegerArgumentType.getInteger(c, "blocks")))))
                        .then(Commands.literal("bounds")
                                .then(Commands.argument("minX", IntegerArgumentType.integer())
                                        .then(Commands.argument("minZ", IntegerArgumentType.integer())
                                                .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                                        .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                                                .executes(c -> selectionBounds(c.getSource(),
                                                                        IntegerArgumentType.getInteger(c, "minX"), IntegerArgumentType.getInteger(c, "minZ"),
                                                                        IntegerArgumentType.getInteger(c, "maxX"), IntegerArgumentType.getInteger(c, "maxZ"))))))))
                        .then(Commands.literal("clear").executes(c -> selectionClear(c.getSource())))
                        .then(Commands.literal("info").executes(c -> selectionInfo(c.getSource()))))
                .then(Commands.literal("pregen")
                        .then(Commands.literal("start").executes(c -> terrainStart(c.getSource(), TerrainMode.PREGEN)))
                        .then(Commands.literal("status").executes(c -> terrainStatus(c.getSource(), TerrainMode.PREGEN)))
                        .then(Commands.literal("pause").executes(c -> terrainPause(c.getSource(), TerrainMode.PREGEN)))
                        .then(Commands.literal("resume").executes(c -> terrainResume(c.getSource(), TerrainMode.PREGEN)))
                        .then(Commands.literal("cancel").executes(c -> terrainCancel(c.getSource(), TerrainMode.PREGEN))))
                .then(Commands.literal("rewipe")
                        .then(Commands.literal("start").executes(c -> terrainStart(c.getSource(), TerrainMode.REWIPE)))
                        .then(Commands.literal("status").executes(c -> terrainStatus(c.getSource(), TerrainMode.REWIPE)))
                        .then(Commands.literal("pause").executes(c -> terrainPause(c.getSource(), TerrainMode.REWIPE)))
                        .then(Commands.literal("resume").executes(c -> terrainResume(c.getSource(), TerrainMode.REWIPE)))
                        .then(Commands.literal("cancel").executes(c -> terrainCancel(c.getSource(), TerrainMode.REWIPE))))
                .then(Commands.literal("wildliferules")
                        .then(Commands.literal("set")
                                .then(Commands.literal("default").executes(c -> setWildlife(c.getSource(), "default")))
                                .then(Commands.literal("always").executes(c -> setWildlife(c.getSource(), "always")))
                                .then(Commands.literal("never").executes(c -> setWildlife(c.getSource(), "never"))))
                        .then(Commands.literal("get").executes(c -> getWildlife(c.getSource()))))
                .then(Commands.literal("health")
                        .then(Commands.literal("scan").executes(c -> healthStart(c.getSource(), HealthScope.EFFECTIVE, false)))
                        .then(Commands.literal("selection").executes(c -> healthStart(c.getSource(), HealthScope.SELECTION, false)))
                        .then(Commands.literal("canvas").executes(c -> healthStart(c.getSource(), HealthScope.CANVAS, false)))
                        .then(Commands.literal("deep").executes(c -> healthStart(c.getSource(), HealthScope.EFFECTIVE, true)))
                        .then(Commands.literal("status").executes(c -> healthStatus(c.getSource()))))
                .then(Commands.literal("queue")
                        .executes(c -> queueStatus(c.getSource()))
                        .then(Commands.literal("pause").executes(c -> queuePause(c.getSource())))
                        .then(Commands.literal("resume").executes(c -> queueResume(c.getSource())))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1, 5))
                                        .executes(c -> queueCancel(c.getSource(), IntegerArgumentType.getInteger(c, "id"))))))
                .then(Commands.literal("overnight")
                        .then(Commands.literal("start").executes(c -> overnightStart(c.getSource())))
                        .then(Commands.literal("status").executes(c -> overnightStatus(c.getSource())))
                        .then(Commands.literal("pause").executes(c -> overnightPause(c.getSource())))
                        .then(Commands.literal("resume").executes(c -> overnightResume(c.getSource())))
                        .then(Commands.literal("cancel").executes(c -> overnightCancel(c.getSource())))));
    }

    // ----- exact effective selection ---------------------------------------------------------

    private static int selectionRadius(CommandSourceStack source, int blocks) {
        try {
            var pos = source.getPosition();
            int cx = (int)Math.floor(pos.x);
            int cz = (int)Math.floor(pos.z);
            long rawMinX = (long)cx - blocks;
            long rawMinZ = (long)cz - blocks;
            long rawMaxX = (long)cx + blocks;
            long rawMaxZ = (long)cz + blocks;
            if (rawMinX < Integer.MIN_VALUE || rawMinZ < Integer.MIN_VALUE
                    || rawMaxX > Integer.MAX_VALUE || rawMaxZ > Integer.MAX_VALUE) {
                return fail(source, "Selection radius exceeds Minecraft block-coordinate range.");
            }
            Selection s = Selection.chunkCover((int)rawMinX, (int)rawMinZ, (int)rawMaxX, (int)rawMaxZ);
            s.write(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            return ok(source, "OceanCanvas selection set from radius " + blocks + " around " + cx + "," + cz
                    + "; authoritative chunk-aligned footprint=" + s + ".");
        } catch (Exception e) { return fail(source, "Selection failed: " + safe(e)); }
    }

    private static int selectionBounds(CommandSourceStack source, int minX, int minZ, int maxX, int maxZ) {
        if (minX > maxX || minZ > maxZ) return fail(source, "Selection bounds invalid: minimum exceeds maximum.");
        try {
            Selection s = Selection.chunkCover(minX, minZ, maxX, maxZ);
            s.write(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            return ok(source, "OceanCanvas authoritative selection=" + s + " (chunk-aligned cover of requested bounds).");
        } catch (Exception e) { return fail(source, "Selection failed: " + safe(e)); }
    }

    private static int selectionClear(CommandSourceStack source) {
        try {
            Files.deleteIfExists(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            return ok(source, "OceanCanvas selection cleared; operations target the configured full Canvas.");
        } catch (Exception e) { return fail(source, "Selection clear failed: " + safe(e)); }
    }

    private static int selectionInfo(CommandSourceStack source) {
        try {
            Selection s = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            return ok(source, s == null ? "OceanCanvas selection: FULL CONFIGURED CANVAS." : "OceanCanvas selection: " + s + ".");
        } catch (Exception e) { return fail(source, "Selection info failed: " + safe(e)); }
    }

    // ----- P0 terrain -----------------------------------------------------------------------

    private static int terrainStart(CommandSourceStack source, TerrainMode mode) {
        try {
            MinecraftServer server = source.getServer();
            if (protectedNow(server)) return fail(source, "OceanCanvas " + mode.command + " refused: current scope is protected.");
            if (projectArchived(server)) return fail(source, "OceanCanvas " + mode.command + " refused: project is archived.");
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            Selection selection = Selection.load(controlRoot(server).resolve(SELECTION_FILE));
            if (mode == TerrainMode.PREGEN && selection == null) {
                return dispatch(source, "oceancanvas flatten start ERASE_CONFIGURED_CANVAS");
            }
            TerrainState desired = TerrainState.initial(core, effectiveBounds(core, selection), mode);
            Path path = terrainRoot(server).resolve(TERRAIN_STATE);
            TerrainState existing = TerrainState.load(path);
            if (existing != null) {
                if (existing.mode == TerrainMode.PREGEN && desired.mode == TerrainMode.REWIPE
                        && existing.status.equals("COMPLETE")) {
                    // Rewipe is a new operation over an already verified pregen, not
                    // a resume of the previous mode. Geometry must still match exactly.
                    existing.requireSameScope(desired);
                    Path history = terrainRoot(server).resolve("history");
                    Files.createDirectories(history);
                    Path parent = history.resolve("completed-pregen-" + java.util.UUID.randomUUID() + ".properties");
                    Files.copy(path, parent);
                    try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    closeTerrainSessions();
                    desired.write(path);
                    return ok(source, "OceanCanvas rewipe started for exact completed pregen scope; original state retained at " + parent.getFileName() + ".");
                }
                existing.requireSameIdentity(desired);
                if (existing.status.equals("COMPLETE")) return ok(source, "OceanCanvas " + mode.command + " is already COMPLETE for this exact scope.");
                existing.withStatus("RUNNING").write(path);
                closeTerrainSessions();
                return ok(source, "OceanCanvas " + mode.command + " resumed at " + existing.nextIndex + "/" + existing.totalChunks + " chunks.");
            }
            refuseConcurrentTerrain(server);
            Files.createDirectories(path.getParent());
            desired.write(path);
            closeTerrainSessions();
            return ok(source, "OceanCanvas " + mode.command + " started for exact chunk footprint "
                    + desired.minChunkX + "," + desired.minChunkZ + " -> " + desired.maxChunkX + "," + desired.maxChunkZ
                    + " (" + desired.totalChunks + " chunks).");
        } catch (Exception e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) AUTHORITATIVE-TERRAIN-START-FAILED mode={}", mode, e);
            return fail(source, "OceanCanvas " + mode.command + " could not start: " + safe(e));
        }
    }

    private static int terrainStatus(CommandSourceStack source, TerrainMode mode) {
        try {
            Selection selection = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            if (mode == TerrainMode.PREGEN && selection == null) {
                return dispatch(source, "oceancanvas flatten status");
            }
            TerrainState s = TerrainState.load(terrainRoot(source.getServer()).resolve(TERRAIN_STATE));
            if (s == null) return ok(source, "OceanCanvas " + mode.command + ": IDLE.");
            if (s.mode != mode) return ok(source, "OceanCanvas " + mode.command + ": IDLE; active durable terrain operation is " + s.mode.command + " " + s.status + ".");
            double pct = s.totalChunks == 0 ? 100.0 : 100.0 * s.nextIndex / s.totalChunks;
            return ok(source, String.format(Locale.ROOT, "OceanCanvas %s: %s %,d/%,d chunks (%.2f%%), stage=%s.",
                    mode.command, s.status, s.nextIndex, s.totalChunks, pct, s.stage));
        } catch (Exception e) { return fail(source, "Terrain status failed: " + safe(e)); }
    }

    private static int terrainPause(CommandSourceStack source, TerrainMode mode) {
        try {
            Selection selection = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            if (mode == TerrainMode.PREGEN && selection == null) return dispatch(source, "oceancanvas flatten pause");
            Path path = terrainRoot(source.getServer()).resolve(TERRAIN_STATE);
            TerrainState s = TerrainState.load(path);
            if (s == null || s.mode != mode) return fail(source, "No matching " + mode.command + " operation exists.");
            if (!s.status.equals("COMPLETE")) s.withStatus("PAUSED").write(path);
            closeTerrainSessions();
            return ok(source, "OceanCanvas " + mode.command + " paused at its durable cursor.");
        } catch (Exception e) { return fail(source, "Terrain pause failed: " + safe(e)); }
    }

    private static int terrainResume(CommandSourceStack source, TerrainMode mode) {
        try {
            if (protectedNow(source.getServer())) return fail(source, "OceanCanvas " + mode.command + " resume refused: protected.");
            Selection selection = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            if (mode == TerrainMode.PREGEN && selection == null) return dispatch(source, "oceancanvas flatten resume");
            Path path = terrainRoot(source.getServer()).resolve(TERRAIN_STATE);
            TerrainState s = TerrainState.load(path);
            if (s == null || s.mode != mode) return fail(source, "No matching " + mode.command + " operation exists.");
            if (s.status.equals("COMPLETE")) return ok(source, "OceanCanvas " + mode.command + " is already COMPLETE.");
            s.withStatus("RUNNING").write(path);
            closeTerrainSessions();
            return ok(source, "OceanCanvas " + mode.command + " resumed.");
        } catch (Exception e) { return fail(source, "Terrain resume failed: " + safe(e)); }
    }

    private static int terrainCancel(CommandSourceStack source, TerrainMode mode) {
        return terrainPause(source, mode);
    }

    private static synchronized void tick(MinecraftServer server) {
        if (evaluatedServer != server) {
            closeTerrainSessions();
            evaluatedServer = server;
        }
        try {
            tickTerrain(server);
        } catch (Throwable t) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) AUTHORITATIVE-TERRAIN-FATAL action=pause-preserve-progress", t);
            pauseTerrainOnFailure(server);
        }
        try { tickHealth(server); }
        catch (Throwable t) { OceanCanvas.LOGGER.error("(Ocean Canvas Core) HEALTH-SCAN-FATAL action=pause", t); pauseHealthOnFailure(server); }
        try { tickOvernight(server); }
        catch (Throwable t) { OceanCanvas.LOGGER.error("(Ocean Canvas Core) OVERNIGHT-ORCHESTRATION-FATAL action=pause", t); pauseOvernightOnFailure(server); }
    }

    private static void tickTerrain(MinecraftServer server) throws Exception {
        Path path = terrainRoot(server).resolve(TERRAIN_STATE);
        TerrainState state = TerrainState.load(path);
        if (state == null || !state.status.equals("RUNNING")) { closeTerrainSessions(); return; }
        if (protectedNow(server)) { state.withStatus("PAUSED").write(path); closeTerrainSessions(); return; }
        CoreConfig core = CoreConfig.loadOrCreate(configDir);
        state.requireCore(core);
        refuseConcurrentFullRuntime(server);
        if (state.nextIndex >= state.totalChunks) {
            state.withStatus("COMPLETE").withStage("DONE").write(path);
            closeTerrainSessions();
            return;
        }
        if (state.mode == TerrainMode.PREGEN) tickPregen(server, core, path, state);
        else tickRewipe(server, core, path, state);
    }

    private static void tickPregen(MinecraftServer server, CoreConfig core, Path path, TerrainState state) throws Exception {
        if (pregenSession == null || pregenSession.index != state.nextIndex) {
            closeTerrainSessions();
            pregenSession = PregenSession.open(server, core, state);
        }
        if (pregenSession.tick()) {
            TerrainState next = state.advanceChunk();
            if (next.nextIndex >= next.totalChunks) next = next.withStatus("COMPLETE").withStage("DONE");
            next.write(path);
            OceanCanvas.LOGGER.info("(Ocean Canvas Core) BOUNDED-PREGEN-PROGRESS completed={}/{} current={} status={}",
                    next.nextIndex, next.totalChunks, pregenSession.key, next.status);
            closeTerrainSessions();
        }
    }

    private static void tickRewipe(MinecraftServer server, CoreConfig core, Path path, TerrainState state) throws Exception {
        if (rewipeSession == null || rewipeSession.index != state.nextIndex) {
            closeTerrainSessions();
            rewipeSession = RewipeSession.open(server, core, state);
        }
        RewipeResult result = rewipeSession.tick(state);
        TerrainState next = result.state;
        if (result.chunkComplete) {
            next = next.advanceChunk().withStage("AUTHOR").withCellCursor(0);
            if (next.nextIndex >= next.totalChunks) next = next.withStatus("COMPLETE").withStage("DONE");
            closeTerrainSessions();
        }
        next.write(path);
    }

    private static void refuseConcurrentTerrain(MinecraftServer server) throws IOException {
        refuseConcurrentFullRuntime(server);
        TerrainState t = TerrainState.load(terrainRoot(server).resolve(TERRAIN_STATE));
        if (t != null && t.status.equals("RUNNING")) throw new IOException("another authoritative terrain operation is RUNNING");
    }

    private static void refuseConcurrentFullRuntime(MinecraftServer server) throws IOException {
        Properties flatten = readProps(fullCanvasRoot(server).resolve(FULL_FLATTEN_STATE));
        Properties restore = readProps(fullCanvasRoot(server).resolve(FULL_RESTORE_STATE));
        if ("RUNNING".equalsIgnoreCase(flatten.getProperty("status", ""))) throw new IOException("full-Canvas flatten is RUNNING");
        if ("RUNNING".equalsIgnoreCase(restore.getProperty("status", ""))) throw new IOException("full-Canvas restore is RUNNING");
    }

    // ----- P1 wildlife ----------------------------------------------------------------------

    private static int setWildlife(CommandSourceStack source, String mode) {
        try {
            Properties p = new Properties();
            p.setProperty("mode", mode);
            p.setProperty("updatedAt", Instant.now().toString());
            writePropsAtomic(controlRoot(source.getServer()).resolve(WILDLIFE_FILE), p, "Ocean Canvas wildlife policy");
            String behavior = mode.equals("never")
                    ? "newly loaded vanilla wildlife inside the effective Canvas scope is removed"
                    : "vanilla spawning/lifecycle is left untouched";
            return ok(source, "OceanCanvas wildlife=" + mode + "; " + behavior + ".");
        } catch (Exception e) { return fail(source, "Wildlife update failed: " + safe(e)); }
    }

    private static int getWildlife(CommandSourceStack source) {
        try {
            return ok(source, "OceanCanvas wildlife=" + wildlifeMode(source.getServer()) + ".");
        } catch (Exception e) { return fail(source, "Wildlife read failed: " + safe(e)); }
    }

    private static void onEntityLoad(Entity entity, ServerLevel world) {
        try {
            if (!(entity instanceof Animal) && !(entity instanceof WaterAnimal) && !(entity instanceof AmbientCreature)) return;
            MinecraftServer server = world.getServer();
            if (!"never".equals(wildlifeMode(server))) return;
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            Selection selection = Selection.load(controlRoot(server).resolve(SELECTION_FILE));
            BlockBounds b = effectiveBlockBounds(core, selection);
            int x = entity.getBlockX();
            int z = entity.getBlockZ();
            if (x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ) entity.discard();
        } catch (Throwable t) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas Core) wildlife policy evaluation failed closed-to-vanilla: {}", safe(t));
        }
    }

    private static String wildlifeMode(MinecraftServer server) throws IOException {
        return readProps(controlRoot(server).resolve(WILDLIFE_FILE)).getProperty("mode", "default").trim().toLowerCase(Locale.ROOT);
    }

    // ----- P1 health ------------------------------------------------------------------------

    private static int healthStart(CommandSourceStack source, HealthScope scope, boolean deep) {
        try {
            MinecraftServer server = source.getServer();
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            Selection selected = Selection.load(controlRoot(server).resolve(SELECTION_FILE));
            if (scope == HealthScope.SELECTION && selected == null) return fail(source, "Health selection requires an active selection.");
            ChunkBounds b = scope == HealthScope.CANVAS ? fullBounds(core) : effectiveBounds(core, selected);
            HealthState desired = HealthState.initial(b, deep);
            Path path = healthRoot(server).resolve(HEALTH_STATE);
            HealthState existing = HealthState.load(path);
            if (existing != null && existing.status.equals("RUNNING")) return ok(source, "OceanCanvas health scan is already RUNNING; use /oceancanvas health status.");
            desired.write(path);
            return ok(source, "OceanCanvas " + (deep ? "deep " : "") + "physical health sampling started across " + b.count() + " chunks.");
        } catch (Exception e) { return fail(source, "Health scan start failed: " + safe(e)); }
    }

    private static int healthStatus(CommandSourceStack source) {
        try {
            HealthState s = HealthState.load(healthRoot(source.getServer()).resolve(HEALTH_STATE));
            if (s == null) return ok(source, "OceanCanvas health: IDLE.");
            double pct = s.totalChunks == 0 ? 100.0 : 100.0 * s.nextChunk / s.totalChunks;
            return ok(source, String.format(Locale.ROOT,
                    "OceanCanvas health: %s %.2f%% chunks=%d/%d samples=%d findings=%d first=%s.",
                    s.status, pct, s.nextChunk, s.totalChunks, s.samples, s.findings,
                    s.firstFinding.isBlank() ? "none" : s.firstFinding));
        } catch (Exception e) { return fail(source, "Health status failed: " + safe(e)); }
    }

    private static void tickHealth(MinecraftServer server) throws Exception {
        Path path = healthRoot(server).resolve(HEALTH_STATE);
        HealthState s = HealthState.load(path);
        if (s == null || !s.status.equals("RUNNING")) return;
        if (s.nextChunk >= s.totalChunks) { s.withStatus("COMPLETE").write(path); return; }
        CoreConfig core = CoreConfig.loadOrCreate(configDir);
        int width = s.maxChunkX - s.minChunkX + 1;
        int cx = s.minChunkX + (int)(s.nextChunk % width);
        int cz = s.minChunkZ + (int)(s.nextChunk / width);
        ServerLevel world = server.overworld();
        if (world == null) throw new IOException("overworld unavailable");
        LevelChunk chunk = world.getChunk(cx, cz);

        long samples = s.samples;
        long findings = s.findings;
        String first = s.firstFinding;
        int grid = s.deep ? 4 : 2;
        int stride = 16 / grid;
        for (int lx = stride / 2; lx < 16; lx += stride) {
            for (int lz = stride / 2; lz < 16; lz += stride) {
                int x = cx * 16 + lx;
                int z = cz * 16 + lz;
                int floor = core.oceanFloorY() + OceanFloorProfile.floorOffset(x, z, core.oceanFloorVariation());
                int[] ys = s.deep
                        ? new int[]{floor - 1, floor, floor + 1, core.waterSurfaceY() - 1, core.waterSurfaceY(), core.waterSurfaceY() + 1, core.waterSurfaceY() + 2}
                        : new int[]{floor - 1, floor, core.waterSurfaceY(), core.waterSurfaceY() + 1};
                for (int y : ys) {
                    BlockState state = chunk.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                    samples++;
                    String mismatch = healthMismatch(state, y, floor, core.waterSurfaceY());
                    if (mismatch != null) {
                        findings++;
                        if (first.isBlank()) first = x + "," + y + "," + z + " " + mismatch;
                    }
                    if (s.deep && y >= floor && y <= core.waterSurfaceY() + 1) {
                        int light = world.getBrightness(LightLayer.SKY, new net.minecraft.core.BlockPos(x, y, z));
                        samples++;
                        if (light < 0 || light > 15) {
                            findings++;
                            if (first.isBlank()) first = x + "," + y + "," + z + " invalid skylight=" + light;
                        }
                    }
                }
            }
        }
        HealthState next = s.advance(samples, findings, first);
        if (next.nextChunk >= next.totalChunks) next = next.withStatus("COMPLETE");
        next.write(path);
    }

    private static String healthMismatch(BlockState state, int y, int floor, int waterY) {
        if (y < floor) return state.is(Blocks.STONE) ? null : "expected STONE actual=" + state;
        if (y < waterY) return state.is(Blocks.WATER) && state.getFluidState().isSource() ? null : "expected SOURCE_WATER actual=" + state;
        if (y == waterY) return ((state.is(Blocks.WATER) && state.getFluidState().isSource()) || state.is(Blocks.ICE)) ? null : "expected SOURCE_WATER_OR_ICE actual=" + state;
        if (y == waterY + 1) return (state.isAir() || state.is(Blocks.SNOW)) ? null : "expected AIR_OR_SNOW actual=" + state;
        return state.isAir() ? null : "expected AIR actual=" + state;
    }

    // ----- P1 queue / P2 overnight -----------------------------------------------------------

    private static int queueStatus(CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            TerrainState terrain = TerrainState.load(terrainRoot(server).resolve(TERRAIN_STATE));
            Properties full = readProps(fullCanvasRoot(server).resolve(FULL_FLATTEN_STATE));
            Properties restore = readProps(fullCanvasRoot(server).resolve(FULL_RESTORE_STATE));
            Properties sw = readProps(server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(SHIPWRECK_DIR).resolve("operation.properties"));
            Properties mon = readProps(server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(MONUMENT_DIR).resolve("operation.properties"));
            String terrainStatus = terrain == null ? full.getProperty("status", "IDLE") : terrain.mode.command + ":" + terrain.status;
            return ok(source, "OceanCanvas queue: 1=terrain[" + terrainStatus + "], 2=restore[" + restore.getProperty("status", "IDLE")
                    + "], 3=shipwreck[" + sw.getProperty("status", "IDLE") + "], 4=monument[" + mon.getProperty("status", "IDLE")
                    + "], 5=health[" + healthStatusText(server) + "].");
        } catch (Exception e) { return fail(source, "Queue status failed: " + safe(e)); }
    }

    private static int queuePause(CommandSourceStack source) {
        try {
            pauseAnyTerrain(source);
            dispatch(source, "oceancanvas restore pause");
            dispatch(source, "oceancanvas shipwreck populate pause");
            dispatch(source, "oceancanvas monument populate pause");
            pauseHealth(source.getServer());
            return ok(source, "OceanCanvas queue paused at durable boundaries.");
        } catch (Exception e) { return fail(source, "Queue pause failed: " + safe(e)); }
    }

    private static int queueResume(CommandSourceStack source) {
        try {
            resumeAnyTerrain(source);
            dispatch(source, "oceancanvas restore resume");
            dispatch(source, "oceancanvas shipwreck populate resume");
            dispatch(source, "oceancanvas monument populate resume");
            resumeHealth(source.getServer());
            return ok(source, "OceanCanvas durable queue entries resumed where applicable.");
        } catch (Exception e) { return fail(source, "Queue resume failed: " + safe(e)); }
    }

    private static int queueCancel(CommandSourceStack source, int id) {
        try {
            return switch (id) {
                case 1 -> { pauseAnyTerrain(source); yield ok(source, "Terrain queue entry cancelled/preserved for resume."); }
                case 2 -> dispatch(source, "oceancanvas restore cancel");
                case 3 -> dispatch(source, "oceancanvas shipwreck populate pause");
                case 4 -> dispatch(source, "oceancanvas monument populate pause");
                case 5 -> { pauseHealth(source.getServer()); yield ok(source, "Health scan paused/preserved for resume."); }
                default -> fail(source, "Unknown queue id " + id + ".");
            };
        } catch (Exception e) { return fail(source, "Queue cancel failed: " + safe(e)); }
    }

    private static int overnightStart(CommandSourceStack source) {
        try {
            Path path = controlRoot(source.getServer()).resolve(OVERNIGHT_FILE);
            Properties p = readProps(path);
            p.setProperty("profile", p.getProperty("profile", "overnight"));
            p.setProperty("status", "RUNNING");
            p.setProperty("phase", "TERRAIN");
            p.setProperty("startedAt", Instant.now().toString());
            p.remove("lastError");
            writePropsAtomic(path, p, "Ocean Canvas overnight orchestration");
            return advanceOvernight(source.getServer(), source);
        } catch (Exception e) { return fail(source, "Overnight start failed: " + safe(e)); }
    }

    private static int overnightStatus(CommandSourceStack source) {
        try {
            Properties p = readProps(controlRoot(source.getServer()).resolve(OVERNIGHT_FILE));
            return ok(source, "OceanCanvas overnight: status=" + p.getProperty("status", "IDLE")
                    + ", profile=" + p.getProperty("profile", "balanced") + ", phase=" + p.getProperty("phase", "NONE")
                    + (p.getProperty("lastError") == null ? "." : ", lastError=" + p.getProperty("lastError") + "."));
        } catch (Exception e) { return fail(source, "Overnight status failed: " + safe(e)); }
    }

    private static int overnightPause(CommandSourceStack source) {
        try {
            setOvernightStatus(source.getServer(), "PAUSED");
            queuePause(source);
            return ok(source, "OceanCanvas overnight paused.");
        } catch (Exception e) { return fail(source, "Overnight pause failed: " + safe(e)); }
    }

    private static int overnightResume(CommandSourceStack source) {
        try {
            setOvernightStatus(source.getServer(), "RUNNING");
            return advanceOvernight(source.getServer(), source);
        } catch (Exception e) { return fail(source, "Overnight resume failed: " + safe(e)); }
    }

    private static int overnightCancel(CommandSourceStack source) {
        try {
            setOvernightStatus(source.getServer(), "CANCELLED");
            queuePause(source);
            return ok(source, "OceanCanvas overnight cancelled with durable work preserved.");
        } catch (Exception e) { return fail(source, "Overnight cancel failed: " + safe(e)); }
    }

    private static void tickOvernight(MinecraftServer server) throws Exception {
        Properties p = readProps(controlRoot(server).resolve(OVERNIGHT_FILE));
        if (!"RUNNING".equalsIgnoreCase(p.getProperty("status", ""))) return;
        String profile = p.getProperty("profile", "balanced").toLowerCase(Locale.ROOT);
        if (profile.equals("quiet") && !server.getPlayerList().getPlayers().isEmpty()) {
            p.setProperty("autoPausedForPlayers", "true");
            writePropsAtomic(controlRoot(server).resolve(OVERNIGHT_FILE), p, "Ocean Canvas overnight orchestration");
            pauseAnyTerrain(server.createCommandSourceStack());
            return;
        }
        if (Boolean.parseBoolean(p.getProperty("autoPausedForPlayers", "false"))) {
            p.setProperty("autoPausedForPlayers", "false");
            writePropsAtomic(controlRoot(server).resolve(OVERNIGHT_FILE), p, "Ocean Canvas overnight orchestration");
            resumeAnyTerrain(server.createCommandSourceStack());
        }
        advanceOvernight(server, server.createCommandSourceStack().withSuppressedOutput());
    }

    private static int advanceOvernight(MinecraftServer server, CommandSourceStack source) throws Exception {
        Path path = controlRoot(server).resolve(OVERNIGHT_FILE);
        Properties p = readProps(path);
        String phase = p.getProperty("phase", "TERRAIN");
        if (phase.equals("TERRAIN")) {
            if (!terrainAnyExists(server)) terrainStart(source, TerrainMode.PREGEN);
            String status = terrainEffectiveStatus(server);
            if (status.equals("PAUSED")) resumeAnyTerrain(source);
            if (!status.equals("COMPLETE")) return 1;
            p.setProperty("phase", "SHIPWRECK"); writePropsAtomic(path, p, "Ocean Canvas overnight orchestration");
            phase = "SHIPWRECK";
        }
        if (phase.equals("SHIPWRECK")) {
            String rule = structureRule("shipwreck");
            if (rule.equals("force_on")) {
                Properties sw = readProps(server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(SHIPWRECK_DIR).resolve("operation.properties"));
                String status = sw.getProperty("status", "IDLE");
                if (status.equals("IDLE")) dispatch(source, "oceancanvas shipwreck populate start");
                else if (status.equals("PAUSED")) dispatch(source, "oceancanvas shipwreck populate resume");
                if (!readProps(server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(SHIPWRECK_DIR).resolve("operation.properties")).getProperty("status", "IDLE").equals("COMPLETE")) return 1;
            }
            p.setProperty("phase", "MONUMENT"); writePropsAtomic(path, p, "Ocean Canvas overnight orchestration");
            phase = "MONUMENT";
        }
        if (phase.equals("MONUMENT")) {
            String rule = structureRule("monument");
            if (rule.equals("force_on")) {
                Properties mon = readProps(server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(MONUMENT_DIR).resolve("operation.properties"));
                String status = mon.getProperty("status", "IDLE");
                if (status.equals("IDLE")) dispatch(source, "oceancanvas monument populate start");
                else if (status.equals("PAUSED")) dispatch(source, "oceancanvas monument populate resume");
                if (!readProps(server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(MONUMENT_DIR).resolve("operation.properties")).getProperty("status", "IDLE").equals("COMPLETE")) return 1;
            }
            p.setProperty("phase", "HEALTH"); writePropsAtomic(path, p, "Ocean Canvas overnight orchestration");
            phase = "HEALTH";
        }
        if (phase.equals("HEALTH")) {
            HealthState h = HealthState.load(healthRoot(server).resolve(HEALTH_STATE));
            if (h == null) {
                CoreConfig core = CoreConfig.loadOrCreate(configDir);
                Selection selection = Selection.load(controlRoot(server).resolve(SELECTION_FILE));
                HealthState.initial(effectiveBounds(core, selection), true).write(healthRoot(server).resolve(HEALTH_STATE));
                return 1;
            }
            if (h.status.equals("PAUSED")) h.withStatus("RUNNING").write(healthRoot(server).resolve(HEALTH_STATE));
            if (!h.status.equals("COMPLETE")) return 1;
            p.setProperty("phase", "COMPLETE"); p.setProperty("status", "COMPLETE"); p.setProperty("completedAt", Instant.now().toString());
            writePropsAtomic(path, p, "Ocean Canvas overnight orchestration");
        }
        return 1;
    }

    // ----- sessions -------------------------------------------------------------------------

    private static final class PregenSession implements AutoCloseable {
        final long index;
        final ChunkKey key;
        final SingleChunkPipeline pipeline;
        final SingleChunkWorldPorts ports;

        private PregenSession(long index, ChunkKey key, SingleChunkPipeline pipeline, SingleChunkWorldPorts ports) {
            this.index = index; this.key = key; this.pipeline = pipeline; this.ports = ports;
        }

        static PregenSession open(MinecraftServer server, CoreConfig core, TerrainState state) throws Exception {
            ServerLevel world = server.overworld();
            if (world == null) throw new IOException("overworld unavailable");
            ChunkKey key = state.keyAtCursor();
            Path root = fullCanvasRoot(server);
            Path chunkRoot = root.resolve("chunks").resolve("chunk_" + key.x() + "_" + key.z());
            BlockStateRegistryFingerprint.Identity registry = BlockStateRegistryFingerprint.compute();
            BlockStateRegistryIdentityStore.ensureExact(root.resolve("block-state-registry.identity"), registry.sha256(), registry.stateCount());
            SingleChunkOperationSpec spec = spec(core, key);
            OperationManifestStore.ensureExact(chunkRoot.resolve("operation.properties"), spec);
            RuntimeReceiptLog receipts = new RuntimeReceiptLog(chunkRoot.resolve("runtime-receipts.log"));
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(new CoreJournal(chunkRoot.resolve("transitions.journal")), key);
            SingleChunkWorldPorts ports = new SingleChunkWorldPorts(world, core, key, receipts, spec.operationId(),
                    chunkRoot.resolve("preimage-blockstates.bin"), chunkRoot.resolve("preimage-blockentities.ocbe"), true);
            ChunkStage stage = pipeline.record().stage();
            if (stage == ChunkStage.RESTORED || stage == ChunkStage.RESTORE_VERIFIED || stage == ChunkStage.COMPLETE) {
                ports.close();
                throw new IOException("selected pregen refused: chunk " + key + " already entered restore lifecycle stage=" + stage);
            }
            return new PregenSession(state.nextIndex, key, pipeline, ports);
        }

        boolean tick() throws Exception {
            ChunkStage stage = pipeline.record().stage();
            if (stage == ChunkStage.FAILED) throw new IOException("chunk " + key + " FAILED: " + pipeline.record().failureReason());
            if (stage == ChunkStage.VERIFIED) { ports.close(); return true; }
            pipeline.tick(ports, System.currentTimeMillis());
            if (pipeline.record().stage() == ChunkStage.VERIFIED) { ports.close(); return true; }
            return false;
        }

        @Override public void close() { ports.close(); }
    }

    private static final class RewipeSession implements AutoCloseable {
        final long index;
        final ChunkKey key;
        final ServerLevel world;
        final CoreConfig core;
        LevelChunk chunk;

        private RewipeSession(long index, ChunkKey key, ServerLevel world, CoreConfig core, LevelChunk chunk) {
            this.index = index; this.key = key; this.world = world; this.core = core; this.chunk = chunk;
        }

        static RewipeSession open(MinecraftServer server, CoreConfig core, TerrainState state) throws Exception {
            ServerLevel world = server.overworld();
            if (world == null) throw new IOException("overworld unavailable");
            ChunkKey key = state.keyAtCursor();
            Path chunkRoot = fullCanvasRoot(server).resolve("chunks").resolve("chunk_" + key.x() + "_" + key.z());
            SingleChunkOperationSpec spec = spec(core, key);
            OperationManifestStore.ensureExact(chunkRoot.resolve("operation.properties"), spec);
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(new CoreJournal(chunkRoot.resolve("transitions.journal")), key);
            if (pipeline.record().stage() != ChunkStage.VERIFIED) {
                throw new IOException("rewipe requires prior durable VERIFIED pregen for chunk " + key + "; current stage=" + pipeline.record().stage());
            }
            if (!Files.isRegularFile(chunkRoot.resolve("preimage-blockstates.bin"))) {
                throw new IOException("rewipe requires retained immutable preimage for chunk " + key);
            }
            LevelChunk chunk = world.getChunk(key.x(), key.z());
            return new RewipeSession(state.nextIndex, key, world, core, chunk);
        }

        RewipeResult tick(TerrainState state) throws Exception {
            int minY = core.oceanFloorY() - core.oceanFloorVariation() - 1;
            int maxY = world.getMaxY() - 1;
            int height = maxY - minY + 1;
            long totalCells = 256L * height;
            long cursor = state.cellCursor;
            int writes = 0;
            int checks = 0;
            long deadline = System.nanoTime() + core.stageWallBudgetMicros() * 1_000L;
            net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            while (cursor < totalCells && checks < core.maxChecksPerTick()
                    && writes < core.maxBlockWritesPerTick() && System.nanoTime() < deadline) {
                int column = (int)(cursor / height);
                int y = minY + (int)(cursor % height);
                int x = key.x() * 16 + (column & 15);
                int z = key.z() * 16 + (column >>> 4);
                pos.set(x, y, z);
                BlockState current = chunk.getBlockState(pos);
                BlockState target = canonical(core, x, z, y);
                checks++;
                if (!sameCanonical(current, target)) {
                    world.setBlock(pos, target, Block.UPDATE_CLIENTS);
                    writes++;
                }
                cursor++;
            }
            TerrainState next = state.withCellCursor(cursor).withStage("AUTHOR");
            if (cursor < totalCells) return new RewipeResult(next, false);
            chunk.markUnsaved();
            world.getServer().saveAllChunks(false, true, true);
            // Submit lighting work across the canonical band; Minecraft owns propagation/settlement.
            int lightMin = core.oceanFloorY() - core.oceanFloorVariation();
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int x = key.x() * 16 + lx, z = key.z() * 16 + lz;
                for (int y = lightMin; y <= core.waterSurfaceY() + 1; y++) {
                    world.getChunkSource().getLightEngine().checkBlock(new net.minecraft.core.BlockPos(x, y, z));
                }
            }
            world.getChunkSource().getLightEngine().propagateLightSources(new ChunkPos(key.x(), key.z()));
            return new RewipeResult(next.withStage("VERIFIED"), true);
        }

        @Override public void close() { chunk = null; }
    }

    private record RewipeResult(TerrainState state, boolean chunkComplete) {}

    // ----- records / helpers ----------------------------------------------------------------

    private enum TerrainMode {
        PREGEN("pregen"), REWIPE("rewipe");
        final String command;
        TerrainMode(String command) { this.command = command; }
        static TerrainMode parse(String raw) { return raw.equalsIgnoreCase("rewipe") ? REWIPE : PREGEN; }
    }

    private enum HealthScope { EFFECTIVE, SELECTION, CANVAS }

    private record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {
        long count() { return ((long)maxX - minX + 1L) * ((long)maxZ - minZ + 1L); }
    }

    private record BlockBounds(int minX, int minZ, int maxX, int maxZ) {}

    private record Selection(int minX, int minZ, int maxX, int maxZ) {
        static Selection chunkCover(int minX, int minZ, int maxX, int maxZ) {
            int minCX = Math.floorDiv(minX, 16), minCZ = Math.floorDiv(minZ, 16);
            int maxCX = Math.floorDiv(maxX, 16), maxCZ = Math.floorDiv(maxZ, 16);
            return new Selection(Math.multiplyExact(minCX, 16), Math.multiplyExact(minCZ, 16),
                    Math.addExact(Math.multiplyExact(maxCX, 16), 15), Math.addExact(Math.multiplyExact(maxCZ, 16), 15));
        }
        static Selection load(Path path) throws IOException {
            Properties p = readProps(path); if (p.isEmpty()) return null;
            return new Selection(Integer.parseInt(req(p, "minX")), Integer.parseInt(req(p, "minZ")),
                    Integer.parseInt(req(p, "maxX")), Integer.parseInt(req(p, "maxZ")));
        }
        void write(Path path) throws IOException {
            Properties p = new Properties();
            p.setProperty("minX", Integer.toString(minX)); p.setProperty("minZ", Integer.toString(minZ));
            p.setProperty("maxX", Integer.toString(maxX)); p.setProperty("maxZ", Integer.toString(maxZ));
            p.setProperty("chunkAligned", "true"); p.setProperty("updatedAt", Instant.now().toString());
            writePropsAtomic(path, p, "Ocean Canvas authoritative selection");
        }
        @Override public String toString() { return "[" + minX + "," + minZ + "]->[" + maxX + "," + maxZ + "]"; }
    }

    private record TerrainState(int schema, TerrainMode mode, String status, String stage,
                                int canvasSize, int centerX, int centerZ, int waterY, int floorY, int variation,
                                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                                long totalChunks, long nextIndex, long cellCursor) {
        static TerrainState initial(CoreConfig core, ChunkBounds b, TerrainMode mode) {
            return new TerrainState(1, mode, "RUNNING", "AUTHOR", core.canvasSize(), core.centerX(), core.centerZ(),
                    core.waterSurfaceY(), core.oceanFloorY(), core.oceanFloorVariation(),
                    b.minX, b.maxX, b.minZ, b.maxZ, b.count(), 0L, 0L);
        }
        ChunkKey keyAtCursor() {
            long width = (long)maxChunkX - minChunkX + 1L;
            return new ChunkKey(minChunkX + (int)(nextIndex % width), minChunkZ + (int)(nextIndex / width));
        }
        TerrainState withStatus(String v) { return new TerrainState(schema, mode, v, stage, canvasSize, centerX, centerZ, waterY, floorY, variation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, nextIndex, cellCursor); }
        TerrainState withStage(String v) { return new TerrainState(schema, mode, status, v, canvasSize, centerX, centerZ, waterY, floorY, variation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, nextIndex, cellCursor); }
        TerrainState withCellCursor(long v) { return new TerrainState(schema, mode, status, stage, canvasSize, centerX, centerZ, waterY, floorY, variation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, nextIndex, v); }
        TerrainState advanceChunk() { return new TerrainState(schema, mode, status, stage, canvasSize, centerX, centerZ, waterY, floorY, variation, minChunkX, maxChunkX, minChunkZ, maxChunkZ, totalChunks, nextIndex + 1L, 0L); }
        void requireCore(CoreConfig c) throws IOException {
            if (canvasSize != c.canvasSize() || centerX != c.centerX() || centerZ != c.centerZ()
                    || waterY != c.waterSurfaceY() || floorY != c.oceanFloorY() || variation != c.oceanFloorVariation())
                throw new IOException("core geometry/config changed since terrain operation was armed");
        }
        void requireSameIdentity(TerrainState o) throws IOException {
            if (mode != o.mode) throw new IOException("requested terrain mode differs from durable operation");
            requireSameScope(o);
        }
        void requireSameScope(TerrainState o) throws IOException {
            if (canvasSize != o.canvasSize || centerX != o.centerX || centerZ != o.centerZ
                    || waterY != o.waterY || floorY != o.floorY || variation != o.variation
                    || minChunkX != o.minChunkX || maxChunkX != o.maxChunkX || minChunkZ != o.minChunkZ || maxChunkZ != o.maxChunkZ)
                throw new IOException("requested terrain scope differs from durable operation; finish/cancel and preserve evidence before changing scope");
        }
        static TerrainState load(Path path) throws IOException {
            Properties p = readProps(path); if (p.isEmpty()) return null;
            return new TerrainState(Integer.parseInt(req(p,"schema")), TerrainMode.parse(req(p,"mode")), req(p,"status"), p.getProperty("stage","AUTHOR"),
                    Integer.parseInt(req(p,"canvasSize")), Integer.parseInt(req(p,"centerX")), Integer.parseInt(req(p,"centerZ")),
                    Integer.parseInt(req(p,"waterY")), Integer.parseInt(req(p,"floorY")), Integer.parseInt(req(p,"variation")),
                    Integer.parseInt(req(p,"minChunkX")), Integer.parseInt(req(p,"maxChunkX")), Integer.parseInt(req(p,"minChunkZ")), Integer.parseInt(req(p,"maxChunkZ")),
                    Long.parseLong(req(p,"totalChunks")), Long.parseLong(req(p,"nextIndex")), Long.parseLong(p.getProperty("cellCursor","0")));
        }
        void write(Path path) throws IOException {
            Properties p = new Properties();
            p.setProperty("schema", Integer.toString(schema)); p.setProperty("mode", mode.command); p.setProperty("status", status); p.setProperty("stage", stage);
            p.setProperty("canvasSize", Integer.toString(canvasSize)); p.setProperty("centerX", Integer.toString(centerX)); p.setProperty("centerZ", Integer.toString(centerZ));
            p.setProperty("waterY", Integer.toString(waterY)); p.setProperty("floorY", Integer.toString(floorY)); p.setProperty("variation", Integer.toString(variation));
            p.setProperty("minChunkX", Integer.toString(minChunkX)); p.setProperty("maxChunkX", Integer.toString(maxChunkX));
            p.setProperty("minChunkZ", Integer.toString(minChunkZ)); p.setProperty("maxChunkZ", Integer.toString(maxChunkZ));
            p.setProperty("totalChunks", Long.toString(totalChunks)); p.setProperty("nextIndex", Long.toString(nextIndex)); p.setProperty("cellCursor", Long.toString(cellCursor));
            writePropsDurable(path, p, "Ocean Canvas authoritative terrain operation");
        }
    }

    private record HealthState(String status, boolean deep, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                               long totalChunks, long nextChunk, long samples, long findings, String firstFinding) {
        static HealthState initial(ChunkBounds b, boolean deep) { return new HealthState("RUNNING", deep, b.minX,b.maxX,b.minZ,b.maxZ,b.count(),0L,0L,0L,""); }
        HealthState withStatus(String v) { return new HealthState(v,deep,minChunkX,maxChunkX,minChunkZ,maxChunkZ,totalChunks,nextChunk,samples,findings,firstFinding); }
        HealthState advance(long s, long f, String first) { return new HealthState(status,deep,minChunkX,maxChunkX,minChunkZ,maxChunkZ,totalChunks,nextChunk+1,s,f,first); }
        static HealthState load(Path path) throws IOException {
            Properties p = readProps(path); if (p.isEmpty()) return null;
            return new HealthState(req(p,"status"), Boolean.parseBoolean(p.getProperty("deep","false")),
                    Integer.parseInt(req(p,"minChunkX")), Integer.parseInt(req(p,"maxChunkX")), Integer.parseInt(req(p,"minChunkZ")), Integer.parseInt(req(p,"maxChunkZ")),
                    Long.parseLong(req(p,"totalChunks")), Long.parseLong(req(p,"nextChunk")), Long.parseLong(p.getProperty("samples","0")), Long.parseLong(p.getProperty("findings","0")), p.getProperty("firstFinding", ""));
        }
        void write(Path path) throws IOException {
            Properties p = new Properties(); p.setProperty("status",status); p.setProperty("deep",Boolean.toString(deep));
            p.setProperty("minChunkX",Integer.toString(minChunkX)); p.setProperty("maxChunkX",Integer.toString(maxChunkX)); p.setProperty("minChunkZ",Integer.toString(minChunkZ)); p.setProperty("maxChunkZ",Integer.toString(maxChunkZ));
            p.setProperty("totalChunks",Long.toString(totalChunks)); p.setProperty("nextChunk",Long.toString(nextChunk)); p.setProperty("samples",Long.toString(samples)); p.setProperty("findings",Long.toString(findings)); p.setProperty("firstFinding",firstFinding);
            writePropsAtomic(path,p,"Ocean Canvas physical health scan");
        }
    }

    private static SingleChunkOperationSpec spec(CoreConfig core, ChunkKey key) {
        return new SingleChunkOperationSpec(1, key, core.canvasSize(), core.centerX(), core.centerZ(), core.waterSurfaceY(), core.oceanFloorY(), core.oceanFloorVariation());
    }

    private static BlockState canonical(CoreConfig core, int x, int z, int y) {
        int floor = core.oceanFloorY() + OceanFloorProfile.floorOffset(x, z, core.oceanFloorVariation());
        if (y < floor) return Blocks.STONE.defaultBlockState();
        if (y <= core.waterSurfaceY()) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    private static boolean sameCanonical(BlockState current, BlockState target) {
        if (target.is(Blocks.WATER)) return current.is(Blocks.WATER) && current.getFluidState().isSource();
        if (target.is(Blocks.AIR)) return current.isAir();
        return current.is(target.getBlock());
    }

    private static ChunkBounds fullBounds(CoreConfig core) {
        var b = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(core.canvasSize(), core.centerX(), core.centerZ());
        return new ChunkBounds(b.minX(), b.maxX(), b.minZ(), b.maxZ());
    }

    private static ChunkBounds effectiveBounds(CoreConfig core, Selection selection) {
        if (selection == null) return fullBounds(core);
        return new ChunkBounds(Math.floorDiv(selection.minX,16), Math.floorDiv(selection.maxX,16), Math.floorDiv(selection.minZ,16), Math.floorDiv(selection.maxZ,16));
    }

    private static BlockBounds effectiveBlockBounds(CoreConfig core, Selection selection) {
        if (selection != null) return new BlockBounds(selection.minX, selection.minZ, selection.maxX, selection.maxZ);
        int half = core.canvasSize()/2;
        return new BlockBounds(core.centerX()-half, core.centerZ()-half, core.centerX()+half-1, core.centerZ()+half-1);
    }

    private static boolean protectedNow(MinecraftServer server) throws IOException {
        return Boolean.parseBoolean(readProps(controlRoot(server).resolve(PROTECTION_FILE)).getProperty("enabled", "false"));
    }

    private static boolean projectArchived(MinecraftServer server) throws IOException {
        return Boolean.parseBoolean(readProps(controlRoot(server).resolve("project.properties")).getProperty("archived", "false"));
    }

    private static String structureRule(String key) throws IOException {
        return readProps(configDir.resolve(STRUCTURE_RULES_FILE)).getProperty(key, "default").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean terrainAnyExists(MinecraftServer server) throws IOException {
        TerrainState t = TerrainState.load(terrainRoot(server).resolve(TERRAIN_STATE));
        if (t != null) return true;
        return !readProps(fullCanvasRoot(server).resolve(FULL_FLATTEN_STATE)).isEmpty();
    }

    private static String terrainEffectiveStatus(MinecraftServer server) throws IOException {
        TerrainState t = TerrainState.load(terrainRoot(server).resolve(TERRAIN_STATE));
        if (t != null) return t.status;
        return readProps(fullCanvasRoot(server).resolve(FULL_FLATTEN_STATE)).getProperty("status", "IDLE");
    }

    private static void pauseAnyTerrain(CommandSourceStack source) throws IOException {
        Path path = terrainRoot(source.getServer()).resolve(TERRAIN_STATE);
        TerrainState t = TerrainState.load(path);
        if (t != null && !t.status.equals("COMPLETE")) { t.withStatus("PAUSED").write(path); closeTerrainSessions(); }
        dispatch(source, "oceancanvas flatten pause");
    }

    private static void resumeAnyTerrain(CommandSourceStack source) throws IOException {
        Path path = terrainRoot(source.getServer()).resolve(TERRAIN_STATE);
        TerrainState t = TerrainState.load(path);
        if (t != null && t.status.equals("PAUSED")) t.withStatus("RUNNING").write(path);
        else dispatch(source, "oceancanvas flatten resume");
    }

    private static void pauseHealth(MinecraftServer server) throws IOException {
        Path path = healthRoot(server).resolve(HEALTH_STATE); HealthState h = HealthState.load(path);
        if (h != null && !h.status.equals("COMPLETE")) h.withStatus("PAUSED").write(path);
    }
    private static void resumeHealth(MinecraftServer server) throws IOException {
        Path path = healthRoot(server).resolve(HEALTH_STATE); HealthState h = HealthState.load(path);
        if (h != null && h.status.equals("PAUSED")) h.withStatus("RUNNING").write(path);
    }
    private static String healthStatusText(MinecraftServer server) throws IOException {
        HealthState h = HealthState.load(healthRoot(server).resolve(HEALTH_STATE)); return h == null ? "IDLE" : h.status;
    }

    private static void setOvernightStatus(MinecraftServer server, String status) throws IOException {
        Path path = controlRoot(server).resolve(OVERNIGHT_FILE); Properties p = readProps(path); p.setProperty("status", status); p.putIfAbsent("profile","balanced"); writePropsAtomic(path,p,"Ocean Canvas overnight orchestration");
    }

    private static void pauseTerrainOnFailure(MinecraftServer server) {
        try { Path p=terrainRoot(server).resolve(TERRAIN_STATE); TerrainState s=TerrainState.load(p); if(s!=null&&!s.status.equals("COMPLETE"))s.withStatus("PAUSED").write(p); } catch(Throwable ignored){} closeTerrainSessions();
    }
    private static void pauseHealthOnFailure(MinecraftServer server) { try { pauseHealth(server); } catch(Throwable ignored){} }
    private static void pauseOvernightOnFailure(MinecraftServer server) { try { Path p=controlRoot(server).resolve(OVERNIGHT_FILE); Properties s=readProps(p); s.setProperty("status","PAUSED"); s.setProperty("lastError","runtime failure; inspect server log"); writePropsAtomic(p,s,"Ocean Canvas overnight orchestration"); } catch(Throwable ignored){} }

    private static synchronized void stop(MinecraftServer server) {
        if (evaluatedServer == server) { closeTerrainSessions(); evaluatedServer = null; }
    }

    private static void closeTerrainSessions() {
        if (pregenSession != null) { pregenSession.close(); pregenSession = null; }
        if (rewipeSession != null) { rewipeSession.close(); rewipeSession = null; }
    }

    private static Path controlRoot(MinecraftServer server) throws IOException { Path p=server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(CONTROL_DIR); Files.createDirectories(p); return p; }
    private static Path terrainRoot(MinecraftServer server) throws IOException { Path p=server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(TERRAIN_DIR); Files.createDirectories(p); return p; }
    private static Path healthRoot(MinecraftServer server) throws IOException { Path p=server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(HEALTH_DIR); Files.createDirectories(p); return p; }
    private static Path fullCanvasRoot(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(FULL_CANVAS_DIR); }

    private static int dispatch(CommandSourceStack source, String command) {
        try { source.getServer().getCommands().performPrefixedCommand(source, command); return 1; }
        catch (Exception e) { return fail(source, "Delegated command failed: " + safe(e)); }
    }

    private static Properties readProps(Path path) throws IOException {
        Properties p=new Properties(); if(Files.isRegularFile(path))try(InputStream in=Files.newInputStream(path)){p.load(in);} return p;
    }
    private static void writePropsAtomic(Path path, Properties p, String comment) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent()); Path tmp=path.resolveSibling(path.getFileName()+".tmp");
        try(OutputStream out=Files.newOutputStream(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){p.store(out,comment);out.flush();}
        try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void writePropsDurable(Path path, Properties p, String comment) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent()); Path tmp=path.resolveSibling(path.getFileName()+".tmp");
        try(OutputStream out=Files.newOutputStream(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){p.store(out,comment);out.flush();}
        try(FileChannel ch=FileChannel.open(tmp,StandardOpenOption.WRITE)){ch.force(true);}
        try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){throw new IOException("atomic durable publication unsupported; staged state preserved at "+tmp,e);}
    }
    private static String req(Properties p,String key)throws IOException{String v=p.getProperty(key);if(v==null||v.isBlank())throw new IOException("missing property "+key);return v.trim();}
    private static int ok(CommandSourceStack source,String message){source.sendSuccess(()->Component.literal(message),false);return 1;}
    private static int fail(CommandSourceStack source,String message){source.sendFailure(Component.literal(message));return 0;}
    private static String safe(Throwable t){String m=t.getMessage();return m==null||m.isBlank()?t.getClass().getSimpleName():m.replace('\n',' ').replace('\r',' ');}
}
