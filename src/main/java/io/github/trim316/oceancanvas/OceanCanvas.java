package io.github.trim316.oceancanvas;

import io.github.trim316.oceancanvas.command.OceanCanvasCommands;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OceanCanvas implements ModInitializer {
    public static final String MOD_ID = "oceancanvas";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        OceanCanvasCommands.register();
        LOGGER.info("Ocean Canvas {} initialized. Terrain transformation is not enabled in this bootstrap build.", OceanCanvasVersion.current());
    }
}
