package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.concurrent.atomic.AtomicInteger;
class OceanCanvasCarveTraceTest {
 @Test void returnsExactResultAndRecordsOriginalOnce(){for(boolean result:new boolean[]{false,true}){var t=new OceanCanvasCarveTrace(10);var calls=new AtomicInteger();var sinks=new AtomicInteger();assertEquals(result,t.run(()->{calls.incrementAndGet();return result;},()->30,x->sinks.incrementAndGet()));assertEquals(1,calls.get());assertEquals(1,sinks.get());assertEquals(20,t.finishedTotal());assertTrue(t.snapshot().contains("returned=true completed="+result));}}
 @Test void exceptionStillFinalizesAndPropagatesIdentity(){var t=new OceanCanvasCarveTrace(1);var ex=new IllegalStateException("original");var sinks=new AtomicInteger();assertSame(ex,assertThrows(IllegalStateException.class,()->t.run(()->{throw ex;},()->9,x->sinks.incrementAndGet())));assertEquals(1,sinks.get());assertTrue(t.snapshot().contains("returned=false"));}
 @Test void phaseDurationsPartitionInvocation(){var t=new OceanCanvasCarveTrace(10);t.phase(OceanCanvasCarveTrace.Phase.SETUP,20);t.phase(OceanCanvasCarveTrace.Phase.LAVA,40);t.run(()->false,()->50,x->{});assertTrue(t.snapshot().contains("phaseNs=[10, 20, 10, 0, 0]"));assertEquals(40,t.finishedTotal());}
 @Test void separateInvocationsDoNotLeakEligibility(){var t=new OceanCanvasCarveTrace(1);t.cooperative=true;assertFalse(new OceanCanvasCarveTrace(2).cooperative);}
}
