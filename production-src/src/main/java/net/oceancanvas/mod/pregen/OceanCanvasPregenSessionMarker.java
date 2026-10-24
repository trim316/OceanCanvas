package net.oceancanvas.mod.pregen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * v253.72 synchronous crash marker for an active Pregen session.
 *
 * <p>SavedData is durable at Minecraft's save boundaries, not at the instant a
 * Java field changes.  Consequently a previous {@code cleanStop=true} record
 * cannot by itself distinguish "the next session crashed before its first
 * autosave" from an actually clean stop.  This tiny sidecar file is forced to
 * disk when a Pregen session is armed and removed only after the graceful
 * server-stop cleanup path has completed.  If the JVM, OS, or machine dies,
 * the file remains and the next launch treats the persisted tail as unclean.</p>
 *
 * <p>The marker contains diagnostics only; presence is the safety signal.  A
 * stale/malformed marker therefore fails closed and causes extra verification,
 * never skipped work.</p>
 */
final class OceanCanvasPregenSessionMarker {
    private static final String FILE_NAME = "pregen-session-armed.marker";

    private OceanCanvasPregenSessionMarker() {}

    private static Path markerPath(ServerLevel world) {
        return world.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("oceancanvas").resolve(FILE_NAME);
    }

    static boolean isArmed(ServerLevel world) {
        try {
            return Files.isRegularFile(markerPath(world));
        } catch (Exception e) {
            // Files.isRegularFile normally fails soft, but any unusual filesystem
            // failure is safer to interpret as an unclean prior session.
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CRASH-RECOVERY could not inspect Pregen session marker; failing closed as UNCLEAN: {}", e.toString());
            return true;
        }
    }

    static void arm(ServerLevel world, OceanCanvasJobState.Snapshot snapshot) {
        if (world == null || snapshot == null) return;
        Path marker = markerPath(world);
        try {
            Files.createDirectories(marker.getParent());
            String body = "OceanCanvas Pregen crash marker\n"
                    + "version=" + OceanCanvas.VERSION + "\n"
                    + "session=" + UUID.randomUUID() + "\n"
                    + "armedAt=" + Instant.now() + "\n"
                    + "kind=" + snapshot.kind() + "\n"
                    + "bounds=" + snapshot.minChunkX() + "," + snapshot.maxChunkX() + ","
                    + snapshot.minChunkZ() + "," + snapshot.maxChunkZ() + "\n"
                    + "checkpoint=" + snapshot.nextIndex() + "," + snapshot.submittedCount() + "\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(marker,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                // This is intentionally synchronous. The file is tiny and this closes
                // the "crash before first autosave" detection hole.
                channel.force(true);
            }
        } catch (IOException e) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) CRASH-RECOVERY could not arm durable Pregen session marker at {}: {}. SavedData recovery remains active but crash-before-autosave detection is degraded.",
                    marker, e.toString());
        }
    }

    static void disarm(ServerLevel world) {
        if (world == null) return;
        Path marker = markerPath(world);
        try {
            if (Files.deleteIfExists(marker)) {
                OceanCanvas.LOGGER.debug("(Ocean Canvas) CRASH-RECOVERY graceful-session marker cleared: {}", marker.getFileName());
            }
        } catch (IOException e) {
            // Leave the marker in place. The next launch will perform conservative
            // recovery, which is the safe outcome when cleanup cannot be proven.
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CRASH-RECOVERY could not clear graceful-session marker {}; next launch will fail closed as UNCLEAN: {}",
                    marker, e.toString());
        }
    }
}
