package net.oceancanvas.mod.pregen;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.LongConsumer;

/**
 * Server-thread-owned primitive long deque used by the Pregen recovery lanes.
 *
 * <p>The historical implementation used {@code ArrayDeque<Long>}. A large graceful
 * restart can legitimately retain six figures of LIGHT_ONLY recovery identities;
 * keeping those as boxed {@code Long} objects adds substantial heap/GC pressure at
 * exactly the time Ocean Canvas is trying to recover a mature world. This deque keeps
 * the same FIFO/add-first/remove semantics in a circular {@code long[]} buffer while
 * still implementing {@link java.util.Collection} for legacy call sites and codecs.
 * It is deliberately not synchronized: a {@code PregenManager.Job} is owned by the
 * integrated/dedicated server thread.</p>
 */
final class OceanCanvasPrimitiveLongDeque extends AbstractCollection<Long> {
    private static final int MIN_CAPACITY = 16;
    private long[] elements = new long[MIN_CAPACITY];
    private int head;
    private int size;

    @Override public int size() { return size; }
    @Override public boolean isEmpty() { return size == 0; }

    @Override public void clear() {
        head = 0;
        size = 0;
    }

    @Override public boolean add(Long value) {
        if (value == null) throw new NullPointerException("value");
        addLast(value.longValue());
        return true;
    }

    void addLast(long value) {
        ensureCapacity(size + 1);
        elements[physicalIndex(size)] = value;
        size++;
    }

    void addFirst(long value) {
        ensureCapacity(size + 1);
        head = (head - 1) & (elements.length - 1);
        elements[head] = value;
        size++;
    }

    long removeFirst() {
        if (size == 0) throw new NoSuchElementException();
        long value = elements[head];
        head = (head + 1) & (elements.length - 1);
        size--;
        if (size == 0) head = 0;
        return value;
    }

    Long peekFirst() {
        return size == 0 ? null : Long.valueOf(elements[head]);
    }

    long firstLong() {
        if (size == 0) throw new NoSuchElementException();
        return elements[head];
    }

    boolean contains(long value) {
        for (int i = 0; i < size; i++) if (elements[physicalIndex(i)] == value) return true;
        return false;
    }

    boolean remove(long value) {
        for (int i = 0; i < size; i++) {
            if (elements[physicalIndex(i)] == value) {
                removeAtLogicalIndex(i);
                return true;
            }
        }
        return false;
    }

    @Override public boolean contains(Object value) {
        return value instanceof Long l && contains(l.longValue());
    }

    @Override public boolean remove(Object value) {
        return value instanceof Long l && remove(l.longValue());
    }

    void forEachLong(LongConsumer consumer) {
        for (int i = 0; i < size; i++) consumer.accept(elements[physicalIndex(i)]);
    }

    long[] toLongArray() {
        long[] out = new long[size];
        for (int i = 0; i < size; i++) out[i] = elements[physicalIndex(i)];
        return out;
    }

    @Override public Iterator<Long> iterator() {
        return new Iterator<>() {
            private int cursor;
            private int lastReturned = -1;

            @Override public boolean hasNext() { return cursor < size; }

            @Override public Long next() {
                if (!hasNext()) throw new NoSuchElementException();
                lastReturned = cursor;
                return Long.valueOf(elements[physicalIndex(cursor++)]);
            }

            @Override public void remove() {
                if (lastReturned < 0) throw new IllegalStateException();
                removeAtLogicalIndex(lastReturned);
                cursor = lastReturned;
                lastReturned = -1;
            }
        };
    }

    private int physicalIndex(int logicalIndex) {
        return (head + logicalIndex) & (elements.length - 1);
    }

    private void ensureCapacity(int required) {
        if (required <= elements.length) return;
        int capacity = elements.length;
        while (capacity < required) capacity <<= 1;
        long[] grown = new long[capacity];
        for (int i = 0; i < size; i++) grown[i] = elements[physicalIndex(i)];
        elements = grown;
        head = 0;
    }

    private void removeAtLogicalIndex(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex >= size) throw new IndexOutOfBoundsException(logicalIndex);
        // Shift the shorter side of the ring. Arbitrary removal is rare (recovery
        // promotion/dedup), but preserving FIFO order is required for restart replay.
        if (logicalIndex < (size >>> 1)) {
            for (int i = logicalIndex; i > 0; i--) {
                elements[physicalIndex(i)] = elements[physicalIndex(i - 1)];
            }
            head = (head + 1) & (elements.length - 1);
        } else {
            for (int i = logicalIndex; i < size - 1; i++) {
                elements[physicalIndex(i)] = elements[physicalIndex(i + 1)];
            }
        }
        size--;
        if (size == 0) head = 0;
    }
}
