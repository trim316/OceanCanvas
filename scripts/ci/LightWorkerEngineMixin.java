package net.oceancanvas.test.mixin;
import net.oceancanvas.test.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.util.thread.ConsecutiveExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ThreadedLevelLightEngine.class,remap=false)
public abstract class LightWorkerEngineMixin {
 @Inject(method="<init>",at=@At("RETURN"),remap=false)
 private void lightWorker$mark(LightChunkGetter getter,ChunkMap map,boolean sky,ConsecutiveExecutor worker,ChunkTaskDispatcher dispatcher,CallbackInfo ci){((LightWorkerMarker)dispatcher).lightWorker$mark();}
}
