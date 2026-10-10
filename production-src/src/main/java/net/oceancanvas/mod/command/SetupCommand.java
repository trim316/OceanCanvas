package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
// me.lucko.fabric.api.permissions.v0, not net.fabricmc.fabric.api.permission.v1
// - see BoundaryCommand's import for the full story of this real,
// user-confirmed fix.
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /oceancanvas setup} - the "guided setup wizard" brainstormed
 * quality-of-life idea, drafted per the user's explicit request this
 * round, deliberately scoped down from its literal brainstormed wording.
 *
 * <p><b>Why a checklist/report command, not a real interactive multi-turn
 * wizard - a scope decision, not a shortfall.</b> A true "wizard" implies
 * a stateful, guided back-and-forth (ask a question, wait for an answer,
 * ask the next one) - Minecraft's command UI has no good primitive for
 * that (no persistent per-player conversation state across separate
 * command invocations without real complexity: tracking an in-progress
 * "session" per player, timing it out, handling them running an unrelated
 * command mid-wizard, etc., for a payoff that doesn't clearly beat just
 * reading a report). What real value the idea has - "help a new user get
 * the mod configured sensibly without reading every field's doc comment in
 * {@code oceancanvas.properties}" - is fully captured by a single-shot
 * report that shows current settings alongside a plain-language
 * recommendation for anything worth a second look, which is what this
 * command actually is.</p>
 *
 * <p>Deliberately read-only, like {@link StatusCommand} - never changes a
 * single setting itself, only tells you what to consider changing and
 * how (the exact command or config line to use), the same "message, not
 * automatic action" posture every other advisory-only feature in this
 * project uses.</p>
 */
public final class SetupCommand {

	private SetupCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("setup")
								// Op-only, matching /oceancanvas admin - the
								// recommendations here are server-tuning advice
								// (permission-gated commands, throttle values),
								// not something a regular player needs.
								.requires(Permissions.require("oceancanvas.setup", 2))
								.executes(SetupCommand::run))
		);
	}

	private static int run(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		OceanCanvasConfig config = OceanCanvasConfig.get();
		List<String> recommendations = new ArrayList<>();

		if (!config.pregenEnabled()) {
			recommendations.add("pregenEnabled is off - /oceancanvas pregen/reset/expand's actual carving is "
					+ "disabled (the canvas still forms normally as you explore, just not proactively). Set "
					+ "pregenEnabled=true in config/oceancanvas.properties if you want to pre-flatten large "
					+ "areas ahead of time instead of seeing the live conversion while exploring.");
		}
		if (!config.backupEnabled()) {
			recommendations.add("backupEnabled is off - large /oceancanvas reset/expand jobs will NOT be "
					+ "automatically backed up first. Recommended to leave this on (it's on by default) unless "
					+ "you have your own backup process you trust more.");
		}
		if (config.canvasSize() > 20_000 && config.pregenEnabled()) {
			recommendations.add("Your canvas (" + config.canvasSize() + "x" + config.canvasSize() + ") is larger "
					+ "than the default 20,000x20,000 - consider raising pregenChunksPerTick if pregen jobs feel "
					+ "slow, or run them in smaller radius batches instead of one huge one.");
		}
		if (config.worldBorderSyncEnabled()) {
			recommendations.add("worldBorderSyncEnabled is on - vanilla's real world border is being kept synced "
					+ "to the canvas edge, which PHYSICALLY blocks crossing it (unlike the default behavior of "
					+ "plain vanilla terrain past the edge). This is intentional if you set it - just confirming, "
					+ "since it's off by default for exactly this reason.");
		}
		if (config.hudEnabled()) {
			recommendations.add("hudEnabled is on, but note it currently has no effect - the boundary HUD class "
					+ "is excluded from the default client build (see build.gradle) until its rendering hook is "
					+ "confirmed against a real build.");
		}
		if (config.undoDepthPerPlayer() <= 1) {
			recommendations.add("undoDepthPerPlayer is " + config.undoDepthPerPlayer() + " - only the single most "
					+ "recent reset per player is undoable. Consider raising it if you want more of a safety net "
					+ "for accidental resets.");
		}

		StringBuilder sb = new StringBuilder();
		sb.append(String.format(java.util.Locale.ROOT,
				"Canvas: %dx%d centered at (%d, %d)%n", config.canvasSize(), config.canvasSize(),
				config.centerX(), config.centerZ()));
		sb.append("pregen/backup/expansion: pregenEnabled=").append(config.pregenEnabled())
				.append(", backupEnabled=").append(config.backupEnabled())
				.append(", expansionEnabled=").append(config.expansionEnabled()).append('\n');
		sb.append("Throttles: pregenChunksPerTick=").append(config.pregenChunksPerTick())
				.append(", flattenerChunksPerTick=").append(config.flattenerChunksPerTick()).append('\n');

		if (recommendations.isEmpty()) {
			sb.append("No specific recommendations - current settings look reasonable for typical use.");
		} else {
			sb.append("Recommendations:");
			for (String rec : recommendations) {
				sb.append("\n  - ").append(rec);
			}
		}

		String report = sb.toString();
		// Localization foundation (drafted this round) - see
		// AdminCommand#run's matching comment and docs/roadmap.md's
		// "localization" note for the full scope decision.
		source.sendSuccess(() -> Component.translatable("oceancanvas.dashboard.setup.header")
				.append(Component.literal("\n" + report)), false);
		return 1;
	}
}
