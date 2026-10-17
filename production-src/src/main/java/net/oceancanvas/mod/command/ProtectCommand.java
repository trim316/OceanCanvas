package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.oceancanvas.mod.pregen.PregenManager;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureKind;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /oceancanvas protect pos1|pos2|create|duplicate|here|list|enable|
 * disable|remove|transfer|rename|precedence|structure|biome|color|mobs} - the user's explicit request: a safe, changeable-later way to
 * mark areas as protected (their own builds, a boundary region ahead of
 * a canvas expansion, a resource-gathering site they only need protected
 * temporarily) so the flattener/pregen/reset/expand never touch them,
 * with an on/off toggle rather than a one-way decision. See
 * {@link OceanCanvasPlayerZones}'s class doc for the actual persisted
 * data model and why player zones need different carving rules than the
 * existing shipwreck-protection mechanism.
 *
 * <p><b>Two ways to define a zone, on purpose - precise selection for a
 * real build, one-shot convenience for a quick area:</b></p>
 * <ul>
 *   <li>{@code pos1}/{@code pos2} capture your exact current position as
 *       one corner each (a WorldEdit-style two-point selection - the
 *       most familiar pattern for this kind of tool to anyone who's used
 *       WorldEdit/FAWE), then {@code create <name>} turns the two
 *       captured corners into a named zone. Gives full control over the
 *       vertical range too, not just the footprint - useful for
 *       protecting just a build's actual height rather than an entire
 *       column.</li>
 *   <li>{@code here <name> <radiusChunks>} is the one-command shortcut:
 *       a square region centered on your current position, sized in
 *       chunks (matching {@code /oceancanvas pregen}/{@code reset}'s own
 *       radius convention), spanning the FULL vertical build range - for
 *       "just protect everything around me right now" without needing a
 *       two-step selection first.</li>
 * </ul>
 *
 * <p><b>Selections ({@code pos1}/{@code pos2}) are in-memory only, per
 * player, not persisted</b> - deliberately: they're a transient UI aid
 * for building a zone, not data worth protecting across a restart the
 * way the zone itself is once created. Losing an in-progress selection
 * on restart (rare - who quits mid-selection) is a fully acceptable
 * tradeoff for not adding persistence complexity to something this
 * disposable.</p>
 *
 * <p><b>Permission level 2 (op-only) for the whole subtree, including
 * read-only {@code list}</b> - unlike {@code status}/{@code here}/
 * {@code stats}, which are safely read-only for any player. This is a
 * deliberate, conservative choice: on a shared server, protection status
 * controls what large destructive operations ({@code reset}, a canvas
 * {@code expand}) are allowed to touch, so letting any player define or
 * (worse) disable a zone would be a real griefing vector. This round
 * stays inside that same op-only boundary on purpose - see {@link
 * OceanCanvasPlayerZones}'s class doc for why "per-player zones" was
 * deliberately implemented as "per-OP-owned zones" instead of opened up
 * to every player.</p>
 *
 * <p><b>Zone ownership (drafted this round).</b> {@code create}/{@code
 * here} now record which op made a zone; {@code enable}/{@code disable}/
 * {@code remove} refuse to touch someone else's zone unless the requester
 * has permission level 3 (see {@link #canOverrideOwnership}) - so one op
 * can no longer silently disable/delete another op's protected build. A
 * zone with no recorded owner (console-created, or persisted from before
 * this feature shipped) stays manageable by any op, unchanged from
 * before - see {@link OceanCanvasPlayerZones}'s class doc for the full
 * reasoning.</p>
 *
 * <p><b>Particle-outline visual feedback (drafted this round, the
 * "selection visualization" half of the follow-up request alongside
 * undo/job persistence).</b> {@code pos1}/{@code pos2} flash a small
 * marker at the exact captured point; {@code create}/{@code here}/the new
 * {@code show} subcommand draw a one-shot wireframe outline of the actual
 * zone bounds, sent ONLY to the command's own player (never broadcast to
 * everyone nearby) via {@code ServerLevel#sendParticles(ServerPlayer,
 * ...)}. Deliberately a one-shot burst, not a persistent live overlay -
 * see {@link #outlineBounds}'s doc for why that's the right scope here,
 * and why the particle-count is explicitly bounded regardless of how
 * large a zone is.</p>
 */
public final class ProtectCommand {

	// Per-player, server-session only - selections are intentionally not persisted.
	// The containing SelectionState is owned by OceanCanvasServerRuntime, so integrated-server
	// switches cannot leak one world's in-progress corners into another.
	private record SelectionPoint(BlockPos pos, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {}
	private static final class SelectionState {
		final Map<UUID, SelectionPoint> pos1 = new HashMap<>();
		final Map<UUID, SelectionPoint> pos2 = new HashMap<>();
	}
	private static SelectionState selections(CommandSourceStack source) {
		return net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime.get(source.getServer())
				.state(SelectionState.class, SelectionState::new);
	}

	/**
	 * Tab-completion for the {@code name} argument, drafted this round
	 * alongside the world border - the "tab-complete" half of the "Visual
	 * boundary + tab-complete" follow-up request. Only wired onto {@code
	 * show}/{@code enable}/{@code disable}/{@code remove}, which all
	 * reference an EXISTING zone - deliberately NOT on {@code create}/
	 * {@code here}, whose {@code name} argument defines a brand-new name,
	 * where suggesting existing names would be actively misleading (it'd
	 * look like autocomplete is nudging you toward overwriting one).
	 *
	 * <p>Reads zones fresh from {@link OceanCanvasPlayerZones} on every
	 * keystroke rather than caching - zone lists are small (this is a
	 * per-player-defined-builds feature, not thousands of entries) and
	 * this keeps suggestions correct even if a zone was just created/
	 * removed by someone else moments ago, which a cached list could miss.
	 * Uses {@code SharedSuggestionProvider.suggest}, the same long-stable
	 * vanilla helper every other tab-completable vanilla command argument
	 * (block ids, entity selectors, etc.) already relies on.</p>
	 */
	private static final SuggestionProvider<CommandSourceStack> SUGGEST_ZONE_NAMES = (context, builder) -> {
		CommandSourceStack source = context.getSource();
		var zones = OceanCanvasPlayerZones.get(source.getLevel()).all();
		return SharedSuggestionProvider.suggest(zones.stream().map(OceanCanvasPlayerZones.Zone::name), builder);
	};

	/**
	 * Tab-completion for {@code override}'s {@code value} argument - drafted
	 * alongside the map screen's per-zone structure-override toggle (see
	 * {@link net.oceancanvas.mod.config.StructureOverride}), giving that same
	 * feature a command-line path too, not just the UI.
	 */
	private static final SuggestionProvider<CommandSourceStack> SUGGEST_OVERRIDE_VALUES = (context, builder) ->
			SharedSuggestionProvider.suggest(List.of("inherit", "force_on", "force_off"), builder);

	/**
	 * Structure kinds, enumerated from {@link OceanCanvasStructureKind}
	 * rather than from a hardcoded list here - adding a kind should not
	 * mean remembering to also edit a command file's tab-completion.
	 */
	private static final SuggestionProvider<CommandSourceStack> SUGGEST_STRUCTURE_KINDS = (context, builder) ->
			SharedSuggestionProvider.suggest(
					java.util.Arrays.stream(OceanCanvasStructureKind.values()).map(OceanCanvasStructureKind::id),
					builder);

	/**
	 * Common ocean-adjacent biomes, offered as a starting point.
	 *
	 * <p><b>A curated list rather than the live registry, deliberately.</b>
	 * Enumerating the real registry needs {@code ResourceKey#location()} to
	 * turn each key back into a printable id - and this build renamed
	 * {@code ResourceLocation} to {@link net.minecraft.resources.Identifier},
	 * so that accessor may well have been renamed alongside it. There is no
	 * other {@code location()} call anywhere in this project to lean on,
	 * and a wrong guess here fails the entire main source set, not just tab
	 * completion. Suggestions are only a convenience: the argument accepts
	 * any string, and the SERVER validates it against the real registry
	 * (see {@code OceanCanvasNetworking#validateBiomeId}), so a datapack
	 * biome that is not listed here still works perfectly well when typed.
	 * Worth revisiting once a real compile confirms the accessor's name.</p>
	 */
	private static final SuggestionProvider<CommandSourceStack> SUGGEST_BIOMES = (context, builder) ->
			SharedSuggestionProvider.suggest(List.of(
					"minecraft:ocean",
					"minecraft:deep_ocean",
					"minecraft:warm_ocean",
					"minecraft:lukewarm_ocean",
					"minecraft:deep_lukewarm_ocean",
					"minecraft:cold_ocean",
					"minecraft:deep_cold_ocean",
					"minecraft:frozen_ocean",
					"minecraft:deep_frozen_ocean",
					"minecraft:river",
					"minecraft:frozen_river",
					"minecraft:beach",
					"minecraft:stony_shore",
					"minecraft:mushroom_fields"),
					builder);

	/**
	 * Same fixed palette as {@code OceanCanvasPlayerZones.VALID_REGION_COLORS}
	 * and the map screen's {@code REGION_COLORS} - three independent copies
	 * by necessity (this module can suggest without importing the client,
	 * and the server module is the one source of truth that actually
	 * validates), kept in sync by all three being this same short, stable
	 * list.
	 */
	private static final SuggestionProvider<CommandSourceStack> SUGGEST_REGION_COLORS = (context, builder) ->
			SharedSuggestionProvider.suggest(
					List.of("red", "orange", "yellow", "lime", "cyan", "blue", "purple", "pink"), builder);

	private ProtectCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("protect")
								.requires(Permissions.require("oceancanvas.protect", 2))
								.then(Commands.literal("pos1").executes(ctx -> capture(ctx, true)))
								.then(Commands.literal("pos2").executes(ctx -> capture(ctx, false)))
								.then(Commands.literal("create")
										.then(Commands.argument("name", StringArgumentType.word())
												.executes(ProtectCommand::create)))
								.then(Commands.literal("duplicate")
										.then(Commands.argument("sourceName", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.then(Commands.argument("newName", StringArgumentType.word())
														.executes(ProtectCommand::duplicate))))
								.then(Commands.literal("here")
										.then(Commands.argument("name", StringArgumentType.word())
												.then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1))
														.executes(ProtectCommand::here))))
								.then(Commands.literal("list")
										.executes(ProtectCommand::list)
										.then(Commands.literal("mine").executes(ProtectCommand::listMine)))
								.then(Commands.literal("show")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.executes(ProtectCommand::show)))
								.then(Commands.literal("enable")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.executes(ctx -> setEnabled(ctx, true))))
								.then(Commands.literal("disable")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.executes(ctx -> setEnabled(ctx, false))))
								.then(Commands.literal("remove")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.executes(ProtectCommand::remove)))
								.then(Commands.literal("transfer")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.then(Commands.argument("newOwner", EntityArgument.player())
														.executes(ProtectCommand::transfer))))
								.then(Commands.literal("rename")
									.then(Commands.argument("name", StringArgumentType.word())
											.suggests(SUGGEST_ZONE_NAMES)
											.then(Commands.argument("newName", StringArgumentType.word())
													.executes(ProtectCommand::rename))))
								.then(Commands.literal("precedence")
									.then(Commands.argument("name", StringArgumentType.word())
											.suggests(SUGGEST_ZONE_NAMES)
											.then(Commands.literal("before")
													.then(Commands.argument("target", StringArgumentType.word())
															.suggests(SUGGEST_ZONE_NAMES)
															.executes(ProtectCommand::precedenceBefore)))))
							.then(Commands.literal("structure")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.then(Commands.argument("kind", StringArgumentType.word())
														.suggests(SUGGEST_STRUCTURE_KINDS)
														.then(Commands.argument("value", StringArgumentType.word())
																.suggests(SUGGEST_OVERRIDE_VALUES)
																.executes(ProtectCommand::setStructureRule)))))
								.then(Commands.literal("mobs")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.then(Commands.literal("block")
														.executes(ctx -> setMobRule(ctx, true)))
												.then(Commands.literal("allow")
														.executes(ctx -> setMobRule(ctx, false)))))
							.then(Commands.literal("biome")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.then(Commands.literal("clear")
														.executes(ctx -> setBiome(ctx, null)))
												.then(Commands.argument("biome", StringArgumentType.string())
														.suggests(SUGGEST_BIOMES)
														.executes(ctx -> setBiome(ctx,
																StringArgumentType.getString(ctx, "biome"))))))
								.then(Commands.literal("color")
										.then(Commands.argument("name", StringArgumentType.word())
												.suggests(SUGGEST_ZONE_NAMES)
												.then(Commands.literal("clear")
														.executes(ctx -> setColor(ctx, null)))
												.then(Commands.argument("color", StringArgumentType.word())
														.suggests(SUGGEST_REGION_COLORS)
														.executes(ctx -> setColor(ctx,
																StringArgumentType.getString(ctx, "color")))))))
		);
	}

	private static int capture(CommandContext<CommandSourceStack> context, boolean first) {
		CommandSourceStack source = context.getSource();
		BlockPos pos = BlockPos.containing(source.getPosition());
		UUID key = sourceKey(source);
		SelectionState state = selections(source);
		Map<UUID, SelectionPoint> target = first ? state.pos1 : state.pos2;
		target.put(key, new SelectionPoint(pos, source.getLevel().dimension()));
		markPoint(source, pos);
		String which = first ? "pos1" : "pos2";
		boolean bothCurrentDimension = selectionPos(state.pos1, key, source) != null && selectionPos(state.pos2, key, source) != null;
		source.sendSuccess(() -> Component.literal(
				"[Ocean Canvas] " + which + " set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
						+ ". " + (bothCurrentDimension
						? "Both corners set - use \"/oceancanvas protect create <name>\" to make a zone."
						: "Set the other corner next, then \"/oceancanvas protect create <name>\".")
		), false);
		return 1;
	}

	private static BlockPos selectionPos(Map<UUID, SelectionPoint> selections, UUID key, CommandSourceStack source) {
		SelectionPoint point = selections.get(key);
		if (point == null) return null;
		if (!point.dimension().equals(source.getLevel().dimension())) {
			selections.remove(key);
			return null;
		}
		return point.pos();
	}

	/** Explicit targeted cleanup; normal lifecycle cleanup is owned by OceanCanvasServerRuntime.close(server). */
	public static void clearSessionSelections(net.minecraft.server.MinecraftServer server) {
		net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime.clearStateIfOpen(server, SelectionState.class);
	}

	private static int create(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UUID key = sourceKey(source);
		String name = StringArgumentType.getString(context, "name");

		SelectionState selectionState = selections(source);
		BlockPos pos1 = selectionPos(selectionState.pos1, key, source);
		BlockPos pos2 = selectionPos(selectionState.pos2, key, source);
		if (pos1 == null || pos2 == null) {
			boolean havePos1 = pos1 != null;
			boolean havePos2 = pos2 != null;
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Set both corners first - "
					+ "\"/oceancanvas protect pos1\" and \"/oceancanvas protect pos2\" (currently set: pos1="
					+ havePos1 + ", pos2=" + havePos2 + ")."), false);
			return 0;
		}

		BoundingBox bounds = BoundingBox.fromCorners(pos1, pos2);
		String error = OceanCanvasPlayerZones.get(source.getLevel()).define(name, bounds, ownerId(source), ownerName(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}

		long chunkCount = (long) Math.ceil((bounds.maxX() - bounds.minX() + 1) / 16.0)
				* (long) Math.ceil((bounds.maxZ() - bounds.minZ() + 1) / 16.0);
		outlineBounds(source, bounds);
		// Broadcast - a new protected zone changes what reset/expand are
		// allowed to touch, worth other ops seeing.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] Zone '" + name + "' created and protected - "
				+ describeBounds(bounds) + " (~" + chunkCount + " chunk(s) footprint). "
				+ "Toggle later with \"/oceancanvas protect disable/enable " + name + "\"."), true);
		return 1;
	}

	/**
	 * {@code /oceancanvas protect duplicate <sourceName> <newName>} - the
	 * command-line path to the map screen's "C" duplicate shortcut (see
	 * {@code OceanCanvasZoneDuplicateRequestPayload}'s class doc). Reuses
	 * pos1/pos2 exactly the way {@link #create} does rather than inventing
	 * a second way to specify a footprint - set both corners, then either
	 * command builds a zone there, the only difference being where the
	 * starting rule set comes from.
	 */
	private static int duplicate(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UUID key = sourceKey(source);
		String sourceName = StringArgumentType.getString(context, "sourceName");
		String newName = StringArgumentType.getString(context, "newName");

		SelectionState selectionState = selections(source);
		BlockPos pos1 = selectionPos(selectionState.pos1, key, source);
		BlockPos pos2 = selectionPos(selectionState.pos2, key, source);
		if (pos1 == null || pos2 == null) {
			boolean havePos1 = pos1 != null;
			boolean havePos2 = pos2 != null;
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] Set both corners first - "
					+ "\"/oceancanvas protect pos1\" and \"/oceancanvas protect pos2\" (currently set: pos1="
					+ havePos1 + ", pos2=" + havePos2 + ")."), false);
			return 0;
		}

		BoundingBox bounds = BoundingBox.fromCorners(pos1, pos2);
		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.duplicate(sourceName, newName, bounds, ownerId(source), ownerName(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}

		outlineBounds(source, bounds);
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] Zone '" + newName + "' created - "
				+ describeBounds(bounds) + ", copying the rules from '" + sourceName + "'. "
				+ "Toggle later with \"/oceancanvas protect disable/enable " + newName + "\"."), true);
		return 1;
	}

	private static int here(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel world = source.getLevel();
		BlockPos pos = BlockPos.containing(source.getPosition());
		String name = StringArgumentType.getString(context, "name");
		int radiusChunks = IntegerArgumentType.getInteger(context, "radiusChunks");

		int centerChunkX = Math.floorDiv(pos.getX(), 16);
		int centerChunkZ = Math.floorDiv(pos.getZ(), 16);
		int minX = (centerChunkX - radiusChunks) * 16;
		int maxX = (centerChunkX + radiusChunks + 1) * 16 - 1;
		int minZ = (centerChunkZ - radiusChunks) * 16;
		int maxZ = (centerChunkZ + radiusChunks + 1) * 16 - 1;

		// Full vertical range - see FULL_HEIGHT_MIN_Y's doc comment for
		// why the bottom is a hardcoded constant rather than a dynamic
		// call, and why the top isn't (world.getMaxY() is already
		// confirmed working elsewhere in this codebase).
		BoundingBox bounds = new BoundingBox(minX, world.getMinY(), minZ, maxX, world.getMaxY() + 1, maxZ);

		String error = OceanCanvasPlayerZones.get(world).define(name, bounds, ownerId(source), ownerName(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}

		long chunkCount = (long) (radiusChunks * 2 + 1) * (radiusChunks * 2 + 1);
		outlineBounds(source, bounds);
		// Broadcast - see `create`'s comment above.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] Zone '" + name + "' created and protected - "
				+ radiusChunks + "-chunk radius around you (" + chunkCount + " chunks, full height). "
				+ "Toggle later with \"/oceancanvas protect disable/enable " + name + "\"."), true);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		return printZoneList(source, OceanCanvasPlayerZones.get(source.getLevel()).all(), "");
	}

	/**
	 * {@code /oceancanvas protect list mine} - drafted alongside {@code
	 * transfer} as the other natural follow-up to this project's zone-
	 * ownership feature: on a server with many ops and many zones, "which
	 * of these are actually mine" is a real, frequent question {@code
	 * list}'s full unfiltered dump doesn't answer directly. Console/
	 * command-block sources have no owner UUID to filter by, so this
	 * falls back to plain {@code list}'s full output rather than returning
	 * an empty/confusing result for them.
	 */
	private static int listMine(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UUID caller = ownerId(source);
		if (caller == null) {
			source.sendSuccess(() -> Component.literal(
					"[Ocean Canvas] Console/command-block sources don't own zones - showing all zones instead."), false);
			return printZoneList(source, OceanCanvasPlayerZones.get(source.getLevel()).all(), "");
		}
		var mine = OceanCanvasPlayerZones.get(source.getLevel()).all().stream()
				.filter(zone -> caller.equals(zone.owner()))
				.toList();
		return printZoneList(source, mine, "your ");
	}

	// Shared by list/listMine - "labelPrefix" is "" for the full list, "your " for the filtered one.
	private static int printZoneList(CommandSourceStack source, List<OceanCanvasPlayerZones.Zone> zones, String labelPrefix) {
		if (zones.isEmpty()) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] No " + labelPrefix + "protected zones "
					+ (labelPrefix.isEmpty() ? "defined yet." : "- either you don't own any, or none are defined yet.")), false);
			return 1;
		}

		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + zones.size() + " " + labelPrefix + "region(s):"), false);
		for (OceanCanvasPlayerZones.Zone zone : zones) {
			source.sendSuccess(() -> Component.literal("  " + zone.name() + " ["
					+ (zone.protectedNow() ? "PROTECTED" : "unprotected") + "] " + describeBounds(zone.bounds())
					+ " - owner: " + (zone.ownerName() != null ? zone.ownerName() : "(unowned/legacy - any op)")), false);
			// Rules on their own line, and only when there are any. A region
			// that carries structure, biome or mob rules but reported only
			// its bounds was a real gap - the rules were invisible from the
			// command line entirely until this was added.
			String rules = OceanCanvasPlayerZones.describeRules(zone);
			if (!"no rules".equals(rules)) {
				source.sendSuccess(() -> Component.literal("      rules: " + rules), false);
			}
		}
		return 1;
	}

	/**
	 * {@code /oceancanvas protect show <name>} - re-outlines an EXISTING
	 * zone's bounds, drafted alongside the particle feedback on {@code
	 * create}/{@code here} so a zone defined a while ago (or by someone
	 * else) can be visually relocated on demand too, not just at the
	 * moment it's first created.
	 */
	private static int show(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		var zones = OceanCanvasPlayerZones.get(source.getLevel()).all();
		for (OceanCanvasPlayerZones.Zone zone : zones) {
			if (zone.name().equalsIgnoreCase(name)) {
				outlineBounds(source, zone.bounds());
				source.sendSuccess(() -> Component.literal("[Ocean Canvas] Outlined zone '" + zone.name() + "' ["
						+ (zone.protectedNow() ? "PROTECTED" : "unprotected") + "] " + describeBounds(zone.bounds())
						+ " with particles (visible to you only). Owner: "
						+ (zone.ownerName() != null ? zone.ownerName() : "(unowned/legacy - any op)")), false);
				return 1;
			}
		}
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] No zone named '" + name
				+ "' - see \"/oceancanvas protect list\"."), false);
		return 0;
	}

	// Total particle count budget for one outlineBounds call, spread
	// across all 12 edges proportional to their length - see that
	// method's doc for why this exists: a zone can legitimately span
	// thousands of blocks (a large "here <name> <radius>" selection), and
	// a fixed per-block sample rate would turn a single command into an
	// unbounded number of particle packets. 600 is plenty dense for a
	// human to clearly see the box shape even on a small selection, and
	// stays a small, one-shot amount of network traffic on a huge one.
	private static final int MAX_OUTLINE_PARTICLES = 600;

	/**
	 * Sends a one-shot wireframe outline of {@code bounds} as particles,
	 * visible only to the command's own player - a no-op for a
	 * console/command-block source (nothing to show particles to).
	 *
	 * <p><b>Deliberately a single burst, not a persistent live overlay</b>
	 * - the brainstormed "ambient boundary HUD" idea (see {@code
	 * docs/roadmap.md}'s Round 3 log) already established that a
	 * persistent client-side overlay is real, currently-unconfirmed-API
	 * risk (client rendering, a whole different code category from
	 * anything else in this server-side-only mod) not worth taking on for
	 * this. A one-shot burst uses the same already-simple, long-stable
	 * {@code ServerLevel#sendParticles} server-side API {@code
	 * clearEntitiesAboveWater}-adjacent code elsewhere in this project
	 * already relies on for similar "notify the client of something"
	 * purposes, and gives the exact feedback actually needed here -
	 * "did my selection/zone end up where I think it did" - without
	 * committing to ongoing per-tick particle spam for a zone that might
	 * sit protected for weeks.</p>
	 *
	 * <p>Outline hugs the actual block volume (from each min coordinate
	 * to {@code max + 1}), not the block centers, so what's drawn visually
	 * matches exactly which blocks are protected. Particle density is
	 * capped at {@link #MAX_OUTLINE_PARTICLES} total, distributed
	 * proportional to each of the 12 edges' length - see that field's doc
	 * for why an unbounded per-block sample rate would be a real risk on a
	 * large zone.</p>
	 *
	 * <p><b>Real, honestly-flagged API-shape risk, unlike most of the rest
	 * of this mod's server-side calls:</b> {@code sendParticles} gained an
	 * extra {@code alwaysShow} boolean in a real, documented vanilla
	 * change (1.20.2, {@code ClientboundLevelParticlesPacket}) on top of
	 * the older {@code overrideLimiter}-only per-player overload - this
	 * code is written against that newer, five-leading-argument shape
	 * ({@code player, particle, overrideLimiter, alwaysShow, x, y, z, ...}),
	 * since a hypothetical 26.2 is far more likely to have kept moving in
	 * that direction than reverted it, but it's genuinely unconfirmed
	 * against this exact build (same caveat as everywhere else - no
	 * network access to a real Loom-generated 26.2 source in this
	 * sandbox). If it doesn't compile, the fix is almost certainly
	 * dropping the {@code false} (alwaysShow) argument here and in {@link
	 * #markPoint}, not a deeper design problem - this whole feature is
	 * additive polish, never load-bearing for anything else in the mod.</p>
	 */
	private static void outlineBounds(CommandSourceStack source, BoundingBox bounds) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return;
		}
		ServerLevel world = source.getLevel();

		double x0 = bounds.minX();
		double x1 = bounds.maxX() + 1.0;
		double y0 = bounds.minY();
		double y1 = bounds.maxY() + 1.0;
		double z0 = bounds.minZ();
		double z1 = bounds.maxZ() + 1.0;

		double[][] corners = {
				{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1},
				{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1},
		};
		// Bottom face (0-1-2-3-0), top face (4-5-6-7-4), then the four
		// vertical edges connecting matching corners - 12 edges total, a
		// complete wireframe box.
		int[][] edges = {
				{0, 1}, {1, 2}, {2, 3}, {3, 0},
				{4, 5}, {5, 6}, {6, 7}, {7, 4},
				{0, 4}, {1, 5}, {2, 6}, {3, 7},
		};

		double totalLength = 0.0;
		for (int[] edge : edges) {
			totalLength += distance(corners[edge[0]], corners[edge[1]]);
		}
		// Never denser than 1 point/block even on a tiny selection -
		// avoids a near-zero spacing (and so a burst of redundant
		// overlapping particles) on a very short edge.
		double spacing = Math.max(1.0, totalLength / MAX_OUTLINE_PARTICLES);

		for (int[] edge : edges) {
			double[] a = corners[edge[0]];
			double[] b = corners[edge[1]];
			double edgeLength = distance(a, b);
			int points = Math.max(2, (int) Math.round(edgeLength / spacing) + 1);
			for (int i = 0; i < points; i++) {
				double t = (double) i / (points - 1);
				double px = a[0] + (b[0] - a[0]) * t;
				double py = a[1] + (b[1] - a[1]) * t;
				double pz = a[2] + (b[2] - a[2]) * t;
				// overrideLimiter=true - a bounded, one-shot command
				// response should always actually show up, not get
				// silently dropped by the client's normal particle-rate
				// throttling the way ambient particles can be.
				world.sendParticles(player, ParticleTypes.END_ROD, true, false, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	private static double distance(double[] a, double[] b) {
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double dz = b[2] - a[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Small marker burst at a single captured point ({@code pos1}/{@code pos2}) - see {@link #outlineBounds}'s doc for the shared design reasoning. */
	private static void markPoint(CommandSourceStack source, BlockPos pos) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return;
		}
		source.getLevel().sendParticles(player, ParticleTypes.END_ROD, true, false,
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.0);
	}

	private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		if (rejectLockedRegionMutation(source, name)) return 0;
		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.setEnabled(name, enabled, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}
		// Broadcast - this directly changes whether reset/expand can now
		// destroy the zone, real information other ops need.
		source.sendSuccess(() -> Component.literal(
				"[Ocean Canvas] Zone '" + name + "' " + (enabled ? "protected." : "unprotected - "
						+ "carving/pregen/reset/expand may now touch it again next time they pass over it.")), true);
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		if (rejectLockedRegionMutation(source, name)) return 0;
		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.remove(name, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}
		net.oceancanvas.mod.project.OceanCanvasProjectData.get(source.getLevel()).removeRegion(name);
		net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(source.getLevel()).removeRegionReference(name);
		// Broadcast - a deleted zone can no longer protect anything, worth
		// other ops seeing before they run a reset/expand near it.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] Zone '" + name + "' deleted entirely "
				+ "(bounds forgotten - linked Project/history records were kept but detached)."), true);
		return 1;
	}

	/**
	 * {@code /oceancanvas protect transfer <name> <newOwner>} - hands a
	 * zone off to another ONLINE op, drafted alongside the ownership
	 * feature so a departing/demoted op's zones don't permanently need a
	 * level-3 override just for routine management. {@code newOwner} uses
	 * {@link EntityArgument#player()}, the same long-stable, widely-used
	 * vanilla single-player-selector argument type {@code /tp}/{@code /msg}
	 * etc. already rely on - deliberately requires the new owner to be
	 * ONLINE right now (not an offline UUID/name lookup via the server's
	 * profile cache, a separate API this project hasn't needed anywhere
	 * else) so a typo'd name fails immediately and obviously with
	 * Brigadier's own "no such player" error, rather than silently
	 * assigning ownership to a UUID nobody can verify was actually the
	 * player meant.
	 */
	private static int transfer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		ServerPlayer newOwner = EntityArgument.getPlayer(context, "newOwner");

		String error = OceanCanvasPlayerZones.get(source.getLevel()).transferOwnership(
				name, ownerId(source), canOverrideOwnership(source), newOwner.getUUID(), newOwner.getName().getString());
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}
		// Broadcast - this changes who can enable/disable/remove the zone
		// going forward, worth other ops seeing.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] Zone '" + name + "' transferred to "
				+ newOwner.getName().getString() + "."), true);
		return 1;
	}

	/**
	 * {@code /oceancanvas protect rename <name> <newName>} - the
	 * command-line counterpart to the map screen's Rename button, added
	 * for the same parity reason every other zone action has both: the
	 * screen and the command tree should never be able to do different
	 * things. See {@link OceanCanvasPlayerZones#rename} for the ownership
	 * gate and the name-collision rule.
	 */
	private static int rename(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		if (rejectLockedRegionMutation(source, name)) return 0;
		String newName = StringArgumentType.getString(context, "newName");

		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.rename(name, newName, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}
		net.oceancanvas.mod.project.OceanCanvasProjectData.get(source.getLevel()).renameRegion(name, newName);
		net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(source.getLevel()).renameRegionReference(name, newName);
		// Broadcast - every other op's notes and commands referred to this
		// zone by its old name a moment ago, so the change is worth seeing.
		source.sendSuccess(() -> Component.literal(
				"[Ocean Canvas] Zone '" + name + "' renamed to '" + newName + "'. Linked Project/history metadata moved with it."), true);
		return 1;
	}

	/**
	 * {@code /oceancanvas protect precedence <name> before <target>} - the
	 * command-line path to the map screen's "Take precedence" button.
	 *
	 * <p>Where two regions overlap and set the same rule differently, the
	 * one defined FIRST wins. Before this existed, changing that answer
	 * meant deleting the winning region and drawing it again, which threw
	 * away its name, its owner and every other rule it carried in order to
	 * fix an ordering problem.</p>
	 *
	 * <p>The literal {@code before} in the middle is not decoration.
	 * {@code precedence a b} could reasonably mean either direction, and
	 * this is a command whose whole purpose is to settle which of two
	 * things wins - the one place where reading it back and being sure is
	 * worth an extra word.</p>
	 */
	private static int precedenceBefore(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		if (rejectLockedRegionMutation(source, name)) return 0;
		String target = StringArgumentType.getString(context, "target");

		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.movePrecedenceBefore(name, target, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}
		// Broadcast: this changes which rules are in force where two
		// regions meet, which is exactly the sort of thing another op
		// would otherwise spend an evening being confused by.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] '" + name
				+ "' now takes precedence over '" + target + "' where they overlap."), true);
		return 1;
	}

	/**
	 * {@code /oceancanvas protect structure <name> <kind> <inherit|force_on|force_off>}
	 * - the command-line path to the same per-region structure rule the map
	 * screen exposes. {@code force_on} keeps that structure kind inside this
	 * region even when the world default is off; {@code force_off} clears it
	 * away even when the default is on; {@code inherit} removes the rule.
	 * See {@link OceanCanvasStructureKind} for what "keep" actually means
	 * per kind, and {@link OceanCanvasPlayerZones#setStructureOverride} for
	 * the ownership gate.
	 */
	private static int setStructureRule(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		if (rejectLockedRegionMutation(source, name)) return 0;
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

		String error = OceanCanvasPlayerZones.get(source.getLevel()).setStructureOverride(
				name, kind, override, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}

		net.oceancanvas.mod.config.StructureOverride finalOverride = override;
		// Broadcast - this changes what the flattener keeps or clears
		// inside this region going forward, worth other ops seeing.
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] Region '" + name + "': "
				+ kind.displayName() + " set to " + finalOverride + "."), true);
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

	/**
	 * {@code /oceancanvas protect mobs <name> block|allow} - the
	 * command-line counterpart to the map screen's mob rule. See
	 * {@code OceanCanvasMobSuppressor} for exactly what "block" does, and
	 * for the one surprising interaction (a spawner or mob farm inside the
	 * region stops producing), which the confirmation below states rather
	 * than leaving to be discovered.
	 */
	private static int setMobRule(CommandContext<CommandSourceStack> context, boolean suppress) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		if (rejectLockedRegionMutation(source, name)) return 0;

		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.setSuppressHostileMobs(name, suppress, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}

		source.sendSuccess(() -> Component.literal(suppress
				? "[Ocean Canvas] Region '" + name + "': hostile mobs will be kept out. Any spawner or mob farm "
						+ "inside it will stop producing. Only takes effect while the region is protected."
				: "[Ocean Canvas] Region '" + name + "': hostile mobs allowed again."), true);
		return 1;
	}

	/**
	 * {@code /oceancanvas protect biome <name> <biome|clear>} - paints a
	 * biome across a region, the command-line counterpart to the map
	 * screen's biome field.
	 *
	 * <p>Validated against the world's own biome registry before anything
	 * is stored (shared with the network handler via {@code
	 * OceanCanvasNetworking#validateBiomeId}, so both paths reject exactly
	 * the same things), because a rule referring to a biome this world does
	 * not have would be written down and then silently never apply.</p>
	 */
	private static int setBiome(CommandContext<CommandSourceStack> context, String biomeId) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");
		if (rejectLockedRegionMutation(source, name)) return 0;

		if (biomeId != null) {
			String problem = net.oceancanvas.mod.network.OceanCanvasNetworking.validateBiomeId(source.getLevel(), biomeId);
			if (problem != null) {
				source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + problem), false);
				return 0;
			}
		}

		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.setBiomeOverride(name, biomeId, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}

		source.sendSuccess(() -> Component.literal(biomeId == null
				? "[Ocean Canvas] Region '" + name + "': biome rule cleared."
				: "[Ocean Canvas] Region '" + name + "': biome set to " + biomeId
						+ ". Chunks already loaded need a reload (relog, or travel away and back) to show it."), true);
		return 1;
	}

	/**
	 * {@code /oceancanvas protect color <name> <color|clear>} - the
	 * command-line counterpart to the map screen's colour swatch. Purely
	 * cosmetic, unlike every other rule this command sets - see {@code
	 * OceanCanvasPlayerZones.Zone#color}'s doc. Validation against the
	 * fixed palette happens inside {@code setColor} itself, the same as
	 * the network handler, so both paths reject exactly the same things.
	 */
	private static int setColor(CommandContext<CommandSourceStack> context, String color) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "name");

		String error = OceanCanvasPlayerZones.get(source.getLevel())
				.setColor(name, color, ownerId(source), canOverrideOwnership(source));
		if (error != null) {
			source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + error), false);
			return 0;
		}

		source.sendSuccess(() -> Component.literal(color == null
				? "[Ocean Canvas] Region '" + name + "': colour cleared."
				: "[Ocean Canvas] Region '" + name + "': colour set to " + color + "."), true);
		return 1;
	}


	/** Keep command-line Region mutation semantics identical to the UI while a named terrain job owns the geometry. */
	private static boolean rejectLockedRegionMutation(CommandSourceStack source, String name) {
		String reason = PregenManager.regionMutationBlockReason(name);
		if (reason == null || reason.isBlank()) return false;
		source.sendSuccess(() -> Component.literal("[Ocean Canvas] " + reason), false);
		return true;
	}

	// --- Ownership helpers - see OceanCanvasPlayerZones's class doc for
	// the full "why level 3, why unowned zones stay open" reasoning. ---

	/** {@code null} for a console/command-block source - matches {@code define}'s own null-owner handling. */
	private static UUID ownerId(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player == null ? null : player.getUUID();
	}

	/** A display-only snapshot of the current player's name, or {@code null} for console - see {@code define}'s doc. */
	private static String ownerName(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player == null ? null : player.getName().getString();
	}

	/**
	 * Whether this source can bypass another op's zone ownership - true
	 * for console (matches the rest of this command's console-is-fully-
	 * trusted handling elsewhere), otherwise permission level 3 (one tier
	 * above the level-2 gate the whole {@code protect} subtree already
	 * requires) - a real, standard vanilla permission level, not an
	 * invented concept, reserved for the rare "an op left/was demoted and
	 * someone needs to clean up their zone" case.
	 */
	private static boolean canOverrideOwnership(CommandSourceStack source) {
		return source.getPlayer() == null || Permissions.require("oceancanvas.protect.override", 3).test(source);
	}

	private static String describeBounds(BoundingBox bounds) {
		return "(" + bounds.minX() + ", " + bounds.minY() + ", " + bounds.minZ() + ") to ("
				+ bounds.maxX() + ", " + bounds.maxY() + ", " + bounds.maxZ() + ")";
	}

	// Console/command-block sources have no player UUID - fall back to a
	// fixed shared key so pos1/pos2 still work for them (a single shared
	// "non-player" selection slot), rather than throwing.
	private static final UUID NON_PLAYER_KEY = new UUID(0, 0);

	private static UUID sourceKey(CommandSourceStack source) {
		return source.getPlayer() != null ? source.getPlayer().getUUID() : NON_PLAYER_KEY;
	}
}
