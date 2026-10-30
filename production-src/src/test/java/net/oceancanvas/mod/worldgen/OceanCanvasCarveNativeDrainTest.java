package net.oceancanvas.mod.worldgen;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCarveNativeDrainTest {
 private static OceanCanvasCarveNativeDrain.Invocation changed(){var i=new OceanCanvasCarveNativeDrain.Invocation();i.cooperative=true;i.mutated=true;return i;}
 @Test void nextBackgroundInvocationWaitsWithoutResubmittingOrBlocking(){
  var d=new OceanCanvasCarveNativeDrain();var future=new CompletableFuture<Void>();var count=new AtomicInteger();
  assertTrue(d.finish(true,changed(),()->{count.incrementAndGet();return future;}));
  for(int n=0;n<100;n++)assertEquals(OceanCanvasLightSliceFence.Status.PENDING,d.status(true));
  assertThrows(IllegalStateException.class,()->d.finish(true,changed(),()->{count.incrementAndGet();return future;}));
  assertEquals(1,count.get());future.complete(null);assertEquals(OceanCanvasLightSliceFence.Status.READY,d.status(true));
 }
 @Test void failedNativeSubmissionCannotReopenAdmission(){
  var d=new OceanCanvasCarveNativeDrain();var failure=new IllegalStateException("native submission");
  assertSame(failure,assertThrows(IllegalStateException.class,()->d.finish(true,changed(),()->{throw failure;})));
  assertEquals(OceanCanvasLightSliceFence.Status.FAILED,d.status(true));
  assertThrows(IllegalStateException.class,()->d.finish(true,changed(),()->{fail("retry overwrites failed work");return null;}));
 }
 @Test void zeroWriteAndNonCooperativeCallsDoNotSubmit(){
  var d=new OceanCanvasCarveNativeDrain();var i=new OceanCanvasCarveNativeDrain.Invocation();
  i.cooperative=true;assertFalse(d.finish(true,i,()->{fail("zero writes");return null;}));
  i.cooperative=false;i.mutated=true;assertFalse(d.finish(true,i,()->{fail("atomic path");return null;}));
 }
 @Test void foregroundDoesNotWaitOrReplaceBackgroundFence(){
  var d=new OceanCanvasCarveNativeDrain();var f=new CompletableFuture<Void>();d.finish(true,changed(),()->f);
  assertEquals(OceanCanvasLightSliceFence.Status.READY,d.status(false));
  assertFalse(d.finish(false,changed(),()->{fail("foreground submission");return null;}));
  assertEquals(OceanCanvasLightSliceFence.Status.PENDING,d.status(true));
 }
 @Test void errorAndCancelCannotBecomeReadinessOrBeOverwritten(){
  for(boolean cancel:new boolean[]{false,true}){var d=new OceanCanvasCarveNativeDrain();var f=new CompletableFuture<Void>();d.finish(true,changed(),()->f);
   if(cancel)f.cancel(false);else f.completeExceptionally(new IllegalStateException());
   for(int n=0;n<3;n++)assertEquals(OceanCanvasLightSliceFence.Status.FAILED,d.status(true));
   assertThrows(IllegalStateException.class,()->d.finish(true,changed(),()->{fail("failed authority overwritten");return null;}));
  }
 }
 @Test void coldSessionHasNoTransientConsumerWork(){
  var old=new OceanCanvasCarveNativeDrain();old.finish(true,changed(),CompletableFuture<Void>::new);
  assertEquals(OceanCanvasLightSliceFence.Status.READY,new OceanCanvasCarveNativeDrain().status(true));
  assertEquals(OceanCanvasLightSliceFence.Status.PENDING,old.status(true));
 }
}
