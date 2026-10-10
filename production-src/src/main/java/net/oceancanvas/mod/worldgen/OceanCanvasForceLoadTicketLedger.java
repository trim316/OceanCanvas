package net.oceancanvas.mod.worldgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Java-side ownership for Ocean Canvas' generic bounded non-blocking force-load pool.
 *
 * <p>The ledger is Minecraft-free. The flattener supplies engine add/remove callbacks,
 * keeping compatibility-sensitive ticket mutation at the already-proven call sites while
 * making deduplication, capacity, age tracking and bounded retirement one explicit ownership
 * transaction.</p>
 */
final class OceanCanvasForceLoadTicketLedger {
    private final OceanCanvasPrimitiveLongLongMap installedMillis = new OceanCanvasPrimitiveLongLongMap();
    private boolean capLogged;

    @FunctionalInterface
    interface TicketAction { void run(long packed) throws Throwable; }

    enum InstallResult {
        ALREADY_PRESENT,
        INSTALLED,
        CAP_REJECTED_FIRST,
        CAP_REJECTED
    }

    synchronized InstallResult install(long packed, long nowMillis, int maxActive, TicketAction engineInstall)
            throws Throwable {
        if (installedMillis.containsKey(packed)) return InstallResult.ALREADY_PRESENT;
        if (installedMillis.size() >= maxActive) {
            if (!capLogged) {
                capLogged = true;
                return InstallResult.CAP_REJECTED_FIRST;
            }
            return InstallResult.CAP_REJECTED;
        }
        engineInstall.run(packed);
        installedMillis.put(packed, nowMillis);
        return InstallResult.INSTALLED;
    }

    synchronized int releaseSettled(
            long nowMillis,
            long maxAgeMillis,
            int maxReleases,
            java.util.function.LongPredicate isSettled,
            TicketAction engineRemove) throws Throwable {
        if (maxReleases <= 0 || installedMillis.isEmpty()) return 0;
        int released = 0;
        for (long packed : installedMillis.keysSnapshot()) {
            if (released >= maxReleases) break;
            long installedAt = installedMillis.get(packed);
            if (installedAt == OceanCanvasPrimitiveLongLongMap.ABSENT) continue;
            if (!isSettled.test(packed) && nowMillis - installedAt < maxAgeMillis) continue;
            engineRemove.run(packed);
            if (installedMillis.remove(packed, installedAt)) released++;
        }
        return released;
    }

    int activeCount() { return installedMillis.size(); }

    List<Entry> entrySnapshot() {
        long[] keys = installedMillis.keysSnapshot();
        ArrayList<Entry> out = new ArrayList<>(keys.length);
        for (long packed : keys) {
            long installedAt = installedMillis.get(packed);
            if (installedAt != OceanCanvasPrimitiveLongLongMap.ABSENT) out.add(new Entry(packed, installedAt));
        }
        return List.copyOf(out);
    }

    synchronized int clearAfterNativeDeactivation() {
        int count = installedMillis.size();
        installedMillis.clear();
        capLogged = false;
        return count;
    }

    record Entry(long packed, long installedMillis) {}
}
