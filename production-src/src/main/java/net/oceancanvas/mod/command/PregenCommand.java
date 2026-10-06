package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.oceancanvas.mod.pregen.PregenManager;
import net.oceancanvas.mod.project.OceanCanvasProjectData;

/** Command surface for bounded Ocean Canvas pregeneration. */
public final class PregenCommand {
	private PregenCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("pregen")
								.requires(Permissions.require("oceancanvas.pregen", 2))
								.then(Commands.literal("start")
										.executes(ctx -> start(ctx, DEFAULT_RADIUS_BLOCKS,
												DEFAULT_CENTER_X, DEFAULT_CENTER_Z, false))
										.then(Commands.argument("radius", IntegerArgumentType.integer(1))
												.executes(ctx -> start(ctx, radiusArg(ctx),
														DEFAULT_CENTER_X, DEFAULT_CENTER_Z, false))
												.then(Commands.literal("confirm")
														.executes(ctx -> start(ctx, radiusArg(ctx),
																DEFAULT_CENTER_X, DEFAULT_CENTER_Z, true)))
												.then(Commands.literal("dryrun")
														.executes(ctx -> dryRun(ctx,
																DEFAULT_CENTER_X, DEFAULT_CENTER_Z)))
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
								.then(Commands.literal("profile")
										.executes(PregenCommand::profileStatus)
										.then(Commands.argument("profile", StringArgumentType.word())
												.suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
														java.util.List.of("quiet", "balanced", "overnight", "custom"), builder))
												.executes(PregenCommand::setProfile)))
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
		ServerPlayer player = source.getPlayer();
		String message = PregenManager.start(source.getLevel(), centerX, centerZ, radiusBlocks, confirmed, player);
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), true);
		return 1;
	}

	private static int dryRun(CommandContext<CommandSourceStack> context, int centerX, int centerZ) {
		CommandSourceStack source = context.getSource();
		int radiusBlocks = radiusArg(context);
		String message = PregenManager.preview(source.getLevel(), centerX, centerZ, radiusBlocks, "PREGEN");
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), false);
		return 1;
	}

	private static int profileStatus(CommandContext<CommandSourceStack> context) {
		OceanCanvasProjectData.PregenProfile profile = OceanCanvasProjectData.get(context.getSource().getLevel()).pregenProfile();
		context.getSource().sendSuccess(() -> Component.literal("[Ocean Canvas] Pregen profile: " + profile.name() + "."), false);
		return 1;
	}

	private static int setProfile(CommandContext<CommandSourceStack> context) {
		String raw = StringArgumentType.getString(context, "profile");
		OceanCanvasProjectData.PregenProfile profile;
		try {
			profile = OceanCanvasProjectData.PregenProfile.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			context.getSource().sendFailure(Component.literal("[Ocean Canvas] Unknown Pregen profile '" + raw
					+ "'. Use quiet, balanced, overnight, or custom."));
			return 0;
		}
		OceanCanvasProjectData.get(context.getSource().getLevel()).setPregenProfile(profile);
		context.getSource().sendSuccess(() -> Component.literal("[Ocean Canvas] Pregen profile: " + profile.name() + "."), true);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(() -> Component.literal("[Ocean Canvas] " + PregenManager.status()), false);
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> context) {
		String ownership = net.oceancanvas.mod.project.OceanCanvasOperationOwnership.controlBlockReason(context.getSource());
		if (!ownership.isBlank()) {
			context.getSource().sendFailure(Component.literal("[Ocean Canvas] " + ownership));
			return 0;
		}
		context.getSource().sendSuccess(() -> Component.literal("[Ocean Canvas] " + PregenManager.cancel(context.getSource().getLevel())), true);
		return 1;
	}
}
