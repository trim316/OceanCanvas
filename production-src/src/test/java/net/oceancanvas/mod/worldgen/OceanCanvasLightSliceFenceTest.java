package net.oceancanvas.mod.worldgen;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasLightSliceFenceTest {
 @Test void incompleteSliceCannotAdvanceOrBeOverwritten() {
  var f=new OceanCanvasLightSliceFence();var work=new CompletableFuture<Void>();f.submitted(work);
  for(int i=0;i<100;i++)assertEquals(OceanCanvasLightSliceFence.Status.PENDING,f.status());
  assertThrows(IllegalStateException.class,()->f.submitted(new CompletableFuture<>()));
  work.complete(null);assertEquals(OceanCanvasLightSliceFence.Status.READY,f.status());
 }
 @Test void finalSliceAwaitsCompletionBeforeRetirementReadiness() {
  var f=new OceanCanvasLightSliceFence();var last=new CompletableFuture<Void>();f.submitted(last);
  assertNotEquals(OceanCanvasLightSliceFence.Status.READY,f.status());last.complete(null);
  assertEquals(OceanCanvasLightSliceFence.Status.READY,f.status());assertEquals(OceanCanvasLightSliceFence.Status.READY,f.status());
 }
 @Test void exceptionAndCancellationRemainFailedDebt() {
  for(boolean cancel:new boolean[]{false,true}) {
   var f=new OceanCanvasLightSliceFence();var w=new CompletableFuture<Void>();f.submitted(w);
   if(cancel)w.cancel(false);else w.completeExceptionally(new IllegalStateException("native failure"));
   assertEquals(OceanCanvasLightSliceFence.Status.FAILED,f.status());assertEquals(OceanCanvasLightSliceFence.Status.FAILED,f.status());
   assertThrows(IllegalStateException.class,()->f.submitted(CompletableFuture.completedFuture(null)));
  }
 }
 @Test void independentChunkAndRestartDoNotInheritTransientFence() {
  var f=new OceanCanvasLightSliceFence();f.submitted(new CompletableFuture<>());
  assertEquals(OceanCanvasLightSliceFence.Status.READY,new OceanCanvasLightSliceFence().status());
  assertEquals(OceanCanvasLightSliceFence.Status.PENDING,f.status());
 }
}
