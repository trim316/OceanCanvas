package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class OceanCanvasLightProofContextAdmissionTest {
    private static OceanCanvasLightRelightResidencyLedger.InstallResult install(
            OceanCanvasLightRelightResidencyLedger ledger,long key) {
        try { return ledger.install(key,0,1,p->{if(key==2) fail("cap exceeded");}); }
        catch(Throwable failure) { throw new AssertionError(failure); }
    }
    @Test void missingStageZeroContextRequestsResidencyBeforeEarlyYieldThenObservesArrival() {
        var live=new AtomicReference<Object>(); var requests=new AtomicInteger();
        assertNull(OceanCanvasLightProofContextAdmission.observe(live::get,true,requests::incrementAndGet));
        assertEquals(1,requests.get());
        Object arrived=new Object();live.set(arrived);
        assertSame(arrived,OceanCanvasLightProofContextAdmission.observe(live::get,true,requests::incrementAndGet));
        assertEquals(1,requests.get());
    }
    @Test void readonlyMissingContextCannotAcquireTicketsOrBorrowProofCredit() {
        assertNull(OceanCanvasLightProofContextAdmission.observe(()->null,false,()->fail("read-only ticket request")));
    }
    @Test void capacityRefusalRetainsMissingContextWithoutInventingReadiness() {
        var ledger=new OceanCanvasLightRelightResidencyLedger();
        assertEquals(OceanCanvasLightRelightResidencyLedger.InstallResult.INSTALLED,install(ledger,1));
        var requests=new AtomicInteger();
        for(int i=0;i<3;i++) assertNull(OceanCanvasLightProofContextAdmission.observe(()->null,true,()->{
            requests.incrementAndGet();
            assertEquals(OceanCanvasLightRelightResidencyLedger.InstallResult.CAP_REJECTED,install(ledger,2));
        }));
        assertEquals(3,requests.get());assertEquals(1,ledger.activeCount());
    }
    @Test void ticketRequestDoesNotSynchronouslyReobserveOrCertifyContext() {
        var live=new AtomicReference<Object>();var reads=new AtomicInteger();
        assertNull(OceanCanvasLightProofContextAdmission.observe(()->{reads.incrementAndGet();return live.get();},true,()->live.set(new Object())));
        assertEquals(1,reads.get());
    }
}
