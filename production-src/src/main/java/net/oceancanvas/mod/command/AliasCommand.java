package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Registers {@code /oc} as a shorthand alias for {@code /oceancanvas} -
 * the "command aliasing" brainstormed quality-of-life idea, drafted per
 * the user's explicit request this round.
 *
 * <p><b>A Brigadier {@code redirect}, not a second, parallel command tree
 * with its own copy of every subcommand.</b> {@code redirect} is
 * Brigadier's own standard, well-established mechanism for exactly this -
 * vanilla Minecraft itself uses it for things like {@code /?} redirecting
 * to {@code /help}. Redirecting {@code oc} straight at the ALREADY-fully-
 * built {@code oceancanvas} node means every subcommand this project has
 * (and every one added in the future) is automatically available under
 * {@code /oc} too, with zero duplicated registration code and zero risk of
 * the alias silently drifting out of sync with the real command tree.</p>
 *
 * <p><b>Must be registered LAST, after every other command class's own
 * {@code register(dispatcher)} call</b> - see {@code OceanCanvas#onInitialize}'s
 * registration order. A redirect needs the target node to already exist at
 * the moment it's created; registering this before the other command
 * classes would redirect to an {@code oceancanvas} node that doesn't have
 * any of their subcommands attached yet (Brigadier's registration calls
 * are what actually attach each subtree - see {@link PregenCommand}'s
 * class doc for how multiple classes registering under the same
 * {@code oceancanvas} literal merge into one tree; that merging is exactly
 * what needs to have already happened before this redirect is created).</p>
 */
public final class AliasCommand {

	private AliasCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		CommandNode<CommandSourceStack> canvasRoot = dispatcher.getRoot().getChild("oceancanvas");
		if (canvasRoot == null) {
			// Defensive only - would mean this was registered before any
			// other oceancanvas command class, which OceanCanvas#onInitialize's
			// ordering is written to prevent. Fails soft (no /oc alias)
			// rather than throwing and breaking every other command's
			// registration in the same CommandRegistrationCallback.
			return;
		}
		dispatcher.register(Commands.literal("oc").redirect(canvasRoot));
	}
}
