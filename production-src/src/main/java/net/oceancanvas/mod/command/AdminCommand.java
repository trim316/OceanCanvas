package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
// me.lucko.fabric.api.permissions.v0, not net.fabricmc.fabric.api.permission.v1
// - see BoundaryCommand's import for the full story of this real,
// user-confirmed fix (the fabricmc-namespaced package doesn't exist at
// all; this is fabric-permissions-api's actual class, in its own
// me.lucko package despite sharing the "fabric.api" segment).
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.operation.OceanCanvasActionLog;
import net.oceancanvas.mod.pregen.PregenManager;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code /oceancanvas admin} - the "dashboard command consolidating stats,
 * active jobs, zone count, and recent destructive actions in one view
 * instead of several separate commands" brainstormed idea, drafted per the
 * user's own explicit, verbatim request this round.
 *
 * <p><b>Deliberately a thin aggregator, not new tracking logic of its
 * own</b> - same design principle {@link StatusCommand#stats} already
 * established: every number shown here already exists for some other
 * reason (config, {@link PregenManager}, {@link OceanCanvasPlayerZones},
 * {@link OceanCanvasProtectedData}, {@link OceanCanvasActionLog}). This
 * command's only real new logic is {@link #diskFootprint}, since nothing
 * else in the project previously needed to know the world save's size on
 * disk.</p>
 *
 * <p><b>Op-only ({@code Permissions.require(2)}), unlike {@code
 * /oceancanvas status}/{@code stats}/{@code here}</b> - this surfaces who
 * ran recent destructive commands and the exact disk footprint of the
 * world save, information that's reasonable to keep to admins rather than
 * open to every player, matching the same permission tier already used for
 * every other server-impacting or sensitive command in this project.</p>
 *
 * <p><b>{@link #diskFootprint} is a real, honest cost, not a cheap
 * lookup</b> - it walks every file under the world save root to sum sizes.
 * For a small/medium canvas this is fast (well under a second); for a very
 * large, heavily-explored canvas with many gigabytes of region files, it
 * could take a real, noticeable moment. Deliberately still run
 * synchronously (like every other command in this project) rather than
 * adding async/threading complexity for what is, in the end, an op running
 * an occasional diagnostic command, not something on any hot path.</p>
 */
public final class AdminCommand {

	private AdminCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("admin")
								// Op-only - see the class doc for why this differs
								// from the read-only status/stats/here commands.
								.requires(Permissions.require("oceancanvas.admin", 2))
								.executes(AdminCommand::run)
								.then(Commands.literal("snapshot").executes(AdminCommand::snapshot))
								.then(Commands.literal("snapshots").executes(AdminCommand::snapshots))
								.then(Commands.literal("diff").executes(AdminCommand::diffSnapshots)))
		);
	}


	private static int snapshot(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source=context.getSource();
		var snap=net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(source.getLevel())
				.capture(source.getLevel(),"manual-admin","Manual admin metadata snapshot");
		source.sendSuccess(() -> Component.literal("Created Ocean Canvas metadata snapshot "+snap.id()+" ("+snap.project().regionCount()+" regions, "
				+snap.terrain().canvasCount()+" Canvas terrain states, "+snap.seals().physical()+" physical seals)."), false);
		return 1;
	}

	private static int snapshots(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source=context.getSource();
		var list=net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(source.getLevel()).recent();
		if(list.isEmpty()){
			source.sendSuccess(() -> Component.literal("No Ocean Canvas metadata snapshots recorded."), false);
			return 1;
		}
		StringBuilder sb=new StringBuilder("Recent metadata snapshots:");
		for(int i=0;i<Math.min(10,list.size());i++){
			var s=list.get(i);
			sb.append("\n  ").append(s.id()).append(" · ").append(s.label()).append(" · ").append(s.reason());
		}
		source.sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	private static int diffSnapshots(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source=context.getSource();
		var lines=net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(source.getLevel()).diffLatestTwo();
		source.sendSuccess(() -> Component.literal("Latest metadata snapshot diff:\n  - "+String.join("\n  - ",lines)), false);
		return 1;
	}

	private static int run(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		OceanCanvasConfig config = OceanCanvasConfig.get();
		ServerLevel world = source.getLevel();
		OceanCanvasProtectedData data = OceanCanvasProtectedData.get(world);
		List<OceanCanvasPlayerZones.Zone> zones = OceanCanvasPlayerZones.get(world).all();
		long activeZones = zones.stream().filter(OceanCanvasPlayerZones.Zone::protectedNow).count();

		long chunksPerSide = (long) Math.ceil(config.canvasSize() / 16.0);
		long totalChunks = chunksPerSide * chunksPerSide;
		double percent = totalChunks == 0 ? 0.0 : (data.flattenedChunkCount() * 100.0) / totalChunks;

		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT,
				"Canvas: %dx%d centered at (%d, %d), expansion %s%n",
				config.canvasSize(), config.canvasSize(), config.centerX(), config.centerZ(),
				config.expansionEnabled() ? "enabled" : "disabled"));
		sb.append(String.format(Locale.ROOT,
				"Progress: ~%,d / %,d chunks flattened (%.2f%%, approximate)%n",
				data.flattenedChunkCount(), totalChunks, percent));
		sb.append("Active job: ").append(PregenManager.isRunning() ? PregenManager.status() : "none").append('\n');
		sb.append(String.format(Locale.ROOT,
				"Protected regions: %d defined (%d currently protected)%n", zones.size(), activeZones));

		// A region is no longer just protected-or-not, so a dashboard that
		// only reported the count was hiding most of what regions now do.
		long withStructureRules = zones.stream()
				.filter(zone -> {
					for (net.oceancanvas.mod.worldgen.OceanCanvasStructureKind kind
							: net.oceancanvas.mod.worldgen.OceanCanvasStructureKind.values()) {
						if (zone.overrideFor(kind) != net.oceancanvas.mod.config.StructureOverride.INHERIT) {
							return true;
						}
					}
					return false;
				})
				.count();
		long withBiome = zones.stream().filter(zone -> zone.biomeOverride() != null).count();
		long withMobRule = zones.stream().filter(OceanCanvasPlayerZones.Zone::suppressHostileMobs).count();
		if (withStructureRules > 0 || withBiome > 0 || withMobRule > 0) {
			sb.append(String.format(Locale.ROOT,
					"  region rules: %d with structure rules, %d painting a biome, %d keeping mobs out%n",
					withStructureRules, withBiome, withMobRule));
		}
		sb.append(String.format(Locale.ROOT,
				"Shipwrecks: %d relocated + spawn shipwreck placed: %s%n",
				data.relocatedShipwreckCount(), data.isGuaranteedShipwreckPlaced() ? "yes" : "no"));
		sb.append("World save size on disk: ").append(diskFootprint(source.getServer().getWorldPath(LevelResource.ROOT))).append('\n');
		sb.append("Automatic backups: ").append(config.backupEnabled()
				? "enabled (threshold " + config.backupThresholdChunks() + " chunks, keeping last "
						+ config.backupRetentionCount() + ")"
				: "disabled").append('\n');

		List<String> recentActions = OceanCanvasActionLog.recentFormatted(world);
		sb.append("Recent destructive actions");
		if (recentActions.isEmpty()) {
			sb.append(": none recorded.");
		} else {
			sb.append(" (most recent first):");
			// Capped to 10 in the dashboard view even though the log
			// itself keeps up to 20 - keeps the command's own chat output
			// from getting too long; the fuller history is still there in
			// the log for a future "admin log" subcommand if ever needed.
			for (int i = 0; i < Math.min(10, recentActions.size()); i++) {
				sb.append("\n  - ").append(recentActions.get(i));
			}
		}

		String report = sb.toString();
		// Localization foundation (drafted this round) - see
		// docs/roadmap.md's "localization" note for the full scope
		// decision. Only the truly static, no-dynamic-data header line
		// uses a real translation key (assets/oceancanvas/lang/en_us.json)
		// via Component.translatable; every dynamic report line below it
		// stays Component.literal - those carry live numbers/coordinates
		// computed with String.format, which a translation KEY alone
		// can't represent without a much larger args-based rewrite (see
		// that roadmap note for why this round deliberately doesn't
		// attempt that for all ~15 command files at once).
		source.sendSuccess(() -> Component.translatable("oceancanvas.dashboard.admin.header")
				.append(Component.literal("\n" + report)), false);
		return 1;
	}

	/**
	 * Sums the size of every file under the world save root - see the
	 * class doc for the honest cost tradeoff. Returns a human-readable
	 * size string (MB or GB, whichever reads better), or an error note if
	 * the walk itself fails (permissions, etc.) rather than throwing and
	 * breaking the rest of the dashboard.
	 */
	private static String diskFootprint(Path worldRoot) {
		if (!Files.isDirectory(worldRoot)) {
			return "unknown (save directory not found)";
		}
		try (java.util.stream.Stream<Path> files = Files.walk(worldRoot)) {
			long totalBytes = files.filter(Files::isRegularFile)
					.mapToLong(AdminCommand::sizeOrZero)
					.sum();
			return humanReadableBytes(totalBytes);
		} catch (IOException e) {
			return "unknown (failed to read: " + e.getMessage() + ")";
		}
	}

	private static long sizeOrZero(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return 0L; // a file that vanished mid-walk (e.g. an active autosave) - harmless to skip, not worth failing the whole report over
		}
	}

	private static String humanReadableBytes(long bytes) {
		double gb = bytes / (1024.0 * 1024.0 * 1024.0);
		if (gb >= 1.0) {
			return String.format(Locale.ROOT, "%.2f GB", gb);
		}
		double mb = bytes / (1024.0 * 1024.0);
		return String.format(Locale.ROOT, "%.1f MB", mb);
	}
}
