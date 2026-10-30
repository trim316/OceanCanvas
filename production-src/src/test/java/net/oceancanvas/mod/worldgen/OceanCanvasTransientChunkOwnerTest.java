package net.oceancanvas.mod.worldgen;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasTransientChunkOwnerTest {
 record Scan(Object chunk,int cursor) {}
 @Test void unloadDiscardsOnlyMatchingIdentityAndKey() {
  var states=new OceanCanvasPrimitiveLongObjectMap<Scan>();var old=new Object();var newer=new Object();
  var scan=new Scan(old,71);var other=new Scan(newer,42);states.put(1,scan);states.put(2,other);
  assertFalse(OceanCanvasTransientChunkOwner.discardMatching(states,1,newer,Scan::chunk));
  assertSame(scan,states.get(1));assertTrue(OceanCanvasTransientChunkOwner.discardMatching(states,1,old,Scan::chunk));
  assertNull(states.get(1));assertSame(other,states.get(2));assertEquals(1,states.size());
 }
 @Test void staleUnloadCannotDiscardReplacementScan() {
  var states=new OceanCanvasPrimitiveLongObjectMap<Scan>();var old=new Object();var live=new Object();
  var replacement=new Scan(live,8);states.put(1,replacement);
  assertFalse(OceanCanvasTransientChunkOwner.discardMatching(states,1,old,Scan::chunk));assertSame(replacement,states.get(1));
 }
 @Test void detachmentDropsOwnerWithoutCompletingNativeFence() {
  var old=new Object();var live=new Object();var owner=new OceanCanvasTransientChunkOwner<>(old);
  var fence=new OceanCanvasLightSliceFence();var nativeWork=new CompletableFuture<Void>();fence.submitted(nativeWork);
  assertFalse(owner.detach(live));assertTrue(owner.matches(old));assertTrue(owner.detach(old));
  assertFalse(owner.matches(old));assertFalse(owner.matches(live));assertFalse(owner.matches(null));
  assertEquals(OceanCanvasLightSliceFence.Status.PENDING,fence.status());assertFalse(nativeWork.isDone());
  nativeWork.complete(null);assertEquals(OceanCanvasLightSliceFence.Status.READY,fence.status());
  var restarted=new OceanCanvasTransientChunkOwner<>(live);assertTrue(restarted.matches(live));
 }
 @Test void detachedNativeFailureAndCancellationRemainFailed() {
  for(boolean cancel:new boolean[]{false,true}) {
   var chunk=new Object();var owner=new OceanCanvasTransientChunkOwner<>(chunk);var fence=new OceanCanvasLightSliceFence();
   var work=new CompletableFuture<Void>();fence.submitted(work);assertTrue(owner.detach(chunk));
   if(cancel)work.cancel(false);else work.completeExceptionally(new IllegalStateException("native"));
   assertEquals(OceanCanvasLightSliceFence.Status.FAILED,fence.status());
   assertThrows(IllegalStateException.class,()->fence.submitted(CompletableFuture.completedFuture(null)));
  }
 }
}
