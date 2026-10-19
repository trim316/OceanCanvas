package net.oceancanvas.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.oceancanvas.mod.server.AquaticWildlifeRuntime;
import net.oceancanvas.mod.server.AuthoritativeOperatorRuntime;

/** Registers the authoritative P0-P2 operator runtimes after the core initializer. */
public final class AuthoritativeOperatorBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        var configDir = FabricLoader.getInstance().getConfigDir();
        AuthoritativeOperatorRuntime.register(configDir);
        AquaticWildlifeRuntime.register(configDir);
    }
}
