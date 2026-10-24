package net.oceancanvas.mod.worldgen;

/**
 * Small growable primitive FIFO used by the light scheduler's event-driven lanes.
 *
 * <p>The scheduler tolerates stale nodes and validates lane membership after poll,
 * so removal-by-value is deliberately unsupported. This keeps enqueue/dequeue O(1)
 * and avoids one boxed {@code Long} plus one linked queue node for every outstanding
 * lighting obligation. All mutators are synchronized because visibility upgrades may
 * be requested from networking/lifecycle callbacks while the server tick drains work.</p>
 */
final class OceanCanvasPrimitiveLongQueue {
    /** No valid Minecraft chunk can encode this packed position inside the world border. */
    static final long EMPTY = Long.MIN_VALUE;

    private long[] values = new long[256];
    private int head;
    private int size;

    synchronized void offer(long value) {
        if (value == EMPTY) throw new IllegalArgumentException("reserved empty sentinel");
        offerUnchecked(value);
    }

    /**
     * Atomically couple authoritative lane-membership admission with queue insertion.
     * This is used by v253.125.36 lane compaction so a concurrent priority upgrade
     * cannot be lost between membership.add() and a queue rebuild.
     */
    synchronized boolean offerIfMembershipAdded(OceanCanvasPrimitiveLongSet membership, long value) {
        if (value == EMPTY) throw new IllegalArgumentException("reserved empty sentinel");
        if (!membership.add(value)) return false;
        offerUnchecked(value);
        return true;
    }

    synchronized long poll() {
        if (size == 0) return EMPTY;
        long value = values[head];
        head++;
        if (head == values.length) head = 0;
        size--;
        if (size == 0) head = 0;
        return value;
    }

    synchronized boolean isEmpty() { return size == 0; }
    synchronized int size() { return size; }
    synchronized void clear() { head = 0; size = 0; }

    /**
     * Replace stale queue history with exactly one primitive node per live lane member.
     * Additions through {@link #offerIfMembershipAdded(OceanCanvasPrimitiveLongSet, long)} are serialized with
     * this rebuild, so a concurrent newly-admitted member cannot lose its queue node.
     * Removals may race and merely leave one harmless stale node for normal validation.
     */
    synchronized int rebuildFromMembership(OceanCanvasPrimitiveLongSet membership) {
        int before = size;
        long[] snapshot = membership.snapshot();
        ensureCapacity(snapshot.length);
        head = 0;
        System.arraycopy(snapshot, 0, values, 0, snapshot.length);
        size = snapshot.length;
        return Math.max(0, before - size);
    }

    private void offerUnchecked(long value) {
        ensureCapacity(size + 1);
        int tail = head + size;
        if (tail >= values.length) tail -= values.length;
        values[tail] = value;
        size++;
    }

    private void ensureCapacity(int wanted) {
        if (wanted <= values.length) return;
        int nextLength = Math.max(wanted, values.length << 1);
        long[] next = new long[nextLength];
        int first = Math.min(size, values.length - head);
        System.arraycopy(values, head, next, 0, first);
        if (size > first) System.arraycopy(values, 0, next, first, size - first);
        values = next;
        head = 0;
    }
}
