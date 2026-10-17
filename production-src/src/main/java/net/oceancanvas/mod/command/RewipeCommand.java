package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.oceancanvas.mod.pregen.PregenManager;

/**
 * {@code /oceancanvas rewipe <radiusBlocks> [confirm]} - the "minimal
 * correction tool" brainstormed idea: re-carve a small region back to
 * the canonical canvas state, undoing a bad manual edit, without a full
 * WorldEdit/FAWE install. See {@link PregenManager#rewipe}'s doc comment
 * for why this is deliberately the exact same underlying mechanism as
 * {@code /oceancanvas pregen}, not a new carving path.
 *
 * <p>Deliberately no bare {@code /oceancanvas rewipe} (no default
 * radius, unlike pregen's) - this command is strictly more destructive
 * than pregen even for a small area (it tears down player builds on
 * purpose), so requiring an explicit, deliberate radius every time is
 * worth the small extra typing.</p>
 *
 * <p>{@code /oceancanvas undo} (see {@link
 * net.oceancanvas.mod.pregen.OceanCanvasUndoManager}) can restore what a
 * mistaken rewipe destroyed, within real limits (small operations, run by
 * a player, undone before a restart) - it doesn't change the "treat this
 * as effectively permanent" framing of the confirmation prompt below, see
 * {@link PregenManager#rewipe}'s doc for why that stays conservative.</p>
 *
 * <p><b>{@code dryrun} (drafted this round)</b> - the same real
 * inconsistency noticed while reviewing this project for further work:
 * {@code /oceancanvas pregen} has had a dry-run preview since Round 2,
 * but {@code rewipe} - the strictly MORE destructive of the two - never
 * got the equivalent. Reuses {@link PregenManager#preview} exactly as
 * {@code PregenCommand}'s own {@code dryrun} does (same chunk-count/
 * tick-estimate math, since a rewipe job and a pregen job clip/walk the
 * exact same region shape), with an extra sentence up front making the
 * destructive intent explicit - {@code preview}'s own wording is
 * deliberately neutral ("would submit N chunks"), written for pregen's
 * "touches only untouched terrain" case, which undersells what a reset
 * dry-run specifically needs to warn about.</p>
 */
public final class RewipeCommand {

	private RewipeCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("rewipe")
								// Same op-only rationale as pregen - see
								// PregenCommand's doc comment - except this is
								// even more consequential, since it deliberately
								// destroys player builds rather than only
								// untouched terrain. Covers dryrun too, same
								// "kept simple rather than a separate preview-only
								// permission tier" reasoning as PregenCommand.
								.requires(Permissions.require("oceancanvas.rewipe", 2))
								.then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(1))
										.executes(ctx -> rewipe(ctx, false))
										.then(Commands.literal("confirm")
												.executes(ctx -> rewipe(ctx, true)))
										.then(Commands.literal("dryrun")
												.executes(RewipeCommand::dryRun))))
		);
	}

	private static int rewipe(CommandContext<CommandSourceStack> context, boolean confirmed) {
		CommandSourceStack source = context.getSource();
		BlockPos pos = BlockPos.containing(source.getPosition());
		int radiusBlocks = IntegerArgumentType.getInteger(context, "radiusBlocks");
		ServerPlayer player = source.getPlayer(); // null for console/command-block sources - handled below

		String message = PregenManager.rewipe(source.getLevel(), pos.getX(), pos.getZ(), radiusBlocks, confirmed, player);
		// Broadcast to other ops, like vanilla /gamerule - this destroys
		// player builds on purpose, other admins should see it happened
		// even if they weren't the one who ran it. See PregenCommand#start's
		// comment for why an occasional no-op refusal gets broadcast too.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), true);
		return 1;
	}

	private static int dryRun(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		BlockPos pos = BlockPos.containing(source.getPosition());
		int radiusBlocks = IntegerArgumentType.getInteger(context, "radiusBlocks");

		// Never broadcast - a dry run never touches the world, same
		// reasoning as PregenCommand's own dryrun.
		String message = "Rewipe dry run - ANYTHING built in this region above the floor would be destroyed if "
				+ "you actually ran this. " + PregenManager.preview(source.getLevel(),pos.getX(), pos.getZ(), radiusBlocks,"REWIPE");
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), false);
		return 1;
	}
}
