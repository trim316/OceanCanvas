package net.oceancanvas.mod.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.mod.OceanCanvas;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Minecraft 26.2 aquatic companion to the authoritative wildlife policy. */
public final class AquaticWildlifeRuntime {
    private static Path configDir;
    private static boolean registered;

    private AquaticWildlifeRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        ServerEntityEvents.ENTITY_LOAD.register(AquaticWildlifeRuntime::onEntityLoad);
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) AQUATIC-WILDLIFE-RUNTIME-REGISTERED mappedClass={}", WaterAnimal.class.getName());
    }

    private static void onEntityLoad(Entity entity, ServerLevel world) {
        try {
            if (!(entity instanceof WaterAnimal)) return;
            MinecraftServer server = world.getServer();
            if (!"never".equals(wildlifeMode(server))) return;
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            Properties selection = readProps(server.getWorldPath(LevelResource.ROOT)
                    .resolve("oceancanvas-core").resolve("operator-control").resolve("selection.properties"));
            int minX, minZ, maxX, maxZ;
            if (selection.isEmpty()) {
                int half = core.canvasSize() / 2;
                minX = core.centerX() - half;
                minZ = core.centerZ() - half;
                maxX = core.centerX() + half - 1;
                maxZ = core.centerZ() + half - 1;
            } else {
                minX = Integer.parseInt(selection.getProperty("minX"));
                minZ = Integer.parseInt(selection.getProperty("minZ"));
                maxX = Integer.parseInt(selection.getProperty("maxX"));
                maxZ = Integer.parseInt(selection.getProperty("maxZ"));
            }
            int x = entity.getBlockX(), z = entity.getBlockZ();
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) entity.discard();
        } catch (Throwable t) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas Core) aquatic wildlife policy evaluation failed closed-to-vanilla: {}", safe(t));
        }
    }

    private static String wildlifeMode(MinecraftServer server) throws Exception {
        Properties p = readProps(server.getWorldPath(LevelResource.ROOT)
                .resolve("oceancanvas-core").resolve("operator-control").resolve("wildlife.properties"));
        return p.getProperty("mode", "default").trim().toLowerCase(Locale.ROOT);
    }

    private static Properties readProps(Path path) throws Exception {
        Properties p = new Properties();
        if (Files.isRegularFile(path)) try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        return p;
    }

    private static String safe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m.replace('\n', ' ').replace('\r', ' ');
    }
}
