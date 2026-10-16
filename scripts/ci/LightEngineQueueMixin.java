package net.oceancanvas.test.mixin;
import net.oceancanvas.test.LightQueueMarker;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LightEngine.class,remap=false)
public abstract class LightEngineQueueMixin {
 @Shadow @Final private LongArrayFIFOQueue increaseQueue;
 @Shadow @Final private LongArrayFIFOQueue decreaseQueue;
 @Inject(method="<init>",at=@At("RETURN"),require=1,remap=false)
 private void lightQueue$mark(CallbackInfo ci){((LightQueueMarker)increaseQueue).lightQueue$mark();((LightQueueMarker)decreaseQueue).lightQueue$mark();}
}
