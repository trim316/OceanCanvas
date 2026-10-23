package net.oceancanvas.mod.worldgen;

/**
 * Read-only bounded diagnostic selector for the oldest authoritative pressure debt.
 * It never mutates debt, scheduler identity, deadlines, tickets, or completion state.
 */
final class OceanCanvasPressureTailWorstDebt {
    record Sample(long packed, OceanCanvasPressureTailAttribution.Snapshot snapshot) {}

    private OceanCanvasPressureTailWorstDebt() {}

    static Sample select(long nowTick,
            OceanCanvasPrimitiveLongLongMap authoritativePressure,
            OceanCanvasLightRetryLedger retryLedger) {
        if (authoritativePressure == null || authoritativePressure.isEmpty()) return null;
        final long[] bestPacked = {Long.MIN_VALUE};
        final long[] bestAge = {Long.MIN_VALUE};
        authoritativePressure.forEachKey(packed -> {
            var snapshot = OceanCanvasPressureTailAuthorityBridge.snapshot(
                    packed, nowTick, authoritativePressure, retryLedger);
            long age = snapshot.state() == OceanCanvasPressureTailAttribution.State.AUTHORITATIVE_OVERDUE
                    ? snapshot.overdueTicks() : -1L;
            if (bestPacked[0] == Long.MIN_VALUE || age > bestAge[0]
                    || (age == bestAge[0] && Long.compareUnsigned(packed, bestPacked[0]) < 0)) {
                bestPacked[0] = packed;
                bestAge[0] = age;
            }
        });
        if (bestPacked[0] == Long.MIN_VALUE) return null;
        return new Sample(bestPacked[0], OceanCanvasPressureTailAuthorityBridge.snapshot(
                bestPacked[0], nowTick, authoritativePressure, retryLedger));
    }
}
