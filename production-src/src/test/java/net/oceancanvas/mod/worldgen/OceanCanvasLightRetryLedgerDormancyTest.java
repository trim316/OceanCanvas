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

        // R1-256 supersedes the old lazy-duplicate diagnostic contract. One
        // authoritative pressure deadline now has exactly one scheduler node.
        // Repeated offers cannot create extra wake work or replace the original
        // deadline; after the sole node is consumed, the key may be scheduled again.
        long tail = 0xFFFF_FFE9_FFFF_FFE9L; // (-23,-23), matching the preserved RED tail.
        ledger.offerPressurePark(tail, 700L);
        ledger.offerPressurePark(tail, 705L);
        require(ledger.pressureParkSize() == 1,
                "repeated pressure admission must retain exactly one scheduler node");
        require(ledger.pressureParkNodeCount(tail) == 1 && ledger.pressureParkDistinctNodeKeys() == 1,
                "pressure attribution must identify one node for the one affected pressure key");
        require(ledger.pollDuePressurePark(699L) == null,
                "duplicate admission must not accelerate the original finite deadline");
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry firstTail = ledger.pollDuePressurePark(700L);
        require(firstTail != null && firstTail.packed() == tail && firstTail.dueTick() == 700L,
                "the original pressure node must remain wakeable at its finite deadline");
        require(ledger.pressureParkSize() == 0 && ledger.queuesEmpty(),
                "consuming the sole pressure node must leave no stale duplicate retry tail");
        require(ledger.pressureParkNodeCount(tail) == 0 && ledger.pressureParkDistinctNodeKeys() == 0,
                "per-key attribution must retire with the sole scheduler node");
        require(ledger.pollDuePressurePark(Long.MAX_VALUE) == null,
                "ignored duplicate admission must not survive as hidden scheduler work");

        ledger.offerPressurePark(tail, 705L);
        require(ledger.pressureParkSize() == 1 && ledger.pressureParkNodeCount(tail) == 1,
                "a consumed key must remain eligible for a later legitimate pressure cycle");
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry secondTail = ledger.pollDuePressurePark(705L);
        require(secondTail != null && secondTail.packed() == tail && secondTail.dueTick() == 705L,
                "re-armed pressure work must retain its new finite deadline");
        require(ledger.pressureParkSize() == 0 && ledger.pressureParkNodeCount(tail) == 0,
                "re-armed pressure work must retire exactly once after consumption");

        ledger.offerGenericDormant(33L);
        ledger.offerPressurePark(44L, 900L);
        ledger.clear();
        require(ledger.genericDormantSize() == 0 && ledger.pressureParkSize() == 0,
                "clear must retire both scheduler lanes and dormant membership");
        require(ledger.pressureParkNodeCount(44L) == 0 && ledger.pressureParkDistinctNodeKeys() == 0,
                "clear must also retire pressure-node diagnostic attribution");
        System.out.println("R1-256 pressure retry dedup regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
