package net.oceancanvas.mod.worldgen;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
/** Nonblocking observation only: never schedules, waits, attaches callbacks or consumes errors. */
final class OceanCanvasFutureObservation {
 static <T> String state(CompletableFuture<T> future, Predicate<T> success) {
  if(future==null)return "ABSENT";
  if(!future.isDone())return "PENDING";
  if(future.isCancelled())return "CANCELLED";
  if(future.isCompletedExceptionally())return "EXCEPTIONAL";
  try {T result=future.getNow(null);return result==null?"DONE_NULL":success.test(result)?"SUCCESS":"UNLOADED";}
  catch(RuntimeException failure){return "UNAVAILABLE";}
 }
}
