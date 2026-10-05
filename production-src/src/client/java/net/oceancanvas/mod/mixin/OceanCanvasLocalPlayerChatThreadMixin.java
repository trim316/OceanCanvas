package net.oceancanvas.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v253.125.51: 26.2 integrated-server teardown can call LocalPlayer.refreshChatAbilities()
 * from the server thread. The .50 runtime caught ChatComponent refreshing its ArrayList
 * concurrently with the render thread and throwing ConcurrentModificationException, which
 * aborted stopServer before the final save completion markers. Marshal only this client-UI
 * refresh back to the Minecraft render thread; all server shutdown/persistence semantics stay
 * vanilla. require=0 keeps the workaround fail-soft across mapping changes.
 */
@Mixin(LocalPlayer.class)
public abstract class OceanCanvasLocalPlayerChatThreadMixin {
    @Inject(method = "refreshChatAbilities", at = @At("HEAD"), cancellable = true, require = 0)
    private void oceancanvas$marshalChatAbilityRefreshToClientThread(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.isSameThread()) return;
        LocalPlayer self = (LocalPlayer)(Object)this;
        client.execute(self::refreshChatAbilities);
        ci.cancel();
    }
}
