package net.oceancanvas.mod.worldgen;

/** Deterministic scheduler-lane regression with no Minecraft dependency. */
public final class OceanCanvasLightRetryLedgerDormancyTest {
    public static void main(String[] args) {
        OceanCanvasLightRetryLedger ledger = new OceanCanvasLightRetryLedger();
        ledger.offerPressurePark(11L, 500L);
        ledger.offerGenericDormant(22L);
        ledger.offerGenericDormant(22L);
        ledger.offerGenericDormant(22L);

        require(ledger.pressureParkSize() == 1, "mixed pressure lane must contain only finite recovery entry");
        require(ledger.pressureParkNodeCount(11L) == 1 && ledger.pressureParkDistinctNodeKeys() == 1,
                "pressure attribution must identify one scheduler node for the one live key");
        require(ledger.genericDormantSize() == 1, "repeated generic admission must remain one dormant obligation");
        require(ledger.pollDuePressurePark(499L) == null, "dormancy must not accelerate finite pressure backoff");
        require(ledger.pressureParkNodeCount(11L) == 1,
                "not-yet-due polling must not consume pressure-node attribution");
        require(ledger.pollGenericDormant(false) == null, "dormant generic debt must not wake while terrain remains");
        require(ledger.genericDormantSize() == 1, "terrain-active polling must not retire dormant membership");
        require(ledger.pressureParkSize() == 1, "generic dormancy polling must not consume mixed recovery heap");

        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry dormant = ledger.pollGenericDormant(true);
        require(dormant != null && dormant.packed() == 22L, "terrain completion must expose generic dormant debt");
        require(dormant.dueTick() == Long.MAX_VALUE, "generic dormant lane must retain stable sentinel");
        require(ledger.genericDormantSize() == 0, "successful wake must retire dormant membership exactly once");
        require(ledger.pollGenericDormant(true) == null, "duplicate admissions must not create duplicate wake work");
        require(ledger.pollDuePressurePark(499L) == null, "generic wake must not accelerate mixed finite deadline");

        ledger.offerGenericDormant(22L);
        require(ledger.genericDormantSize() == 1, "woken key must be eligible for a later legitimate dormancy cycle");
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry rearmed = ledger.pollGenericDormant(true);
        require(rearmed != null && rearmed.packed() == 22L && ledger.genericDormantSize() == 0,
                "re-armed dormant membership must wake exactly once");

        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pressure = ledger.pollDuePressurePark(500L);
        require(pressure != null && pressure.packed() == 11L && pressure.dueTick() == 500L,
                "mixed pressure entry must wake only at its original finite deadline");
        require(ledger.pressureParkNodeCount(11L) == 0 && ledger.pressureParkDistinctNodeKeys() == 0,
                "consuming the final node must retire its per-key diagnostic identity");

        final int cohort = 4096;
        for (int i = 0; i < cohort; i++) {
            long packed = 100_000L + i;
            ledger.offerGenericDormant(packed);
            ledger.offerGenericDormant(packed);
        }
        require(ledger.genericDormantSize() == cohort,
                "large dormant cohort must retain exactly one live membership per packed key");
        require(ledger.pollGenericDormant(false) == null,
                "large dormant cohort must remain completely invisible while terrain is active");
        require(ledger.genericDormantSize() == cohort,
                "terrain-active probe must not consume any large-cohort membership");

        boolean[] seen = new boolean[cohort];
        int drained = 0;
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry;
        while ((entry = ledger.pollGenericDormant(true)) != null) {
            int index = (int) (entry.packed() - 100_000L);
            require(index >= 0 && index < cohort, "large dormant cohort returned an unknown packed key");
            require(!seen[index], "large dormant cohort returned a duplicate packed key");
            require(entry.dueTick() == Long.MAX_VALUE, "large dormant cohort lost stable sentinel identity");
            seen[index] = true;
            drained++;
        }
        require(drained == cohort, "large dormant cohort must drain every obligation exactly once");
        require(ledger.genericDormantSize() == 0,
                "large dormant cohort membership must be empty after exact drain");

        // R1-246: the failed terminal-tail proof showed pressureRetryQueued=2 for one
        // visible tail chunk. Pressure nodes are intentionally lazy-invalidated, so
        // multiple heap entries for one packed key are not by themselves evidence of
        // lost liveness. Pin the exact scheduler behavior: both finite nodes become
        // observable at their own deadlines and the lane becomes empty afterward.
        long tail = 0xFFFF_FFE9_FFFF_FFE9L; // (-23,-23), matching the preserved RED tail.
        ledger.offerPressurePark(tail, 700L);
        ledger.offerPressurePark(tail, 705L);
        require(ledger.pressureParkSize() == 2,
                "pressure retry telemetry must count scheduler nodes, including stale/lazy-invalidated nodes");
        require(ledger.pressureParkNodeCount(tail) == 2 && ledger.pressureParkDistinctNodeKeys() == 1,
                "R1-247 must distinguish two scheduler nodes from one affected pressure key");
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry firstTail = ledger.pollDuePressurePark(700L);
        require(firstTail != null && firstTail.packed() == tail && firstTail.dueTick() == 700L,
                "earliest duplicate pressure node must remain wakeable at its finite deadline");
        require(ledger.pressureParkSize() == 1,
                "draining earliest duplicate pressure node must leave only the later scheduler node");
        require(ledger.pressureParkNodeCount(tail) == 1 && ledger.pressureParkDistinctNodeKeys() == 1,
                "per-key attribution must decrement exactly with the consumed duplicate node");
        require(ledger.pollDuePressurePark(704L) == null,
                "later duplicate pressure node must not be accelerated by draining the earlier node");
        require(ledger.pressureParkNodeCount(tail) == 1,
                "not-yet-due duplicate polling must preserve the remaining per-key node count");
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry secondTail = ledger.pollDuePressurePark(705L);
        require(secondTail != null && secondTail.packed() == tail && secondTail.dueTick() == 705L,
                "later duplicate pressure node must remain independently drainable");
        require(ledger.pressureParkSize() == 0 && ledger.queuesEmpty(),
                "duplicate pressure scheduler nodes must not create an immortal retry tail after both deadlines drain");
        require(ledger.pressureParkNodeCount(tail) == 0 && ledger.pressureParkDistinctNodeKeys() == 0,
                "per-key attribution must fully retire after the final duplicate scheduler node drains");

        ledger.offerGenericDormant(33L);
        ledger.offerPressurePark(44L, 900L);
        ledger.clear();
        require(ledger.genericDormantSize() == 0 && ledger.pressureParkSize() == 0,
                "clear must retire both scheduler lanes and dormant membership");
        require(ledger.pressureParkNodeCount(44L) == 0 && ledger.pressureParkDistinctNodeKeys() == 0,
                "clear must also retire pressure-node diagnostic attribution");
        System.out.println("R1-247 pressure retry node attribution regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
