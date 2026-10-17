package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.project.OceanCanvasExpansionPlanner;
import net.oceancanvas.mod.project.OceanCanvasProjectData;

public final class PlanCommand {
    private PlanCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("oceancanvas")
                        .then(Commands.literal("plan")
                                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.planning.view", 1))
                                .then(Commands.literal("expand")
                                        .then(Commands.argument("size", IntegerArgumentType.integer(1))
                                                .executes(ctx -> preview(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "size")))))
                                .then(Commands.literal("export")
                                        .executes(ctx -> export(ctx.getSource(), "plan"))
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> export(ctx.getSource(), StringArgumentType.getString(ctx,"name")))))
                                .then(Commands.literal("templates")
                                        .executes(ctx -> {
                                            var data = OceanCanvasProjectData.get(ctx.getSource().getLevel());
                                            String names = data.templates().stream()
                                                    .map(OceanCanvasProjectData.RegionTemplate::displayName)
                                                    .collect(java.util.stream.Collectors.joining(", "));
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Region templates ready: " + names),
                                                    false);
                                            return 1;
                                        })))
        );
    }

    private static int export(CommandSourceStack source,String name) {
        try {
            var result=net.oceancanvas.mod.planning.OceanCanvasPlanExporter.export(source.getLevel(),name);
            source.sendSuccess(() -> Component.literal("Exported "+result.objects()+" Plan objects, "+result.references()+" references, "+result.groups()+" layers, and "+result.terrainAssets()+" Terrain Assets to "+result.directory()+" (bounds "+result.bounds().minX()+","+result.bounds().minZ()+" -> "+result.bounds().maxX()+","+result.bounds().maxZ()+")."),false);
            return 1;
        } catch(java.io.IOException ex) {
            source.sendFailure(Component.literal("Plan export failed: "+ex.getMessage())); return 0;
        }
    }

    private static int preview(CommandSourceStack source, int requested) {
        var plan = OceanCanvasExpansionPlanner.plan(OceanCanvasConfig.get(), requested);
        if (!plan.valid()) {
            source.sendFailure(Component.literal("Expansion preview: " + plan.warning()));
            return 0;
        }

        var benchmark = OceanCanvasProjectData.get(source.getLevel()).benchmark();
        String estimate = "runtime estimate unavailable until the benchmark system has learned this machine";
        if (benchmark != null && benchmark.sustainableChunksPerSecond() > 0.01D) {
            long seconds = Math.round(plan.newRingChunks() / benchmark.sustainableChunksPerSecond());
            estimate = "learned estimate ~" + formatDuration(seconds);
        }

        String finalEstimate = estimate;
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Expansion preview %,.0f -> %,.0f blocks: ~%,d new ring chunks (%,d total target); %s. No world changes made.",
                (double) plan.currentSize(), (double) plan.requestedSize(), plan.newRingChunks(),
                plan.targetChunks(), finalEstimate)), false);
        return 1;
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? h + "h " + m + "m" : m > 0 ? m + "m " + s + "s" : s + "s";
    }
}
