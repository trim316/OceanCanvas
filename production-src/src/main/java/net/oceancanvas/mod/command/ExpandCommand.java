package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.pregen.PregenManager;

/**
 * {@code /oceancanvas expand <newRadiusChunks> [confirm]} - the
 * "guided expansion command" brainstormed idea, now actually designed
 * and drafted rather than deferred, per the user's explicit follow-up
 * request: grow the canvas and safely handle the boundary in one step,
 * with player-defined {@link net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones}
 * ({@code /oceancanvas protect}) as the actual "safe override" mechanism
 * for anything already built near the old edge.
 *
 * <p><b>Grow-only, never shrink - explicit, permanent design decision,
 * not a v1 gap.</b> Shrinking would mean chunks that used to be inside
 * the canvas (real ocean floor, guaranteed structures, whatever a player
 * built) suddenly reporting as outside it via {@code isInsideCanvas()},
 * with no code anywhere in this project that reverts flattened terrain
 * back to natural generation - the result would be stale ocean sitting
 * past the new "edge", contradicting the documented M1 boundary decision
 * ("past the edge, chunks are left as plain, ordinary vanilla terrain").
 * Rejected outright with a clear error rather than silently doing
 * something half-correct.</p>
 *
 * <p><b>Centered on the canvas's own configured center, not wherever the
 * command is run from</b> - the one meaningful difference from {@code
 * /oceancanvas pregen}/{@code reset}, which are both deliberately
 * centered on the source's position instead. Expansion is a property of
 * the whole canvas, not of where an op happens to be standing.</p>
 *
 * <p><b>Always requires {@code confirm}, no threshold</b> - same
 * reasoning as {@code /oceancanvas reset}: this permanently changes
 * {@code canvasSize} in {@code oceancanvas.properties} (not undoable by
 * this mod - shrinking back is refused, see above), so a "just try it"
 * default would be a real hazard on any canvas size.</p>
 *
 * <p><b>{@code dryrun} (drafted this round)</b> - previously, the only
 * preview was the unconfirmed-attempt message above, which shows the
 * resulting canvas size but never the actual ring chunk count/time
 * estimate {@code pregen}/{@code reset}'s own dry-run modes report. Adds
 * an explicit {@code dryrun} keyword for parity with those two, backed by
 * {@link PregenManager#expandPreview}.</p>
 */
public final class ExpandCommand {

