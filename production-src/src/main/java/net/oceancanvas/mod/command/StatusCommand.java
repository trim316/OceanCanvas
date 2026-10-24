package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.pregen.OceanCanvasUndoManager;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

/**
 * {@code /oceancanvas status} - reports the current canvas region settings.
 * This is deliberately the very first command: before any world generation
 * work lands, we want a fast way to confirm the mod is loaded and to see
 * what settings a given world was configured with.
 */
public final class StatusCommand {

	private StatusCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("status")
								.executes(StatusCommand::run))
						.then(Commands.literal("here")
								.executes(StatusCommand::here))
						.then(Commands.literal("stats")
								.executes(StatusCommand::stats))
		);
	}

	private static int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		CommandSourceStack source = context.getSource();

		source.sendSuccess(() -> Component.literal(
				"Ocean Canvas - size " + config.canvasSize() + "x" + config.canvasSize()
						+ ", center (" + config.centerX() + ", " + config.centerZ() + ")"
						+ ", expansion " + (config.expansionEnabled() ? "enabled" : "disabled")
		), false);

		return 1;
	}

	/**
	 * {@code /oceancanvas here} - reports whether the command source's
	 * current position is inside the protected canvas, and how far it is
	 * to the nearest edge. Outside the canvas is not an error case: by
	 * design, chunks outside the configured region are left as plain
	 * vanilla terrain (the flattening pass in OceanCanvasSurfaceFlattener
	 * only ever touches positions where isInsideCanvas() is true), so
	 * sailing past the edge should show you ordinary Minecraft continents
	 * again. This command exists to make that boundary visible/testable
	 * rather than something you have to infer from world-gen behavior.
	 */
	private static int here(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		CommandSourceStack source = context.getSource();
		net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(source.getPosition());

		// Taper zone reporting (drafted this round, alongside the soft
		// edge transition) - CanvasZone.TAPER is a genuinely distinct
		// third state (see that enum's doc comment for the user's own
		// explicit design decision), so it gets its own message branch
		// below rather than being folded into "inside" or "outside".
		OceanCanvasConfig.CanvasZone zone = config.canvasZone(pos.getX(), pos.getZ());
		int radius = config.radius();
		int distToEdge = radius - Math.max(
				Math.abs(pos.getX() - config.centerX()),
				Math.abs(pos.getZ() - config.centerZ())
		);

		// Added alongside /oceancanvas protect - reports protection status
		// at your current position too, since "am I standing somewhere
		// safe from a future reset/expand" is exactly the kind of thing
		// this command already exists to make visible/testable rather
		// than left to infer.
		boolean protectedHere = OceanCanvasPlayerZones.get(source.getLevel())
				.isProtected(pos.getX(), pos.getY(), pos.getZ());

		// Added alongside /oceancanvas undo (drafted this round) - only
		// meaningful per-player (undo history is per-player, see
		// OceanCanvasUndoManager's class doc), so only shown for an actual
		// player source, same as the console-check on the undo command
		// itself.
		net.minecraft.server.level.ServerPlayer player = source.getPlayer();
		int undoCount = player == null ? 0 : OceanCanvasUndoManager.history(player.getUUID()).size();

		String zoneMessage;
		switch (zone) {
			case INSIDE -> zoneMessage = "Inside the canvas - " + distToEdge + " blocks to the nearest edge.";
			case TAPER -> zoneMessage = "In the soft edge transition zone, " + (-distToEdge) + " blocks past the "
					+ "canvas edge - the excavated floor is blending back toward natural terrain here "
					+ "(taperWidthChunks=" + config.taperWidthChunks() + ").";
			default -> zoneMessage = "Outside the canvas (" + (-distToEdge) + " blocks past the edge) - "
					+ "expect plain vanilla terrain here, not ocean.";
		}

		// Every region covering this exact position, with what each one
		// actually does - previously this said only "inside a protected
		// zone", which stopped being the whole story the moment regions
		// started carrying structure, biome and mob rules.
		java.util.List<OceanCanvasPlayerZones.Zone> here = OceanCanvasPlayerZones.get(source.getLevel())
				.zonesAt(pos.getX(), pos.getY(), pos.getZ());

		source.sendSuccess(() -> Component.literal(
				zoneMessage
						+ (protectedHere ? " This position is inside a protected zone." : "")
						+ (undoCount > 0 ? " You have " + undoCount + " undoable reset(s) (\"/oceancanvas undo\")."
								: "")
		), false);

		if (!here.isEmpty()) {
			// Named 'region', not 'zone' - a local named zone (the
			// CanvasZone above) is already in scope for this whole method,
			// and Java forbids a local shadowing an enclosing one.
			for (OceanCanvasPlayerZones.Zone region : here) {
				String rules = OceanCanvasPlayerZones.describeRules(region);
				source.sendSuccess(() -> Component.literal("  region '" + region.name() + "' ["
						+ (region.protectedNow() ? "PROTECTED" : "unprotected") + "] - " + rules), false);
			}
		}

		return 1;
	}

	/**
	 * {@code /oceancanvas stats} - a Chunky-style progress readout,
	 * drafted per the brainstormed "gives the project a visible status
	 * dashboard feel rather than only pass/fail log lines" idea. All
	 * three underlying numbers already exist in
	 * {@link OceanCanvasProtectedData} for other reasons (persisted,
	 * survives a restart) - this command is deliberately just a report,
	 * no new tracking logic of its own. Read-only, so left open to any
	 * player like {@code status}/{@code here} - unlike
	 * {@code /oceancanvas pregen}, this can't affect the server.
	 */
	private static int stats(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		CommandSourceStack source = context.getSource();
		OceanCanvasProtectedData data = OceanCanvasProtectedData.get(source.getLevel());

		long chunksPerSide = (long) Math.ceil(config.canvasSize() / 16.0);
		long totalChunks = chunksPerSide * chunksPerSide;
		double percent = totalChunks == 0 ? 0.0 : (data.flattenedChunkCount() * 100.0) / totalChunks;

		java.util.List<OceanCanvasPlayerZones.Zone> zones = OceanCanvasPlayerZones.get(source.getLevel()).all();
		long activeZones = zones.stream().filter(OceanCanvasPlayerZones.Zone::protectedNow).count();

		source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
				"Ocean Canvas stats - ~%,d / %,d chunks flattened (%.2f%%, approximate), "
						+ "%d shipwreck(s) protected (%d relocated + spawn shipwreck placed: %s), "
						+ "%d player zone(s) defined (%d currently protected)",
				data.flattenedChunkCount(), totalChunks, percent,
				data.protectedRegionCount(), data.relocatedShipwreckCount(),
				data.isGuaranteedShipwreckPlaced() ? "yes" : "no",
				zones.size(), activeZones
		)), false);

		return 1;
	}
}
