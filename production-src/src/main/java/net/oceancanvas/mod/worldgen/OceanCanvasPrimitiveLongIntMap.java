package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.function.LongToIntFunction;

/**
 * Striped primitive long->int map for hot, transient lighting scheduler state.
 *
 * <p>v253.125.41 removes the remaining Long/Integer boxing from active light-delay
 * and pass bookkeeping without changing the authoritative proof state or any on-disk
 * format. Keys are striped exactly like {@link OceanCanvasPrimitiveLongSet}; hot
 * point operations lock one stripe, while rare snapshots acquire all stripes in a
 * stable order.</p>
 */
final class OceanCanvasPrimitiveLongIntMap {
    static final int ABSENT = Integer.MIN_VALUE;
    private static final int STRIPE_COUNT = 32;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;

    private final Long2IntOpenHashMap[] stripes = new Long2IntOpenHashMap[STRIPE_COUNT];
    private final AtomicInteger size = new AtomicInteger();

    OceanCanvasPrimitiveLongIntMap() {
        for (int i = 0; i < stripes.length; i++) {
            Long2IntOpenHashMap map = new Long2IntOpenHashMap();
            map.defaultReturnValue(ABSENT);
            stripes[i] = map;
        }
    }

    private static int stripeIndex(long value) {
        long mixed = value ^ (value >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return ((int) mixed) & STRIPE_MASK;
    }

    int put(long key, int value) {
        if (value == ABSENT) throw new IllegalArgumentException("Integer.MIN_VALUE is reserved as the absent sentinel");
        Long2IntOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            int previous = stripe.put(key, value);
            if (previous == ABSENT) size.incrementAndGet();
            return previous;
        }
    }

    int get(long key) {
        Long2IntOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) { return stripe.get(key); }
    }

    int getOrDefault(long key, int fallback) {
        int value = get(key);
        return value == ABSENT ? fallback : value;
    }

    boolean containsKey(long key) {
        Long2IntOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) { return stripe.containsKey(key); }
    }

    int remove(long key) {
        Long2IntOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            int previous = stripe.remove(key);
            if (previous != ABSENT) size.decrementAndGet();
            return previous;
        }
    }


    /** Atomic capped increment without Integer boxing; returns the resulting value. */
    int incrementCapped(long key, int cap) {
        Long2IntOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            int previous = stripe.get(key);
            int next = previous == ABSENT ? 1 : (previous >= cap ? cap : previous + 1);
            stripe.put(key, next);
            if (previous == ABSENT) size.incrementAndGet();
            return next;
        }
    }


    /** Allocation-free value count used by Pregen audit diagnostics. */
    int countValuesAtLeast(int threshold) {
        int count = 0;
        for (Long2IntOpenHashMap stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) {
                    long key = it.nextLong();
                    if (stripe.get(key) >= threshold) count++;
                }
            }
        }
        return count;
    }

    int size() { return size.get(); }
    boolean isEmpty() { return size.get() == 0; }

    void clear() {
        withAllStripeLocks(0, () -> {
            for (Long2IntOpenHashMap stripe : stripes) stripe.clear();
            size.set(0);
        });
    }

    /** Allocation-free read traversal. The consumer must not structurally mutate this map. */
    void forEachKey(LongConsumer consumer) {
        for (Long2IntOpenHashMap stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) consumer.accept(it.nextLong());
            }
        }
    }

    /** Allocation-free primitive reduction used by exact small-debt counting. */
    int sumKeys(LongToIntFunction function) {
        int sum = 0;
        for (Long2IntOpenHashMap stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) sum += function.applyAsInt(it.nextLong());
            }
        }
        return sum;
    }

    /** Rare atomic primitive snapshot for diagnostics/reconciliation. */
    long[] keysSnapshot() {
        final long[][] holder = new long[1][];
        withAllStripeLocks(0, () -> {
            long[] out = new long[size.get()];
            int i = 0;
            for (Long2IntOpenHashMap stripe : stripes) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) out[i++] = it.nextLong();
            }
            if (i != out.length) throw new IllegalStateException("primitive long-int map size drift while fully locked");
            holder[0] = out;
        });
        return holder[0];
    }


    /** Boxed diagnostic view over one atomic primitive snapshot. Never used by the hot scheduler. */
    Iterable<Long> boxedKeySnapshot() {
        long[] keys = keysSnapshot();
        return () -> new Iterator<>() {
            int index;
            @Override public boolean hasNext() { return index < keys.length; }
            @Override public Long next() {
                if (!hasNext()) throw new NoSuchElementException();
                return Long.valueOf(keys[index++]);
            }
        };
    }

    /** Copies at most {@code limit} keys without boxing. */
    int copyFirstKeys(long[] destination, int limit) {
        int cap = Math.min(Math.max(0, limit), destination.length);
        if (cap == 0) return 0;
        int count = 0;
        for (Long2IntOpenHashMap stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) {
                    destination[count++] = it.nextLong();
                    if (count == cap) return count;
                }
            }
        }
        return count;
    }

    private void withAllStripeLocks(int index, Runnable action) {
        if (index == stripes.length) {
            action.run();
            return;
        }
        synchronized (stripes[index]) {
            withAllStripeLocks(index + 1, action);
        }
    }
}
