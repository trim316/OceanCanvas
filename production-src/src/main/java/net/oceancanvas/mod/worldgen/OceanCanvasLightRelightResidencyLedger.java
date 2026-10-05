package net.oceancanvas.mod.worldgen;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

/**
 * Minecraft-free ownership for the bounded relight residency ticket pool.
 * Engine ticket mutation remains supplied by the flattener callbacks.
 *
 * <p>v253.125.41 stores membership and install time in one primitive map. The old
 * ConcurrentHashMap<Long,Long> plus duplicate Set<Long> boxed every entry twice;
 * this ledger is already synchronized at the ticket-ownership boundary, so one
 * primitive map preserves the same atomic install/release semantics with less
 * allocation and less bookkeeping.</p>
 */
final class OceanCanvasLightRelightResidencyLedger {
    static final long ABSENT_NANOS = Long.MIN_VALUE;
    private final Long2LongOpenHashMap installedNs = new Long2LongOpenHashMap();
    private final AtomicLong installs = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();
    private final AtomicLong rotations = new AtomicLong();

    OceanCanvasLightRelightResidencyLedger() {
        installedNs.defaultReturnValue(ABSENT_NANOS);
    }

    @FunctionalInterface interface TicketAction { void run(long packed) throws Throwable; }

    enum InstallResult { ALREADY_PRESENT, CAP_REJECTED, INSTALLED }

    synchronized InstallResult install(long packed, long nowNs, int maxActive, TicketAction engineInstall)
            throws Throwable {
        if (installedNs.containsKey(packed)) return InstallResult.ALREADY_PRESENT;
        if (installedNs.size() >= maxActive) return InstallResult.CAP_REJECTED;
        engineInstall.run(packed);
        installedNs.put(packed, nowNs);
        installs.incrementAndGet();
        return InstallResult.INSTALLED;
    }

    synchronized boolean release(long packed, TicketAction engineRemove) throws Throwable {
        if (!installedNs.containsKey(packed)) return false;
        // Keep bookkeeping authoritative until native removal succeeds. If the
        // engine call throws, later cleanup can still see/retry this ownership.
        engineRemove.run(packed);
        installedNs.remove(packed);
        releases.incrementAndGet();
        return true;
    }

    synchronized boolean contains(long packed) { return installedNs.containsKey(packed); }
    synchronized int activeCount() { return installedNs.size(); }
    synchronized long installedAtNanos(long packed) { return installedNs.get(packed); }
    synchronized long[] activeSnapshot() {
        long[] out = new long[installedNs.size()];
        int i = 0;
        for (LongIterator it = installedNs.keySet().iterator(); it.hasNext();) out[i++] = it.nextLong();
        return out;
    }

    /** Allocation-free diagnostic count; the predicate must not mutate this ledger. */
    synchronized int countMatching(LongPredicate predicate) {
        int count = 0;
        for (LongIterator it = installedNs.keySet().iterator(); it.hasNext();) {
            if (predicate.test(it.nextLong())) count++;
        }
        return count;
    }

    long installCount() { return installs.get(); }
    long releaseCount() { return releases.get(); }
    long rotationCount() { return rotations.get(); }
    long recordRotation() { return rotations.incrementAndGet(); }

    synchronized int clearAfterNativeDeactivation() {
        int n = installedNs.size();
        installedNs.clear();
        return n;
    }
}
