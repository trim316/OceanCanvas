package net.oceancanvas.mod;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.gui.OceanCanvasGroundZeroScreen;
import net.oceancanvas.mod.network.OceanCanvasZoneClientCache;

/**
 * Ground-zero client bootstrap for the Ocean Canvas UI rebuild.
 *
 * <p>The previous UI implementation is intentionally not referenced from active
 * source. Its source has been preserved in {@code docs/archive/legacy-ui-source-v253.15.zip} so individual
 * capabilities can be reintroduced deliberately without carrying forward the
 * old screen architecture.</p>
 */
public final class OceanCanvasClient implements ClientModInitializer {
    private static KeyMapping openUiKey;
    private static boolean openRequested;

    @Override
    public void onInitializeClient() {
        // v253.72.7: FastQuit can detach the client before the integrated server
        // reaches SERVER_STOPPING. Signal the common/server-side coordinator at
        // the network disconnect boundary so any in-progress Ocean Canvas chunk
        // pass yields quickly instead of continuing to feed work behind the title
        // screen. This is safe for multiplayer: that remote server is not in this
        // JVM, and a later integrated SERVER_STARTING resets the flag.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.request("client-play-disconnect");
            net.oceancanvas.mod.planning.OceanCanvasReferenceTextureCache.clear();
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
                net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.request("client-stopping"));

        // Keep the client data cache and optional Voxy compatibility alive: these
        // are feature infrastructure, not presentation code.
        OceanCanvasZoneClientCache.register();
        net.oceancanvas.mod.compat.OceanCanvasVoxyCompat.register();
        // Selectively recover the proven 26.2 in-world Blueprint renderer from the v253.14 archive.
        // The rejected legacy screen tree stays archived; only this presentation-independent renderer returns.
        net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.register();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "main"));
        openUiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.oceancanvas.ui", InputConstants.Type.KEYSYM, InputConstants.KEY_M, category));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ocmap").executes(context -> {
                    openRequested = true;
                    return 1;
                })));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openUiKey != null && openUiKey.consumeClick()) openRequested = true;
            if (openRequested) {
                openRequested = false;
                client.gui.setScreen(new OceanCanvasGroundZeroScreen(null));
            }
        });
    }
}
