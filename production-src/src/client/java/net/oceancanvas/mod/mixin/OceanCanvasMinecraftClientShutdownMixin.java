package net.oceancanvas.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v253.73.3: request Save & Quit preemption at the START of the client
 * disconnect path, before FastQuit tears down the play connection. The older
 * Fabric DISCONNECT/CLIENT_STOPPING callbacks remain as fallbacks, but runtime
 * evidence showed they can arrive after the integrated server has already
 * stopped ticking, leaving no chance for cooperative Pregen/light work to yield.
 *
 * <p>The handlers intentionally match the 26.2 target descriptors separately:
 * disconnectWithSavingScreen() has no arguments while disconnectFromWorld(...)
 * receives the disconnect message. require=0 is defensive compatibility so a
 * future mapping change falls back to the existing Fabric lifecycle callbacks
 * instead of preventing startup.</p>
 */
@Mixin(Minecraft.class)
public class OceanCanvasMinecraftClientShutdownMixin {
    @Inject(method = "disconnectWithSavingScreen", at = @At("HEAD"), require = 0)
    private void oceancanvas$requestEarlySaveQuitPreemption(CallbackInfo ci) {
        OceanCanvasShutdownCoordinator.request("client-save-quit-head");
    }

    @Inject(method = "disconnectFromWorld", at = @At("HEAD"), require = 0)
    private void oceancanvas$requestEarlyDisconnectPreemption(Component message, CallbackInfo ci) {
        OceanCanvasShutdownCoordinator.request("client-disconnect-head");
    }
}
