package net.oceancanvas.mod.worldgen;

/** Deterministic regression for terminal pressure-tail telemetry formatting. */
public final class OceanCanvasPressureTailTelemetryTest {
    public static void main(String[] args) {
        require("pressureTail=none".equals(OceanCanvasPressureTailTelemetry.format(null)),
                "empty authoritative pressure debt must be explicit");

        long packed = (((long) -23) << 32) ^ (-23 & 0xffffffffL);
        var snapshot = OceanCanvasPressureTailAttribution.classify(true, 700L, 1000L, 2);
        var sample = new OceanCanvasPressureTailWorstDebt.Sample(packed, snapshot);
        String line = OceanCanvasPressureTailTelemetry.format(sample);
        require(line.contains("pressureTail=-23,-23"), "preserved terminal key must decode exactly");
        require(line.contains("state=AUTHORITATIVE_OVERDUE"), "authoritative state must be visible");
        require(line.contains("dueTick=700"), "deadline must be visible");
        require(line.contains("overdueTicks=300"), "overdue age must be visible");
        require(line.contains("schedulerNodes=2"), "per-key scheduler multiplicity must be visible");
        require(line.contains("duplicateNodes=true"), "duplicate-node attribution must be explicit");

        System.out.println("R1-251 pressure-tail telemetry regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
