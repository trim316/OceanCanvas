package net.oceancanvas.mod.gui;

import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Screen-resolution GPU renderer for the Ocean Canvas world map.
 *
 * <p>The pre-v252 renderer reduced the world to large solid-colour GUI rectangles. That
 * was fast, but it threw away exactly the information that makes JourneyMap look like a
 * map: one-pixel coastlines, rivers, roads, tree edges, beaches and block-scale relief.
 * v252 instead rasterises JourneyMap's persisted surface pixels into a viewport-sized
 * image on a worker thread and uploads that image as one GPU texture. The render thread
 * therefore performs one blit instead of thousands of fills while retaining effectively
 * one source sample for every screen pixel.</p>
 *
 * <p>The current texture remains usable while panning: because its world scale is known,
 * it is simply shifted on screen until the replacement snapshot arrives. Zoom changes
 * request a new raster immediately and temporarily fall back to the lightweight sampler;
 * a stale texture is never stretched and blurred.</p>
 */
final class OceanCanvasMapRasterRenderer implements AutoCloseable {
    private static final int MAX_TEXTURE_DIMENSION = 4096;
    private static final int PAN_REFRESH_PX = 96;
    private static final long MIN_REFRESH_MS = 120L;
    private static final int CANVAS_OCEAN = 0xFF1D4D73;

    record Frame(Identifier texture, int width, int height, double viewX, double viewZ,
                 double blocksPerPixel, long sourceRevision) { }
    private record Ready(byte[] png, int width, int height, double viewX, double viewZ,
                         double blocksPerPixel, long sourceRevision, long serial) { }
    private record TextureEntry(Identifier id, Object texture) { }

    private final OceanCanvasMapTerrain terrain;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OceanCanvas-Map-Raster");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicBoolean rendering = new AtomicBoolean();
    private final AtomicLong serials = new AtomicLong();
    private volatile Ready ready;
    private volatile boolean closed;
    private Frame frame;
    private TextureEntry textureEntry;
    private long lastRequestMs;
    private double requestedViewX, requestedViewZ, requestedBpp = Double.NaN;
    private int requestedWidth, requestedHeight;
    private long requestedRevision = Long.MIN_VALUE;

    OceanCanvasMapRasterRenderer(OceanCanvasMapTerrain terrain) { this.terrain = terrain; }

    /** Called on the render thread. Returns the best completed texture and schedules fresher work as needed. */
    Frame frame(int width, int height, double viewX, double viewZ, double blocksPerPixel) {
        if(closed)return frame;
        width = Math.max(1, Math.min(MAX_TEXTURE_DIMENSION, width));
        height = Math.max(1, Math.min(MAX_TEXTURE_DIMENSION, height));
        consumeReady();

        long revision = terrain.rasterRevision();
        boolean noFrame = frame == null;
        boolean dimensionsChanged = frame != null && (frame.width() != width || frame.height() != height);
        boolean zoomChanged = frame != null && Math.abs(frame.blocksPerPixel() - blocksPerPixel) > Math.max(0.002, blocksPerPixel * 0.002);
        double panPx = frame == null ? Double.POSITIVE_INFINITY : Math.max(
                Math.abs(viewX - frame.viewX()) / Math.max(0.0001, blocksPerPixel),
                Math.abs(viewZ - frame.viewZ()) / Math.max(0.0001, blocksPerPixel));
        boolean sourceChanged = frame == null || frame.sourceRevision() != revision;

        long now = System.currentTimeMillis();
        if ((noFrame || dimensionsChanged || zoomChanged || panPx >= PAN_REFRESH_PX || sourceChanged)
                && now - lastRequestMs >= MIN_REFRESH_MS) {
            request(width, height, viewX, viewZ, blocksPerPixel, revision, now);
        }
        return frame;
    }

