package net.oceancanvas.core.geometry;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Shared block-coordinate region geometry kernel.
 *
 * <p>This class intentionally has no Minecraft/Fabric dependencies so the exact
 * polygon-to-chunk coverage logic used by the client editor can also be fuzzed
 * offline. Chunk packing matches Minecraft's {@code ChunkPos.pack(int,int)}
 * layout without importing it, which keeps the kernel available to the test
 * harness.</p>
 */
public final class OceanCanvasRegionGeometry {
    public static final long DEFAULT_MAX_RASTER_CHUNKS = 1_600_000L;

    private OceanCanvasRegionGeometry() {}

    /** Inclusive block-coordinate envelope. */
    public record BlockBounds(int minX, int minZ, int maxX, int maxZ) {
        public long width() { return (long) maxX - minX + 1L; }
        public long height() { return (long) maxZ - minZ + 1L; }
    }

    /** Inclusive chunk-coordinate envelope shared by Regions, previews, Pregen and Restore. */
    public record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {
        public boolean isEmpty() { return minX > maxX || minZ > maxZ; }
        public long count() {
            if (isEmpty()) return 0L;
            long width = (long) maxX - minX + 1L, height = (long) maxZ - minZ + 1L;
            return width > Long.MAX_VALUE / height ? Long.MAX_VALUE : width * height;
        }
        public boolean contains(int x, int z) { return !isEmpty() && x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        public boolean containsPacked(long packed) { return contains(chunkX(packed), chunkZ(packed)); }
        public ChunkBounds intersect(ChunkBounds other) {
            if (other == null) return emptyChunkBounds();
            return new ChunkBounds(Math.max(minX, other.minX), Math.min(maxX, other.maxX),
                    Math.max(minZ, other.minZ), Math.min(maxZ, other.maxZ));
        }
    }

    public static ChunkBounds emptyChunkBounds() { return new ChunkBounds(1, 0, 1, 0); }

