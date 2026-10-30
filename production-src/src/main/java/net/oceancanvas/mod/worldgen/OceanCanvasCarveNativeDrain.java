package net.oceancanvas.mod.worldgen;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
/** Consumer-completion pacing for owned background cooperative mutation invocations. */
final class OceanCanvasCarveNativeDrain {
 static final class Invocation { boolean cooperative, mutated; }
 private final OceanCanvasLightSliceFence fence = new OceanCanvasLightSliceFence();
 OceanCanvasLightSliceFence.Status status(boolean background) {
  return background ? fence.status() : OceanCanvasLightSliceFence.Status.READY;
 }
 boolean finish(boolean background, Invocation invocation, Supplier<CompletableFuture<?>> nativePost) {
  if (!background || !invocation.cooperative || !invocation.mutated) return false;
  if (fence.status() != OceanCanvasLightSliceFence.Status.READY) throw new IllegalStateException("Prior carve native work pending or failed");
  try { fence.submitted(nativePost.get()); }
  catch (RuntimeException | Error failure) {
   fence.submitted(CompletableFuture.failedFuture(failure));
   throw failure;
  }
  return true;
 }
}
