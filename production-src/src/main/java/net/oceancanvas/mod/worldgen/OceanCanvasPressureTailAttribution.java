package net.oceancanvas.mod.worldgen;

/**
 * Read-only classification for terminal pressure-retry diagnostics.
 *
 * <p>This helper deliberately has no scheduling or completion authority. It
 * combines the authoritative pressure-map state with R1-247's scheduler-node
 * attribution so diagnostics can distinguish stale heap nodes, missing wake
 * nodes, future deadlines, and overdue authoritative debt without changing
 * any retry deadline or queue membership.</p>
 */
final class OceanCanvasPressureTailAttribution {
    enum State {
        NO_AUTHORITATIVE_DEBT,
        AUTHORITATIVE_WITHOUT_NODE,
        AUTHORITATIVE_NOT_DUE,
        AUTHORITATIVE_OVERDUE
    }

    record Snapshot(State state, int schedulerNodes, long dueTick, long overdueTicks) {
        boolean duplicateSchedulerNodes() { return schedulerNodes > 1; }
    }

    private OceanCanvasPressureTailAttribution() {}

    static Snapshot classify(boolean authoritativePresent, long dueTick, long nowTick, int schedulerNodes) {
        int nodes = Math.max(0, schedulerNodes);
        if (!authoritativePresent) {
            return new Snapshot(State.NO_AUTHORITATIVE_DEBT, nodes, dueTick, 0L);
        }
        if (nodes == 0) {
            return new Snapshot(State.AUTHORITATIVE_WITHOUT_NODE, 0, dueTick,
                    dueTick <= nowTick ? saturatedSubtract(nowTick, dueTick) : 0L);
        }
        if (dueTick > nowTick) {
            return new Snapshot(State.AUTHORITATIVE_NOT_DUE, nodes, dueTick, 0L);
        }
        return new Snapshot(State.AUTHORITATIVE_OVERDUE, nodes, dueTick, saturatedSubtract(nowTick, dueTick));
    }

    private static long saturatedSubtract(long left, long right) {
        if (right < 0L && left > Long.MAX_VALUE + right) return Long.MAX_VALUE;
        return Math.max(0L, left - right);
    }
}
