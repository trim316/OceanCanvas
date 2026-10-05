package net.oceancanvas.mod.planning;

import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local, non-critical storage for imported planning images.  Imports are copied into a content-addressed
 * cache and converted into a Google-Maps-style tile pyramid.  Ocean Canvas SavedData only keeps the
 * returned asset id; deleting this cache can make a reference image unavailable but can never damage
 * regions, terrain or the Minecraft save.
 */
public final class OceanCanvasReferenceAssetStore {
    public static final int TILE_SIZE = 256;
    private static final int SAMPLE_TILE_CACHE_LIMIT = 48;
    private static final long MAX_DECODED_PIXELS = 64L * 1024L * 1024L;
    private static final int MAX_SOURCE_DIMENSION = 32_768;
    private static final Map<String, BufferedImage> SAMPLE_TILE_CACHE = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, BufferedImage>(64,0.75f,true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String,BufferedImage> eldest) {
                    return size() > SAMPLE_TILE_CACHE_LIMIT;
                }
            });
    private OceanCanvasReferenceAssetStore() { }

    public record ImportedAsset(String assetId, int width, int height, int levels, Path directory) { }

    public static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("oceancanvas").resolve("planning-assets");
    }

    public static ImportedAsset importImage(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) throw new IOException("Image file does not exist");
        String lower = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")))
            throw new IOException("Reference images must be PNG or JPG");
        long maxBytes = 512L * 1024L * 1024L;
        if (Files.size(source) > maxBytes) throw new IOException("Reference image is larger than 512 MiB");

        String assetId = sha256(source);
        Path dir = root().resolve(assetId);
        Path manifest = dir.resolve("manifest.txt");
        if (Files.isRegularFile(manifest)) return readManifest(assetId, dir, manifest);

        validateDecodeDimensions(source);
        BufferedImage original = ImageIO.read(source.toFile());
        if (original == null) throw new IOException("Unsupported or corrupt image");
        Files.createDirectories(dir);
        Files.copy(source, dir.resolve("source" + extension(lower)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        BufferedImage level = ensureArgb(original);
        int levelIndex = 0;
        List<String> manifestLines = new ArrayList<>();
        manifestLines.add("schema=1");
        manifestLines.add("assetId=" + assetId);
        manifestLines.add("width=" + original.getWidth());
        manifestLines.add("height=" + original.getHeight());
        while (true) {
            writeLevel(dir, levelIndex, level);
            manifestLines.add("level." + levelIndex + "=" + level.getWidth() + "x" + level.getHeight());
            levelIndex++;
            if (level.getWidth() <= TILE_SIZE && level.getHeight() <= TILE_SIZE) break;
            level = downsampleHalf(level);
        }
        manifestLines.add("levels=" + levelIndex);
        Files.write(manifest, manifestLines, StandardCharsets.UTF_8);
        return new ImportedAsset(assetId, original.getWidth(), original.getHeight(), levelIndex, dir);
    }

    /** Local derivative for one rotation. The original asset id remains authoritative;
     *  derivatives are disposable render caches and never enter SavedData. */
    public record RotatedAsset(String cacheId, int width, int height, int levels, double rotationDegrees) { }

    public static RotatedAsset rotatedAsset(String assetId, double rotationDegrees) throws IOException {
        double normalized = normalizeRotation(rotationDegrees);
        if (Math.abs(normalized) < 0.0001D) {
            ImportedAsset info = assetInfo(assetId);
            return new RotatedAsset(assetId, info.width(), info.height(), info.levels(), 0.0D);
        }
        String rotationKey = String.format(java.util.Locale.ROOT, "%+.3f", normalized).replace('+','p').replace('-','m').replace('.','_');
        String cacheId = assetId + "__rot_" + rotationKey;
        Path dir = root().resolve(cacheId);
        Path manifest = dir.resolve("manifest.txt");
        if (Files.isRegularFile(manifest)) {
            ImportedAsset info = readManifest(cacheId, dir, manifest);
            return new RotatedAsset(cacheId, info.width(), info.height(), info.levels(), normalized);
        }
        Path source = sourcePath(assetId);
        validateDecodeDimensions(source);
        BufferedImage original = ImageIO.read(source.toFile());
        if (original == null) throw new IOException("Reference source image is unreadable");
        BufferedImage rotated = rotateArgb(ensureArgb(original), normalized);
        Files.createDirectories(dir);
        int levels = writePyramid(dir, cacheId, rotated);
        return new RotatedAsset(cacheId, rotated.getWidth(), rotated.getHeight(), levels, normalized);
    }

    /** Read image dimensions through ImageIO metadata before allocating pixel storage. */
    private static int[] validateDecodeDimensions(Path source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) throw new IOException("Reference image could not be opened");
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported or corrupt image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long pixels = (long) width * (long) height;
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION || pixels > MAX_DECODED_PIXELS) {
                    throw new IOException("Reference image decoded dimensions are too large: " + width + "x" + height
                            + " (max dimension " + MAX_SOURCE_DIMENSION + ", max pixels " + MAX_DECODED_PIXELS + ")");
                }
                return new int[]{width, height};
            } finally {
                reader.dispose();
            }
        }
    }

    private static Path sourcePath(String assetId) throws IOException {
        Path dir = root().resolve(assetId == null ? "" : assetId);
        Path png = dir.resolve("source.png");
        if (Files.isRegularFile(png)) return png;
        Path jpg = dir.resolve("source.jpg");
        if (Files.isRegularFile(jpg)) return jpg;
        throw new IOException("Original reference source is missing");
    }

    private static BufferedImage rotateArgb(BufferedImage src, double degrees) {
        double r = Math.toRadians(degrees), sin=Math.abs(Math.sin(r)), cos=Math.abs(Math.cos(r));
        int nw=Math.max(1,(int)Math.ceil(src.getWidth()*cos + src.getHeight()*sin));
        int nh=Math.max(1,(int)Math.ceil(src.getHeight()*cos + src.getWidth()*sin));
        if ((long)nw * (long)nh > MAX_DECODED_PIXELS || nw > MAX_SOURCE_DIMENSION || nh > MAX_SOURCE_DIMENSION)
            throw new IllegalArgumentException("Rotated reference image would exceed safe decoded dimensions: " + nw + "x" + nh);
        BufferedImage out=new BufferedImage(nw,nh,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform tx=new AffineTransform();
        tx.translate(nw/2.0,nh/2.0); tx.rotate(r); tx.translate(-src.getWidth()/2.0,-src.getHeight()/2.0);
        g.drawImage(src,tx,null); g.dispose(); return out;
    }

    private static int writePyramid(Path dir, String assetId, BufferedImage original) throws IOException {
        BufferedImage level=original; int levelIndex=0; java.util.List<String> manifestLines=new java.util.ArrayList<>();
        manifestLines.add("schema=1"); manifestLines.add("assetId="+assetId);
        manifestLines.add("width="+original.getWidth()); manifestLines.add("height="+original.getHeight());
        while(true){ writeLevel(dir,levelIndex,level); manifestLines.add("level."+levelIndex+"="+level.getWidth()+"x"+level.getHeight()); levelIndex++;
            if(level.getWidth()<=TILE_SIZE && level.getHeight()<=TILE_SIZE) break; level=downsampleHalf(level); }
        manifestLines.add("levels="+levelIndex); Files.write(dir.resolve("manifest.txt"),manifestLines,StandardCharsets.UTF_8); return levelIndex;
    }

    private static double normalizeRotation(double value){ while(value>180)value-=360; while(value<-180)value+=360; return value; }


    /** Disposable north-up render cache for a two-point similarity or three-point affine registration.
     * The original content-addressed asset remains authoritative and untouched. */
    public static RotatedAsset affineRegisteredAsset(String assetId, String registrationPoints) throws IOException {
        var pts=OceanCanvasReferenceRegistration.parse(registrationPoints);
        if(pts.size()<2) return rotatedAsset(assetId,0.0D);
        var t=OceanCanvasReferenceRegistration.solve(pts);
        ImportedAsset info=assetInfo(assetId);
        double[] bb=OceanCanvasReferenceRegistration.worldBounds(t,info.width(),info.height());
        double worldW=Math.max(1e-9,bb[2]-bb[0]), worldH=Math.max(1e-9,bb[3]-bb[1]);
        double nominal=Math.sqrt(Math.abs(t.determinant()));
        if(!(nominal>1e-9) || !Double.isFinite(nominal)) throw new IOException("Affine registration has invalid scale");
        int outW=Math.max(1,(int)Math.ceil(worldW/nominal)), outH=Math.max(1,(int)Math.ceil(worldH/nominal));
        int maxDim=8192; double reduce=Math.max(outW/(double)maxDim,outH/(double)maxDim);
        if(reduce>1.0){nominal*=reduce;outW=Math.max(1,(int)Math.ceil(worldW/nominal));outH=Math.max(1,(int)Math.ceil(worldH/nominal));}
        String key=sha256Text(assetId+"|affine|"+registrationPoints+"|"+outW+"x"+outH);
        String cacheId=assetId+"__aff_"+key.substring(0,16); Path dir=root().resolve(cacheId); Path manifest=dir.resolve("manifest.txt");
        if(Files.isRegularFile(manifest)){ImportedAsset cached=readManifest(cacheId,dir,manifest);return new RotatedAsset(cacheId,cached.width(),cached.height(),cached.levels(),0.0D);}
        Path sourceFile=sourcePath(assetId);validateDecodeDimensions(sourceFile);BufferedImage source=ImageIO.read(sourceFile.toFile());if(source==null)throw new IOException("Reference source image is unreadable");
        BufferedImage out=new BufferedImage(outW,outH,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform tx=new AffineTransform(t.a()/nominal,t.c()/nominal,t.b()/nominal,t.d()/nominal,(t.tx()-bb[0])/nominal,(t.tz()-bb[1])/nominal);
        g.drawImage(ensureArgb(source),tx,null);g.dispose();Files.createDirectories(dir);int levels=writePyramid(dir,cacheId,out);
        return new RotatedAsset(cacheId,outW,outH,levels,0.0D);
    }

    private static String sha256Text(String text) throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IOException(ex);}
    }

    public static Path tilePath(String assetId, int level, int tileX, int tileY) {
        return root().resolve(assetId).resolve("L" + level).resolve(tileX + "_" + tileY + ".png");
    }

    /** Reads cached asset dimensions/LOD count without loading image pixels. */
    public static ImportedAsset assetInfo(String assetId) throws IOException {
        Path dir = root().resolve(assetId == null ? "" : assetId);
        Path manifest = dir.resolve("manifest.txt");
        if (!Files.isRegularFile(manifest)) throw new IOException("Reference asset is missing");
        return readManifest(assetId, dir, manifest);
    }

    public static int[] levelSize(String assetId, int level) throws IOException {
        ImportedAsset info = assetInfo(assetId);
        int w=info.width(), h=info.height();
        for(int i=0;i<Math.max(0,level);i++){w=Math.max(1,(w+1)/2);h=Math.max(1,(h+1)/2);}
        return new int[]{w,h};
    }

    /** Samples one ARGB pixel from a cached pyramid level without registering a GPU texture. Used by
     *  the in-world Blueprint mosaic so image-backed guides can remain client-only and distance-capped. */
    public static int sampleArgb(String cacheId, int level, double u, double v) throws IOException {
        int[] size=levelSize(cacheId,level);
        int px=(int)Math.floor(Math.max(0.0,Math.min(0.999999,u))*size[0]);
        int py=(int)Math.floor(Math.max(0.0,Math.min(0.999999,v))*size[1]);
        int tx=Math.floorDiv(px,TILE_SIZE), ty=Math.floorDiv(py,TILE_SIZE);
        int lx=Math.floorMod(px,TILE_SIZE), ly=Math.floorMod(py,TILE_SIZE);
        String key=cacheId+"|"+level+"|"+tx+"|"+ty;
        BufferedImage image=SAMPLE_TILE_CACHE.get(key);
        if(image==null){
            Path tile=tilePath(cacheId,level,tx,ty);
            image=ImageIO.read(tile.toFile());
            if(image==null) throw new IOException("Reference tile is unreadable");
            SAMPLE_TILE_CACHE.put(key,image);
        }
        return image.getRGB(Math.min(lx,image.getWidth()-1),Math.min(ly,image.getHeight()-1));
    }

    public static boolean exists(String assetId) {
        return assetId != null && !assetId.isBlank() && Files.isRegularFile(root().resolve(assetId).resolve("manifest.txt"));
    }

    private static void writeLevel(Path dir, int index, BufferedImage image) throws IOException {
        Path levelDir = dir.resolve("L" + index); Files.createDirectories(levelDir);
        int tilesX = (image.getWidth() + TILE_SIZE - 1) / TILE_SIZE;
        int tilesY = (image.getHeight() + TILE_SIZE - 1) / TILE_SIZE;
        for (int ty=0; ty<tilesY; ty++) for (int tx=0; tx<tilesX; tx++) {
            int x = tx*TILE_SIZE, y = ty*TILE_SIZE;
            int w = Math.min(TILE_SIZE, image.getWidth()-x), h = Math.min(TILE_SIZE, image.getHeight()-y);
            BufferedImage tile = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tile.createGraphics(); g.drawImage(image, 0,0,w,h,x,y,x+w,y+h,null); g.dispose();
            ImageIO.write(tile, "PNG", levelDir.resolve(tx + "_" + ty + ".png").toFile());
        }
    }

    private static BufferedImage downsampleHalf(BufferedImage src) {
        int w=Math.max(1,(src.getWidth()+1)/2), h=Math.max(1,(src.getHeight()+1)/2);
        BufferedImage dst=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src,0,0,w,h,null); g.dispose(); return dst;
    }

    private static BufferedImage ensureArgb(BufferedImage src) {
        if (src.getType()==BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out=new BufferedImage(src.getWidth(),src.getHeight(),BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=out.createGraphics(); g.drawImage(src,0,0,null); g.dispose(); return out;
    }

    private static String extension(String lower) { return lower.endsWith(".png") ? ".png" : ".jpg"; }
    private static String sha256(Path source) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(source)) { byte[] buf=new byte[1024*1024]; int n; while((n=in.read(buf))>0) digest.update(buf,0,n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IOException(ex); }
    }
    private static ImportedAsset readManifest(String id, Path dir, Path file) throws IOException {
        int width=0,height=0,levels=0;
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            if(line.startsWith("width=")) width=Integer.parseInt(line.substring(6));
            else if(line.startsWith("height=")) height=Integer.parseInt(line.substring(7));
            else if(line.startsWith("levels=")) levels=Integer.parseInt(line.substring(7));
        }
        return new ImportedAsset(id,width,height,levels,dir);
    }
}
