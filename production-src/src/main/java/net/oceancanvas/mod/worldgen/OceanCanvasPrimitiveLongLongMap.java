package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.function.LongToIntFunction;

/**
 * Striped primitive long->long map for large transient lighting-debt state.
 *
 * <p>v253.125.42 extends the primitive active-state work from .41 to the dormant
 * strict-fault and pressure-park lanes. These maps can retain thousands of chunk
 * coordinates for long periods; storing both key and due-tick as primitives avoids
 * one boxed key, one boxed value and one concurrent-map node per obligation while
 * preserving independent stripe locking for cross-thread wake/park activity.</p>
 *
 * <p>{@link Long#MIN_VALUE} is reserved as the absent sentinel. Ocean Canvas due
 * ticks/timestamps are non-negative, so the sentinel cannot collide with valid
 * runtime state.</p>
 */
final class OceanCanvasPrimitiveLongLongMap {
    static final long ABSENT = Long.MIN_VALUE;
    private static final int STRIPE_COUNT = 32;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;

    private final Long2LongOpenHashMap[] stripes = new Long2LongOpenHashMap[STRIPE_COUNT];
    private final AtomicInteger size = new AtomicInteger();

    OceanCanvasPrimitiveLongLongMap() {
        for (int i = 0; i < stripes.length; i++) {
            Long2LongOpenHashMap map = new Long2LongOpenHashMap();
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

    long put(long key, long value) {
        if (value == ABSENT) throw new IllegalArgumentException("Long.MIN_VALUE is reserved as the absent sentinel");
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            long previous = stripe.put(key, value);
            if (previous == ABSENT) size.incrementAndGet();
            return previous;
        }
    }


    /** Atomic put-if-absent without boxing; returns existing value or ABSENT when inserted. */
    long putIfAbsent(long key, long value) {
        if (value == ABSENT) throw new IllegalArgumentException("Long.MIN_VALUE is reserved as the absent sentinel");
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            long previous = stripe.get(key);
            if (previous != ABSENT || stripe.containsKey(key)) return previous;
            stripe.put(key, value);
            size.incrementAndGet();
            return ABSENT;
        }
    }

    long get(long key) {
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) { return stripe.get(key); }
    }

    long getOrDefault(long key, long fallback) {
        long value = get(key);
        return value == ABSENT ? fallback : value;
    }

    boolean containsKey(long key) {
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) { return stripe.containsKey(key); }
    }

    long remove(long key) {
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            long previous = stripe.remove(key);
            if (previous != ABSENT) size.decrementAndGet();
            return previous;
        }
    }

    /** Compare-and-remove used by retry wake paths. */
    boolean remove(long key, long expectedValue) {
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            if (!stripe.containsKey(key) || stripe.get(key) != expectedValue) return false;
            stripe.remove(key);
            size.decrementAndGet();
            return true;
        }
    }

    /** Compare-and-replace used by pressure postponement without boxing. */
    boolean replace(long key, long expectedValue, long newValue) {
        if (newValue == ABSENT) throw new IllegalArgumentException("Long.MIN_VALUE is reserved as the absent sentinel");
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            if (!stripe.containsKey(key) || stripe.get(key) != expectedValue) return false;
            stripe.put(key, newValue);
            return true;
        }
    }


    /** Atomic max merge without boxing; returns the resulting value. */
    long mergeMax(long key, long value) {
        if (value == ABSENT) throw new IllegalArgumentException("Long.MIN_VALUE is reserved as the absent sentinel");
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            long previous = stripe.get(key);
            if (previous == ABSENT) {
                stripe.put(key, value);
                size.incrementAndGet();
                return value;
            }
            if (value > previous) stripe.put(key, value);
            return Math.max(previous, value);
        }
    }

    /** Atomic min merge without boxing; returns the resulting value. */
    long mergeMin(long key, long value) {
        if (value == ABSENT) throw new IllegalArgumentException("Long.MIN_VALUE is reserved as the absent sentinel");
        Long2LongOpenHashMap stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            long previous = stripe.get(key);
            if (previous == ABSENT) {
                stripe.put(key, value);
                size.incrementAndGet();
                return value;
            }
            if (value < previous) stripe.put(key, value);
            return Math.min(previous, value);
        }
    }

    /** Allocation-free value count used by visible-light diagnostics. */
    int countValuesAtMost(long threshold) {
        int count = 0;
        for (Long2LongOpenHashMap stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) {
                    long key = it.nextLong();
                    if (stripe.get(key) <= threshold) count++;
                }
            }
        }
        return count;
    }

    /**
     * Removes at most {@code limit} entries whose value is <= threshold and copies
     * their keys into {@code destination}. Hot callers can reuse a fixed primitive
     * buffer and perform side effects after the stripe locks are released.
     */
    int drainKeysAtOrBelow(long threshold, long[] destination, int limit) {
        int cap = Math.min(Math.max(0, limit), destination.length);
        if (cap == 0) return 0;
        int count = 0;
        for (Long2LongOpenHashMap stripe : stripes) {
            synchronized (stripe) {
                int stripeStart = count;
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext() && count < cap;) {
                    long key = it.nextLong();
                    if (stripe.get(key) <= threshold) destination[count++] = key;
                }
                // Remove after iteration so this works with both fastutil and the
                // minimal HashMap-backed regression stub without iterator mutation.
                for (int i = stripeStart; i < count; i++) {
                    if (stripe.remove(destination[i]) != ABSENT) size.decrementAndGet();
                }
                if (count == cap) return count;
            }
        }
        return count;
    }

    int size() { return size.get(); }
    boolean isEmpty() { return size.get() == 0; }

    void clear() {
        withAllStripeLocks(0, () -> {
            for (Long2LongOpenHashMap stripe : stripes) stripe.clear();
            size.set(0);
        });
    }

    /** Allocation-free read traversal. The consumer must not structurally mutate this map. */
    void forEachKey(LongConsumer consumer) {
        for (Long2LongOpenHashMap stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) consumer.accept(it.nextLong());
            }
        }
    }

    /** Allocation-free primitive reduction used by exact small-debt counting. */
    int sumKeys(LongToIntFunction function) {
        int sum = 0;
        for (Long2LongOpenHashMap stripe : stripes) {
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
            for (Long2LongOpenHashMap stripe : stripes) {
                for (LongIterator it = stripe.keySet().iterator(); it.hasNext();) out[i++] = it.nextLong();
            }
            if (i != out.length) throw new IllegalStateException("primitive long-long map size drift while fully locked");
            holder[0] = out;
        });
        return holder[0];
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
