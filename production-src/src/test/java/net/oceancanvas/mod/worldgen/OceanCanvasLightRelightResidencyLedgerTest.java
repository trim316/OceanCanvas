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
}
