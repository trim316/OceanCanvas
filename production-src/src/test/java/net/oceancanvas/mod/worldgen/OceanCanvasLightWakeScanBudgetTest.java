package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasLightWakeScanBudgetTest {
    @Test void allCapacityRefusedCohortYieldsWithoutLosingOrAcceleratingDebt() {
        var heap = new OceanCanvasPrimitiveLongDeadlineHeap();
        var deadlines = new java.util.HashMap<Long, Long>();
        for (long i=0; i<4096; i++) { deadlines.put(i, 100L); heap.offer(i,100L); }
        var budget = new OceanCanvasLightWakeScanBudget(0);
        int attempts=0;
        while (budget.tryClaim(1)) {
            var entry=heap.pollDue(100);
            assertNotNull(entry);
            long packed=entry.packed();
            assertEquals(100L, deadlines.get(packed));
            deadlines.put(packed,105L); heap.offer(packed,105L); attempts++;
        }
        assertEquals(64,attempts);
        assertEquals(4096,deadlines.size()); assertEquals(4096,heap.size());
        assertEquals(64,deadlines.values().stream().filter(t->t==105L).count());
        // Remaining due entries are still reachable before the refused requeues.
        for(int i=0;i<4032;i++) assertEquals(100L,heap.pollDue(100).dueTick());
        assertNull(heap.pollDue(104)); assertNotNull(heap.pollDue(105));
    }
    @Test void staleEntriesConsumeAttemptsEvenWithNoSuccessfulWake() {
        var budget=new OceanCanvasLightWakeScanBudget(100);
        int attempts=0;
        while(budget.tryClaim(101)) attempts++;
        assertEquals(64,attempts);
    }
    @Test void wallBudgetYieldsBeforeAnotherEntryIsRemoved() {
        var heap=new OceanCanvasPrimitiveLongDeadlineHeap(); heap.offer(7,0);
        var budget=new OceanCanvasLightWakeScanBudget(Long.MAX_VALUE-4_000_000L);
        assertFalse(budget.tryClaim(Long.MIN_VALUE+4_000_000L));
        assertEquals(1,heap.size());
        assertTrue(new OceanCanvasLightWakeScanBudget(0).tryClaim(7_999_999));
        assertFalse(new OceanCanvasLightWakeScanBudget(0).tryClaim(8_000_000));
    }
}
