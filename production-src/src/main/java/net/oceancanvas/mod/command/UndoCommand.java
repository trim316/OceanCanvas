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
 * {@code /oceancanvas undo} (plus {@code /oceancanvas undo list}) - undoes
 * the CALLING PLAYER's own most recent {@code /oceancanvas reset}, the
 * "undo... reset" half of this round's follow-up request (alongside job
 * persistence and the broadcast tuning across the other command classes).
 * See {@link OceanCanvasUndoManager}'s class doc for the full design and,
 * importantly, its honestly-documented limitations - worth reading before
 * relying on this for anything valuable.
 *
 * <p><b>Deliberately per-CALLER, not per-target-area.</b> Undo always
 * means "undo MY last reset", not "undo whatever the last reset anywhere
 * on the server was" - simpler to reason about (no need to pick which
 * area/whose operation), and matches the realistic use case (you just ran
 * a reset yourself and immediately realize it was a mistake).</p>
 *
 * <p><b>No "undo a specific numbered entry" support - {@code undo list}
 * is read-only.</b> {@code /oceancanvas undo} always undoes whichever
 * operation is currently most recent in your history, then removes it -
 * so running it twice in a row naturally reaches the one before it,
 * walking backward through history in order. Adding a "pick number 2"
 * option would let you undo an OLDER operation while a NEWER one (that
 * you're choosing to keep) is still sitting on top of the world, which
 * only makes sense if operations are independent of each other - reset
 * jobs generally aren't (a later reset can easily touch ground an earlier
 * one already changed), so out-of-order undo would often produce a
 * confusing, partially-inconsistent result. Strict last-in-first-out
 * avoids that entirely for a real, if narrower, correctness reason - not
 * just missing polish.</p>
 *
 * <p>See {@link RedoCommand} for the companion {@code /oceancanvas redo} -
 * every successful undo becomes redo-able immediately.</p>
 */
public final class UndoCommand {

	private UndoCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("undo")
								// Same op-only rationale as reset itself -
								// undo restores world state at real
								// server cost (bounded, but still real
								// chunk force-loading/block writes over
								// several ticks).
								.requires(Permissions.require("oceancanvas.undo", 2))
								.executes(UndoCommand::undo)
								.then(Commands.literal("list").executes(UndoCommand::list)))
		);
	}

	private static int undo(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Undo is per-player - run this as a "
					+ "player, not from the console/a command block."), false);
			return 0;
		}

		String message = OceanCanvasUndoManager.undoMostRecent(player.getUUID());
		// Broadcast - this restores world state (or reports why it
		// couldn't), same "other ops should see it happened" reasoning as
		// reset/pregen/expand's own broadcast.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), true);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Undo is per-player - run this as a "
					+ "player, not from the console/a command block."), false);
			return 0;
		}

		List<OceanCanvasUndoManager.UndoOperation> history = OceanCanvasUndoManager.history(player.getUUID());
		if (history.isEmpty()) {
			// Never broadcast - purely informational, doesn't change anything.
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] No undoable operations right now - run "
					+ "\"/oceancanvas reset\" first."), false);
			return 1;
		}

		StringBuilder sb = new StringBuilder("[Ocean Canvas] Your undoable operations (most recent first, run "
				+ "\"/oceancanvas undo\" to undo #1):");
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
