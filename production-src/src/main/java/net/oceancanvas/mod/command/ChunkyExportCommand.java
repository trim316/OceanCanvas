package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /oceancanvas chunky export} - "Real Chunky interop reconsidered
 * from a different angle" per the user's own explicit request this round.
 *
 * <p><b>Why this is a real, safe interop path where the previously
 * investigated one wasn't.</b> {@code docs/roadmap.md}'s earlier Chunky
 * investigation (see that log's entry) concluded a genuine API/data
 * integration was a dead end: Chunky is a Bukkit/Paper-ecosystem plugin
 * with no published Fabric-side API to call into, and its own on-disk
 * progress-tracking format is undocumented and not something to guess at
 * safely. This command sidesteps both problems entirely by not
 * integrating with Chunky's internals AT ALL - it just emits the exact
 * ordinary chat commands a person would type into Chunky themselves,
 * matching the canvas's own current bounds. If Chunky (or a
 * differently-configured version of it, or even a future replacement
 * pre-generation mod with similar commands) is installed, running these
 * lines does real, correct work; if it isn't installed, the output is
 * inert plain text - either way, this mod never touches Chunky's code,
 * data files, or dependencies, so there is nothing here that can break
 * if Chunky's own internals change.</p>
 *
 * <p><b>Command syntax confirmed against Chunky's own published
 * documentation</b> (github.com/pop4959/Chunky/wiki/Commands, checked this
 * round, not guessed): {@code /chunky center <x> <z>}, {@code /chunky
 * shape square}, and {@code /chunky radius <radius>} (radius in BLOCKS by
 * default - confirmed explicitly, since guessing wrong here would silently
 * pre-generate the wrong-sized area). {@code oceancanvas.mod.config.OceanCanvasConfig#radius()}
 * already returns exactly that unit (half the canvas size, in blocks), so
 * no unit conversion is needed at all - the one value plugs directly in.</p>
 *
 * <p>Deliberately read-only and open to any player (no permission gate),
 * matching {@link StatusCommand}'s own read-only commands - this never
 * touches the world, config, or any file Chunky itself would read; it only
 * ever writes its own separate, clearly-named export file and prints
 * copyable text to chat.</p>
 */
public final class ChunkyExportCommand {

	private static final String EXPORT_FILE_NAME = "oceancanvas_chunky_export.mcfunction";

	private ChunkyExportCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("chunky")
								.then(Commands.literal("export")
										.executes(ChunkyExportCommand::export)))
		);
	}

	private static int export(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		OceanCanvasConfig config = OceanCanvasConfig.get();

		String lines = "# Ocean Canvas -> Chunky export - matches the canvas's current bounds.\n"
				+ "# Paste these into chat one at a time (or run as an .mcfunction if you've saved this\n"
				+ "# file into a datapack's functions folder), assuming Chunky is installed.\n"
				+ "/chunky center " + config.centerX() + " " + config.centerZ() + "\n"
				+ "/chunky shape square\n"
				+ "/chunky radius " + config.radius() + "\n"
				+ "/chunky start\n";

		Path exportPath = FabricLoader.getInstance().getConfigDir().resolve(EXPORT_FILE_NAME);
		String fileNote;
		try {
			Files.createDirectories(exportPath.getParent());
			try (OutputStream out = Files.newOutputStream(exportPath)) {
				out.write(lines.getBytes(StandardCharsets.UTF_8));
			}
			fileNote = " Also written to " + exportPath + ".";
		} catch (IOException e) {
			OceanCanvas.LOGGER.warn("(Ocean Canvas) Failed to write {}", EXPORT_FILE_NAME, e);
			fileNote = " (Could not also write this to a file: " + e.getMessage() + " - the text below is still valid.)";
		}

		final String finalFileNote = fileNote;
		// Localization foundation (drafted this round) - see
		// AdminCommand#run's matching comment for the full scope decision.
		source.sendSuccess(() -> Component.translatable("oceancanvas.chunky.export.header")
				.append(Component.literal("\n" + lines + finalFileNote)), false);
		return 1;
	}
}
