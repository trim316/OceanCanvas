package net.oceancanvas.test.mixin;
import net.oceancanvas.test.*;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LongArrayFIFOQueue.class,remap=false)
public abstract class LightQueueMixin implements LightQueueMarker {
 @Shadow protected transient int length;
 @Unique private boolean lightQueue$marked;
 public void lightQueue$mark(){if(!lightQueue$marked){lightQueue$marked=true;LightQueueProbe.marked.incrementAndGet();}}
 // ordinal zero is the capacity floor; ordinal one remains the native /4 occupancy test.
 @ModifyConstant(method="reduce",constant=@Constant(intValue=4,ordinal=0),require=1,allow=1,remap=false)
 private int lightQueue$floor(int nativeFloor){return lightQueue$marked&&LightQueueProbe.RETAIN?65536:nativeFloor;}
 @Inject(method="resize",at=@At("HEAD"),require=1,remap=false)
 private void lightQueue$resize(int size,int newLength,CallbackInfo ci){if(lightQueue$marked)LightQueueProbe.resize(length,newLength);}
}
