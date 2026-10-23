package net.oceancanvas.mod.worldgen;

/** Deterministic regression for oldest authoritative pressure-tail selection. */
public final class OceanCanvasPressureTailWorstDebtTest {
    public static void main(String[] args) {
        long a = (((long) -23) << 32) ^ (-23 & 0xffffffffL);
        long b = (((long) 4) << 32) ^ (7 & 0xffffffffL);
        long c = (((long) 8) << 32) ^ (9 & 0xffffffffL);
        var authority = new OceanCanvasPrimitiveLongLongMap();
        var ledger = new OceanCanvasLightRetryLedger();

        require(OceanCanvasPressureTailWorstDebt.select(1000L, authority, ledger) == null,
                "empty authoritative pressure debt must produce no sample");

        authority.put(a, 900L); // overdue by 100
        authority.put(b, 700L); // overdue by 300: must win
        authority.put(c, 1200L); // future debt cannot outrank overdue debt
        ledger.offerPressurePark(a, 900L);
        ledger.offerPressurePark(b, 700L);
        ledger.offerPressurePark(b, 700L);
        ledger.offerPressurePark(c, 1200L);

        var worst = OceanCanvasPressureTailWorstDebt.select(1000L, authority, ledger);
        require(worst != null && worst.packed() == b, "oldest overdue authoritative debt must win");
        require(worst.snapshot().state() == OceanCanvasPressureTailAttribution.State.AUTHORITATIVE_OVERDUE,
                "winner must preserve authoritative overdue classification");
        require(worst.snapshot().overdueTicks() == 300L && worst.snapshot().schedulerNodes() == 2,
                "winner must retain exact overdue age and scheduler-node multiplicity");

        authority.remove(b);
        worst = OceanCanvasPressureTailWorstDebt.select(1000L, authority, ledger);
        require(worst != null && worst.packed() == a,
                "stale scheduler nodes without authority must not remain eligible");
        require(worst.snapshot().overdueTicks() == 100L,
                "next authoritative overdue debt must become visible after removal");

        System.out.println("R1-250 worst authoritative pressure-tail regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
