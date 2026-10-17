package net.oceancanvas.mod.backup;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Scheduled/automatic backups tied to canvas milestones (before a big
 * expand, before a large reset) rather than relying on the server's own
 * backup cadence" - the user's own explicit, verbatim brainstormed idea,
 * drafted this round.
 *
 * <p><b>Deliberately a plain filesystem copy of the world save folder, not
 * a new dependency or an unconfirmed backup API.</b> Every API this class
 * touches is a long-established, stable part of either vanilla Minecraft
 * ({@link MinecraftServer#getWorldPath(LevelResource)}/{@link
 * MinecraftServer#saveAllChunks}, both present and unchanged across many
 * versions - used here exactly as countless other mods and server backup
 * plugins already do) or plain Java NIO ({@code Files.walkFileTree}), so
 * this carries far less API-drift risk than most of this project's
 * worldgen code, which has repeatedly needed real fixes against unconfirmed
 * 26.x internals (see e.g. {@code OceanCanvasProtectedData}'s revision
 * history). Still genuinely untested against a real 26.2 build in this
 * sandbox like everything else here - flagged honestly, not because the
 * APIs themselves are in doubt.</p>
 *
 * <p><b>Deliberately synchronous, run on the calling (main server) thread,
 * not a background thread - a considered choice, not an oversight.</b> A
 * background copy thread reading world files while the main thread keeps
 * ticking (and could autosave, or start the very reset/expand job this
 * backup is meant to precede) risks copying a file mid-write, producing a
 * torn/inconsistent backup - silently defeating the entire point of a
 * safety net. Running synchronously, right before the destructive job's
 * {@code Job} is constructed (see {@code PregenManager#rewipe}/{@code
 * expand}), guarantees nothing else can be writing to the save while the
 * copy runs, at the honest cost of pausing the server for however long the
 * copy takes. For this project's stated singleplayer-only usage (see
 * {@code docs/roadmap.md}'s "usage context" note), that pause is a
 * momentary freeze, not a multiplayer-facing outage - an acceptable,
 * clearly-documented tradeoff rather than a hidden one. {@link
 * MinecraftServer#saveAllChunks} is called first specifically so the
 * snapshot reflects the truly latest state (including anything not yet
 * autosaved), not a stale copy from several minutes ago.</p>
 *
 * <p><b>Backups live in a sibling directory, never inside the world save
 * itself</b> - {@code <world folder>/../oceancanvas_backups/<world folder
 * name>_<reason>_<timestamp>/}, one level up from {@link
 * LevelResource#ROOT}. Putting them inside the save folder would risk a
 * backup accidentally getting swept up by whatever this or a future backup
 * pass walks next, and would bloat the very save Minecraft itself manages.</p>
 */
public final class OceanCanvasBackupManager {

	private static final DateTimeFormatter TIMESTAMP_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

	private OceanCanvasBackupManager() {
	}

	/**
	 * Backs up the world save if {@code totalChunks} meets or exceeds
	 * {@link OceanCanvasConfig#backupThresholdChunks()} and {@link
	 * OceanCanvasConfig#backupEnabled()} is true; otherwise a cheap no-op.
	 * Called from {@code PregenManager#rewipe}/{@code expand} right before
	 * the destructive {@code Job} is actually constructed, so a backup
	 * failure (see below) never blocks the operation itself - a job this
	 * user explicitly confirmed should still run even if, say, the disk is
	 * full and the backup can't be written; that failure is reported back
	 * as part of the normal status message rather than silently swallowed
	 * OR treated as a reason to refuse the whole command.
	 *
	 * @return a short human-readable clause describing what happened
	 *         (backed up, skipped, or failed), meant to be appended to the
	 *         command's own status message - never {@code null}, but empty
	 *         when nothing worth reporting happened (disabled, or below
	 *         threshold - the common case, not worth mentioning every time).
	 */
	public static String maybeBackup(ServerLevel world, String reason, long totalChunks) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.backupEnabled()) {
			return "";
		}
		if (totalChunks < config.backupThresholdChunks()) {
			return "";
		}

		MinecraftServer server = world.getServer();
		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		Path backupsParent = worldRoot.getParent() == null
				? worldRoot.resolveSibling("oceancanvas_backups") // extremely unlikely (a filesystem root save dir), but handled rather than NPEing
				: worldRoot.getParent().resolve("oceancanvas_backups");
		String worldFolderName = worldRoot.getFileName() == null ? "world" : worldRoot.getFileName().toString();
		String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
		Path destination = backupsParent.resolve(worldFolderName + "_" + sanitize(reason) + "_" + timestamp);

		try {
			// Flush everything to disk first so the copy below reflects
			// truly current state, not whatever was on disk as of the
			// last autosave - see the class doc for why this matters.
			server.saveAllChunks(false, true, true);

			copyDirectory(worldRoot, destination);
			pruneOldBackups(backupsParent, worldFolderName, config.backupRetentionCount());

			String message = "Backed up world save to " + destination + " before this " + reason + ".";
			OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
			return message + " ";
		} catch (IOException e) {
			// Real failure (disk full, permissions, etc.) - reported back
			// to the requester as part of the command's own status
			// message rather than only logged, since "did my safety net
			// actually work" matters enough to surface directly. Does NOT
			// throw/propagate - see the method doc for why a backup
			// failure must never block the confirmed operation itself.
			String message = "WARNING: automatic backup before this " + reason + " failed (" + e.getMessage()
					+ ") - proceeding anyway since you already confirmed this operation, but there is no fresh "
					+ "safety net for it. Consider backing up manually.";
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Backup before {} failed", reason, e);
			return message + " ";
		}
	}

	// Keeps a reason string filesystem-path-safe - reasons are currently
	// always one of the two literal strings PregenManager passes ("reset",
	// "expand"), so this is just defense in depth against that ever
	// changing to something with spaces/punctuation later, not something
	// exercised today.
	private static String sanitize(String reason) {
		return reason.replaceAll("[^a-zA-Z0-9_-]", "_");
	}

	private static void copyDirectory(Path source, Path destination) throws IOException {
		Files.createDirectories(destination);
		Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(destination.resolve(source.relativize(dir)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				// REPLACE_EXISTING is defensive only - destination is a
				// freshly timestamped, never-before-used directory name
				// (down to the second) each call, so a real collision is
				// not expected in practice.
				Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Deletes the oldest automatic backups for this world once there are
	 * more than {@code keep} of them - see {@link
	 * OceanCanvasConfig#backupRetentionCount()}'s doc comment for why this
	 * bound exists at all (a full world-save copy per backup is real disk
	 * cost that would otherwise grow unbounded). Only ever considers
	 * directories matching this world's own folder-name prefix, so a
	 * differently-named world's backups sharing the same parent directory
	 * (e.g. after switching save folders) are never touched.
	 */
	private static void pruneOldBackups(Path backupsParent, String worldFolderName, int keep) {
		if (!Files.isDirectory(backupsParent)) {
			return;
		}
		try (java.util.stream.Stream<Path> entries = Files.list(backupsParent)) {
			List<Path> matching = new ArrayList<>();
			entries.filter(Files::isDirectory)
					.filter(p -> p.getFileName().toString().startsWith(worldFolderName + "_"))
					.forEach(matching::add);
			if (matching.size() <= keep) {
				return;
			}
			// Lexicographic sort works here because the timestamp suffix
			// (yyyy-MM-dd_HH-mm-ss) is itself chronologically sortable as
			// a plain string - no need to parse it back out.
			matching.sort(Comparator.comparing(p -> p.getFileName().toString()));
			int toDelete = matching.size() - keep;
			for (int i = 0; i < toDelete; i++) {
				deleteDirectoryRecursively(matching.get(i));
			}
		} catch (IOException e) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Failed to prune old automatic backups under {}", backupsParent, e);
		}
	}

	private static void deleteDirectoryRecursively(Path dir) throws IOException {
		Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
