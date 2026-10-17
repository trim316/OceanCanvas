package net.oceancanvas.mod.planning;

import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime GPU cache for planning-reference tiles.
 *
 * <p>The implementation intentionally keeps all 26.2 texture internals behind reflection. Minecraft's
 * dynamic-texture constructors have changed repeatedly through the 1.21/26.x render rewrite, while the
 * stable thing the rest of Ocean Canvas needs is simply an {@link Identifier} that GUI extraction can
 * blit. If a particular runtime cannot resolve the texture API, reference images fail soft: vector plans,
 * regions and the world continue working and the map shows the reference bounds instead.</p>
 */
public final class OceanCanvasReferenceTextureCache {
    private static final int MAX_TEXTURES = 192;
    private static final long MAX_ESTIMATED_TEXTURE_BYTES = 128L * 1024L * 1024L;
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static long estimatedTextureBytes;

    private record Entry(Identifier id, Object texture, long estimatedBytes) { }
    private record EncodedTexture(byte[] png, long estimatedBytes) { }

    private OceanCanvasReferenceTextureCache() { }

    public static synchronized Identifier texture(String assetId, int level, int tileX, int tileY, double opacity) {
        int alphaBucket = Math.max(10, Math.min(100, (int)Math.round(opacity * 10.0) * 10));
        String key = assetId + ":" + level + ":" + tileX + ":" + tileY + ":" + alphaBucket;
        Entry existing = CACHE.get(key);
        if (existing != null) return existing.id();

        Path tile = OceanCanvasReferenceAssetStore.tilePath(assetId, level, tileX, tileY);
        if (!Files.isRegularFile(tile)) return null;
        try {
            EncodedTexture encoded = alphaAdjustedPng(tile, alphaBucket / 100.0);
            Identifier id = Identifier.fromNamespaceAndPath("oceancanvas", "planning/" + sanitize(key));
            net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.RegisteredTexture uploaded =
                    net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.uploadPng(
                            encoded.png(), id, "Ocean Canvas reference " + assetId.substring(0, Math.min(12, assetId.length())));
            if (uploaded == null) return null;
            Entry replaced = CACHE.put(key, new Entry(uploaded.id(), uploaded.texture(), encoded.estimatedBytes()));
            if (replaced != null) { estimatedTextureBytes -= replaced.estimatedBytes(); net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.release(replaced.id(), replaced.texture()); }
            estimatedTextureBytes += encoded.estimatedBytes();
            trim();
            return id;
        } catch (Throwable failure) {
            net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.record("client.reference-texture",
                    "could not create planning texture for " + key, failure);
            return null;
        }
    }

    public static synchronized void clear() {
        for (Entry e : CACHE.values()) net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.release(e.id(), e.texture());
        CACHE.clear();
        estimatedTextureBytes = 0L;
    }

    private static EncodedTexture alphaAdjustedPng(Path file, double opacity) throws Exception {
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) throw new IllegalArgumentException("Unreadable planning tile");
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics(); g.drawImage(src, 0, 0, null); g.dispose();
        if (opacity < 0.999) {
            for (int y=0; y<out.getHeight(); y++) for (int x=0; x<out.getWidth(); x++) {
                int c=out.getRGB(x,y); int a=(c>>>24)&255; a=(int)Math.round(a*opacity);
                out.setRGB(x,y,(c&0x00FFFFFF)|(a<<24));
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "PNG", bytes);
        long estimatedBytes = Math.multiplyExact(Math.multiplyExact((long)out.getWidth(), (long)out.getHeight()), 4L);
        return new EncodedTexture(bytes.toByteArray(), estimatedBytes);
    }

    private static void trim() {
        while (CACHE.size() > MAX_TEXTURES || estimatedTextureBytes > MAX_ESTIMATED_TEXTURE_BYTES) {
            String oldest = CACHE.keySet().iterator().next();
            Entry e = CACHE.remove(oldest);
            if (e != null) {
                estimatedTextureBytes = Math.max(0L, estimatedTextureBytes - e.estimatedBytes());
                net.oceancanvas.mod.compat.OceanCanvasClientTextureCompat.release(e.id(), e.texture());
            }
        }
    }

    private static String sanitize(String raw) { return raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9/._-]","_"); }
}
