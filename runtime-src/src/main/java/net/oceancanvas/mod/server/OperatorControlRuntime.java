package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.mod.OceanCanvas;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;

/**
 * Production operator command surface for Ocean Canvas.
 *
 * <p>This layer intentionally delegates destructive full-Canvas work to the
 * already-proven FullCanvasServerRuntime instead of creating a second terrain
 * writer. Durable selection/protection/rule/profile state is stored separately.
 * Any requested destructive scope that the production engine cannot yet honor
 * exactly fails closed instead of silently broadening to the full Canvas.</p>
 */
public final class OperatorControlRuntime {
    private static final String ROOT_DIR = "oceancanvas-core";
    private static final String CONTROL_DIR = "operator-control";
    private static final String SELECTION_FILE = "selection.properties";
    private static final String PROTECTION_FILE = "protection.properties";
    private static final String WILDLIFE_FILE = "wildlife.properties";
    private static final String OVERNIGHT_FILE = "overnight.properties";
    private static final String STRUCTURE_RULES_FILE = "oceancanvas-structure-rules.properties";
    private static final String FULL_CANVAS_DIR = "full-canvas";
    private static final String FLATTEN_STATE = "operation.properties";
    private static final String RESTORE_STATE = "restore.properties";

    private static Path configDir;
    private static boolean registered;

