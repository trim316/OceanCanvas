package net.oceancanvas.test.mixin;
import net.oceancanvas.test.CarvePressureProbe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener",remap=false)
public abstract class CarvePressureMixin {
 @Inject(method="flattenChunk",at=@At("HEAD"),remap=false)
 private static void carve$begin(ServerLevel w,LevelChunk c,CallbackInfoReturnable<Boolean> cir){CarvePressureProbe.begin();}
 @Inject(method="flattenChunk",at=@At("RETURN"),remap=false)
 private static void carve$end(ServerLevel w,LevelChunk c,CallbackInfoReturnable<Boolean> cir){CarvePressureProbe.end(w,c,Boolean.TRUE.equals(cir.getReturnValue()));}
 @Inject(method="finishOneReadyPregenTarget",at=@At("HEAD"),remap=false)
 private static void carve$watchdog(ServerLevel w,CallbackInfoReturnable<Boolean> cir){CarvePressureProbe.watchdog();}
 @Inject(method="runtimePressureHoldsLightFinalizer",at=@At("RETURN"),remap=false)
 private static void carve$pressure(ServerLevel w,CallbackInfoReturnable<Boolean> cir){CarvePressureProbe.pressure(Boolean.TRUE.equals(cir.getReturnValue()));}
}
