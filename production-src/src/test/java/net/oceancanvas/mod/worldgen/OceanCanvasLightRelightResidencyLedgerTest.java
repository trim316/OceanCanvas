package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OceanCanvasLightRelightResidencyLedgerTest {
    @Test void expandedContextIsBoundedAndReleasedAtItsExactRadius() throws Throwable {
        var ledger = new OceanCanvasLightRelightResidencyLedger();
        var installed = new ArrayList<Integer>();
        for (long p = 1; p <= 3; p++) ledger.install(p, 10, 3, key -> {});
        assertTrue(ledger.ensureRadius(1, 2, 2, (p,r) -> installed.add(r)));
        assertTrue(ledger.ensureRadius(1, 2, 2, (p,r) -> fail("Duplicate ticket")));
        assertTrue(ledger.ensureRadius(2, 2, 2, (p,r) -> installed.add(r)));
        assertFalse(ledger.ensureRadius(3, 2, 2, (p,r) -> fail("Exceeded expanded cap")));
        assertFalse(ledger.ensureRadius(99, 2, 2, (p,r) -> fail("Orphan ticket")));
        var removed = new ArrayList<Integer>();
        ledger.releaseWithRadii(1, (p,r) -> removed.add(r));
        assertEquals(List.of(1,2), removed);
        assertTrue(ledger.ensureRadius(3, 2, 2, (p,r) -> installed.add(r)));
        assertEquals(2, ledger.activeCount());
    }
    @Test void failedRemovalRetainsOnlyTheNativeTicketStillOwned() throws Throwable {
        var ledger = new OceanCanvasLightRelightResidencyLedger();
        ledger.install(1, 10, 1, p -> {});
        ledger.ensureRadius(1, 2, 2, (p,r) -> {});
        assertThrows(Exception.class, () -> ledger.releaseWithRadii(1, (p,r) -> { if(r == 2) throw new Exception("native failure"); }));
        assertTrue(ledger.contains(1));
        var retried = new ArrayList<Integer>();
        ledger.releaseWithRadii(1, (p,r) -> retried.add(r));
        assertEquals(List.of(2), retried);
        assertEquals(0, ledger.activeCount());
    }
    @Test void failedInstallDoesNotClaimAnExpandedNativeTicket() throws Throwable {
        var ledger = new OceanCanvasLightRelightResidencyLedger();
        ledger.install(1, 10, 1, p -> {});
        assertThrows(Exception.class, () -> ledger.ensureRadius(1, 2, 2, (p,r) -> { throw new Exception("install failure"); }));
        var removed = new ArrayList<Integer>();
        ledger.releaseWithRadii(1, (p,r) -> removed.add(r));
        assertEquals(List.of(1), removed);
    }

    @Test void nativeFullLoadBoundaryDefersOnlyNewBackgroundLeases() {
        assertTrue(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,10,3));
        assertFalse(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,11,3));
        assertFalse(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,16,3));
        assertTrue(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,16,16));
    }
    @Test void productiveAndVisibleWorkRemainEligibleDuringLoadPressure() {
        assertTrue(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(true,false,16,0));
        assertTrue(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,true,16,0));
    }
    @Test void invalidLedgerCountsFailClosedForNewBackgroundWork() {
        assertFalse(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,-1,0));
        assertFalse(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,0,-1));
        assertFalse(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,1,2));
    }
    @Test void deferralRetainsTicketsAndDebtThenResumesWithoutReplay() throws Throwable {
        var ledger = new OceanCanvasLightRelightResidencyLedger();
        ledger.install(1,10,48,key -> {});
        var pending = new java.util.HashSet<Long>(List.of(1L,2L));
        for (int i=0;i<100;i++) {
            assertFalse(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,16,3));
            assertTrue(ledger.contains(1));
            assertEquals(1,ledger.activeCount());
            assertEquals(java.util.Set.of(1L,2L),pending);
        }
        assertTrue(OceanCanvasLightRelightResidencyLedger.newLeaseAllowedDuringNativeLoads(false,false,16,16));
        assertEquals(OceanCanvasLightRelightResidencyLedger.InstallResult.INSTALLED,ledger.install(2,20,48,key -> {}));
        assertEquals(OceanCanvasLightRelightResidencyLedger.InstallResult.ALREADY_PRESENT,ledger.install(2,21,48,key -> fail("Duplicate")));
        assertEquals(2,ledger.activeCount());
        assertEquals(java.util.Set.of(1L,2L),pending);
    }
}
