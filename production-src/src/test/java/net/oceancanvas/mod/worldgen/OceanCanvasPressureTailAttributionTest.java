package net.oceancanvas.mod.worldgen;

/** Deterministic regression for terminal pressure-tail diagnostic attribution. */
public final class OceanCanvasPressureTailAttributionTest {
    public static void main(String[] args) {
        var stale = OceanCanvasPressureTailAttribution.classify(false, 700L, 900L, 2);
        require(stale.state() == OceanCanvasPressureTailAttribution.State.NO_AUTHORITATIVE_DEBT,
                "heap nodes without authoritative debt must be classified as stale diagnostics only");
        require(stale.duplicateSchedulerNodes(), "two scheduler nodes must remain visible diagnostically");

        var missing = OceanCanvasPressureTailAttribution.classify(true, 700L, 900L, 0);
        require(missing.state() == OceanCanvasPressureTailAttribution.State.AUTHORITATIVE_WITHOUT_NODE,
                "authoritative debt with no scheduler node must be explicit");
        require(missing.overdueTicks() == 200L, "missing-node overdue age must be preserved");

        var future = OceanCanvasPressureTailAttribution.classify(true, 950L, 900L, 1);
        require(future.state() == OceanCanvasPressureTailAttribution.State.AUTHORITATIVE_NOT_DUE,
                "future authoritative deadline must not be reported overdue");
        require(future.overdueTicks() == 0L, "future deadline must have zero overdue age");

        var due = OceanCanvasPressureTailAttribution.classify(true, 900L, 900L, 1);
        require(due.state() == OceanCanvasPressureTailAttribution.State.AUTHORITATIVE_OVERDUE,
                "deadline equal to current tick is due and must be classified as serviceable");
        require(due.overdueTicks() == 0L, "just-due debt has zero elapsed overdue ticks");

        var overdue = OceanCanvasPressureTailAttribution.classify(true, 700L, 900L, 2);
        require(overdue.state() == OceanCanvasPressureTailAttribution.State.AUTHORITATIVE_OVERDUE,
                "past authoritative deadline with nodes must be explicit overdue debt");
        require(overdue.schedulerNodes() == 2 && overdue.overdueTicks() == 200L,
                "overdue attribution must preserve node multiplicity and age");

        var saturated = OceanCanvasPressureTailAttribution.classify(true, Long.MIN_VALUE, Long.MAX_VALUE, 1);
        require(saturated.overdueTicks() == Long.MAX_VALUE,
                "diagnostic age arithmetic must saturate rather than overflow");

        System.out.println("R1-248 pressure-tail authority classifier regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
