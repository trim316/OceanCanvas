package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Last-registered compatibility owner for shipwreck rule mutation.
 * Preserves monument/portal keys added by the generic P0 structure-rule surface.
 */
public final class StructureRuleCompatibilityRuntime {
    private static final String FILE = "oceancanvas-structure-rules.properties";
    private static Path configDir;
    private static boolean registered;
    private StructureRuleCompatibilityRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
        registered = true;
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/structures"), PermissionLevel.ADMINS))
                .then(Commands.literal("structurerules")
                        .then(Commands.literal("set")
                                .then(Commands.literal("shipwreck")
                                        .then(Commands.literal("force_on").executes(c -> set(c.getSource(), "force_on")))
                                        .then(Commands.literal("default").executes(c -> set(c.getSource(), "default")))
                                        .then(Commands.literal("never").executes(c -> set(c.getSource(), "never")))))
                        .then(Commands.literal("get")
                                .then(Commands.literal("shipwreck").executes(c -> get(c.getSource()))))));
    }

    private static int set(CommandSourceStack source, String value) {
        try {
            Path path = configDir.resolve(FILE);
            Properties p = read(path);
            p.setProperty("shipwreck", value);
            write(path, p);
            source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck rule set to " + value + "."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas could not update shipwreck rule: " + e.getMessage()));
            return 0;
        }
    }

    private static int get(CommandSourceStack source) {
        try {
            String v = read(configDir.resolve(FILE)).getProperty("shipwreck", "default");
            source.sendSuccess(() -> Component.literal("OceanCanvas shipwreck rule: " + v + "."), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("OceanCanvas could not read shipwreck rule: " + e.getMessage()));
            return 0;
        }
    }

    private static Properties read(Path path) throws Exception {
        Properties p = new Properties();
        if (Files.exists(path)) try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        return p;
    }

    private static void write(Path path, Properties p) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "Ocean Canvas structure rules"); }
        try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
