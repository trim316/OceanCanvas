package net.oceancanvas.mod.worldgen;


/** Session-scoped scheduling state for lazy persisted-light certificate revalidation. */
final class OceanCanvasPersistedLightAuditSession {
    private static final int DEADLINE_COMPACT_MIN_STALE = 256;
    private static final int DEADLINE_COMPACT_RATIO = 3;

    private final OceanCanvasPrimitiveLongLongMap dueTick = new OceanCanvasPrimitiveLongLongMap();
    private final OceanCanvasPrimitiveLongSet audited = new OceanCanvasPrimitiveLongSet();
    // v253.125.36: persisted audits are true deadline work. The old drain walked the
    // whole ConcurrentHashMap looking for a small due cohort and allocated an
    // ArrayList plus immutable Map.Entry objects for every batch. Keep the map as
    // authoritative correctness state and use a primitive deadline heap only for
    // scheduling. Repeated boundary reschedules may leave stale heap nodes; rare
    // compaction rebuilds one exact node per authoritative map entry.
    private final OceanCanvasPrimitiveLongDeadlineHeap deadlines = new OceanCanvasPrimitiveLongDeadlineHeap();
    private long heapCompactions;
    private long staleDeadlineNodesDiscarded;

    int pendingCount() { return dueTick.size(); }
    boolean pendingEmpty() { return dueTick.isEmpty(); }
    long heapCompactions() { return heapCompactions; }
    long staleDeadlineNodesDiscarded() { return staleDeadlineNodesDiscarded; }

    /** Direct regional count; full-Canvas callers should use pendingCount(). */
    int countInBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        return dueTick.sumKeys(packed -> {
            int cx = net.minecraft.world.level.ChunkPos.getX(packed);
            int cz = net.minecraft.world.level.ChunkPos.getZ(packed);
            return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ ? 1 : 0;
        });
    }

    synchronized void scheduleEarliest(long packed, long due) {
        long previous = dueTick.get(packed);
        if (previous != OceanCanvasPrimitiveLongLongMap.ABSENT && previous <= due) return;
        dueTick.put(packed, due);
        deadlines.offer(packed, due);
        maybeCompactDeadlines();
    }

    synchronized void scheduleLatest(long packed, long due) {
        long previous = dueTick.get(packed);
        if (previous != OceanCanvasPrimitiveLongLongMap.ABSENT && previous >= due) return;
        dueTick.put(packed, due);
        deadlines.offer(packed, due);
        maybeCompactDeadlines();
    }

    /** Atomically claim the next due authoritative audit without allocating a batch. */
    synchronized OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDue(long nowTick) {
        int staleThisCall = 0;
        while (true) {
            OceanCanvasPrimitiveLongDeadlineHeap.DueEntry entry = deadlines.pollDue(nowTick);
            if (entry == null) return null;
            long packed = entry.packed(), due = entry.dueTick();
            if (dueTick.remove(packed, due)) return entry;
            staleDeadlineNodesDiscarded++;
            staleThisCall++;
            // A pathological reschedule storm should not make one drain spend an
            // unbounded amount of time discarding stale nodes. Compact from the
            // authoritative map after a bounded stale prefix and try again.
            if (staleThisCall >= 64) {
                deadlines.rebuildFrom(dueTick);
                heapCompactions++;
                staleThisCall = 0;
            }
        }
    }

    synchronized boolean removePending(long packed, long expectedDue) {
        return dueTick.remove(packed, expectedDue);
    }
    synchronized void removePending(long packed) { dueTick.remove(packed); }
    boolean wasAudited(long packed) { return audited.contains(packed); }
    void markAudited(long packed) { audited.add(packed); }
    void clearAudited(long packed) { audited.remove(packed); }

    synchronized void clear() {
        dueTick.clear();
        audited.clear();
        deadlines.clear();
        heapCompactions = 0L;
        staleDeadlineNodesDiscarded = 0L;
    }

    private void maybeCompactDeadlines() {
        int live = dueTick.size();
        int queued = deadlines.size();
        if (queued <= DEADLINE_COMPACT_MIN_STALE) return;
        if (queued <= live * DEADLINE_COMPACT_RATIO + DEADLINE_COMPACT_MIN_STALE) return;
        deadlines.rebuildFrom(dueTick);
        heapCompactions++;
    }
}
