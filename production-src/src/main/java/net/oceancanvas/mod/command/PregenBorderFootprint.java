package net.oceancanvas.mod.command;

/** Pure geometry for the explicitly reported natural-terrain pregen border. */
public record PregenBorderFootprint(int requestedRadius, int operationRadius,
        long minX, long maxX, long minZ, long maxZ) {
    public static final int BORDER_BLOCKS = 16;
    private static final int MAX_RADIUS_BLOCKS = 30_000_000;

    public static PregenBorderFootprint plan(int requestedRadius, int centerX, int centerZ,
            int canvasCenterX, int canvasCenterZ, int canvasRadius) {
        int radius = (int) Math.min(MAX_RADIUS_BLOCKS, (long) requestedRadius + BORDER_BLOCKS);
        return new PregenBorderFootprint(requestedRadius, radius,
                Math.max((long) centerX - radius, (long) canvasCenterX - canvasRadius),
                Math.min((long) centerX + radius - 1L, (long) canvasCenterX + canvasRadius - 1L),
                Math.max((long) centerZ - radius, (long) canvasCenterZ - canvasRadius),
                Math.min((long) centerZ + radius - 1L, (long) canvasCenterZ + canvasRadius - 1L));
    }

    public long width() { return Math.max(0L, maxX - minX + 1L); }
    public long height() { return Math.max(0L, maxZ - minZ + 1L); }
    public long chunks() {
        if (width() == 0L || height() == 0L) return 0L;
        return (Math.floorDiv(maxX,16L)-Math.floorDiv(minX,16L)+1L)
                * (Math.floorDiv(maxZ,16L)-Math.floorDiv(minZ,16L)+1L);
    }
    public String description() {
        return "Requested inner canvas " + (requestedRadius * 2L) + " x " + (requestedRadius * 2L)
                + " blocks; a 16-block natural-terrain border expands the operation, clipped to the configured canvas: "
                + width() + " x " + height() + " blocks, " + chunks() + " chunks, bounds ["
                + minX + ".." + maxX + "] x [" + minZ + ".." + maxZ
                + "]. Existing player-protection rules remain active.";
    }
}
