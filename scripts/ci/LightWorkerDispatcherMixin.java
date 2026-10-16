package net.oceancanvas.test.mixin;
import net.oceancanvas.test.*;
import net.minecraft.server.level.*;
import net.minecraft.util.thread.TaskScheduler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ChunkTaskDispatcher.class,remap=false)
public abstract class LightWorkerDispatcherMixin implements LightWorkerMarker {
 @Shadow @Final private TaskScheduler<Runnable> executor;
 @Shadow protected abstract void pollTask();
 @Unique private boolean lightWorker$marked;
 public void lightWorker$mark(){lightWorker$marked=true;LightWorkerProbe.marked.incrementAndGet();}
 @Inject(method="scheduleForExecution",at=@At("HEAD"),cancellable=true,remap=false)
 private void lightWorker$schedule(ChunkTaskPriorityQueue.TasksForChunk popped,CallbackInfo ci){
  if(!lightWorker$marked||!LightWorkerProbe.ENABLED)return;
  LightWorkerProbe.schedule(executor,popped.tasks(),this::pollTask);ci.cancel();
 }
}