    public static BlockBounds blockBounds(int x1, int z1, int x2, int z2) {
        return new BlockBounds(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public static BlockBounds blockBounds(List<Integer> raw) {
        List<Integer> v = cleanVertices(raw);
        if (v.isEmpty()) return new BlockBounds(0, 0, 0, 0);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < v.size(); i += 2) {
            minX = Math.min(minX, v.get(i)); maxX = Math.max(maxX, v.get(i));
            minZ = Math.min(minZ, v.get(i + 1)); maxZ = Math.max(maxZ, v.get(i + 1));
        }
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    public static ChunkBounds chunkBoundsForBlocks(int x1, int z1, int x2, int z2) {
        BlockBounds b = blockBounds(x1, z1, x2, z2);
        return new ChunkBounds(Math.floorDiv(b.minX, 16), Math.floorDiv(b.maxX, 16),
                Math.floorDiv(b.minZ, 16), Math.floorDiv(b.maxZ, 16));
    }

    /** Whole chunks whose complete 16x16 footprint lies inside the inclusive block envelope. */
    public static ChunkBounds wholeChunksInsideBlocks(int minX, int minZ, int maxX, int maxZ) {
        BlockBounds b = blockBounds(minX, minZ, maxX, maxZ);
        int minCX = (int) -Math.floorDiv(-(long) b.minX, 16L);
        int minCZ = (int) -Math.floorDiv(-(long) b.minZ, 16L);
        int maxCX = (int) Math.floorDiv((long) b.maxX - 15L, 16L);
        int maxCZ = (int) Math.floorDiv((long) b.maxZ - 15L, 16L);
        return new ChunkBounds(minCX, maxCX, minCZ, maxCZ);
    }

    public static boolean isChunkAligned(BlockBounds b) {
        return b != null && Math.floorMod(b.minX, 16) == 0 && Math.floorMod(b.minZ, 16) == 0
                && Math.floorMod(b.maxX, 16) == 15 && Math.floorMod(b.maxZ, 16) == 15;
    }

    public static long intersectionCount(ChunkBounds a, ChunkBounds b) {
        return a == null || b == null ? 0L : a.intersect(b).count();
    }

    /** Lazy immutable rectangular chunk view; never boxes the full footprint up front. */
    public static Set<Long> rectangularChunkSet(ChunkBounds bounds) {
        if (bounds == null || bounds.isEmpty()) return Set.of();
        return new AbstractSet<>() {
            @Override public Iterator<Long> iterator() {
                return new Iterator<>() {
                    private int x = bounds.minX, z = bounds.minZ;
                    private boolean has = true;
                    @Override public boolean hasNext() { return has; }
                    @Override public Long next() {
                        if (!has) throw new NoSuchElementException();
                        long packed = packChunk(x, z);
                        if (x < bounds.maxX) x++;
                        else if (z < bounds.maxZ) { x = bounds.minX; z++; }
                        else has = false;
                        return packed;
                    }
                };
            }
            @Override public int size() { return (int) Math.min(Integer.MAX_VALUE, bounds.count()); }
            @Override public boolean contains(Object value) { return value instanceof Long packed && bounds.containsPacked(packed); }
        };
    }

    /** Lazy immutable intersection of an arbitrary chunk set and a rectangular envelope. */
    public static Set<Long> clippedChunkSet(Set<Long> base, ChunkBounds clip) {
        if (base == null || base.isEmpty() || clip == null || clip.isEmpty()) return Set.of();
        return new AbstractSet<>() {
            private volatile int cachedSize = -1;
            @Override public Iterator<Long> iterator() {
                Iterator<Long> it = base.iterator();
                return new Iterator<>() {
                    private Long next; private boolean ready;
                    private void advance() {
                        while (!ready && it.hasNext()) { Long candidate = it.next(); if (clip.containsPacked(candidate)) { next = candidate; ready = true; } }
                    }
                    @Override public boolean hasNext() { advance(); return ready; }
                    @Override public Long next() {
                        advance(); if (!ready) throw new NoSuchElementException();
                        Long current = next; next = null; ready = false; return current;
                    }
                };
            }
            @Override public int size() {
                int known = cachedSize; if (known >= 0) return known;
                long count = 0L; for (Long ignored : this) { if (++count >= Integer.MAX_VALUE) break; }
                int result = (int) Math.min(Integer.MAX_VALUE, count); cachedSize = result; return result;
            }
            @Override public boolean contains(Object value) {
                return value instanceof Long packed && clip.containsPacked(packed) && base.contains(packed);
            }
        };
    }

    public static long packChunk(int x, int z) {
        return ((long)x & 0xFFFFFFFFL) | (((long)z & 0xFFFFFFFFL) << 32);
    }

    public static int chunkX(long packed) { return (int)(packed & 0xFFFFFFFFL); }
    public static int chunkZ(long packed) { return (int)(packed >>> 32); }

    public static List<Integer> cleanVertices(List<Integer> raw) {
        if (raw == null) return List.of();
        ArrayList<Integer> out = new ArrayList<>();
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            int x = raw.get(i), z = raw.get(i + 1);
            if (out.size() >= 2 && out.get(out.size() - 2) == x && out.get(out.size() - 1) == z) continue;
            out.add(x); out.add(z);
        }
        if (out.size() >= 6 && out.get(0).equals(out.get(out.size() - 2)) && out.get(1).equals(out.get(out.size() - 1))) {
            out.remove(out.size() - 1); out.remove(out.size() - 1);
        }
        return List.copyOf(out);
    }

    public static boolean pointInPolygon(double x, double z, List<Integer> raw) {
        return pointInPolygonCleaned(x, z, cleanVertices(raw));
    }

    private static boolean pointInPolygonCleaned(double x, double z, List<Integer> v) {
        boolean inside = false;
        int n = v.size() / 2;
        if (n < 3) return false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = v.get(i * 2), zi = v.get(i * 2 + 1);
            double xj = v.get(j * 2), zj = v.get(j * 2 + 1);
            boolean crosses = ((zi > z) != (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
            if (crosses) inside = !inside;
        }
        return inside;
    }

    /** Same canonical crossing rule for client vector points without flattening/allocating a second list. */
    public static boolean pointInPolygonPairs(double x, double z, List<int[]> points) {
        if (points == null || points.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            int[] a = points.get(i), b = points.get(j);
            if (a == null || b == null || a.length < 2 || b.length < 2) continue;
            double xi = a[0], zi = a[1], xj = b[0], zj = b[1];
            boolean crosses = ((zi > z) != (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
            if (crosses) inside = !inside;
        }
        return inside;
    }

    public static boolean polygonIntersectsChunk(List<Integer> raw, int cx, int cz) {
        return polygonIntersectsChunkCleaned(cleanVertices(raw), cx, cz);
    }

    private static boolean polygonIntersectsChunkCleaned(List<Integer> v, int cx, int cz) {
        if (v.size() < 6) return false;
        double minX = cx * 16.0, minZ = cz * 16.0, maxX = minX + 16, maxZ = minZ + 16;
        if (pointInPolygonCleaned(minX + 8, minZ + 8, v)
                || pointInPolygonCleaned(minX, minZ, v) || pointInPolygonCleaned(maxX, minZ, v)
                || pointInPolygonCleaned(maxX, maxZ, v) || pointInPolygonCleaned(minX, maxZ, v)) return true;
        int n = v.size() / 2;
        for (int i = 0; i < n; i++) {
            double x = v.get(i * 2), z = v.get(i * 2 + 1);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) return true;
            int j = (i + 1) % n;
            double x2 = v.get(j * 2), z2 = v.get(j * 2 + 1);
            if (segmentsIntersect(x, z, x2, z2, minX, minZ, maxX, minZ)
                    || segmentsIntersect(x, z, x2, z2, maxX, minZ, maxX, maxZ)
                    || segmentsIntersect(x, z, x2, z2, maxX, maxZ, minX, maxZ)
                    || segmentsIntersect(x, z, x2, z2, minX, maxZ, minX, minZ)) return true;
        }
        return false;
    }

    /**
     * Immutable lazy chunk view for a polygon. The vertices are the canonical shape;
     * asking for a chunk set must not allocate ~1.56 million boxed Long objects for a
     * full 20k canvas. Membership is calculated from the cleaned polygon and iteration
     * rasterizes one candidate chunk at a time.
     */
    public static Set<Long> polygonChunkSet(List<Integer> raw) {
        List<Integer> v = cleanVertices(raw);
        if (v.size() < 6) return Set.of();
        BlockBounds blockBounds = blockBounds(v);
        ChunkBounds chunkBounds = chunkBoundsForBlocks(blockBounds.minX, blockBounds.minZ, blockBounds.maxX, blockBounds.maxZ);
        final int minCX = chunkBounds.minX, maxCX = chunkBounds.maxX;
        final int minCZ = chunkBounds.minZ, maxCZ = chunkBounds.maxZ;
        return new AbstractSet<>() {
            private volatile int cachedSize = -1;
            @Override public Iterator<Long> iterator() {
                return new Iterator<>() {
                    private int cx = minCX, cz = minCZ;
                    private Long next = advance();
                    private Long advance() {
                        while (cz <= maxCZ) {
                            int tx = cx, tz = cz;
                            if (cx < maxCX) cx++; else { cx = minCX; cz++; }
                            if (polygonIntersectsChunkCleaned(v, tx, tz)) return packChunk(tx, tz);
                        }
                        return null;
                    }
                    @Override public boolean hasNext() { return next != null; }
                    @Override public Long next() {
                        if (next == null) throw new NoSuchElementException();
                        Long current = next; next = advance(); return current;
                    }
                };
            }
            @Override public boolean isEmpty() { return !iterator().hasNext(); }
            @Override public boolean contains(Object value) {
                if (!(value instanceof Long packed)) return false;
                int cx = chunkX(packed), cz = chunkZ(packed);
                return cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ
                        && polygonIntersectsChunkCleaned(v, cx, cz);
            }
            @Override public int size() {
                int known = cachedSize;
                if (known >= 0) return known;
                long count = 0;
                for (Long ignored : this) { if (++count >= Integer.MAX_VALUE) break; }
                int result = (int)Math.min(Integer.MAX_VALUE, count);
                cachedSize = result;
                return result;
            }
        };
    }

    public static List<Long> polygonChunks(List<Integer> raw) {
        return polygonChunks(raw, DEFAULT_MAX_RASTER_CHUNKS);
    }

    /**
     * Materializing compatibility helper for diagnostics/tests. Runtime Region state
     * should use {@link #polygonChunkSet(List)} instead.
     */
    public static List<Long> polygonChunks(List<Integer> raw, long maxRasterChunks) {
        List<Integer> v = cleanVertices(raw);
        if (v.size() < 6) return List.of();
        BlockBounds blockBounds = blockBounds(v);
        ChunkBounds chunkBounds = chunkBoundsForBlocks(blockBounds.minX, blockBounds.minZ, blockBounds.maxX, blockBounds.maxZ);
        long area = chunkBounds.count();
        if (area <= 0 || area > Math.max(1L, maxRasterChunks)) return List.of();
        ArrayList<Long> out = new ArrayList<>();
        for (long packed : polygonChunkSet(v)) out.add(packed);
        return List.copyOf(out);
    }

    public static Set<Long> translatedChunkSet(Set<Long> chunks, int dxChunks, int dzChunks) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (long packed : chunks) out.add(packChunk(chunkX(packed) + dxChunks, chunkZ(packed) + dzChunks));
        return Set.copyOf(out);
    }

    public static List<Integer> translateVertices(List<Integer> raw, int dx, int dz) {
        List<Integer> v = cleanVertices(raw);
        ArrayList<Integer> out = new ArrayList<>(v.size());
        for (int i = 0; i + 1 < v.size(); i += 2) { out.add(v.get(i) + dx); out.add(v.get(i + 1) + dz); }
        return List.copyOf(out);
    }

    private static double orient(double ax, double az, double bx, double bz, double cx, double cz) {
        return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
    }

    private static boolean segmentsIntersect(double ax, double az, double bx, double bz,
                                             double cx, double cz, double dx, double dz) {
        double o1 = orient(ax, az, bx, bz, cx, cz), o2 = orient(ax, az, bx, bz, dx, dz);
        double o3 = orient(cx, cz, dx, dz, ax, az), o4 = orient(cx, cz, dx, dz, bx, bz);
        return (o1 == 0 && onSegment(ax, az, bx, bz, cx, cz))
                || (o2 == 0 && onSegment(ax, az, bx, bz, dx, dz))
                || (o3 == 0 && onSegment(cx, cz, dx, dz, ax, az))
                || (o4 == 0 && onSegment(cx, cz, dx, dz, bx, bz))
                || ((o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0));
    }

    private static boolean onSegment(double ax, double az, double bx, double bz, double px, double pz) {
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx) && pz >= Math.min(az, bz) && pz <= Math.max(az, bz);
    }
}
