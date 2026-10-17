package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.oceancanvas.mod.config.OceanCanvasConfig;

/**
 * {@code /oceancanvas worldborder on|off|status} - the "visual boundary"
 * half of this round's "Visual boundary + tab-complete" follow-up request.
 * Syncs vanilla's real {@link WorldBorder} (the actual client-rendered
 * red-and-blue barrier, plus its warning/damage behavior) to the canvas's
 * configured center/size, so the edge of the ocean is visible rather than
 * something you only notice by sailing past it into ordinary terrain.
 *
 * <p><b>Strictly opt-in - never applied automatically.</b> A finite-size
 * {@code WorldBorder} doesn't just render a line, it PHYSICALLY BLOCKS
 * crossing it (pushes entities back, deals damage past the warning zone).
 * That directly contradicts the already-shipped M1 decision that sailing
 * past the canvas edge should surface plain, ordinary vanilla terrain -
 * so this mod never turns the border on by itself. It's a deliberate,
 * explicit choice an op makes with this command (or the persisted
 * {@code worldBorderSyncEnabled} config flag it drives - see
 * {@link OceanCanvasConfig}'s field doc), never a default.</p>
 *
 * <p><b>Overworld only</b> - matches every other canvas-bounds check in
 * this mod ({@code isInsideCanvas}, the flattener, the biome masker), all
 * of which are hardcoded to {@code Level.OVERWORLD}. There's no per-canvas
 * concept in another dimension for the border to represent.</p>
 *
 * <p><b>Re-applied automatically on server start and after a successful
 * {@code /oceancanvas expand}, but only while the flag is on</b> - see the
 * {@code SERVER_STARTED} hook in {@code OceanCanvas} and the call from
 * {@code ExpandCommand}. Without that, turning this on would silently stop
 * matching the canvas the moment the server restarts or the canvas grows,
 * which defeats the entire point of a *visual* boundary.</p>
 *
 * <p><b>"off" doesn't delete the border, it just makes it effectively
 * unbounded</b> - vanilla has no real "no border" state, so this sets the
 * size to a very large literal (6.0E7 blocks, comfortably past vanilla's
 * own hard world limit in every direction) rather than referencing any
 * vanilla "default size" constant, whose exact name/value across versions
 * isn't something worth guessing at. At that size the border is never
 * reachable during normal play, which is functionally "off".</p>
 */
public final class BoundaryCommand {

	// See the class doc's "off" paragraph for why this is a literal
	// rather than a referenced vanilla constant.
	private static final double DISABLED_BORDER_SIZE = 6.0E7;

	private BoundaryCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("worldborder")
								.then(Commands.literal("on")
										// Op-only - this changes real, immediately-felt
										// gameplay (players can suddenly be pushed/damaged
										// at the edge), not just a cosmetic toggle.
										.requires(Permissions.require("oceancanvas.worldborder", 2))
										.executes(ctx -> setEnabled(ctx, true)))
								.then(Commands.literal("off")
										.requires(Permissions.require("oceancanvas.worldborder", 2))
										.executes(ctx -> setEnabled(ctx, false)))
								.then(Commands.literal("status")
										// Read-only, informational - same rationale as
										// /oceancanvas status having no permission gate.
										.executes(BoundaryCommand::status)))
		);
	}

	private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
		CommandSourceStack source = context.getSource();
		OceanCanvasConfig updated = OceanCanvasConfig.get().toBuilder().worldBorderSyncEnabled(enabled).save();
		apply(source.getServer().overworld(), updated);

		String message = enabled
				? "[Ocean Canvas] World border sync enabled - vanilla's world border now matches the canvas ("
						+ updated.canvasSize() + "x" + updated.canvasSize() + " blocks, centered at ("
						+ updated.centerX() + ", " + updated.centerZ() + "). Players will be physically blocked "
						+ "from crossing it, including anyone already past the edge - see \"/oceancanvas "
						+ "worldborder off\" to revert."
				: "[Ocean Canvas] World border sync disabled - the border has been pushed back out to "
						+ (long) DISABLED_BORDER_SIZE + " blocks (effectively unreachable), matching this mod's "
						+ "usual \"sail past the canvas edge into ordinary terrain\" behavior.";

		// Broadcast - this changes real, immediately-felt world state for
		// every player on the server, same "other ops should see it
		// happened" reasoning as reset/expand/pregen's own broadcasts.
		source.sendSuccess(() -> Component.literal(message), true);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		OceanCanvasConfig config = OceanCanvasConfig.get();
		WorldBorder border = source.getServer().overworld().getWorldBorder();

		String message = "[Ocean Canvas] World border sync is " + (config.worldBorderSyncEnabled() ? "ON" : "OFF")
				+ " (config). Live border right now: size " + (long) border.getSize() + " blocks, centered at ("
				+ (long) border.getCenterX() + ", " + (long) border.getCenterZ() + "). Toggle with "
				+ "\"/oceancanvas worldborder on\" or \"off\".";

		// Never broadcast - purely informational, doesn't change anything.
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	/**
	 * Actually pushes the config's current state onto the live vanilla
	 * border. Package-private and static rather than folded into {@code
	 * setEnabled} so {@code OceanCanvas}'s server-start hook and {@code
	 * ExpandCommand} can both re-apply the already-persisted flag/bounds
	 * without going through a command context that doesn't exist yet (at
	 * server start) or that isn't this command's own (from expand).
	 */
	public static void apply(ServerLevel overworld, OceanCanvasConfig config) {
		WorldBorder border = overworld.getWorldBorder();
		if (config.worldBorderSyncEnabled()) {
			// +0.5 to center on the block, not its negative-X/Z corner -
			// matches how canvas bounds are already reasoned about
			// elsewhere (isInsideCanvas treats centerX/centerZ as the
			// block coordinate, not a corner).
			border.setCenter(config.centerX() + 0.5, config.centerZ() + 0.5);
			border.setSize(config.canvasSize());
		} else {
			border.setSize(DISABLED_BORDER_SIZE);
		}
	}
}
