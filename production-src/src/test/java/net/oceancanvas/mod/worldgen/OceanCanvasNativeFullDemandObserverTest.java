package net.oceancanvas.mod.worldgen;
import java.util.concurrent.CompletableFuture;import java.util.concurrent.atomic.AtomicInteger;import java.util.function.Supplier;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasNativeFullDemandObserverTest {
 @Test void foreignCallRetainsOriginalSupplier() {Supplier<Integer> s=()->3;assertSame(s,OceanCanvasNativeFullDemandObserver.capture(s));}
 @Test void capturedSupplierExecutesOnceAndReturnsExactNativeFuture() {
  var trace=new OceanCanvasFullDemandTrace(System.nanoTime());var nativeFuture=new CompletableFuture<Integer>();var calls=new AtomicInteger();
  Supplier<CompletableFuture<Integer>> original=()->{calls.incrementAndGet();return nativeFuture;};
  var captured=OceanCanvasNativeFullDemandObserver.call(trace,()->OceanCanvasNativeFullDemandObserver.capture(original));
  assertSame(original,OceanCanvasNativeFullDemandObserver.capture(original));
  assertSame(nativeFuture,captured.get());assertEquals(1,calls.get());assertTrue(trace.snapshot(System.nanoTime()).contains("phase=NATIVE_MAIN_RETURNED"));
  nativeFuture.complete(4);assertTrue(trace.snapshot(System.nanoTime()).contains("phase=NATIVE_FUTURE_DONE"));assertEquals(4,nativeFuture.getNow(0));
 }
 @Test void nativeExceptionPropagatesAndTokenIsCleared() {
  var trace=new OceanCanvasFullDemandTrace(1);var failure=new IllegalArgumentException("native");Supplier<Integer> original=()->{throw failure;};
  assertSame(failure,assertThrows(IllegalArgumentException.class,()->OceanCanvasNativeFullDemandObserver.call(trace,()->OceanCanvasNativeFullDemandObserver.capture(original).get())));
  assertSame(original,OceanCanvasNativeFullDemandObserver.capture(original));
 }
 @Test void exceptionalFutureKeepsExactOutcomeAndLateCompletionCannotReopenTerminal() {
  var trace=new OceanCanvasFullDemandTrace(1);var f=new CompletableFuture<Integer>();
  var wrapped=OceanCanvasNativeFullDemandObserver.call(trace,()->OceanCanvasNativeFullDemandObserver.capture(()->f));assertSame(f,wrapped.get());
  trace.advance(OceanCanvasFullDemandTrace.Phase.TERMINAL,2);f.completeExceptionally(new IllegalStateException("native"));
  assertTrue(f.isCompletedExceptionally());assertTrue(trace.snapshot(3).contains("phase=TERMINAL"));
 }
}
