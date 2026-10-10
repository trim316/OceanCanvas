package net.oceancanvas.mod.worldgen;

/** Immutable sampled heightmap/physical-surface agreement evidence. */
record OceanCanvasHeightmapDiag(
        int sampledColumns,
        int mismatchedColumns,
        int maxAbsDelta,
        int physicalSurfaceMin,
        int physicalSurfaceMax,
        int heightmapMin,
        int heightmapMax) {}
