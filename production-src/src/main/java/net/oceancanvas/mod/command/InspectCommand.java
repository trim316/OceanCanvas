package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.project.OceanCanvasInspectorService;
import net.oceancanvas.mod.project.OceanCanvasTerrainStateData;

/** Power-user equivalent of the map's forthcoming Inspector / Explain This panel. */
public final class InspectCommand {
    private InspectCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .then(Commands.literal("inspect")
                        .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.planning.view", 1))
                        .executes(ctx -> inspect(ctx.getSource(),
                                ctx.getSource().getPosition().x(), ctx.getSource().getPosition().z()))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> inspect(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z")))))
                        // Explicit terrain-state marking. Automatic player-modification
                        // detection is still deliberately not implemented (see roadmap
                        // "CUSTOM_OR_MODIFIED is reserved ... not yet automatically
                        // detected"), but that has left the enum value entirely
                        // unreachable in practice - no code path ever set it. This gives
                        // an operator who knows a chunk was hand-edited (WorldEdit,
                        // manual building, etc.) an explicit, auditable way to record
                        // that, matching how VANILLA/CANVAS are already recorded by
                        // Restore/Pregen. It never guesses; it only records what the
                        // operator states.
                        .then(Commands.literal("mark")
                                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.protect", 2))
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .executes(ctx -> mark(ctx.getSource(),
                                                ctx.getSource().getPosition().x(), ctx.getSource().getPosition().z(),
                                                StringArgumentType.getString(ctx, "state"))))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("state", StringArgumentType.word())
                                                        .executes(ctx -> mark(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                StringArgumentType.getString(ctx, "state")))))))));
    }

    private static int mark(CommandSourceStack source, double x, double z, String rawState) {
        TerrainStateChoice choice = TerrainStateChoice.parse(rawState);
        if (choice == null) {
            source.sendFailure(Component.literal(
                    "Unknown terrain state '" + rawState + "'. Use vanilla, canvas, custom, or unknown (clears the explicit state)."));
            return 0;
        }
        int chunkX = (int) Math.floor(Math.floor(x) / 16.0D);
        int chunkZ = (int) Math.floor(Math.floor(z) / 16.0D);
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        OceanCanvasTerrainStateData.get(source.getLevel()).set(pos, choice.state());
        source.sendSuccess(() -> Component.literal("Ocean Canvas: chunk " + pos.x() + "," + pos.z()
                + " explicitly marked " + pretty(choice.state().name()) + "."), false);
        return 1;
    }

    private enum TerrainStateChoice {
        VANILLA(OceanCanvasTerrainStateData.TerrainState.VANILLA),
        CANVAS(OceanCanvasTerrainStateData.TerrainState.CANVAS),
        CUSTOM(OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED),
        UNKNOWN(OceanCanvasTerrainStateData.TerrainState.UNKNOWN);

        private final OceanCanvasTerrainStateData.TerrainState state;
        TerrainStateChoice(OceanCanvasTerrainStateData.TerrainState state) { this.state = state; }
        OceanCanvasTerrainStateData.TerrainState state() { return state; }

        static TerrainStateChoice parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "vanilla" -> VANILLA;
                case "canvas" -> CANVAS;
                case "custom", "modified", "custom_or_modified" -> CUSTOM;
                case "unknown", "clear" -> UNKNOWN;
                default -> null;
            };
        }
    }

    private static int inspect(CommandSourceStack source, double x, double z) {
        var snapshot = OceanCanvasInspectorService.inspect(source.getLevel(), (int) Math.floor(x), (int) Math.floor(z));
        source.sendSuccess(() -> Component.literal("Ocean Canvas inspector — X " + snapshot.blockX() + " Z " + snapshot.blockZ()
                + " | chunk " + snapshot.chunkX() + "," + snapshot.chunkZ()
                + " | terrain " + pretty(snapshot.terrainState().name())
                + " | processed " + (snapshot.processed() ? "yes" : "no")
                + " | protected " + (snapshot.protectedHere() ? "yes" : "no")), false);
        source.sendSuccess(() -> Component.literal("Regions: "
                + (snapshot.containingRegions().isEmpty() ? "none" : String.join(" -> ", snapshot.containingRegions()))), false);
        for (var rule : snapshot.rules()) {
            source.sendSuccess(() -> Component.literal(rule.label() + ": " + rule.value() + " <- " + rule.source()), false);
        }
        return 1;
    }

    private static String pretty(String value) {
        String[] parts = value.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) out.append(' ');
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
