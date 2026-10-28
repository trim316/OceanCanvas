package net.oceancanvas.mod.worldgen;

/** Scheduling only. Columns have no persisted completion meaning; restart rescans. */
final class OceanCanvasCooperativeCarvePolicy {
    private OceanCanvasCooperativeCarvePolicy() {}
    static boolean shouldYield(boolean unstructuredWholeOwned, int completedColumns,
            long elapsedNs, long existingBudgetNs) {
        return unstructuredWholeOwned && completedColumns > 0 && completedColumns < 256
                && elapsedNs >= 0 && existingBudgetNs > 0 && elapsedNs >= existingBudgetNs;
    }
}