    private OperatorControlRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) OPERATOR-CONTROL-RUNTIME-REGISTERED p0p1p2=true");
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/operator"), PermissionLevel.ADMINS))

                // P0: canonical terrain operations. Pregen/Rewipe both target the Canvas blank-ocean authoring
                // engine in this restart line; Restore delegates to the immutable-preimage restore path.
                .then(Commands.literal("pregen")
                        .then(Commands.literal("start").executes(c -> terrainStart(c.getSource(), "pregen")))
                        .then(Commands.literal("status").executes(c -> terrainStatus(c.getSource(), false, "Pregen")))
                        .then(Commands.literal("pause").executes(c -> dispatch(c.getSource(), "oceancanvas flatten pause")))
                        .then(Commands.literal("resume").executes(c -> terrainResume(c.getSource(), "pregen")))
                        .then(Commands.literal("cancel").executes(c -> dispatch(c.getSource(), "oceancanvas flatten cancel"))))
                .then(Commands.literal("rewipe")
                        .then(Commands.literal("start").executes(c -> terrainStart(c.getSource(), "rewipe")))
                        .then(Commands.literal("status").executes(c -> terrainStatus(c.getSource(), false, "Rewipe")))
                        .then(Commands.literal("pause").executes(c -> dispatch(c.getSource(), "oceancanvas flatten pause")))
                        .then(Commands.literal("resume").executes(c -> terrainResume(c.getSource(), "rewipe")))
                        .then(Commands.literal("cancel").executes(c -> dispatch(c.getSource(), "oceancanvas flatten cancel"))))

                // P0: durable targeting state. The current production terrain writer is full-Canvas only;
                // non-full selections are preserved but destructive starts refuse until an exact bounded writer exists.
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

                // P0: protection is persisted immediately and blocks OceanCanvas destructive starts.
                .then(Commands.literal("protect")
                        .then(Commands.literal("selection").executes(c -> setProtected(c.getSource(), true)))
                        .then(Commands.literal("status").executes(c -> protectionStatus(c.getSource()))))
                .then(Commands.literal("unprotect")
                        .then(Commands.literal("selection").executes(c -> setProtected(c.getSource(), false))))

                // P0: generic structure rules. Shipwreck execution remains owned by ShipwreckStructureRuntime.
                .then(Commands.literal("structurerules")
                        .then(Commands.literal("set")
                                .then(ruleSetter("monument"))
                                .then(ruleSetter("ruined_portal")))
                        .then(Commands.literal("get")
                                .then(Commands.literal("monument").executes(c -> getStructureRule(c.getSource(), "monument")))
                                .then(Commands.literal("ruined_portal").executes(c -> getStructureRule(c.getSource(), "ruined_portal"))))
                        .then(Commands.literal("list").executes(c -> listStructureRules(c.getSource()))))

                // P1: wildlife policy.
                .then(Commands.literal("wildliferules")
                        .then(Commands.literal("set")
                                .then(Commands.literal("default").executes(c -> setWildlife(c.getSource(), "default")))
                                .then(Commands.literal("always").executes(c -> setWildlife(c.getSource(), "always")))
                                .then(Commands.literal("never").executes(c -> setWildlife(c.getSource(), "never"))))
                        .then(Commands.literal("get").executes(c -> getWildlife(c.getSource()))))

                // P0/P1: overall inspection and conservative metadata health.
                .then(Commands.literal("status").executes(c -> overallStatus(c.getSource())))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("config").then(Commands.literal("show").executes(c -> configShow(c.getSource()))))
                .then(Commands.literal("health")
                        .then(Commands.literal("scan").executes(c -> health(c.getSource(), "scan")))
                        .then(Commands.literal("selection").executes(c -> health(c.getSource(), "selection")))
                        .then(Commands.literal("canvas").executes(c -> health(c.getSource(), "canvas")))
                        .then(Commands.literal("deep").executes(c -> health(c.getSource(), "deep")))
                        .then(Commands.literal("repair-safe").executes(c -> healthRepairSafe(c.getSource())))
                        .then(Commands.literal("repair-metadata").executes(c -> healthRepairSafe(c.getSource()))))

                // P1: queue view/control over currently durable production operations.
                .then(Commands.literal("queue").executes(c -> queueStatus(c.getSource()))
                        .then(Commands.literal("pause").executes(c -> queuePause(c.getSource())))
                        .then(Commands.literal("resume").executes(c -> queueResume(c.getSource())))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(c -> queueCancel(c.getSource(), IntegerArgumentType.getInteger(c, "id"))))))

                // P2: persisted operating profile and overnight workflow control.
                .then(Commands.literal("overnight")
                        .then(Commands.literal("start").executes(c -> overnightStart(c.getSource())))
                        .then(Commands.literal("status").executes(c -> overnightStatus(c.getSource())))
                        .then(Commands.literal("pause").executes(c -> overnightPause(c.getSource())))
                        .then(Commands.literal("resume").executes(c -> overnightResume(c.getSource())))
                        .then(Commands.literal("cancel").executes(c -> overnightCancel(c.getSource())))
                        .then(Commands.literal("profile")
                                .then(Commands.literal("quiet").executes(c -> setProfile(c.getSource(), "quiet")))
                                .then(Commands.literal("balanced").executes(c -> setProfile(c.getSource(), "balanced")))
                                .then(Commands.literal("overnight").executes(c -> setProfile(c.getSource(), "overnight")))
                                .then(Commands.literal("custom").executes(c -> setProfile(c.getSource(), "custom")))))

                // P2: minimal project/planning command equivalents for power users.
                .then(Commands.literal("project")
                        .then(Commands.literal("status").executes(c -> projectStatus(c.getSource())))
                        .then(Commands.literal("archive").executes(c -> projectArchive(c.getSource(), true)))
                        .then(Commands.literal("unarchive").executes(c -> projectArchive(c.getSource(), false))))
                .then(Commands.literal("plan")
                        .then(Commands.literal("status").executes(c -> planStatus(c.getSource())))
                        .then(Commands.literal("clear-selection").executes(c -> selectionClear(c.getSource())))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> ruleSetter(String name) {
        return Commands.literal(name)
                .then(Commands.literal("force_on").executes(c -> setStructureRule(c.getSource(), name, "force_on")))
                .then(Commands.literal("default").executes(c -> setStructureRule(c.getSource(), name, "default")))
                .then(Commands.literal("never").executes(c -> setStructureRule(c.getSource(), name, "never")));
    }

    private static int terrainStart(CommandSourceStack source, String kind) {
        try {
            if (projectArchived(source.getServer())) return fail(source, "OceanCanvas " + kind + " refused: project is archived.");
            Selection sel = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            if (sel != null && !sel.matchesFullCanvas(CoreConfig.loadOrCreate(configDir))) {
                return fail(source, "OceanCanvas " + kind + " refused: current production writer is full-Canvas only; clear the bounded selection or use the exact bounded runtime once validated.");
            }
            if (Protection.load(controlRoot(source.getServer()).resolve(PROTECTION_FILE)).enabled) {
                return fail(source, "OceanCanvas " + kind + " refused: current selection/Canvas is protected.");
            }
            writeOperationIntent(source.getServer(), kind);
            return dispatch(source, "oceancanvas flatten start ERASE_CONFIGURED_CANVAS");
        } catch (Exception e) { return fail(source, "OceanCanvas " + kind + " could not start: " + safe(e)); }
    }

    private static int terrainResume(CommandSourceStack source, String kind) {
        try {
            if (Protection.load(controlRoot(source.getServer()).resolve(PROTECTION_FILE)).enabled) {
                return fail(source, "OceanCanvas " + kind + " resume refused: protected.");
            }
            writeOperationIntent(source.getServer(), kind);
            return dispatch(source, "oceancanvas flatten resume");
        } catch (Exception e) { return fail(source, "OceanCanvas " + kind + " resume failed: " + safe(e)); }
    }

    private static int terrainStatus(CommandSourceStack source, boolean restore, String label) {
        try {
            Path p = fullCanvasRoot(source.getServer()).resolve(restore ? RESTORE_STATE : FLATTEN_STATE);
            Properties state = readProps(p);
            if (state.isEmpty()) return ok(source, label + ": IDLE.");
            return ok(source, label + ": " + state.getProperty("status", "UNKNOWN") + " "
                    + state.getProperty("nextIndex", "0") + "/" + state.getProperty("totalChunks", "?") + " chunks.");
        } catch (Exception e) { return fail(source, label + " status failed: " + safe(e)); }
    }

    private static int selectionRadius(CommandSourceStack source, int blocks) {
        try {
            var pos = source.getPosition();
            int cx = (int)Math.floor(pos.x);
            int cz = (int)Math.floor(pos.z);
            Selection s = new Selection(cx - blocks, cz - blocks, cx + blocks, cz + blocks);
            s.write(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            return ok(source, "OceanCanvas selection set to radius " + blocks + " around " + cx + "," + cz + ".");
        } catch (Exception e) { return fail(source, "Selection failed: " + safe(e)); }
    }

    private static int selectionBounds(CommandSourceStack source, int minX, int minZ, int maxX, int maxZ) {
        if (minX > maxX || minZ > maxZ) return fail(source, "Selection bounds invalid: minimum exceeds maximum.");
        try {
            new Selection(minX, minZ, maxX, maxZ).write(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            return ok(source, "OceanCanvas selection set to [" + minX + "," + minZ + "] -> [" + maxX + "," + maxZ + "].");
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
            return ok(source, s == null ? "OceanCanvas selection: FULL CONFIGURED CANVAS." : "OceanCanvas selection: " + s);
        } catch (Exception e) { return fail(source, "Selection info failed: " + safe(e)); }
    }

    private static int setProtected(CommandSourceStack source, boolean enabled) {
        try {
            Selection s = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            new Protection(enabled, s == null ? "full_canvas" : s.toString()).write(controlRoot(source.getServer()).resolve(PROTECTION_FILE));
            return ok(source, "OceanCanvas protection " + (enabled ? "enabled" : "disabled") + ".");
        } catch (Exception e) { return fail(source, "Protection update failed: " + safe(e)); }
    }

    private static int protectionStatus(CommandSourceStack source) {
        try {
            Protection p = Protection.load(controlRoot(source.getServer()).resolve(PROTECTION_FILE));
            return ok(source, "OceanCanvas protection: " + (p.enabled ? "PROTECTED " + p.scope : "OFF") + ".");
        } catch (Exception e) { return fail(source, "Protection status failed: " + safe(e)); }
    }

    private static int setStructureRule(CommandSourceStack source, String key, String value) {
        try {
            Path path = configDir.resolve(STRUCTURE_RULES_FILE);
            Properties p = readProps(path);
            p.setProperty(key, value);
            writePropsAtomic(path, p, "Ocean Canvas structure rules");
            return ok(source, "OceanCanvas " + key + " rule set to " + value + ".");
        } catch (Exception e) { return fail(source, "Structure rule update failed: " + safe(e)); }
    }

    private static int getStructureRule(CommandSourceStack source, String key) {
        try { return ok(source, "OceanCanvas " + key + " rule: " + readProps(configDir.resolve(STRUCTURE_RULES_FILE)).getProperty(key, "default") + "."); }
        catch (Exception e) { return fail(source, "Structure rule read failed: " + safe(e)); }
    }

    private static int listStructureRules(CommandSourceStack source) {
        try {
            Properties p = readProps(configDir.resolve(STRUCTURE_RULES_FILE));
            return ok(source, "OceanCanvas structure rules: shipwreck=" + p.getProperty("shipwreck", "default")
                    + ", monument=" + p.getProperty("monument", "default")
                    + ", ruined_portal=" + p.getProperty("ruined_portal", "default") + ".");
        } catch (Exception e) { return fail(source, "Structure rule list failed: " + safe(e)); }
    }

    private static int setWildlife(CommandSourceStack source, String value) {
        try {
            Properties p = new Properties(); p.setProperty("mode", value);
            writePropsAtomic(controlRoot(source.getServer()).resolve(WILDLIFE_FILE), p, "Ocean Canvas wildlife rule");
            return ok(source, "OceanCanvas wildlife rule set to " + value + ".");
        } catch (Exception e) { return fail(source, "Wildlife rule update failed: " + safe(e)); }
    }

    private static int getWildlife(CommandSourceStack source) {
        try { return ok(source, "OceanCanvas wildlife rule: " + readProps(controlRoot(source.getServer()).resolve(WILDLIFE_FILE)).getProperty("mode", "default") + "."); }
        catch (Exception e) { return fail(source, "Wildlife rule read failed: " + safe(e)); }
    }

    private static int overallStatus(CommandSourceStack source) {
        try {
            Properties flatten = readProps(fullCanvasRoot(source.getServer()).resolve(FLATTEN_STATE));
            Properties restore = readProps(fullCanvasRoot(source.getServer()).resolve(RESTORE_STATE));
            Selection sel = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            Protection protection = Protection.load(controlRoot(source.getServer()).resolve(PROTECTION_FILE));
            String profile = readProps(controlRoot(source.getServer()).resolve(OVERNIGHT_FILE)).getProperty("profile", "balanced");
            return ok(source, "OceanCanvas status: flatten=" + flatten.getProperty("status", "IDLE")
                    + ", restore=" + restore.getProperty("status", "IDLE")
                    + ", selection=" + (sel == null ? "full_canvas" : sel)
                    + ", protected=" + protection.enabled + ", profile=" + profile + ".");
        } catch (Exception e) { return fail(source, "OceanCanvas status failed: " + safe(e)); }
    }

    private static int info(CommandSourceStack source) {
        try {
            CoreConfig c = CoreConfig.loadOrCreate(configDir);
            return ok(source, "OceanCanvas " + OceanCanvas.VERSION + ": canvas=" + c.canvasSize() + "x" + c.canvasSize()
                    + " center=" + c.centerX() + "," + c.centerZ() + " waterY=" + c.waterSurfaceY()
                    + " floorY=" + c.oceanFloorY() + " variation=" + c.oceanFloorVariation() + ".");
        } catch (Exception e) { return fail(source, "OceanCanvas info failed: " + safe(e)); }
    }

    private static int configShow(CommandSourceStack source) { return info(source); }

    private static int health(CommandSourceStack source, String scope) {
        try {
            MinecraftServer server = source.getServer();
            Properties flatten = readProps(fullCanvasRoot(server).resolve(FLATTEN_STATE));
            Properties restore = readProps(fullCanvasRoot(server).resolve(RESTORE_STATE));
            boolean inconsistent = "COMPLETE".equalsIgnoreCase(restore.getProperty("status", ""))
                    && !"COMPLETE".equalsIgnoreCase(flatten.getProperty("status", ""));
            int findings = inconsistent ? 1 : 0;
            Selection sel = Selection.load(controlRoot(server).resolve(SELECTION_FILE));
            String detail = inconsistent ? " restore COMPLETE without flatten COMPLETE" : " no metadata contradictions detected";
            return ok(source, "OceanCanvas health " + scope + ": findings=" + findings + "; scope="
                    + ("selection".equals(scope) && sel != null ? sel : "configured_canvas") + ";" + detail + ". Physical block/biome sampling remains conservative and is not inferred from metadata.");
        } catch (Exception e) { return fail(source, "OceanCanvas health scan failed: " + safe(e)); }
    }

    private static int healthRepairSafe(CommandSourceStack source) {
        try {
            // Deliberately metadata-only. Normalize missing control files; never change blocks.
            Path root = controlRoot(source.getServer()); Files.createDirectories(root);
            if (!Files.exists(root.resolve(WILDLIFE_FILE))) {
                Properties p = new Properties(); p.setProperty("mode", "default"); writePropsAtomic(root.resolve(WILDLIFE_FILE), p, "Ocean Canvas wildlife rule");
            }
            if (!Files.exists(root.resolve(OVERNIGHT_FILE))) {
                Properties p = new Properties(); p.setProperty("profile", "balanced"); p.setProperty("status", "IDLE"); writePropsAtomic(root.resolve(OVERNIGHT_FILE), p, "Ocean Canvas overnight state");
            }
            return ok(source, "OceanCanvas safe metadata repair complete; no terrain blocks were changed.");
        } catch (Exception e) { return fail(source, "Safe metadata repair failed: " + safe(e)); }
    }

    private static int queueStatus(CommandSourceStack source) {
        try {
            Properties flatten = readProps(fullCanvasRoot(source.getServer()).resolve(FLATTEN_STATE));
            Properties restore = readProps(fullCanvasRoot(source.getServer()).resolve(RESTORE_STATE));
            Properties sw = readProps(source.getServer().getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve("shipwreck-population").resolve("operation.properties"));
            Properties mon = readProps(source.getServer().getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve("monument-population").resolve("operation.properties"));
            return ok(source, "OceanCanvas queue: 1=terrain[" + flatten.getProperty("status", "IDLE") + "], 2=restore["
                    + restore.getProperty("status", "IDLE") + "], 3=shipwreck[" + sw.getProperty("status", "IDLE")
                    + "], 4=monument[" + mon.getProperty("status", "IDLE") + "].");
        } catch (Exception e) { return fail(source, "Queue status failed: " + safe(e)); }
    }

    private static int queuePause(CommandSourceStack source) {
        dispatch(source, "oceancanvas flatten pause");
        dispatch(source, "oceancanvas shipwreck populate pause");
        dispatch(source, "oceancanvas monument populate pause");
        return ok(source, "OceanCanvas active queue entries paused at their durable boundaries.");
    }

    private static int queueResume(CommandSourceStack source) {
        dispatch(source, "oceancanvas flatten resume");
        dispatch(source, "oceancanvas shipwreck populate resume");
        dispatch(source, "oceancanvas monument populate resume");
        return ok(source, "OceanCanvas resumable queue entries were asked to resume; inert entries remain inert.");
    }

    private static int queueCancel(CommandSourceStack source, int id) {
        return switch (id) {
            case 1 -> dispatch(source, "oceancanvas flatten cancel");
            case 2 -> dispatch(source, "oceancanvas restore cancel");
            case 3 -> dispatch(source, "oceancanvas shipwreck populate pause");
            case 4 -> dispatch(source, "oceancanvas monument populate pause");
            default -> fail(source, "Unknown queue id " + id + ". Use /oceancanvas queue.");
        };
    }

    private static int setProfile(CommandSourceStack source, String profile) {
        try {
            Path p = controlRoot(source.getServer()).resolve(OVERNIGHT_FILE);
            Properties state = readProps(p); state.setProperty("profile", profile); state.putIfAbsent("status", "IDLE");
            writePropsAtomic(p, state, "Ocean Canvas overnight state");
            return ok(source, "OceanCanvas operating profile set to " + profile + ".");
        } catch (Exception e) { return fail(source, "Profile update failed: " + safe(e)); }
    }

    private static int overnightStart(CommandSourceStack source) {
        try {
            Path p = controlRoot(source.getServer()).resolve(OVERNIGHT_FILE);
            Properties state = readProps(p);
            state.setProperty("profile", state.getProperty("profile", "overnight"));
            state.setProperty("status", "RUNNING"); state.setProperty("startedAt", Instant.now().toString());
            writePropsAtomic(p, state, "Ocean Canvas overnight state");
            int result = terrainStart(source, "pregen");
            if (result == 0) {
                state.setProperty("status", "PAUSED"); writePropsAtomic(p, state, "Ocean Canvas overnight state");
            }
            return result;
        } catch (Exception e) { return fail(source, "Overnight start failed: " + safe(e)); }
    }

    private static int overnightStatus(CommandSourceStack source) {
        try {
            Properties s = readProps(controlRoot(source.getServer()).resolve(OVERNIGHT_FILE));
            return ok(source, "OceanCanvas overnight: " + s.getProperty("status", "IDLE") + ", profile=" + s.getProperty("profile", "balanced")
                    + (s.getProperty("startedAt") == null ? "." : ", started=" + s.getProperty("startedAt") + "."));
        } catch (Exception e) { return fail(source, "Overnight status failed: " + safe(e)); }
    }

    private static int overnightPause(CommandSourceStack source) { return setOvernightStatusAndDispatch(source, "PAUSED", "oceancanvas flatten pause"); }
    private static int overnightResume(CommandSourceStack source) { return setOvernightStatusAndDispatch(source, "RUNNING", "oceancanvas flatten resume"); }
    private static int overnightCancel(CommandSourceStack source) { return setOvernightStatusAndDispatch(source, "CANCELLED", "oceancanvas flatten cancel"); }

    private static int setOvernightStatusAndDispatch(CommandSourceStack source, String status, String command) {
        try {
            Path p = controlRoot(source.getServer()).resolve(OVERNIGHT_FILE); Properties s = readProps(p); s.setProperty("status", status);
            s.putIfAbsent("profile", "balanced"); writePropsAtomic(p, s, "Ocean Canvas overnight state");
            dispatch(source, command); return ok(source, "OceanCanvas overnight status=" + status + ".");
        } catch (Exception e) { return fail(source, "Overnight update failed: " + safe(e)); }
    }

    private static int projectStatus(CommandSourceStack source) {
        try {
            Properties p = readProps(controlRoot(source.getServer()).resolve("project.properties"));
            return ok(source, "OceanCanvas project: archived=" + Boolean.parseBoolean(p.getProperty("archived", "false")) + ".");
        } catch (Exception e) { return fail(source, "Project status failed: " + safe(e)); }
    }

    private static int projectArchive(CommandSourceStack source, boolean archived) {
        try {
            Properties p = readProps(controlRoot(source.getServer()).resolve("project.properties")); p.setProperty("archived", Boolean.toString(archived));
            writePropsAtomic(controlRoot(source.getServer()).resolve("project.properties"), p, "Ocean Canvas project state");
            return ok(source, "OceanCanvas project " + (archived ? "archived; destructive starts are locked" : "unarchived") + ".");
        } catch (Exception e) { return fail(source, "Project archive update failed: " + safe(e)); }
    }

    private static int planStatus(CommandSourceStack source) {
        try {
            Selection s = Selection.load(controlRoot(source.getServer()).resolve(SELECTION_FILE));
            return ok(source, "OceanCanvas planning command surface active; current target=" + (s == null ? "full_canvas" : s) + ". Map/control-center remains canonical for rich planning geometry.");
        } catch (Exception e) { return fail(source, "Plan status failed: " + safe(e)); }
    }

    private static boolean projectArchived(MinecraftServer server) throws IOException {
        return Boolean.parseBoolean(readProps(controlRoot(server).resolve("project.properties")).getProperty("archived", "false"));
    }

    private static int dispatch(CommandSourceStack source, String command) {
        try {
            source.getServer().getCommands().performPrefixedCommand(source, command);
            return 1;
        } catch (Exception e) { return fail(source, "OceanCanvas delegated command failed: " + safe(e)); }
    }

    private static void writeOperationIntent(MinecraftServer server, String kind) throws IOException {
        Properties p = new Properties(); p.setProperty("operation", kind); p.setProperty("updatedAt", Instant.now().toString());
        writePropsAtomic(controlRoot(server).resolve("operation-intent.properties"), p, "Ocean Canvas canonical operation intent");
    }

    private static Path controlRoot(MinecraftServer server) throws IOException {
        Path p = server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(CONTROL_DIR); Files.createDirectories(p); return p;
    }

    private static Path fullCanvasRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).resolve(FULL_CANVAS_DIR);
    }

    private static Properties readProps(Path path) throws IOException {
        Properties p = new Properties();
        if (Files.exists(path)) try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        return p;
    }

    private static void writePropsAtomic(Path path, Properties p, String comment) throws IOException {
        Files.createDirectories(path.getParent()); Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, comment); }
        try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static int ok(CommandSourceStack source, String message) { source.sendSuccess(() -> Component.literal(message), false); return 1; }
    private static int fail(CommandSourceStack source, String message) { source.sendFailure(Component.literal(message)); return 0; }
    private static String safe(Throwable t) { String m = t.getMessage(); return m == null || m.isBlank() ? t.getClass().getSimpleName() : m; }

    private record Selection(int minX, int minZ, int maxX, int maxZ) {
        static Selection load(Path path) throws IOException {
            Properties p = readProps(path); if (p.isEmpty()) return null;
            return new Selection(Integer.parseInt(p.getProperty("minX")), Integer.parseInt(p.getProperty("minZ")),
                    Integer.parseInt(p.getProperty("maxX")), Integer.parseInt(p.getProperty("maxZ")));
        }
        void write(Path path) throws IOException {
            Properties p = new Properties(); p.setProperty("minX", Integer.toString(minX)); p.setProperty("minZ", Integer.toString(minZ));
            p.setProperty("maxX", Integer.toString(maxX)); p.setProperty("maxZ", Integer.toString(maxZ)); writePropsAtomic(path, p, "Ocean Canvas selection");
        }
        boolean matchesFullCanvas(CoreConfig c) {
            int half = c.canvasSize() / 2;
            return minX <= c.centerX() - half && minZ <= c.centerZ() - half && maxX >= c.centerX() + half - 1 && maxZ >= c.centerZ() + half - 1;
        }
        @Override public String toString() { return "[" + minX + "," + minZ + "]->[" + maxX + "," + maxZ + "]"; }
    }

    private record Protection(boolean enabled, String scope) {
        static Protection load(Path path) throws IOException {
            Properties p = readProps(path); return new Protection(Boolean.parseBoolean(p.getProperty("enabled", "false")), p.getProperty("scope", "none"));
        }
        void write(Path path) throws IOException {
            Properties p = new Properties(); p.setProperty("enabled", Boolean.toString(enabled)); p.setProperty("scope", scope); writePropsAtomic(path, p, "Ocean Canvas protection");
        }
    }
}
