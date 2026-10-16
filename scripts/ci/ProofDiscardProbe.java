package net.oceancanvas.test;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.lang.reflect.*;
import java.util.stream.Collectors;
public final class ProofDiscardProbe implements ModInitializer {
 private static final ProofDiscardCounter counter=new ProofDiscardCounter();
 private static Method sessionGetter;
 private static boolean failed;
 private static long nanos,maxNanos,sweeps,ticks;
 public static void beforeReset(long key){
  if(failed)return;long start=System.nanoTime();
  try{
   if(sessionGetter==null){sessionGetter=Class.forName("net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener").getDeclaredMethod("lightFinalizerSession");sessionGetter.setAccessible(true);}
   Object session=sessionGetter.invoke(null);
   counter.observe(session,key,()->StackWalker.getInstance().walk(frames->frames.filter(f->f.getClassName().equals("net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener")).limit(8).map(f->f.getMethodName()+":"+f.getLineNumber()).collect(Collectors.joining("<-"))));
  }catch(Throwable e){failed=true;System.out.println("PROOF_DISCARD_FAILED "+e);}
  finally{long elapsed=System.nanoTime()-start;nanos+=elapsed;maxNanos=Math.max(maxNanos,elapsed);}
 }
 public static void sweepReturned(boolean complete){if(complete)sweeps++;}
 private static void dump(){
  System.out.println("PROOF_DISCARD_SAMPLE ticks="+ticks+" resets="+counter.resets+" partialSweeps="+counter.partialSweeps+" discardedChecks="+counter.discardedChecks+" completedSweeps="+sweeps+" pathSamples="+counter.pathSamples+" observerNs="+nanos+" maxObserverNs="+maxNanos+" failed="+failed+" diagnosticOnly=true");
  counter.paths.entrySet().stream().sorted(java.util.Map.Entry.<String,Long>comparingByValue().reversed()).limit(12).forEach(e->System.out.println("PROOF_DISCARD_PATH samples="+e.getValue()+" path="+e.getKey()));
 }
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{ticks++;if(ticks==1||ticks%200==0)dump();});}
}
