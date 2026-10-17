package net.oceancanvas.mod.worldgen;

/** Pure ticket-geometry policy for the independent structure-relocation rescue lane. */
public final class OceanCanvasStructureRescuePolicy {
    private OceanCanvasStructureRescuePolicy() {}

    public record TicketPlan(int anchorChunkX, int anchorChunkZ, int radius) {}

    /**
     * Computes the single FORCED ticket that keeps a structure footprint plus one chunk of
     * placement-safety ring in the TICKING tier.
     *
     * <p>Ticket propagation increases level by one per chunk. A FORCED ticket of radius R has
     * center level 33-R, so every required chunk must satisfy R >= manhattanDistance+1.</p>
     */
    public static TicketPlan ticketPlan(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        int minCx = Math.floorDiv(minBlockX, 16) - 1;
        int maxCx = Math.floorDiv(maxBlockX, 16) + 1;
        int minCz = Math.floorDiv(minBlockZ, 16) - 1;
        int maxCz = Math.floorDiv(maxBlockZ, 16) + 1;
        int anchorCx = Math.floorDiv(minCx + maxCx, 2);
        int anchorCz = Math.floorDiv(minCz + maxCz, 2);
        int maxManhattan = 0;
        for (int cx : new int[]{minCx, maxCx}) for (int cz : new int[]{minCz, maxCz}) {
            maxManhattan = Math.max(maxManhattan, Math.abs(cx - anchorCx) + Math.abs(cz - anchorCz));
        }
        return new TicketPlan(anchorCx, anchorCz, maxManhattan + 1);
    }
}
