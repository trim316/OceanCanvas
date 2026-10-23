package net.oceancanvas.mod.worldgen;

/** World-session scheduling only; never supplies or changes a lighting proof. */
final class OceanCanvasPressureProofCadence {
    enum Lane { NONE, TICKET_NEIGHBORHOOD, GENERIC }
    private boolean claimed;
    private long lastTick;
    private boolean genericNext;

    Lane claim(long tick, boolean terrainComplete, boolean ticketsPresent) {
        if (claimed && tick == lastTick) return Lane.NONE;
        claimed = true;
        lastTick = tick;
        if (!terrainComplete) return ticketsPresent ? Lane.TICKET_NEIGHBORHOOD : Lane.NONE;
        if (!ticketsPresent) return Lane.GENERIC;
        Lane lane = genericNext ? Lane.GENERIC : Lane.TICKET_NEIGHBORHOOD;
        genericNext = !genericNext;
        return lane;
    }
}
