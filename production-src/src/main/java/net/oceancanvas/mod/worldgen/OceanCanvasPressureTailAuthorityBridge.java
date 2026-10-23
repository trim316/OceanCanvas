package net.oceancanvas.mod.worldgen;

/** Read-only bridge from authoritative pressure debt and retry-node identity to tail attribution. */
final class OceanCanvasPressureTailAuthorityBridge {
    private OceanCanvasPressureTailAuthorityBridge() {}

    static OceanCanvasPressureTailAttribution.Snapshot snapshot(
            long packed,
            long nowTick,
            OceanCanvasPrimitiveLongLongMap authoritativePressure,
            OceanCanvasLightRetryLedger retryLedger) {
        long dueTick = authoritativePressure.get(packed);
        boolean present = dueTick != OceanCanvasPrimitiveLongLongMap.ABSENT;
        return OceanCanvasPressureTailAttribution.classify(
                present, dueTick, nowTick, retryLedger.pressureParkNodeCount(packed));
    }
}
