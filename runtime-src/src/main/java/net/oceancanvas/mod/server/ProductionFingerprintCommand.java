package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Bounded independent terrain fingerprint used by disposable production-runtime
 * acceptance tests. It has no mutation authority and refuses canvases larger
 * than 16 chunks so it cannot accidentally become a full-world scan facility.
 *
 * <p>The compact per-chunk diagnostics intentionally expose only truncated
 * hashes. They are localization evidence, not the release identity; the full
 * 256-bit whole-region hash remains authoritative.</p>
 */
public final class ProductionFingerprintCommand {
    private static final int MAX_CHUNKS = 16;
    private static Path configDir;
    private static boolean registered;

    private ProductionFingerprintCommand() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
        registered = true;
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/productionfingerprint"), PermissionLevel.ADMINS))
                .then(Commands.literal("productionfingerprint")
                        .executes(context -> fingerprint(context.getSource()))));
    }

    private static int fingerprint(CommandSourceStack source) {
        try {
            CoreConfig core = CoreConfig.loadOrCreate(configDir);
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(core.canvasSize(), core.centerX(), core.centerZ());
            if (bounds.count() > MAX_CHUNKS) {
                source.sendFailure(Component.literal("OceanCanvas production fingerprint refused: configured Canvas is "
                        + bounds.count() + " chunks; diagnostic maximum is " + MAX_CHUNKS + "."));
                return 0;
            }

            ServerLevel world = source.getServer().overworld();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "seed=" + world.getSeed() + "\n");
            update(digest, "chunks=" + bounds.minX() + "," + bounds.minZ() + ".." + bounds.maxX() + "," + bounds.maxZ() + "\n");
            update(digest, "y=" + world.getMinY() + ".." + (world.getMaxY() - 1) + "\n");

            int authoredStartY = core.oceanFloorY() - core.oceanFloorVariation() - 1;
            int highStartY = core.waterSurfaceY() + 2;
            StringBuilder detail = new StringBuilder();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            long cells = 0;
            for (int cz = bounds.minZ(); cz <= bounds.maxZ(); cz++) {
                for (int cx = bounds.minX(); cx <= bounds.maxX(); cx++) {
                    LevelChunk chunk = world.getChunk(cx, cz);
                    MessageDigest chunkDigest = MessageDigest.getInstance("SHA-256");
                    MessageDigest deepDigest = MessageDigest.getInstance("SHA-256");
                    MessageDigest authoredDigest = MessageDigest.getInstance("SHA-256");
                    MessageDigest highDigest = MessageDigest.getInstance("SHA-256");
                    update(digest, "chunk=" + cx + "," + cz + "\n");
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int blockX = (cx << 4) + x;
                            int blockZ = (cz << 4) + z;
                            for (int y = world.getMinY(); y < world.getMaxY(); y++) {
                                pos.set(blockX, y, blockZ);
                                byte[] state = chunk.getBlockState(pos).toString().getBytes(StandardCharsets.UTF_8);
                                digest.update(state); digest.update((byte) 0);
                                chunkDigest.update(state); chunkDigest.update((byte) 0);
                                MessageDigest band = y < authoredStartY ? deepDigest
                                        : (y < highStartY ? authoredDigest : highDigest);
                                band.update(state); band.update((byte) 0);
                                cells++;
                            }
                        }
                    }
                    if (detail.length() > 0) detail.append('|');
                    detail.append(cx).append(',').append(cz).append(':')
                            .append(shortHash(chunkDigest, 12)).append(':')
                            .append(shortHash(deepDigest, 8)).append(':')
                            .append(shortHash(authoredDigest, 8)).append(':')
                            .append(shortHash(highDigest, 8));
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            long finalCells = cells;
            String compactDetail = detail.toString();
            source.sendSuccess(() -> Component.literal("OceanCanvas production fingerprint: sha256=" + hash
                    + " chunks=" + bounds.count() + " cells=" + finalCells + " seed=" + world.getSeed()
                    + " bands=deep<" + authoredStartY + ",authored<" + highStartY + ",high detail=" + compactDetail), false);
            OceanCanvas.LOGGER.info("(Ocean Canvas) PRODUCTION-FINGERPRINT sha256={} chunks={} cells={} seed={} bands=deep<{},authored<{},high detail={}",
                    hash, bounds.count(), finalCells, world.getSeed(), authoredStartY, highStartY, compactDetail);
            return 1;
        } catch (Exception e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) production fingerprint failed", e);
            source.sendFailure(Component.literal("OceanCanvas production fingerprint failed: " + safe(e)));
            return 0;
        }
    }

    private static String shortHash(MessageDigest digest, int hexChars) {
        String full = HexFormat.of().formatHex(digest.digest());
        return full.substring(0, Math.min(hexChars, full.length()));
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }
}
