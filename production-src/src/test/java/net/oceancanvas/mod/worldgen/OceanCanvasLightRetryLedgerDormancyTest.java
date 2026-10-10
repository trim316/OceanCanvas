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
        require(ledger.genericDormantSize() == 1, "repeated generic admission must remain one dormant obligation");
        require(ledger.pollDuePressurePark(499L) == null, "dormancy must not accelerate finite pressure backoff");
        require(ledger.pollGenericDormant(false) == null, "dormant generic debt must not wake while terrain remains");
        require(ledger.genericDormantSize() == 1, "terrain-active polling must not retire dormant membership");
        require(ledger.pressureParkSize() == 1, "generic dormancy polling must not consume mixed recovery heap");

        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry dormant = ledger.pollGenericDormant(true);
        require(dormant != null && dormant.packed() == 22L, "terrain completion must expose generic dormant debt");
        require(dormant.dueTick() == Long.MAX_VALUE, "generic dormant lane must retain stable sentinel");
        require(ledger.genericDormantSize() == 0, "successful wake must retire dormant membership exactly once");
        require(ledger.pollGenericDormant(true) == null, "duplicate admissions must not create duplicate wake work");
        require(ledger.pollDuePressurePark(499L) == null, "generic wake must not accelerate mixed finite deadline");

        // After a completed wake, the same packed key may become dormant again if
        // authoritative debt is legitimately re-armed. Deduplication is per live
        // dormant membership, not a permanent tombstone.
        ledger.offerGenericDormant(22L);
        require(ledger.genericDormantSize() == 1, "woken key must be eligible for a later legitimate dormancy cycle");
        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry rearmed = ledger.pollGenericDormant(true);
        require(rearmed != null && rearmed.packed() == 22L && ledger.genericDormantSize() == 0,
                "re-armed dormant membership must wake exactly once");

        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pressure = ledger.pollDuePressurePark(500L);
        require(pressure != null && pressure.packed() == 11L && pressure.dueTick() == 500L,
                "mixed pressure entry must wake only at its original finite deadline");

        // R1-137: scale the scheduler invariant itself without claiming Minecraft scale.
        // A large terrain-phase cohort must remain exactly deduplicated, completely
        // invisible while terrain is active, and drain exactly once after completion.
        // This catches heap/membership skew before a disposable 5k campaign can hide it
        // behind runtime timing noise.
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

        ledger.offerGenericDormant(33L);
        ledger.clear();
        require(ledger.genericDormantSize() == 0 && ledger.pressureParkSize() == 0,
                "clear must retire both scheduler lanes and dormant membership");
        System.out.println("R1-137 dedicated generic dormant cohort integrity PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
