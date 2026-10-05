package net.oceancanvas.mod.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Incrementally maintained row/run index for chunk-key ledgers.
 * Value {@code 0} means absent; non-zero values are disjoint, maximally merged
 * inclusive X intervals per Z row.
 */
public final class OceanCanvasByteRowRunIndex {
    public record Run(int z, int minX, int maxX, byte value) {}
    private record Segment(int minX, int maxX, byte value) {}

    @FunctionalInterface
    public interface RunConsumer {
        void accept(int z, int minX, int maxX, byte value);
    }

    private final TreeMap<Integer, TreeMap<Integer, Segment>> rows = new TreeMap<>();
    private long pointCount;

    public long pointCount() { return pointCount; }
    public boolean isEmpty() { return pointCount == 0L; }

    public byte get(int x, int z) {
        TreeMap<Integer, Segment> row = rows.get(z);
        if (row == null) return 0;
        Map.Entry<Integer, Segment> floor = row.floorEntry(x);
        if (floor == null) return 0;
        Segment s = floor.getValue();
        return x <= s.maxX ? s.value : 0;
    }

    public void set(int x, int z, byte value) {
        TreeMap<Integer, Segment> row = rows.computeIfAbsent(z, ignored -> new TreeMap<>());
        Map.Entry<Integer, Segment> floor = row.floorEntry(x);
        Segment containing = floor != null && x <= floor.getValue().maxX ? floor.getValue() : null;
        byte old = containing == null ? 0 : containing.value;
        if (old == value) {
            if (row.isEmpty()) rows.remove(z);
            return;
        }

        if (containing != null) {
            row.remove(containing.minX);
            if (containing.minX < x) {
                Segment left = new Segment(containing.minX, x - 1, containing.value);
                row.put(left.minX, left);
            }
            if (x < containing.maxX) {
                Segment right = new Segment(x + 1, containing.maxX, containing.value);
                row.put(right.minX, right);
            }
        }

        if (old == 0 && value != 0) pointCount++;
        else if (old != 0 && value == 0) pointCount--;

        if (value != 0) putAndMerge(row, new Segment(x, x, value));
        if (row.isEmpty()) rows.remove(z);
    }

    /**
     * Replace an inclusive range in O(overlapping-runs log n), rather than calling
     * {@link #set(int, int, byte)} once per X coordinate. This is the persisted-run
     * load path for mature Canvas worlds where one run commonly represents an entire
     * 1,250-chunk row.
     */
    public void setRange(int minX, int maxX, int z, byte value) {
        if (maxX < minX) return;
        long width = (long) maxX - (long) minX + 1L;
        if (width > 1_000_001L) throw new IllegalArgumentException("row run too wide: " + width);

        TreeMap<Integer, Segment> row = rows.get(z);
        if (row == null) {
            if (value == 0) return;
            row = new TreeMap<>();
            rows.put(z, row);
            row.put(minX, new Segment(minX, maxX, value));
            pointCount += width;
            return;
        }

        long oldCovered = 0L;
        Map.Entry<Integer, Segment> entry = row.floorEntry(minX);
        if (entry == null || entry.getValue().maxX < minX) entry = row.ceilingEntry(minX);
        while (entry != null) {
            Segment segment = entry.getValue();
            if (segment.minX > maxX) break;
            int key = entry.getKey();
            Map.Entry<Integer, Segment> next = row.higherEntry(key);
            if (segment.maxX >= minX) {
                int overlapMin = Math.max(minX, segment.minX);
                int overlapMax = Math.min(maxX, segment.maxX);
                if (overlapMax >= overlapMin) oldCovered += (long) overlapMax - (long) overlapMin + 1L;
                row.remove(key);
                if (segment.minX < minX) {
                    Segment left = new Segment(segment.minX, minX - 1, segment.value);
                    row.put(left.minX, left);
                }
                if (segment.maxX > maxX) {
                    Segment right = new Segment(maxX + 1, segment.maxX, segment.value);
                    row.put(right.minX, right);
                }
            }
            entry = next;
        }

        long newCovered = value == 0 ? 0L : width;
        pointCount += newCovered - oldCovered;
        if (value != 0) putAndMerge(row, new Segment(minX, maxX, value));
        if (row.isEmpty()) rows.remove(z);
    }

    public void clear() {
        rows.clear();
        pointCount = 0L;
    }

    /** Visit runs without materializing an intermediate Run list. */
    public void forEachRun(RunConsumer consumer) {
        if (consumer == null) throw new NullPointerException("consumer");
        for (Map.Entry<Integer, TreeMap<Integer, Segment>> rowEntry : rows.entrySet()) {
            int z = rowEntry.getKey();
            for (Segment s : rowEntry.getValue().values()) {
                consumer.accept(z, s.minX, s.maxX, s.value);
            }
        }
    }

    public List<Run> snapshot() {
        if (rows.isEmpty()) return List.of();
        ArrayList<Run> out = new ArrayList<>();
        forEachRun((z, minX, maxX, value) -> out.add(new Run(z, minX, maxX, value)));
        return List.copyOf(out);
    }

    private static void putAndMerge(TreeMap<Integer, Segment> row, Segment input) {
        Segment merged = input;
        Map.Entry<Integer, Segment> lowerEntry = row.floorEntry(merged.minX);
        if (lowerEntry != null) {
            Segment lower = lowerEntry.getValue();
            if (lower.value == merged.value && (long) lower.maxX + 1L == merged.minX) {
                row.remove(lower.minX);
                merged = new Segment(lower.minX, merged.maxX, merged.value);
            }
        }
        Map.Entry<Integer, Segment> higherEntry = row.ceilingEntry(merged.minX);
        if (higherEntry != null) {
            Segment higher = higherEntry.getValue();
            if (higher.value == merged.value && (long) merged.maxX + 1L == higher.minX) {
                row.remove(higher.minX);
                merged = new Segment(merged.minX, higher.maxX, merged.value);
            }
        }
        row.put(merged.minX, merged);
    }
}
