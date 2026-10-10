package net.oceancanvas.mod.gui;


import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking read-only bridge to JourneyMap's persisted map tiles.
 *
 * <p>v250 deliberately treats JourneyMap as the long-range map source. Ocean Canvas can
 * render currently-loaded chunks itself, but a full-screen planning map must also show
 * already explored land thousands of blocks away. JourneyMap persists 512x512 surface
 * tiles named {@code x,z.png}; this class consumes those tiles without ever doing file IO
 * or PNG decoding on Minecraft's render thread.</p>
 *
 * <p>The old bridge assumed one exact {@code DIM0/day} path and sampled only the top-left
 * pixel of a large zoom cell. Both were too brittle: modern JourneyMap installations can
 * use different overworld directory names, and at 0.2x one Ocean Canvas cell can cover
 * 512-1024 blocks. v250 locates day-map directories recursively and downsamples the
 * complete screen cell, so a continent cannot disappear merely because the single probe
 * happened to land in water.</p>
 */
final class OceanCanvasJourneyMapTiles {
    private static final int TILE_BLOCKS = 512;
    private static final int MAX_IMAGES = 256;
    private static final long MISSING_RETRY_MS = 30_000L;
    private static final int DOWNSAMPLE_GRID = 4;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OceanCanvas-JourneyMap-Tile-IO");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private record TileImage(BufferedImage image, long modifiedMs, long checkedMs) { }

