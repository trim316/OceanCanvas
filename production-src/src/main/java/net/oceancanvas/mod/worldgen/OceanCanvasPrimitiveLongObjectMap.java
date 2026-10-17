package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * Striped primitive long-to-object map for transient runtime state.
 *
 * <p>The map exists for cooperative proof cursors and other session-only state where
 * {@code ConcurrentHashMap<Long,V>} spends a boxed {@link Long} plus a hash node per
 * active chunk. Keys are striped exactly like {@link OceanCanvasPrimitiveLongSet};
 * hot operations synchronize only one stripe. There is intentionally no weakly
 * consistent entry-set view: callers use key-directed operations so iteration cannot
 * accidentally become an unbounded hot-path scan.</p>
 *
 * <p>Values are never persisted by this container and {@code null} values are not
 * supported. Authoritative completion/certificate state remains elsewhere.</p>
 */
final class OceanCanvasPrimitiveLongObjectMap<V> {
    private static final int STRIPE_COUNT = 32;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;

    @SuppressWarnings("unchecked")
    private final Long2ObjectOpenHashMap<V>[] stripes = new Long2ObjectOpenHashMap[STRIPE_COUNT];
    private final AtomicInteger size = new AtomicInteger();

    OceanCanvasPrimitiveLongObjectMap() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new Long2ObjectOpenHashMap<>();
    }

    private static int stripeIndex(long value) {
        long mixed = value ^ (value >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return ((int) mixed) & STRIPE_MASK;
    }

    V get(long key) {
        Long2ObjectOpenHashMap<V> stripe = stripes[stripeIndex(key)];
        synchronized (stripe) { return stripe.get(key); }
    }

    V put(long key, V value) {
        Objects.requireNonNull(value, "value");
        Long2ObjectOpenHashMap<V> stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            boolean existed = stripe.containsKey(key);
            V previous = stripe.put(key, value);
            if (!existed) size.incrementAndGet();
            return previous;
        }
    }

    V putIfAbsent(long key, V value) {
        Objects.requireNonNull(value, "value");
        Long2ObjectOpenHashMap<V> stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            V previous = stripe.get(key);
            if (previous != null || stripe.containsKey(key)) return previous;
            stripe.put(key, value);
            size.incrementAndGet();
            return null;
        }
    }

    V computeIfAbsent(long key, LongFunction<? extends V> factory) {
        Objects.requireNonNull(factory, "factory");
        Long2ObjectOpenHashMap<V> stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            V previous = stripe.get(key);
            if (previous != null || stripe.containsKey(key)) return previous;
            V created = Objects.requireNonNull(factory.apply(key), "factory result");
            stripe.put(key, created);
            size.incrementAndGet();
            return created;
        }
    }

    V remove(long key) {
        Long2ObjectOpenHashMap<V> stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            if (!stripe.containsKey(key)) return null;
            V previous = stripe.remove(key);
            size.decrementAndGet();
            return previous;
        }
    }


    boolean remove(long key, V expectedValue) {
        Long2ObjectOpenHashMap<V> stripe = stripes[stripeIndex(key)];
        synchronized (stripe) {
            if (!stripe.containsKey(key)) return false;
            V current = stripe.get(key);
            if (!Objects.equals(current, expectedValue)) return false;
            stripe.remove(key);
            size.decrementAndGet();
            return true;
        }
    }


    /** Allocation-free value count for routine diagnostics. The predicate must not mutate this map. */
    int countValues(Predicate<? super V> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        int count = 0;
        for (Long2ObjectOpenHashMap<V> stripe : stripes) {
            synchronized (stripe) {
                for (V value : stripe.values()) if (predicate.test(value)) count++;
            }
        }
        return count;
    }

    java.util.ArrayList<V> valuesSnapshot() {
        final java.util.ArrayList<V>[] holder = new java.util.ArrayList[1];
        withAllStripeLocks(0, () -> {
            java.util.ArrayList<V> out = new java.util.ArrayList<>(size.get());
            for (Long2ObjectOpenHashMap<V> stripe : stripes) out.addAll(stripe.values());
            holder[0] = out;
        });
        return holder[0];
    }

    boolean containsKey(long key) {
        Long2ObjectOpenHashMap<V> stripe = stripes[stripeIndex(key)];
        synchronized (stripe) { return stripe.containsKey(key); }
    }

    int size() { return size.get(); }
    boolean isEmpty() { return size.get() == 0; }

    void clear() {
        withAllStripeLocks(0, () -> {
            for (Long2ObjectOpenHashMap<V> stripe : stripes) stripe.clear();
            size.set(0);
        });
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
