package net.oceancanvas.mod.worldgen;

/**
 * Read-only composition boundary for terminal LIGHT-DIAG pressure-tail telemetry.
 * This class deliberately owns no scheduler, debt, deadline, ticket, repair, or
 * completion authority; it only snapshots the existing authoritative pressure map
 * and retry-ledger identity and formats the result for an existing diagnostic line.
 */
final class OceanCanvasPressureTailDiagnosticLine {
    private OceanCanvasPressureTailDiagnosticLine() {}

    static String format(long nowTick,
            OceanCanvasPrimitiveLongLongMap authoritativePressure,
            OceanCanvasLightRetryLedger retryLedger) {
        return OceanCanvasPressureTailTelemetry.format(
                OceanCanvasPressureTailWorstDebt.select(nowTick, authoritativePressure, retryLedger));
    }
}
