package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.mod.OceanCanvas;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Opt-in shipwreck repopulation for a completed Ocean Canvas.
 *
 * <p>The candidate grid is delegated to Minecraft's own random-spread placement
 * implementation using the vanilla 26.2 shipwreck structure-set parameters:
 * spacing=24 chunks, separation=4 chunks, salt=165745295, LINEAR spread.
 * This preserves the world's seed-based vanilla spacing instead of inventing a
 * Canvas-specific scatter pattern. One candidate is handled per server tick and
 * progress is durable.</p>
 */
public final class ShipwreckStructureRuntime {
    private static final String RULES_FILE = "oceancanvas-structure-rules.properties";
    private static final String ROOT_DIR = "oceancanvas-core";
    private static final String FULL_CANVAS_DIR = "full-canvas";
    private static final String FLATTEN_STATE_FILE = "operation.properties";
    private static final String POPULATION_DIR = "shipwreck-population";
    private static final String POPULATION_STATE_FILE = "operation.properties";

    private static final int SPACING = 24;
    private static final int SEPARATION = 4;
    private static final int SALT = 165745295;
    private static final RandomSpreadStructurePlacement VANILLA_PLACEMENT =
            new RandomSpreadStructurePlacement(SPACING, SEPARATION, RandomSpreadType.LINEAR, SALT);

    private static Path configDir;
    private static MinecraftServer evaluatedServer;
    private static boolean registered;

