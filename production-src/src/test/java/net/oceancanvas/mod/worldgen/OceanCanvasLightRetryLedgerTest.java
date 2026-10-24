package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasLightRetryLedgerTest {
    @Test
    void pressureParkKeepsExactlyOneSchedulerNodePerChunk() {
        OceanCanvasLightRetryLedger ledger = new OceanCanvasLightRetryLedger();
        long packed = 0x1234_5678_9abc_def0L;

        ledger.offerPressurePark(packed, 100L);
        ledger.offerPressurePark(packed, 120L);
        ledger.offerPressurePark(packed, 80L);

        assertEquals(1, ledger.pressureParkSize());
        assertEquals(1, ledger.pressureParkNodeCount(packed));
        assertEquals(1, ledger.pressureParkDistinctNodeKeys());
        assertNull(ledger.pollDuePressurePark(99L));

        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry due = ledger.pollDuePressurePark(100L);
        assertNotNull(due);
        assertEquals(packed, due.packed());
        assertEquals(0, ledger.pressureParkSize());
        assertEquals(0, ledger.pressureParkNodeCount(packed));
        assertEquals(0, ledger.pressureParkDistinctNodeKeys());
        assertNull(ledger.pollDuePressurePark(Long.MAX_VALUE));
    }

    @Test
    void pressureParkCanBeScheduledAgainAfterItsOnlyNodeIsConsumed() {
        OceanCanvasLightRetryLedger ledger = new OceanCanvasLightRetryLedger();
        long packed = 42L;

        ledger.offerPressurePark(packed, 5L);
        assertNotNull(ledger.pollDuePressurePark(5L));
        ledger.offerPressurePark(packed, 9L);

        assertEquals(1, ledger.pressureParkSize());
        assertEquals(1, ledger.pressureParkNodeCount(packed));
        assertNull(ledger.pollDuePressurePark(8L));
        assertNotNull(ledger.pollDuePressurePark(9L));
        assertTrue(ledger.queuesEmpty());
    }
}