    private final LinkedHashMap<Long, TileImage> images = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, TileImage> e) {
            return size() > MAX_IMAGES;
        }
    };
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Long> missingUntil = new ConcurrentHashMap<>();
    private final AtomicBoolean locating = new AtomicBoolean(false);
    private final AtomicLong revision = new AtomicLong();
    private volatile Path dayDir;
    private volatile long lastLocateRequestMs;

    /** Exact one-block lookup retained for callers that really need it. */
    int colorAt(int blockX, int blockZ) {
        return colorAt(blockX, blockZ, 1);
    }

    /**
     * Returns an average JourneyMap colour for the complete map cell beginning at
     * {@code blockX/blockZ}. A multi-point average is intentional when zoomed out: the
     * screen cell itself represents a large area, so this produces the same readable LOD
     * behaviour a proper map texture would instead of throwing away land between probes.
     */
    int colorAt(int blockX, int blockZ, int sampleStep) {
        Path dir = dayDir;
        if (dir == null) {
            requestLocate();
            return OceanCanvasMapTerrain.UNKNOWN;
        }

        int step = Math.max(1, sampleStep);
        if (step <= 32) {
            return pixelAt(dir, blockX + step / 2, blockZ + step / 2);
        }

        long rr = 0, gg = 0, bb = 0;
        int count = 0;
        for (int gz = 0; gz < DOWNSAMPLE_GRID; gz++) {
            int wz = blockZ + (int) Math.floor((gz + 0.5) * step / DOWNSAMPLE_GRID);
            for (int gx = 0; gx < DOWNSAMPLE_GRID; gx++) {
                int wx = blockX + (int) Math.floor((gx + 0.5) * step / DOWNSAMPLE_GRID);
                int argb = pixelAt(dir, wx, wz);
                if (argb == OceanCanvasMapTerrain.UNKNOWN || (argb >>> 24) == 0) continue;
                rr += (argb >>> 16) & 0xff;
                gg += (argb >>> 8) & 0xff;
                bb += argb & 0xff;
                count++;
            }
        }
        if (count == 0) return OceanCanvasMapTerrain.UNKNOWN;
        return 0xff000000 | ((int)(rr / count) << 16) | ((int)(gg / count) << 8) | (int)(bb / count);
    }

    private int pixelAt(Path dir, int blockX, int blockZ) {
        int tx = Math.floorDiv(blockX, TILE_BLOCKS);
        int tz = Math.floorDiv(blockZ, TILE_BLOCKS);
        long key = (((long) tx) << 32) ^ (tz & 0xffffffffL);
        TileImage tile;
        synchronized (images) {
            tile = images.get(key);
        }
        if (tile == null) {
            long now = System.currentTimeMillis();
            Long retryAfter = missingUntil.get(key);
            if (retryAfter == null || now >= retryAfter) requestTile(dir, tx, tz, key);
            return OceanCanvasMapTerrain.UNKNOWN;
        }

        long now = System.currentTimeMillis();
        if (now - tile.checkedMs() > 2_000L) requestTile(dir, tx, tz, key, true);
        BufferedImage image = tile.image();

        int localX = Math.floorMod(blockX, TILE_BLOCKS);
        int localZ = Math.floorMod(blockZ, TILE_BLOCKS);
        int px = Math.min(image.getWidth() - 1, (int) ((long) localX * image.getWidth() / TILE_BLOCKS));
        int pz = Math.min(image.getHeight() - 1, (int) ((long) localZ * image.getHeight() / TILE_BLOCKS));
        int argb = image.getRGB(px, pz);
        return ((argb >>> 24) == 0) ? OceanCanvasMapTerrain.UNKNOWN : argb;
    }

    private void requestTile(Path dir, int tx, int tz, long key) { requestTile(dir, tx, tz, key, false); }

    private void requestTile(Path dir, int tx, int tz, long key, boolean refresh) {
        if (!pending.add(key)) return;
        IO.execute(() -> {
            try {
                TileImage existing; synchronized (images) { existing = images.get(key); }
                long knownModified = refresh && existing != null ? existing.modifiedMs() : -1L;
                net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.TileRead read =
                        net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.readTile(dir, tx, tz, knownModified);
                if (read.state() == net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.TileState.UNCHANGED && existing != null) {
                    synchronized (images) { images.put(key, new TileImage(existing.image(), existing.modifiedMs(), System.currentTimeMillis())); }
                    return;
                }
                if (read.state() != net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.TileState.LOADED || read.image() == null) {
                    if (!refresh) missingUntil.put(key, System.currentTimeMillis() + MISSING_RETRY_MS);
                    return;
                }
                synchronized (images) { images.put(key, new TileImage(read.image(), read.modifiedMs(), System.currentTimeMillis())); }
                missingUntil.remove(key);
                revision.incrementAndGet();
            } finally {
                pending.remove(key);
            }
        });
    }

    long revision() { return revision.get(); }

    /**
     * High-resolution raster lookup used by the GPU viewport renderer. It reads JourneyMap's
     * persisted one-pixel-per-block surface image directly instead of first quantising the
     * world into Ocean Canvas map cells. Missing tiles are queued asynchronously and return
     * transparent until their PNG has arrived.
     */
    int rasterColorAt(double worldX, double worldZ) {
        Path dir = dayDir;
        if (dir == null) { requestLocate(); return OceanCanvasMapTerrain.UNKNOWN; }
        return pixelAt(dir, (int)Math.floor(worldX), (int)Math.floor(worldZ));
    }

    /**
     * Builds a complete screen-resolution JourneyMap viewport on the map worker thread.
     * Unlike per-pixel async sampling this reads each visible persisted tile once, draws it
     * directly into the destination raster, then releases it. A 20k x 20k overview therefore
     * does not require thousands of decoded 512px tiles to remain resident just to paint one
     * frame.
     */
    BufferedImage renderViewport(int width, int height, double viewX, double viewZ, double bpp) {
        Path dir = dayDir;
        if (dir == null) { requestLocate(); return null; }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    bpp > 1.0 ? java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR : java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double left = viewX - width * 0.5 * bpp, top = viewZ - height * 0.5 * bpp;
            double right = left + width * bpp, bottom = top + height * bpp;
            int minTx=Math.floorDiv((int)Math.floor(left),TILE_BLOCKS), maxTx=Math.floorDiv((int)Math.ceil(right),TILE_BLOCKS);
            int minTz=Math.floorDiv((int)Math.floor(top),TILE_BLOCKS), maxTz=Math.floorDiv((int)Math.ceil(bottom),TILE_BLOCKS);
            for(int tz=minTz;tz<=maxTz;tz++) for(int tx=minTx;tx<=maxTx;tx++) {
                long key = (((long)tx) << 32) ^ (tz & 0xffffffffL);
                try {
                    BufferedImage tile=tileForViewport(dir,tx,tz,key); if(tile==null) continue;
                    int x1=(int)Math.floor((tx*(double)TILE_BLOCKS-left)/bpp);
                    int y1=(int)Math.floor((tz*(double)TILE_BLOCKS-top)/bpp);
                    int x2=(int)Math.ceil(((tx+1)*(double)TILE_BLOCKS-left)/bpp);
                    int y2=(int)Math.ceil(((tz+1)*(double)TILE_BLOCKS-top)/bpp);
                    g.drawImage(tile,x1,y1,x2,y2,0,0,tile.getWidth(),tile.getHeight(),null);
                } catch(RuntimeException ignored) {}
            }
        } finally { g.dispose(); }
        return out;
    }


    /**
     * Synchronous worker-thread tile lookup for complete viewport renders. Reuses the same
     * bounded decoded-image LRU as point sampling and checks mtime before decoding, so a
     * stationary map no longer reparses every visible PNG on every raster refresh.
     */
    private BufferedImage tileForViewport(Path dir, int tx, int tz, long key) {
        TileImage existing;
        synchronized (images) { existing = images.get(key); }
        long knownModified = existing == null ? -1L : existing.modifiedMs();
        net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.TileRead read =
                net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.readTile(dir, tx, tz, knownModified);
        if (read.state() == net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.TileState.UNCHANGED)
            return existing == null ? null : existing.image();
        if (read.state() != net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.TileState.LOADED || read.image() == null) return null;
        synchronized (images) { images.put(key, new TileImage(read.image(), read.modifiedMs(), System.currentTimeMillis())); }
        missingUntil.remove(key);
        revision.incrementAndGet();
        return read.image();
    }

    private void requestLocate() {
        long now = System.currentTimeMillis();
        if (now - lastLocateRequestMs < 5_000L || !locating.compareAndSet(false, true)) return;
        lastLocateRequestMs = now;
        IO.execute(() -> {
            try {
                Path found = net.oceancanvas.mod.compat.OceanCanvasJourneyMapCompat.locateSurfaceTileDirectory();
                if (found != null && !found.equals(dayDir)) {
                    dayDir = found;
                    synchronized (images) { images.clear(); }
                    missingUntil.clear();
                    revision.incrementAndGet();
                }
            } finally {
                locating.set(false);
            }
        });
    }

}
