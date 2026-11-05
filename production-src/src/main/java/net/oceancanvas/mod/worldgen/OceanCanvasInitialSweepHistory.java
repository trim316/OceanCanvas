package net.oceancanvas.mod.worldgen;

/** Session-only native operation history, never a physical or light certificate. */
final class OceanCanvasInitialSweepHistory<T> {
    private final OceanCanvasPrimitiveLongObjectMap<OceanCanvasTransientChunkOwner<T>> completed =
            new OceanCanvasPrimitiveLongObjectMap<>();

    boolean matches(long packed, T chunk) {
        var owner = completed.get(packed);
        return owner != null && owner.matches(chunk);
    }

    // Call only after the complete initial native sweep and its fence return ready.
    void completed(long packed, T chunk) {
        completed.put(packed, new OceanCanvasTransientChunkOwner<>(chunk));
    }

    boolean canReuse(long packed, T chunk, boolean currentPhysicalAudit, boolean currentNativeFields) {
        return matches(packed, chunk) && currentPhysicalAudit && currentNativeFields;
    }

    void retire(long packed) {
        completed.remove(packed);
    }
}
