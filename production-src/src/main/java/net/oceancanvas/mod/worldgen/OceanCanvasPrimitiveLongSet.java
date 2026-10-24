package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.LongConsumer;

/**
 * Striped primitive long set used by hot scheduler/recovery membership ledgers.
 *
 * <p>v253.125.40 keeps the zero-boxing behavior introduced for scheduler hints but
 * removes the single monitor as a cross-thread contention point. Each key maps to
 * exactly one stripe; add/remove/contains therefore synchronize only that stripe.
 * {@link #snapshot()} acquires every stripe in a stable order because scheduler
 * queue compaction needs an atomic membership image: a concurrent admission may
 * block briefly, but it can never become member=true without appearing in the
 * rebuilt primitive queue.</p>
 *
 * <p>The set stores only transient runtime bookkeeping. Authoritative world state,
 * lighting certificates, Pregen ordering and on-disk formats are unchanged.</p>
 */
public final class OceanCanvasPrimitiveLongSet {
    private static final int STRIPE_COUNT = 32;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;

    private final LongOpenHashSet[] stripes = new LongOpenHashSet[STRIPE_COUNT];
    private final AtomicInteger size = new AtomicInteger();

    public OceanCanvasPrimitiveLongSet() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new LongOpenHashSet();
    }

    private static int stripeIndex(long value) {
        long mixed = value ^ (value >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return ((int) mixed) & STRIPE_MASK;
    }

    public boolean add(long value) {
        LongOpenHashSet stripe = stripes[stripeIndex(value)];
        synchronized (stripe) {
            if (!stripe.add(value)) return false;
            size.incrementAndGet();
            return true;
        }
    }

    public boolean remove(long value) {
        LongOpenHashSet stripe = stripes[stripeIndex(value)];
        synchronized (stripe) {
            if (!stripe.remove(value)) return false;
            size.decrementAndGet();
            return true;
        }
    }

    public boolean contains(long value) {
        LongOpenHashSet stripe = stripes[stripeIndex(value)];
        synchronized (stripe) { return stripe.contains(value); }
    }

    public boolean isEmpty() { return size.get() == 0; }
    public int size() { return size.get(); }

    public void clear() { withAllStripeLocks(0, () -> {
        for (LongOpenHashSet stripe : stripes) stripe.clear();
        size.set(0);
    }); }




    /** Allocation-free read traversal for large hot sets. The consumer must not mutate this set. */
    public void forEachLong(LongConsumer consumer) {
        for (LongOpenHashSet stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.iterator(); it.hasNext();) consumer.accept(it.nextLong());
            }
        }
    }

    /**
     * Copies only a bounded rotating window from the current set rather than materializing
     * the entire backlog. The ordinal is interpreted modulo the atomically observed size;
     * iteration order is intentionally unspecified, matching the set's existing scheduler-only
     * use. This is for bounded liveness/diagnostic sampling, never authoritative target order.
     */
    public long[] windowFromOrdinal(long ordinal, int limit) {
        if (limit <= 0 || isEmpty()) return new long[0];
        final long[][] holder = new long[1][];
        withAllStripeLocks(0, () -> {
            int total = size.get();
            if (total <= 0) { holder[0] = new long[0]; return; }
            int count = Math.min(limit, total);
            int start = (int) Math.floorMod(ordinal, (long) total);
            long[] out = new long[count];
            int outIndex = 0;
            int ordinalIndex = 0;
            for (LongOpenHashSet stripe : stripes) {
                for (LongIterator it = stripe.iterator(); it.hasNext();) {
                    long value = it.nextLong();
                    if (ordinalIndex++ >= start && outIndex < count) out[outIndex++] = value;
                }
            }
            if (outIndex < count) {
                int need = count - outIndex;
                outer: for (LongOpenHashSet stripe : stripes) {
                    for (LongIterator it = stripe.iterator(); it.hasNext();) {
                        out[outIndex++] = it.nextLong();
                        if (--need == 0) break outer;
                    }
                }
            }
            holder[0] = out;
        });
        return holder[0];
    }

    /** Allocation-free conditional removal across stripes. */
    public int removeIf(LongPredicate predicate) {
        int removed = 0;
        for (LongOpenHashSet stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.iterator(); it.hasNext();) {
                    long value = it.nextLong();
                    if (!predicate.test(value)) continue;
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed != 0) size.addAndGet(-removed);
        return removed;
    }

    /** Allocation-free bounded query for large recovery/Rewipe ownership sets.
     * The predicate must not mutate this set. */
    public boolean anyMatch(LongPredicate predicate) {
        for (LongOpenHashSet stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.iterator(); it.hasNext();) {
                    if (predicate.test(it.nextLong())) return true;
                }
            }
        }
        return false;
    }

    /** Allocation-free count for diagnostics/final-drain intersection tests. */
    public int countMatching(LongPredicate predicate) {
        int count = 0;
        for (LongOpenHashSet stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.iterator(); it.hasNext();) {
                    if (predicate.test(it.nextLong())) count++;
                }
            }
        }
        return count;
    }

    /**
     * Copies at most {@code limit} matching keys. Used by bounded final-drain nudges
     * so a million-chunk Rewipe never allocates a million-key snapshot just to
     * service eight targets. The predicate must not mutate this set.
     */
    public long[] firstMatching(int limit, LongPredicate predicate) {
        if (limit <= 0) return new long[0];
        long[] out = new long[limit];
        int count = 0;
        outer: for (LongOpenHashSet stripe : stripes) {
            synchronized (stripe) {
                for (LongIterator it = stripe.iterator(); it.hasNext();) {
                    long value = it.nextLong();
                    if (!predicate.test(value)) continue;
                    out[count++] = value;
                    if (count == limit) break outer;
                }
            }
        }
        return count == out.length ? out : Arrays.copyOf(out, count);
    }


    /** Removes and returns up to {@code limit} arbitrary keys as primitives. */
    public long[] drainFirst(int limit) {
        if (limit <= 0 || isEmpty()) return new long[0];
        long[] out = new long[Math.min(limit, size())];
        int count = 0;
        for (LongOpenHashSet stripe : stripes) {
            synchronized (stripe) {
                while (count < out.length && !stripe.isEmpty()) {
                    LongIterator it = stripe.iterator();
                    if (!it.hasNext()) break;
                    long value = it.nextLong();
                    it.remove();
                    out[count++] = value;
                    size.decrementAndGet();
                }
            }
            if (count == out.length) break;
        }
        return count == out.length ? out : Arrays.copyOf(out, count);
    }

    /**
     * Primitive atomic snapshot. This is deliberately a rare operation: hot
     * membership checks remain allocation-free and stripe-local.
     */
    public long[] snapshot() {
        final long[][] holder = new long[1][];
        withAllStripeLocks(0, () -> {
            long[] out = new long[size.get()];
            int i = 0;
            for (LongOpenHashSet stripe : stripes) {
                for (LongIterator it = stripe.iterator(); it.hasNext();) out[i++] = it.nextLong();
            }
            if (i != out.length) throw new IllegalStateException("primitive long set size drift while fully locked");
            holder[0] = out;
        });
        return holder[0];
    }


    /** Rare boxed snapshot for sort/report call sites; hot membership stays primitive. */
    public java.util.ArrayList<Long> boxedSnapshot() {
        long[] keys = snapshot();
        java.util.ArrayList<Long> out = new java.util.ArrayList<>(keys.length);
        for (long key : keys) out.add(Long.valueOf(key));
        return out;
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
