package net.oceancanvas.mod.worldgen;

import java.util.Map;

/**
 * Growable primitive min-heap keyed by an absolute game-tick deadline.
 *
 * <p>The heap deliberately does not own authoritative membership. Callers keep the
 * authoritative packed-chunk -> deadline map and validate/claim every popped node.
 * This lets deadline changes append cheaply while stale nodes are discarded without
 * object allocation. {@link #rebuildFrom(Map)} is provided for rare stale-node
 * compaction when repeated reschedules would otherwise retain too much heap memory.</p>
 */
final class OceanCanvasPrimitiveLongDeadlineHeap {
    private long[] packed = new long[256];
    private long[] due = new long[256];
    private int size;
    private final DueEntry pollResult = new DueEntry();

    synchronized void offer(long value, long dueTick) {
        ensureCapacity(size + 1);
        int i = size++;
        packed[i] = value;
        due[i] = dueTick;
        siftUp(i);
    }

    synchronized DueEntry pollDue(long nowTick) {
        if (size == 0 || due[0] > nowTick) return null;
        long value = packed[0], dueTick = due[0];
        int last = --size;
        if (last > 0) {
            packed[0] = packed[last];
            due[0] = due[last];
            siftDown(0);
        }
        return pollResult.set(value, dueTick);
    }

    synchronized int size() { return size; }
    synchronized boolean isEmpty() { return size == 0; }
    synchronized void clear() { size = 0; }

    /** Replace stale scheduling nodes with one exact node per authoritative entry. */
    synchronized void rebuildFrom(Map<Long, Long> authoritativeDeadlines) {
        int wanted = authoritativeDeadlines.size();
        ensureCapacity(wanted);
        int i = 0;
        for (Map.Entry<Long, Long> entry : authoritativeDeadlines.entrySet()) {
            packed[i] = entry.getKey().longValue();
            due[i] = entry.getValue().longValue();
            i++;
        }
        size = i;
        for (int parent = (size >>> 1) - 1; parent >= 0; parent--) siftDown(parent);
    }


    /** Primitive-authoritative rebuild used by persisted-light audits. */
    synchronized void rebuildFrom(OceanCanvasPrimitiveLongLongMap authoritativeDeadlines) {
        long[] keys = authoritativeDeadlines.keysSnapshot();
        ensureCapacity(keys.length);
        int i = 0;
        for (long key : keys) {
            long deadline = authoritativeDeadlines.get(key);
            if (deadline == OceanCanvasPrimitiveLongLongMap.ABSENT) continue;
            packed[i] = key;
            due[i] = deadline;
            i++;
        }
        size = i;
        for (int parent = (size >>> 1) - 1; parent >= 0; parent--) siftDown(parent);
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (lessOrEqual(parent, i)) break;
            swap(parent, i);
            i = parent;
        }
    }

    private void siftDown(int i) {
        while (true) {
            int left = (i << 1) + 1;
            if (left >= size) return;
            int right = left + 1;
            int best = right < size && !lessOrEqual(left, right) ? right : left;
            if (lessOrEqual(i, best)) return;
            swap(i, best);
            i = best;
        }
    }

    private boolean lessOrEqual(int a, int b) {
        if (due[a] != due[b]) return due[a] < due[b];
        return packed[a] <= packed[b];
    }

    private void swap(int a, int b) {
        long p = packed[a]; packed[a] = packed[b]; packed[b] = p;
        long d = due[a]; due[a] = due[b]; due[b] = d;
    }

    private void ensureCapacity(int wanted) {
        if (wanted <= packed.length) return;
        int next = Math.max(wanted, packed.length << 1);
        packed = java.util.Arrays.copyOf(packed, next);
        due = java.util.Arrays.copyOf(due, next);
    }

    /** Reused by one heap; consume synchronously before the next poll on that heap. */
    static final class DueEntry {
        private long packed;
        private long dueTick;
        long packed() { return packed; }
        long dueTick() { return dueTick; }
        private DueEntry set(long packed, long dueTick) {
            this.packed = packed;
            this.dueTick = dueTick;
            return this;
        }
    }
}