    private ShipwreckStructureRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(ShipwreckStructureRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (evaluatedServer == server) evaluatedServer = null;
        });
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) SHIPWRECK-RUNTIME-REGISTERED vanillaSpacing={} vanillaSeparation={} vanillaSalt={}",
                SPACING, SEPARATION, SALT);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/structures"), PermissionLevel.ADMINS))
                .then(Commands.literal("structurerules")
                        .then(Commands.literal("set")
                                .then(Commands.literal("shipwreck")
                                        .then(Commands.literal("force_on")
                                                .executes(context -> setRule(context.getSource(), Rule.FORCE_ON)))
                                        .then(Commands.literal("default")
                                                .executes(context -> setRule(context.getSource(), Rule.DEFAULT)))
                                        .then(Commands.literal("never")
                                                .executes(context -> setRule(context.getSource(), Rule.NEVER)))))
                        .then(Commands.literal("get")
                                .then(Commands.literal("shipwreck")
                                        .executes(context -> getRule(context.getSource())))))
                .then(Commands.literal("shipwreck")
                        .then(Commands.literal("populate")
                                .then(Commands.literal("start")
                                        .executes(context -> populateStart(context.getSource())))
                                .then(Commands.literal("status")
                                        .executes(context -> populateStatus(context.getSource())))
                                .then(Commands.literal("pause")
                                        .executes(context -> populatePause(context.getSource())))
                                .then(Commands.literal("resume")
                                        .executes(context -> populateResume(context.getSource()))))));
    }

    private static int setRule(CommandSourceStack source, Rule rule) {
        try {
            Rules rules = Rules.load(configDir.resolve(RULES_FILE));
            rules = new Rules(rule);
            rules.write(configDir.resolve(RULES_FILE));
            source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck rule set to " + rule.commandName + "."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas could not update shipwreck rule: " + safe(e)));
            return 0;
        }
    }

    private static int getRule(CommandSourceStack source) {
        try {
            Rule rule = Rules.load(configDir.resolve(RULES_FILE)).shipwreck;
            source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck rule: " + rule.commandName + "."), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas could not read shipwreck rule: " + safe(e)));
            return 0;
        }
    }

    private static int populateStart(CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            if (Rules.load(configDir.resolve(RULES_FILE)).shipwreck != Rule.FORCE_ON) {
                source.sendFailure(Component.literal(
                        "OceanCanvas shipwreck population refused: first run /oceancanvas structurerules set shipwreck force_on."));
                return 0;
            }
            requireFlattenComplete(server);
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            ServerLevel world = server.overworld();
            if (world == null) throw new IOException("overworld unavailable");

            Path statePath = populationRoot(server).resolve(POPULATION_STATE_FILE);
            State desired = State.initial(core, world.getSeed());
            State existing = State.load(statePath);
            if (existing != null) {
                existing.requireSameGeometry(desired);
                if (existing.status.equals("COMPLETE")) {
                    source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck population is already COMPLETE."), false);
                    return 1;
                }
                existing.withStatus("RUNNING").write(statePath);
                source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck population resumed."), true);
                return 1;
            }

            Files.createDirectories(statePath.getParent());
            desired.write(statePath);
            source.sendSuccess(() -> Component.literal(
                    "OceanCanvas shipwreck population started with vanilla 26.2 spacing (24 chunks), separation (4 chunks), and world-seed placement."), true);
            return 1;
        } catch (Exception e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) SHIPWRECK-POPULATION-START-FAILED", e);
            source.sendFailure(Component.literal("OceanCanvas shipwreck population could not start: " + safe(e)));
            return 0;
        }
    }

    private static int populateStatus(CommandSourceStack source) {
        try {
            State state = State.load(populationRoot(source.getServer()).resolve(POPULATION_STATE_FILE));
            if (state == null) {
                source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck population: IDLE."), false);
                return 1;
            }
            long total = state.totalCells();
            long completed = state.completedCells();
            double pct = total == 0 ? 100.0 : 100.0 * completed / total;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "OceanCanvas shipwreck population: %s %,d/%,d grid cells (%.2f%%), placed %,d.",
                    state.status, completed, total, pct, state.placed)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas shipwreck population status failed: " + safe(e)));
            return 0;
        }
    }

    private static int populatePause(CommandSourceStack source) {
        try {
            Path statePath = populationRoot(source.getServer()).resolve(POPULATION_STATE_FILE);
            State state = State.load(statePath);
            if (state == null) {
                source.sendFailure(Component.literal("OceanCanvas shipwreck population is not configured."));
                return 0;
            }
            if (!state.status.equals("COMPLETE")) state.withStatus("PAUSED").write(statePath);
            source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck population paused at a durable grid-cell boundary."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas shipwreck population pause failed: " + safe(e)));
            return 0;
        }
    }

    private static int populateResume(CommandSourceStack source) {
        try {
            Path statePath = populationRoot(source.getServer()).resolve(POPULATION_STATE_FILE);
            State state = State.load(statePath);
            if (state == null) {
                source.sendFailure(Component.literal("No prior shipwreck population exists. Use /oceancanvas shipwreck populate start."));
                return 0;
            }
            if (state.status.equals("COMPLETE")) {
                source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck population is already COMPLETE."), false);
                return 1;
            }
            state.withStatus("RUNNING").write(statePath);
            source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck population resumed."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas shipwreck population resume failed: " + safe(e)));
            return 0;
        }
    }

    private static synchronized void tick(MinecraftServer server) {
        if (evaluatedServer != server) evaluatedServer = server;
        try {
            Path statePath = populationRoot(server).resolve(POPULATION_STATE_FILE);
            State state = State.load(statePath);
            if (state == null || !state.status.equals("RUNNING")) return;
            if (Rules.load(configDir.resolve(RULES_FILE)).shipwreck != Rule.FORCE_ON) {
                state.withStatus("PAUSED").write(statePath);
                return;
            }
            requireFlattenComplete(server);
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            ServerLevel world = server.overworld();
            if (world == null) throw new IOException("overworld unavailable");
            State desired = State.initial(core, world.getSeed());
            state.requireSameGeometry(desired);

            if (state.completeCursor()) {
                state.withStatus("COMPLETE").write(statePath);
                OceanCanvas.LOGGER.info("(Ocean Canvas Core) SHIPWRECK-POPULATION-COMPLETE placed={}", state.placed);
                return;
            }

            int sourceChunkX = Math.multiplyExact(state.regionX, SPACING);
            int sourceChunkZ = Math.multiplyExact(state.regionZ, SPACING);
            ChunkPos candidate = VANILLA_PLACEMENT.getPotentialStructureChunk(state.worldSeed, sourceChunkX, sourceChunkZ);
            long placed = state.placed;
            if (candidate.x >= state.minChunkX && candidate.x <= state.maxChunkX
                    && candidate.z >= state.minChunkZ && candidate.z <= state.maxChunkZ) {
                int blockX = candidate.getMinBlockX();
                int blockZ = candidate.getMinBlockZ();
                CommandSourceStack commandSource = server.createCommandSourceStack().withLevel(world).withSuppressedOutput();
                server.getCommands().performPrefixedCommand(commandSource,
                        "place structure minecraft:shipwreck " + blockX + " " + core.oceanFloorY() + " " + blockZ);
                placed++;
            }

            State advanced = state.advance(placed);
            if (advanced.completeCursor()) advanced = advanced.withStatus("COMPLETE");
            advanced.write(statePath);
        } catch (Throwable t) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) SHIPWRECK-POPULATION-FATAL action=pause-preserve-progress", t);
            try {
                Path statePath = populationRoot(server).resolve(POPULATION_STATE_FILE);
                State state = State.load(statePath);
                if (state != null && !state.status.equals("COMPLETE")) state.withStatus("PAUSED").write(statePath);
            } catch (Throwable ignored) {}
        }
    }

    private static void requireFlattenComplete(MinecraftServer server) throws IOException {
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(FULL_CANVAS_DIR).resolve(FLATTEN_STATE_FILE);
        if (!Files.exists(path)) throw new IOException("full Canvas flatten has not been started");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        if (!"COMPLETE".equalsIgnoreCase(p.getProperty("status", ""))) {
            throw new IOException("full Canvas flatten must be COMPLETE before shipwreck population");
        }
    }

    private static Path populationRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(POPULATION_DIR);
    }

    private static String safe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private enum Rule {
        NEVER("never"), DEFAULT("default"), FORCE_ON("force_on");
        final String commandName;
        Rule(String commandName) { this.commandName = commandName; }
    }

    private record Rules(Rule shipwreck) {
        static Rules load(Path path) throws IOException {
            Properties p = new Properties();
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) { p.load(in); }
            }
            String raw = p.getProperty("shipwreck", "default").trim().toLowerCase(Locale.ROOT);
            Rule rule = switch (raw) {
                case "never" -> Rule.NEVER;
                case "force_on" -> Rule.FORCE_ON;
                default -> Rule.DEFAULT;
            };
            return new Rules(rule);
        }

        void write(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            Properties p = new Properties();
            p.setProperty("shipwreck", shipwreck.commandName);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "Ocean Canvas structure rules. default=do not add structures; force_on=populate after flatten; never=disabled.");
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static final class State {
        final String status;
        final long worldSeed;
        final int canvasSize;
        final int centerX;
        final int centerZ;
        final int minChunkX;
        final int maxChunkX;
        final int minChunkZ;
        final int maxChunkZ;
        final int minRegionX;
        final int maxRegionX;
        final int minRegionZ;
        final int maxRegionZ;
        final int regionX;
        final int regionZ;
        final long placed;

        State(String status, long worldSeed, int canvasSize, int centerX, int centerZ,
              int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
              int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
              int regionX, int regionZ, long placed) {
            this.status = status;
            this.worldSeed = worldSeed;
            this.canvasSize = canvasSize;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.minChunkX = minChunkX;
            this.maxChunkX = maxChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkZ = maxChunkZ;
            this.minRegionX = minRegionX;
            this.maxRegionX = maxRegionX;
            this.minRegionZ = minRegionZ;
            this.maxRegionZ = maxRegionZ;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.placed = placed;
        }

        static State initial(CoreConfig core, long seed) {
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(core.canvasSize(), core.centerX(), core.centerZ());
            int minChunkX = bounds.minChunkX();
            int maxChunkX = bounds.maxChunkX();
            int minChunkZ = bounds.minChunkZ();
            int maxChunkZ = bounds.maxChunkZ();
            int minRegionX = Math.floorDiv(minChunkX, SPACING);
            int maxRegionX = Math.floorDiv(maxChunkX, SPACING);
            int minRegionZ = Math.floorDiv(minChunkZ, SPACING);
            int maxRegionZ = Math.floorDiv(maxChunkZ, SPACING);
            return new State("RUNNING", seed, core.canvasSize(), core.centerX(), core.centerZ(),
                    minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    minRegionX, minRegionZ, 0L);
        }

        static State load(Path path) throws IOException {
            if (!Files.exists(path)) return null;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(path)) { p.load(in); }
            return new State(
                    p.getProperty("status", "PAUSED"),
                    Long.parseLong(p.getProperty("worldSeed")),
                    Integer.parseInt(p.getProperty("canvasSize")),
                    Integer.parseInt(p.getProperty("centerX")),
                    Integer.parseInt(p.getProperty("centerZ")),
                    Integer.parseInt(p.getProperty("minChunkX")),
                    Integer.parseInt(p.getProperty("maxChunkX")),
                    Integer.parseInt(p.getProperty("minChunkZ")),
                    Integer.parseInt(p.getProperty("maxChunkZ")),
                    Integer.parseInt(p.getProperty("minRegionX")),
                    Integer.parseInt(p.getProperty("maxRegionX")),
                    Integer.parseInt(p.getProperty("minRegionZ")),
                    Integer.parseInt(p.getProperty("maxRegionZ")),
                    Integer.parseInt(p.getProperty("regionX")),
                    Integer.parseInt(p.getProperty("regionZ")),
                    Long.parseLong(p.getProperty("placed", "0")));
        }

        void write(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            Properties p = new Properties();
            p.setProperty("status", status);
            p.setProperty("worldSeed", Long.toString(worldSeed));
            p.setProperty("canvasSize", Integer.toString(canvasSize));
            p.setProperty("centerX", Integer.toString(centerX));
            p.setProperty("centerZ", Integer.toString(centerZ));
            p.setProperty("minChunkX", Integer.toString(minChunkX));
            p.setProperty("maxChunkX", Integer.toString(maxChunkX));
            p.setProperty("minChunkZ", Integer.toString(minChunkZ));
            p.setProperty("maxChunkZ", Integer.toString(maxChunkZ));
            p.setProperty("minRegionX", Integer.toString(minRegionX));
            p.setProperty("maxRegionX", Integer.toString(maxRegionX));
            p.setProperty("minRegionZ", Integer.toString(minRegionZ));
            p.setProperty("maxRegionZ", Integer.toString(maxRegionZ));
            p.setProperty("regionX", Integer.toString(regionX));
            p.setProperty("regionZ", Integer.toString(regionZ));
            p.setProperty("placed", Long.toString(placed));
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "Ocean Canvas shipwreck population progress"); }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        State withStatus(String nextStatus) {
            return new State(nextStatus, worldSeed, canvasSize, centerX, centerZ,
                    minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    regionX, regionZ, placed);
        }

        State advance(long nextPlaced) {
            int nextX = regionX + 1;
            int nextZ = regionZ;
            if (nextX > maxRegionX) {
                nextX = minRegionX;
                nextZ++;
            }
            return new State(status, worldSeed, canvasSize, centerX, centerZ,
                    minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    nextX, nextZ, nextPlaced);
        }

        boolean completeCursor() { return regionZ > maxRegionZ; }

        long totalCells() {
            return (long) (maxRegionX - minRegionX + 1) * (long) (maxRegionZ - minRegionZ + 1);
        }

        long completedCells() {
            if (completeCursor()) return totalCells();
            long rows = (long) regionZ - minRegionZ;
            long cols = (long) maxRegionX - minRegionX + 1L;
            return rows * cols + (regionX - minRegionX);
        }

        void requireSameGeometry(State other) throws IOException {
            if (worldSeed != other.worldSeed || canvasSize != other.canvasSize || centerX != other.centerX || centerZ != other.centerZ
                    || minChunkX != other.minChunkX || maxChunkX != other.maxChunkX
                    || minChunkZ != other.minChunkZ || maxChunkZ != other.maxChunkZ) {
                throw new IOException("shipwreck population state does not match the current world seed / Canvas geometry");
            }
        }
    }
}
