package net.oceancanvas.mod.worldgen;

/** Read-only formatting for terminal pressure-tail attribution. */
final class OceanCanvasPressureTailTelemetry {
    private OceanCanvasPressureTailTelemetry() {}

    static String format(OceanCanvasPressureTailWorstDebt.Sample sample) {
        if (sample == null) return "pressureTail=none";
        long packed = sample.packed();
        int chunkX = (int) (packed >> 32);
        int chunkZ = (int) packed;
        var snapshot = sample.snapshot();
        return "pressureTail=" + chunkX + "," + chunkZ
                + " state=" + snapshot.state()
                + " dueTick=" + snapshot.dueTick()
                + " overdueTicks=" + snapshot.overdueTicks()
                + " schedulerNodes=" + snapshot.schedulerNodes()
                + " duplicateNodes=" + snapshot.duplicateSchedulerNodes();
    }
}
