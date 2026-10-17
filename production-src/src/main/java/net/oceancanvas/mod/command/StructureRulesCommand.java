package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.pregen.PregenManager;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureKind;

import java.util.List;
import java.util.Locale;

/**
 * {@code /oceancanvas structurerules set|clear|show} - v121 follow-up
 * request: "you need to be able to set structure rules before pregen,
 * expand, and rewipe". A drawn region has always been able to carry its
 * own structure rules (see {@code /oceancanvas protect structure} and
 * {@link OceanCanvasPlayerZones}), and {@code region-pregen}/{@code
 * region-rewipe} already capture those region rules before terrain work begins;
 * Region Pregen itself now protects the region only after physical completion.
 * What was actually missing was any way to set a
 * structure rule for a PLAIN, radius-based {@code /oceancanvas pregen
 * start}/{@code rewipe}/{@code expand} - those never touch a region at
 * all, so they had no rule-setting mechanism whatsoever.
 *
 * <p>This command fixes that with a small staging area, not a new rule
 * system: {@code set} stages a rule, {@code show} reports what's staged,
 * and the very next {@code pregen start}/{@code rewipe}/{@code expand}
 * captures whatever is staged at that moment as an immutable, per-job rule
 * set (see {@code PregenManager.Job#structureRules}'s doc) - deliberately
 * "set it, then run the operation", the exact workflow the follow-up
 * request describes, rather than a new command-line argument bolted onto
 * three already-long command trees. Staged rules are NOT auto-cleared
 * after a run, so the same rule set can be reused for a rewipe immediately
 * followed by a pregen without re-typing it - {@code clear} is there for
 * when that's not what you want.</p>
 *
 * <p><b>Deliberately in-memory only, like {@link PregenManager}'s own
 * {@code activeJob}</b> - see {@code PregenManager#pendingStructureRules}'s
 * doc for why this is a staging area and not a durable setting.</p>
 */
public final class StructureRulesCommand {

	private StructureRulesCommand() {
	}

	private static final SuggestionProvider<CommandSourceStack> SUGGEST_STRUCTURE_KINDS = (context, builder) ->
			SharedSuggestionProvider.suggest(
					java.util.Arrays.stream(OceanCanvasStructureKind.values()).map(OceanCanvasStructureKind::id),
					builder);

	private static final SuggestionProvider<CommandSourceStack> SUGGEST_OVERRIDE_VALUES = (context, builder) ->
			SharedSuggestionProvider.suggest(List.of("inherit", "force_on", "force_off"), builder);

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("structurerules")
								// Same op-only rationale as pregen/rewipe/expand themselves -
								// this changes what those operations do to structures.
								.requires(Permissions.require("oceancanvas.structurerules", 2))
								.then(Commands.literal("set")
										.then(Commands.argument("kind", StringArgumentType.word())
												.suggests(SUGGEST_STRUCTURE_KINDS)
												.then(Commands.argument("value", StringArgumentType.word())
														.suggests(SUGGEST_OVERRIDE_VALUES)
														.executes(StructureRulesCommand::set))))
								.then(Commands.literal("clear").executes(StructureRulesCommand::clear))
								.then(Commands.literal("show").executes(StructureRulesCommand::show)))
		);
	}

	private static int set(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String rawKind = StringArgumentType.getString(context, "kind");
		String rawValue = StringArgumentType.getString(context, "value");

		OceanCanvasStructureKind kind = OceanCanvasStructureKind.byId(rawKind);
		if (kind == null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Unknown structure kind '" + rawKind
					+ "'. Known kinds: " + knownKinds() + "."), false);
			return 0;
		}

		net.oceancanvas.mod.config.StructureOverride override;
		try {
			override = net.oceancanvas.mod.config.StructureOverride.valueOf(rawValue.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			source.sendSuccess(() -> Component.literal(
					"[Ocean Canvas] Unrecognized value '" + rawValue + "' - use inherit, force_on, or force_off."), false);
			return 0;
		}

		String message = PregenManager.setPendingStructureRule(kind, override);
		// Broadcast - this silently changes what the NEXT pregen/rewipe/expand
		// on this server does, worth every op seeing, same reasoning as
		// ProtectCommand's own per-region structure rule command.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), true);
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String message = PregenManager.clearPendingStructureRules();
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + message), true);
		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		// Never broadcast - a pure read, same reasoning as pregen/rewipe/expand's own status/dryrun.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + PregenManager.describePendingStructureRules()), false);
		return 1;
	}

	private static String knownKinds() {
		StringBuilder builder = new StringBuilder();
		for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(kind.id());
		}
		return builder.toString();
	}
}
