package net.oceancanvas.mod.diagnostic;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;import java.util.concurrent.atomic.AtomicInteger;
class OceanCanvasThreadSnapshotTest {
 @Test void onlyRequestedThreadAndFixedDepthOnce(){var calls=new AtomicInteger();var info=ManagementFactory.getThreadMXBean().getThreadInfo(Thread.currentThread().threadId(),64);var s=OceanCanvasThreadSnapshot.capture(123L,(id,depth)->{assertEquals(123L,id);assertEquals(64,depth);calls.incrementAndGet();return info;});assertEquals(1,calls.get());assertTrue(s.contains("targetId=123"));assertTrue(s.contains("locks=false"));assertTrue(s.length()<=32768);}
 @Test void invalidIdentityDoesNotInvokeProvider(){assertTrue(OceanCanvasThreadSnapshot.capture(-1L,(id,depth)->{fail();return null;}).contains("missing-server-thread-id"));}
 @Test void endedThreadIsExplicitlyUnavailable(){assertTrue(OceanCanvasThreadSnapshot.capture(123L,(id,depth)->null).contains("thread-ended"));}
 @Test void providerFailureHasNoGlobalFallback(){var calls=new AtomicInteger();var s=OceanCanvasThreadSnapshot.capture(123L,(id,depth)->{calls.incrementAndGet();throw new IllegalStateException("private details");});assertEquals(1,calls.get());assertTrue(s.contains("unavailable=IllegalStateException"));assertFalse(s.contains("private details"));}
}
