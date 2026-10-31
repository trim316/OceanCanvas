package net.oceancanvas.mod.mixin;
import net.minecraft.server.level.ServerChunkCache;import net.minecraft.server.level.ServerLevel;import net.minecraft.core.SectionPos;import net.minecraft.world.level.LightLayer;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerChunkCache.class)
abstract class OceanCanvasNativeLightCertificateInvalidationMixin {
 @Shadow @Final private ServerLevel level;
 @Inject(method="lambda$onLightUpdate$0",at=@At("HEAD"))
 private void oceancanvas$invalidateAfterNativePublication(SectionPos section,LightLayer layer,CallbackInfo ci){OceanCanvasSurfaceFlattener.onNativeLightPublication(level,section);}
 @Inject(method="save(Z)V",at={@At("HEAD"),@At("RETURN")})
 private void oceancanvas$drainChangedProofTokens(boolean flush,CallbackInfo ci){OceanCanvasSurfaceFlattener.flushCompletedLightInvalidations(level);}
}
