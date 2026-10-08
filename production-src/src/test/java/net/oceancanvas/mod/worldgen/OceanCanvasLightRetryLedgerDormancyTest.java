package net.oceancanvas.mod.worldgen;

/** Deterministic scheduler-lane regression with no Minecraft dependency. */
public final class OceanCanvasLightRetryLedgerDormancyTest {
    public static void main(String[] args) {
        OceanCanvasLightRetryLedger ledger = new OceanCanvasLightRetryLedger();
        ledger.offerPressurePark(11L, 500L);
        ledger.offerGenericDormant(22L);

        require(ledger.pressureParkSize() == 1, "mixed pressure lane must contain only finite recovery entry");
        require(ledger.genericDormantSize() == 1, "generic dormant entry must be physically isolated");
        require(ledger.pollDuePressurePark(499L) == null, "dormancy must not accelerate finite pressure backoff");
        require(ledger.pollGenericDormant(false) == null, "dormant generic debt must not wake while terrain remains");
        require(ledger.pressureParkSize() == 1, "generic dormancy polling must not consume mixed recovery heap");

        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry dormant = ledger.pollGenericDormant(true);
        require(dormant != null && dormant.packed() == 22L, "terrain completion must expose generic dormant debt");
        require(dormant.dueTick() == Long.MAX_VALUE, "generic dormant lane must retain stable sentinel");
        require(ledger.pollDuePressurePark(499L) == null, "generic wake must not accelerate mixed finite deadline");

        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pressure = ledger.pollDuePressurePark(500L);
        require(pressure != null && pressure.packed() == 11L && pressure.dueTick() == 500L,
                "mixed pressure entry must wake only at its original finite deadline");

        ledger.offerGenericDormant(33L);
        ledger.clear();
        require(ledger.genericDormantSize() == 0 && ledger.pressureParkSize() == 0,
                "clear must retire both scheduler lanes");
        System.out.println("R1-136 dedicated generic dormant lane PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
