package net.oceancanvas.mod.worldgen;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
/** Captures only the exact bridge's existing asynchronous native supplier. Attribution only. */
public final class OceanCanvasNativeFullDemandObserver {
 private static final ThreadLocal<OceanCanvasFullDemandTrace> CURRENT = new ThreadLocal<>();
 private OceanCanvasNativeFullDemandObserver() {}
 static <T> T call(OceanCanvasFullDemandTrace trace, Supplier<T> original) {
  var previous=CURRENT.get();CURRENT.set(trace);
  try{return original.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
 }
 public static <T> Supplier<T> capture(Supplier<T> original) {
  var trace=CURRENT.get();if(trace==null)return original;
  return ()->{
   trace.advance(OceanCanvasFullDemandTrace.Phase.NATIVE_MAIN_ENTERED,System.nanoTime());
   T result=original.get();
   trace.advance(OceanCanvasFullDemandTrace.Phase.NATIVE_MAIN_RETURNED,System.nanoTime());
   if(result instanceof CompletableFuture<?> future)future.whenComplete((value,error)->trace.advance(OceanCanvasFullDemandTrace.Phase.NATIVE_FUTURE_DONE,System.nanoTime()));
   return result;
  };
 }
}