    private void request(int width, int height, double viewX, double viewZ, double bpp, long revision, long now) {
        if(closed)return;
        // Avoid starting the exact same request repeatedly while the current worker is busy.
        if (rendering.get()
                && requestedWidth == width && requestedHeight == height
                && Math.abs(requestedViewX - viewX) < bpp * 8
                && Math.abs(requestedViewZ - viewZ) < bpp * 8
                && Math.abs(requestedBpp - bpp) < Math.max(0.002, bpp * 0.002)
                && requestedRevision == revision) return;
        if (!rendering.compareAndSet(false, true)) return;

        lastRequestMs = now;
        requestedWidth = width; requestedHeight = height;
        requestedViewX = viewX; requestedViewZ = viewZ; requestedBpp = bpp;
        requestedRevision = revision;
        long serial = serials.incrementAndGet();
        worker.execute(() -> {
            try {
                BufferedImage image = raster(width, height, viewX, viewZ, bpp);
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32_768, width * height));
                ImageIO.write(image, "PNG", out);
                if(!closed && serial==serials.get()) ready = new Ready(out.toByteArray(), width, height, viewX, viewZ, bpp, terrain.rasterRevision(), serial);
            } catch (Throwable ignored) {
                // Fail soft: drawTerrain keeps its previous/fallback renderer available.
            } finally {
                rendering.set(false);
            }
        });
    }

    private BufferedImage raster(int width, int height, double viewX, double viewZ, double bpp) {
        // Composite JourneyMap's persisted tiles directly into one viewport image. This is both
        // higher fidelity and dramatically more reliable at canvas overview zoom than asking the
        // asynchronous tile cache for millions of individual pixels.
        BufferedImage out = terrain.journeyViewport(width,height,viewX,viewZ,bpp);
        if(out==null) out=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        double leftWorld=viewX-width*0.5*bpp, topWorld=viewZ-height*0.5*bpp;
        // Ocean Canvas remains authoritative for already-flattened protected canvas pixels.
        for(int y=0;y<height;y++){double wz=topWorld+(y+0.5)*bpp;for(int x=0;x<width;x++){
            double wx=leftWorld+(x+0.5)*bpp; if(terrain.isAuthoritativeCanvas(wx,wz)) out.setRGB(x,y,CANVAS_OCEAN);
        }}
        return out;
    }

    private static int average(int a, int b, int c, int d) {
        long aa=0, rr=0, gg=0, bb=0; int n=0;
        int[] v={a,b,c,d};
        for(int x:v){ if(x==OceanCanvasMapTerrain.UNKNOWN || (x>>>24)==0) continue;
            aa+=(x>>>24)&255; rr+=(x>>>16)&255; gg+=(x>>>8)&255; bb+=x&255; n++; }
        if(n==0) return OceanCanvasMapTerrain.UNKNOWN;
        return ((int)(aa/n)<<24)|((int)(rr/n)<<16)|((int)(gg/n)<<8)|(int)(bb/n);
    }

    /** Render-thread handoff: decode/register the worker-produced PNG without doing any filesystem IO here. */
    private void consumeReady() {
        Ready r = ready;
        if (r == null) return;
        ready = null;
        try {
            Identifier id = Identifier.fromNamespaceAndPath("oceancanvas", "map/raster_" + r.serial());
            net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.RegisteredTexture uploaded =
                    net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.uploadPng(r.png(), id, "Ocean Canvas map raster");
            if (uploaded == null) return;
            TextureEntry old = textureEntry;
            textureEntry = new TextureEntry(uploaded.id(), uploaded.texture());
            frame = new Frame(id, r.width(), r.height(), r.viewX(), r.viewZ(), r.blocksPerPixel(), r.sourceRevision());
            if (old != null) net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.release(old.id(), old.texture());
        } catch (Throwable ignored) { }
    }

    @Override public void close(){
        if(closed)return;
        closed=true;
        serials.incrementAndGet();
        ready=null;
        worker.shutdownNow();
        rendering.set(false);
        TextureEntry old=textureEntry;
        textureEntry=null;
        frame=null;
        if(old!=null)net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.release(old.id(),old.texture());
    }

    private static void closeQuietly(Object o) { if(o instanceof AutoCloseable c) try{c.close();}catch(Exception ignored){} }
}
