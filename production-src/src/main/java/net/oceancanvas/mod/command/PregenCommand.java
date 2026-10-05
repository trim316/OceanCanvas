package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.oceancanvas.mod.pregen.PregenManager;

/**
 * {@code /oceancanvas pregen start|status|cancel} (plus {@code dryrun}/
 * {@code confirm} modifiers on {@code start}) - the command surface for
 * the drafted, off-by-default Chunky-like pre-generation feature. See
 * {@link PregenManager}'s class doc for the actual design; this class is
 * intentionally thin (argument parsing and reporting only).
 *
 * <p>Registered as a separate {@code oceancanvas} root alongside {@link
 * StatusCommand}'s - Brigadier merges multiple registrations of the same
 * literal name's children into one command tree (standard, well-
 * established Brigadier behavior used by many mods to let unrelated
 * classes each own their own subcommand without a shared registration
 * method), so {@code /oceancanvas status}, {@code /oceancanvas here}, and
 * {@code /oceancanvas pregen ...} all end up under the one command as
 * expected, with no coupling between the two classes.</p>
 */
public final class PregenCommand {

	private PregenCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("pregen")
								.requires(Permissions.require("oceancanvas.pregen", 2))
								.then(Commands.literal("start")
										// Keep a small bare-start convenience, but otherwise let
										// Brigadier's normal <argument-name> hints explain syntax.
										.executes(ctx -> start(ctx, DEFAULT_RADIUS_BLOCKS,
												DEFAULT_CENTER_X, DEFAULT_CENTER_Z, false))
										.then(Commands.argument("radius", IntegerArgumentType.integer(1))
												// Radius only => default center 0,0.
												.executes(ctx -> start(ctx, radiusArg(ctx),
														DEFAULT_CENTER_X, DEFAULT_CENTER_Z, false))
												.then(Commands.literal("confirm")
														.executes(ctx -> start(ctx, radiusArg(ctx),
																DEFAULT_CENTER_X, DEFAULT_CENTER_Z, true)))
												.then(Commands.literal("dryrun")
														.executes(ctx -> dryRun(ctx,
																DEFAULT_CENTER_X, DEFAULT_CENTER_Z)))
												// Standard Minecraft-style syntax:
												// /oceancanvas pregen start <radius> <x-coord> <z-coord> confirm
												.then(Commands.argument("x-coord", IntegerArgumentType.integer())
														.then(Commands.argument("z-coord", IntegerArgumentType.integer())
																.executes(ctx -> start(ctx, radiusArg(ctx),
																		centerXArg(ctx), centerZArg(ctx), false))
																.then(Commands.literal("confirm")
																		.executes(ctx -> start(ctx, radiusArg(ctx),
																				centerXArg(ctx), centerZArg(ctx), true)))
																.then(Commands.literal("dryrun")
																		.executes(ctx -> dryRun(ctx,
																				centerXArg(ctx), centerZArg(ctx))))))))
								.then(Commands.literal("status").executes(PregenCommand::status))
								.then(Commands.literal("cancel").executes(PregenCommand::cancel)))
		);
	}

	private static final int DEFAULT_RADIUS_BLOCKS = 256;
	private static final int DEFAULT_CENTER_X = 0;
	private static final int DEFAULT_CENTER_Z = 0;

	private static int radiusArg(CommandContext<CommandSourceStack> context) {
		return IntegerArgumentType.getInteger(context, "radius");
	}

	private static int centerXArg(CommandContext<CommandSourceStack> context) {
		return IntegerArgumentType.getInteger(context, "x-coord");
	}

	private static int centerZArg(CommandContext<CommandSourceStack> context) {
		return IntegerArgumentType.getInteger(context, "z-coord");
	}

	private static int start(CommandContext<CommandSourceStack> context, int radiusBlocks,
			int centerX, int centerZ, boolean confirmed) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer(); // null for console/command-block sources - handled below

		String message = PregenManager.start(source.getLevel(), centerX, centerZ, radiusBlocks, confirmed, player);
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), true);
		return 1;
	}

	private static int dryRun(CommandContext<CommandSourceStack> context, int centerX, int centerZ) {
		CommandSourceStack source = context.getSource();
		int radiusBlocks = radiusArg(context);
		String message = PregenManager.preview(source.getLevel(),centerX,centerZ,radiusBlocks,"PREGEN");
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(() -> Component.literal("[Ocean Canvas] " + PregenManager.status()), false);
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> context) {
		String ownership=net.oceancanvas.mod.project.OceanCanvasOperationOwnership.controlBlockReason(context.getSource());
		if(!ownership.isBlank()){context.getSource().sendFailure(Component.literal("[Ocean Canvas] "+ownership));return 0;}
		context.getSource().sendSuccess(() -> Component.literal("[Ocean Canvas] " + PregenManager.cancel(context.getSource().getLevel())), true);
		return 1;
	}
}
