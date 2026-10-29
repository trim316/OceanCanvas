package net.oceancanvas.mod.performance;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.concurrent.atomic.AtomicLong;
class OceanCanvasPositiveStartupPollTest {
 @Test void positiveReuseStillReportsPendingUntilFreshZero() {var p=new OceanCanvasPositiveStartupPoll();var k=new Object();assertEquals(2,p.poll(k,()->0,()->2));assertEquals(2,p.poll(k,()->49_999_999,()->{throw new AssertionError();}));assertEquals(0,p.poll(k,()->50_000_000,()->0));}
 @Test void zeroIsNeverCached() {var p=new OceanCanvasPositiveStartupPoll();var k=new Object();assertEquals(0,p.poll(k,()->0,()->0));assertEquals(3,p.poll(k,()->0,()->3));assertEquals(2,p.scans());}
 @Test void counterIdentityDoesNotCrossWorldStartup() {var p=new OceanCanvasPositiveStartupPoll();assertEquals(7,p.poll(new Object(),()->0,()->7));assertEquals(0,p.poll(new Object(),()->1,()->0));}
 @Test void backwardsClockForcesFreshNativeScan() {var p=new OceanCanvasPositiveStartupPoll();var k=new Object();assertEquals(7,p.poll(k,()->100,()->7));assertEquals(0,p.poll(k,()->99,()->0));}
 @Test void backoffStartsAfterExpensiveScanCompletes() {var p=new OceanCanvasPositiveStartupPoll();var k=new Object();var clock=new AtomicLong();assertEquals(2,p.poll(k,clock::get,()->{clock.set(100_000_000);return 2;}));assertEquals(2,p.poll(k,()->149_999_999,()->{throw new AssertionError();}));assertEquals(0,p.poll(k,()->150_000_000,()->0));}
 @Test void nativeExceptionPropagatesAndClearsCachedPositive() {var p=new OceanCanvasPositiveStartupPoll();var k=new Object();p.poll(k,()->0,()->2);assertThrows(IllegalStateException.class,()->p.poll(k,()->50_000_000,()->{throw new IllegalStateException();}));assertEquals(0,p.poll(k,()->50_000_001,()->0));}
}
