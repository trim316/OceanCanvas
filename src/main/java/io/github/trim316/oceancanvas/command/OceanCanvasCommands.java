package io.github.trim316.oceancanvas.command;

import com.mojang.brigadier.Command;
import io.github.trim316.oceancanvas.OceanCanvasVersion;
import io.github.trim316.oceancanvas.config.OceanCanvasSettings;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class OceanCanvasCommands {
    private OceanCanvasCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("oceancanvas")
                        .executes(context -> showStatus(context.getSource()))
                        .then(Commands.literal("status")
                                .executes(context -> showStatus(context.getSource()))))
        );
    }

    private static int showStatus(net.minecraft.commands.CommandSourceStack source) {
        OceanCanvasSettings settings = OceanCanvasSettings.defaults();
        source.sendSuccess(() -> Component.literal("Ocean Canvas " + OceanCanvasVersion.current()), false);
        source.sendSuccess(() -> Component.literal(
                "Bootstrap mode: terrain transformation disabled; default protected ocean is "
                        + settings.fullOceanWidth() + " × " + settings.fullOceanWidth()
                        + " blocks with a " + settings.transitionWidth() + "-block transition."
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
