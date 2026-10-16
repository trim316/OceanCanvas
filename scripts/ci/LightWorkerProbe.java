package net.oceancanvas.test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.thread.TaskScheduler;
public final class LightWorkerProbe implements ModInitializer {
 public static final boolean ENABLED=Boolean.getBoolean("oceancanvas.lightWorkerBatch");
 public static final AtomicLong marked=new AtomicLong(),tasks=new AtomicLong(),groups=new AtomicLong(),failures=new AtomicLong();
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{if(server.getTickCount()%200==0)System.out.println("LIGHT_WORKER_SNAPSHOT enabled="+ENABLED+" marked="+marked+" tasks="+tasks+" groups="+groups+" failures="+failures);});}
 public static void schedule(TaskScheduler<Runnable> worker,List<Runnable> originals,Runnable poll){
  tasks.addAndGet(originals.size());
  List<CompletableFuture<?>> pending=new ArrayList<>();
  for(int start=0;start<originals.size();start+=64){
   int lo=start,hi=Math.min(originals.size(),start+64);groups.incrementAndGet();
   pending.add(worker.scheduleWithResult(done->{
    Throwable first=null;
    for(int i=lo;i<hi;i++)try{originals.get(i).run();}catch(RuntimeException|Error ex){failures.incrementAndGet();if(first==null)first=ex;else if(first!=ex)first.addSuppressed(ex);}
    // Native failed tasks leave their completion future pending. Execute later
    // original wrappers, then expose the failure to the native executor.
    if(first instanceof Error error)throw error;
    if(first instanceof RuntimeException error)throw error;
    done.complete(net.minecraft.util.Unit.INSTANCE);
   }));
  }
  CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenAccept(ignored->poll.run());
 }
}
