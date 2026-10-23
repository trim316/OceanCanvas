package net.oceancanvas.mod.worldgen;

/** Deterministic regression for read-only authoritative pressure-tail telemetry. */
public final class OceanCanvasPressureTailAuthorityBridgeTest {
    public static void main(String[] args) {
        long packed = (((long) -23) << 32) ^ (-23 & 0xffffffffL);
        var ledger = new OceanCanvasLightRetryLedger();
        var authoritative = new OceanCanvasPrimitiveLongLongMap();

        ledger.offerPressurePark(packed, 700L);
        ledger.offerPressurePark(packed, 700L);
        var stale = OceanCanvasPressureTailAuthorityBridge.snapshot(packed, 900L, authoritative, ledger);
        require(stale.state() == OceanCanvasPressureTailAttribution.State.NO_AUTHORITATIVE_DEBT,
                "scheduler-only duplicate nodes must remain stale diagnostics");
        require(stale.schedulerNodes() == 2, "duplicate scheduler identity must remain visible");

        authoritative.put(packed, 700L);
        var overdue = OceanCanvasPressureTailAuthorityBridge.snapshot(packed, 900L, authoritative, ledger);
        require(overdue.state() == OceanCanvasPressureTailAttribution.State.AUTHORITATIVE_OVERDUE,
                "authoritative past deadline must classify overdue");
        require(overdue.schedulerNodes() == 2 && overdue.overdueTicks() == 200L,
                "bridge must combine exact authoritative deadline with per-key node count");

        require(ledger.pollDuePressurePark(900L) != null, "first due scheduler node must drain");
        var oneNode = OceanCanvasPressureTailAuthorityBridge.snapshot(packed, 900L, authoritative, ledger);
        require(oneNode.schedulerNodes() == 1,
                "bridge must observe exact node decrement without mutating authoritative debt");
        require(authoritative.get(packed) == 700L,
                "diagnostic snapshot and scheduler drain must not remove authoritative debt");

        authoritative.remove(packed);
        var staleTail = OceanCanvasPressureTailAuthorityBridge.snapshot(packed, 900L, authoritative, ledger);
        require(staleTail.state() == OceanCanvasPressureTailAttribution.State.NO_AUTHORITATIVE_DEBT,
                "removing authority must expose remaining heap node as stale only");
        require(staleTail.schedulerNodes() == 1, "remaining stale node must stay attributable");

        System.out.println("R1-249 pressure-tail authority bridge regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
