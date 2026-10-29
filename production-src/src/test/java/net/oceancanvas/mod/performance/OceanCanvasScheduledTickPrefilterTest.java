package net.oceancanvas.mod.performance;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
class OceanCanvasScheduledTickPrefilterTest {
 @Test void completeNativeConjunctionTruthTableUnchanged() {
  for(boolean ticking:new boolean[]{false,true}) for(boolean loaded:new boolean[]{false,true}) for(boolean futureReady:new boolean[]{false,true}) {
   boolean original=loaded && ticking && futureReady;
   boolean candidate=OceanCanvasScheduledTickPrefilter.entitiesLoadedIfBlockTicking(ticking,()->loaded) && ticking && futureReady;
   assertEquals(original,candidate);
  }
 }
 @Test void untickingNeverLooksUpEntityStorage() {assertFalse(OceanCanvasScheduledTickPrefilter.entitiesLoadedIfBlockTicking(false,()->{throw new AssertionError("unneeded lookup");}));}
 @Test void tickingRunsExactLookupOnce() {var count=new AtomicInteger();assertTrue(OceanCanvasScheduledTickPrefilter.entitiesLoadedIfBlockTicking(true,()->{count.incrementAndGet();return true;}));assertEquals(1,count.get());}
 @Test void changingEntityReadinessIsNeverCached() {assertFalse(OceanCanvasScheduledTickPrefilter.entitiesLoadedIfBlockTicking(true,()->false));assertTrue(OceanCanvasScheduledTickPrefilter.entitiesLoadedIfBlockTicking(true,()->true));}
}