	private ExpandCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("expand")
								// Same op-only rationale as pregen/reset -
								// this permanently changes the canvas's
								// configured size and can force-load/carve
								// a very large new area.
								.requires(Permissions.require("oceancanvas.expand", 2))
								.then(Commands.argument("newRadiusChunks", IntegerArgumentType.integer(1))
										.executes(ctx -> expand(ctx, false))
										.then(Commands.literal("confirm")
												.executes(ctx -> expand(ctx, true)))
										.then(Commands.literal("dryrun")
												.executes(ExpandCommand::dryRun))))
		);
	}

	private static int dryRun(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int newRadiusChunks = IntegerArgumentType.getInteger(context, "newRadiusChunks");
		OceanCanvasConfig config = OceanCanvasConfig.get();

		// Never broadcast - a dry run never touches the config or the
		// world, same reasoning as pregen/reset's own dryrun.
		String message = PregenManager.expandPreview(config.radius(), config.centerX(), config.centerZ(), newRadiusChunks);
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), false);
		return 1;
	}

	private static int expand(CommandContext<CommandSourceStack> context, boolean confirmed) {
		CommandSourceStack source = context.getSource();
		int newRadiusChunks = IntegerArgumentType.getInteger(context, "newRadiusChunks");
		OceanCanvasConfig config = OceanCanvasConfig.get();

		int oldRadiusBlocks = config.radius();
		int newRadiusBlocks = newRadiusChunks * 16;

		if (newRadiusBlocks <= oldRadiusBlocks) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + newRadiusChunks + " chunks ("
					+ newRadiusBlocks + " blocks radius) isn't larger than the current canvas ("
					+ oldRadiusBlocks + " blocks radius, " + (oldRadiusBlocks / 16) + " chunks) - shrinking or "
					+ "leaving the canvas the same size isn't supported. See this command's doc comment for why "
					+ "shrinking specifically is a permanent design decision, not a missing feature."), false);
			return 0;
		}

		if (PregenManager.isRunning()) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] A pregen/reset/expand job is already "
					+ "running (" + PregenManager.status() + ") - wait for it to finish or run "
					+ "\"/oceancanvas pregen cancel\" first, so expand's own flattening pass doesn't start on "
					+ "top of it."), false);
			return 0;
		}

		int newCanvasSize = newRadiusBlocks * 2;
		if (!confirmed) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] This will grow the canvas from "
					+ config.canvasSize() + "x" + config.canvasSize() + " to " + newCanvasSize + "x" + newCanvasSize
					+ " blocks, centered on the same point (" + config.centerX() + ", " + config.centerZ() + "). "
					+ "The new area is respected by any protected zone (\"/oceancanvas protect list\") "
					+ "regardless of when it was created - protect anything you've already built near the old "
					+ "edge first if you haven't. This is NOT reversible by this mod (shrinking back isn't "
					+ "supported). Re-run as \"/oceancanvas expand " + newRadiusChunks + " confirm\" if you're sure."),
					false);
			return 0;
		}

		int minChunkX=Math.floorDiv(config.centerX()-newRadiusBlocks,16),maxChunkX=Math.floorDiv(config.centerX()+newRadiusBlocks-1,16);
		int minChunkZ=Math.floorDiv(config.centerZ()-newRadiusBlocks,16),maxChunkZ=Math.floorDiv(config.centerZ()+newRadiusBlocks-1,16);
		String foreverBlock=net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockRect(source.getLevel(),"EXPAND",minChunkX,maxChunkX,minChunkZ,maxChunkZ,"","","");
		if(!foreverBlock.isEmpty()){source.sendFailure(Component.literal("[Ocean Canvas] "+foreverBlock));return 0;}

		OceanCanvasConfig updated = config.toBuilder().canvasSize(newCanvasSize).save();
		reportExpanded(source, config, updated, oldRadiusBlocks, newRadiusChunks);
		return 1;
	}

	// Small helper purely to keep `expand` itself under a readable length -
	// not a meaningful abstraction boundary, just line-count hygiene.
	private static void reportExpanded(CommandSourceStack source, OceanCanvasConfig oldConfig,
			OceanCanvasConfig updated, int oldRadiusBlocks, int newRadiusChunks) {
		// Centered on the canvas's own center - see the class doc for why
		// this, unlike pregen/reset, deliberately ignores the command
		// source's actual position.
		String pregenMessage = PregenManager.expand(
				source.getLevel(), oldRadiusBlocks, updated.centerX(), updated.centerZ(),
				newRadiusChunks, true, source.getPlayer());

		// Re-sync the world border to the new bounds too, but only if it was
		// already being synced - BoundaryCommand.apply is a no-op push
		// (harmlessly re-sets the huge "disabled" size) when the flag is
		// off, so this is safe to call unconditionally rather than adding a
		// second worldBorderSyncEnabled() check here.
		BoundaryCommand.apply(source.getServer().overworld(), updated);

		// Broadcast, unlike the validation/confirmation messages above (which
		// haven't changed anything yet) - the config WAS just permanently
		// changed by this point, worth every op seeing regardless of who ran it.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] Canvas expanded: " + oldConfig.canvasSize() + "x"
				+ oldConfig.canvasSize() + " -> " + updated.canvasSize() + "x" + updated.canvasSize()
				+ " blocks. The new boundary is active immediately. " + pregenMessage), true);
	}
}
