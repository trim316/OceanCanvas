package io.github.trim316.oceancanvas;

import net.fabricmc.loader.api.FabricLoader;

public final class OceanCanvasVersion {
    private OceanCanvasVersion() {
    }

    public static String current() {
        return FabricLoader.getInstance()
                .getModContainer(OceanCanvas.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
