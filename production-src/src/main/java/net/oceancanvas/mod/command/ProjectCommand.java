package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.project.OceanCanvasProjectData;
import net.oceancanvas.mod.project.OceanCanvasProjectPackageImporter;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

/** Command-side editor for the project metadata that will also surface on the map UI. */
public final class ProjectCommand {
    private ProjectCommand() {}

    private static final SuggestionProvider<CommandSourceStack> ZONES = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    OceanCanvasPlayerZones.get(context.getSource().getLevel()).all().stream().map(OceanCanvasPlayerZones.Zone::name),
                    builder);

    private static final SuggestionProvider<CommandSourceStack> STAGES = (context, builder) ->
            SharedSuggestionProvider.suggest(java.util.List.of("reserved", "terrain", "detailing", "complete", "archived"), builder);

    /** Lists .oceanproject files sitting in this world's oceancanvas/projects directory - the same directory
     *  both the exporter writes into and a package dropped in from another world/server would go. */
    private static final SuggestionProvider<CommandSourceStack> PACKAGE_FILES = (context, builder) -> {
        ServerLevel world = context.getSource().getLevel();
        Path dir = world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("projects");
        var names = new ArrayList<String>();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".oceanproject"))
                        .forEach(p -> names.add(p.getFileName().toString()));
            } catch (IOException ignored) { }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .then(Commands.literal("project")
                        .requires(Permissions.require("oceancanvas.project.manage", 1))
                        .then(Commands.literal("info")
                                .then(Commands.argument("region", StringArgumentType.word()).suggests(ZONES)
                                        .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "region")))))
                        .then(Commands.literal("stage")
                                .then(Commands.argument("region", StringArgumentType.word()).suggests(ZONES)
                                        .then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGES)
                                                .executes(ctx -> stage(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "region"),
                                                        StringArgumentType.getString(ctx, "stage"))))))
                        .then(Commands.literal("note")
                                .then(Commands.argument("region", StringArgumentType.word()).suggests(ZONES)
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> note(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "region"),
                                                        StringArgumentType.getString(ctx, "text"))))))
                        .then(Commands.literal("clear-note")
                                .then(Commands.argument("region", StringArgumentType.word()).suggests(ZONES)
                                        .executes(ctx -> note(ctx.getSource(), StringArgumentType.getString(ctx, "region"), ""))))
                        .then(Commands.literal("import")
                                .then(Commands.argument("file", StringArgumentType.greedyString()).suggests(PACKAGE_FILES)
                                        .executes(ctx -> doImport(ctx.getSource(), StringArgumentType.getString(ctx, "file")))))));
    }

    private static OceanCanvasPlayerZones.Zone zone(CommandSourceStack source, String name) {
        return OceanCanvasPlayerZones.get(source.getLevel()).zoneByName(name);
    }

    private static int info(CommandSourceStack source, String name) {
        OceanCanvasPlayerZones.Zone zone = zone(source, name);
        if (zone == null) {
            source.sendFailure(Component.literal("No region named '" + name + "'."));
            return 0;
        }
        OceanCanvasProjectData.RegionMeta meta = OceanCanvasProjectData.get(source.getLevel()).ensureRegionMeta(zone.name());
        String notes = meta.notes().isBlank() ? "no notes" : meta.notes();
        source.sendSuccess(() -> Component.literal("Project region '" + zone.name() + "': stage "
                + pretty(meta.parsedStage()) + "; " + notes + "."), false);
        return 1;
    }

    private static int stage(CommandSourceStack source, String name, String raw) {
        OceanCanvasPlayerZones.Zone zone = zone(source, name);
        if (zone == null) {
            source.sendFailure(Component.literal("No region named '" + name + "'."));
            return 0;
        }
        OceanCanvasProjectData.RegionStage stage = switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "reserved" -> OceanCanvasProjectData.RegionStage.RESERVED;
            case "terrain", "terrain_construction" -> OceanCanvasProjectData.RegionStage.TERRAIN_CONSTRUCTION;
            case "detailing" -> OceanCanvasProjectData.RegionStage.DETAILING;
            case "complete" -> OceanCanvasProjectData.RegionStage.COMPLETE;
            case "archived", "archive" -> OceanCanvasProjectData.RegionStage.ARCHIVED;
            default -> null;
        };
        if (stage == null) {
            source.sendFailure(Component.translatable("oceancanvas.project.stage.invalid"));
            return 0;
        }
        OceanCanvasProjectData data = OceanCanvasProjectData.get(source.getLevel());
        data.setRegionStage(zone.name(), stage);
        if (stage == OceanCanvasProjectData.RegionStage.COMPLETE) data.markMilestone("region_complete");
        source.sendSuccess(() -> Component.literal("Region '" + zone.name() + "' stage: " + pretty(stage) + "."), false);
        return 1;
    }

    private static int note(CommandSourceStack source, String name, String text) {
        OceanCanvasPlayerZones.Zone zone = zone(source, name);
        if (zone == null) {
            source.sendFailure(Component.literal("No region named '" + name + "'."));
            return 0;
        }
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() > 512) {
            source.sendFailure(Component.translatable("oceancanvas.project.note.too_long"));
            return 0;
        }
        OceanCanvasProjectData.get(source.getLevel()).setRegionNotes(zone.name(), normalized);
        source.sendSuccess(() -> Component.literal(normalized.isBlank()
                ? "Cleared notes for region '" + zone.name() + "'."
                : "Saved notes for region '" + zone.name() + "'."), false);
        return 1;
    }

    /** {@code /oceancanvas project import <file>} - reads back an .oceanproject package written by
     *  OceanCanvasProjectPackageExporter. See OceanCanvasProjectPackageImporter's own doc for the
     *  collision/dangling-reference policy this applies. */
    private static int doImport(CommandSourceStack source, String rawFile) {
        ServerLevel world = source.getLevel();
        try {
            Path file = OceanCanvasProjectPackageImporter.resolve(world, rawFile);
            var result = OceanCanvasProjectPackageImporter.importPackage(world, file);
            String summary = "Imported '" + result.projectName() + "' (" + result.tasks() + " tasks, "
                    + result.planObjects() + " Plan objects, " + result.terrainAssets() + " Terrain Assets)."
                    + (result.warnings().isEmpty() ? "" : " " + result.warnings().size()
                    + " reference(s) could not be resolved and were cleared - see the log for detail.");
            source.sendSuccess(() -> Component.literal(summary), true);
            for (String warning : result.warnings())
                net.oceancanvas.mod.OceanCanvas.LOGGER.warn("[Ocean Canvas] Project import ({}): {}", rawFile, warning);
            return 1;
        } catch (IllegalArgumentException ex) {
            source.sendFailure(Component.literal("Project import failed: " + ex.getMessage()));
            return 0;
        } catch (IOException ex) {
            net.oceancanvas.mod.OceanCanvas.LOGGER.error("Failed to import Ocean Canvas project package {}", rawFile, ex);
            source.sendFailure(Component.literal("Project import failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
            return 0;
        }
    }

    private static String pretty(OceanCanvasProjectData.RegionStage stage) {
        return switch (stage) {
            case RESERVED -> "Reserved";
            case TERRAIN_CONSTRUCTION -> "Terrain Construction";
            case DETAILING -> "Detailing";
            case COMPLETE -> "Complete";
            case ARCHIVED -> "Archived";
        };
    }
}
