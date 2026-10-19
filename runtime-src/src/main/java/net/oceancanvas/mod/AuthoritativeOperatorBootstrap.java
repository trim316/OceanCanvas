package net.oceancanvas.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.oceancanvas.mod.server.AuthoritativeOperatorRuntime;

/** Registers the authoritative P0-P2 operator runtime after the core initializer. */
public final class AuthoritativeOperatorBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        AuthoritativeOperatorRuntime.register(FabricLoader.getInstance().getConfigDir());
    }
}
