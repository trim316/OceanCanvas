package net.oceancanvas.mod.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.oceancanvas.mod.gui.OceanCanvasSettingsScreen;

/** Mod Menu entry point for the canonical Ocean Canvas settings screen. */
public final class OceanCanvasModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return OceanCanvasSettingsScreen::new;
    }
}
