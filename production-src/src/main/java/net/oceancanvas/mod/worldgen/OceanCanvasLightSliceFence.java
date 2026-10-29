package net.oceancanvas.mod.worldgen;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
/** Nonblocking next-slice admission; failed native work never becomes readiness. */
final class OceanCanvasLightSliceFence {
 enum Status { READY, PENDING, FAILED }
 private CompletableFuture<?> pending;
 Status status() {
  if(pending==null)return Status.READY;
  if(!pending.isDone())return Status.PENDING;
  if(pending.isCompletedExceptionally()||pending.isCancelled())return Status.FAILED;
  pending=null;return Status.READY;
 }
 void submitted(CompletableFuture<?> future) {
  if(status()!=Status.READY)throw new IllegalStateException("Native slice pending or failed");
  pending=Objects.requireNonNull(future);
 }
}
