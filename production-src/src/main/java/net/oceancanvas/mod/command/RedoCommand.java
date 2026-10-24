package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.oceancanvas.mod.pregen.OceanCanvasUndoManager;

import java.util.List;

/**
 * {@code /oceancanvas redo} (plus {@code /oceancanvas redo list}) - the
 * "redo" half of this round's "Progress UX + redo" follow-up request,
 * the natural companion to {@link UndoCommand}. Re-applies the calling
 * player's single most recently undone {@code /oceancanvas reset}. See
 * {@link OceanCanvasUndoManager}'s class doc for why this re-runs the
 * actual reset job over its original center/radius rather than replaying
 * a second recorded "after" snapshot (never captured in the first place -
 * {@code flattenChunk}'s own idempotence makes that unnecessary).
 *
 * <p>Same strict last-in-first-out shape as undo, same reasoning: redo
 * always reapplies whichever operation you most recently undid, then
 * removes it from the redo stack - no "redo a specific numbered entry"
 * support, for the identical reason {@link UndoCommand}'s doc gives for
 * undo (reset operations aren't independent of each other, so picking
 * one out of order could interact oddly with whatever's happened to the
 * world since).</p>
 */
public final class RedoCommand {

	private RedoCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("redo")
								// Same op-only rationale as undo/reset - this
								// re-runs a real, potentially large carving
								// job, not just a cheap lookup.
								.requires(Permissions.require("oceancanvas.redo", 2))
								.executes(RedoCommand::redo)
								.then(Commands.literal("list").executes(RedoCommand::list)))
		);
	}

	private static int redo(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Redo is per-player - run this as a "
					+ "player, not from the console/a command block."), false);
			return 0;
		}

		String message = OceanCanvasUndoManager.redoMostRecent(player);
		// Broadcast - this re-runs a real reset job (destroys whatever's
		// now sitting in that area on purpose, same as reset itself),
		// same "other ops should see it happened" reasoning as reset's
		// own broadcast.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), true);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Redo is per-player - run this as a "
					+ "player, not from the console/a command block."), false);
			return 0;
		}

		List<OceanCanvasUndoManager.UndoOperation> history = OceanCanvasUndoManager.redoHistory(player.getUUID());
		if (history.isEmpty()) {
			// Never broadcast - purely informational, doesn't change anything.
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Nothing to redo - undo something first "
					+ "with \"/oceancanvas undo\"."), false);
			return 1;
		}

		StringBuilder sb = new StringBuilder("[Ocean Canvas] Your redo-able (undone) operations (most recent "
				+ "first, run \"/oceancanvas redo\" to reapply #1):");
		int i = 1;
		for (OceanCanvasUndoManager.UndoOperation op : history) {
			sb.append("\n  ").append(i++).append(". ").append(op.description())
					.append(" - ").append(op.recordCount()).append(" block(s) recorded");
		}
		String message = sb.toString();
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}
}
