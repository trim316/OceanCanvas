package net.oceancanvas.mod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckStructure;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat;
import net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Independent liveness lane for pending natural-shipwreck relocation.
 *
 * <p>A pending original structure footprint is itself a lighting-finalization barrier. This
 * service therefore owns the tiny rescue-ticket pool and services it independently of ordinary
 * Pregen terrain admission. Terrain mutation and light re-arm policy remain in
 * {@link OceanCanvasSurfaceFlattener}; this class owns only rescue state, ticket lifetime,
 * structure discovery and rescue diagnostics.</p>
 *
 * <p>State is owned by {@link OceanCanvasServerRuntime}, with a separate lane per
 * {@link ServerLevel}. No world reference or rescue ticket survives the owning server runtime.</p>
 */
public final class OceanCanvasStructureRelocationRescueService {
    private OceanCanvasStructureRelocationRescueService() {}

    private static final int TICKET_MAX = 8;
    private static final int RADIUS_MAX = 8;
    private static final long WARN_NS = 15_000_000_000L;
    private static final long LOG_INTERVAL_NS = 10_000_000_000L;
    private static final AtomicInteger ACTIVE_TICKETS = new AtomicInteger();

    /** Terrain/light operations deliberately retained by the flattener policy boundary. */
    public interface Hooks {
        boolean allChunksReadyForRelocation(ServerLevel world, BoundingBox box);
        BoundingBox relocateShipwreckIfNeeded(ServerLevel world, StructureStart start);
        void revalidatePlacedStructureEnvironment(ServerLevel world, BlockPos min, BlockPos max);
        void rearmLightingAfterStructureMutation(ServerLevel world, BoundingBox box);
        Iterable<Long> pendingLightChunks();
        int pendingLightCount();
    }

    /** Service one bounded rescue pass for this level. */
    public static void tick(ServerLevel world, Hooks hooks) {
        if (world == null || hooks == null) return;
        state(world).tick(world, hooks);
    }

    /**
     * Clear Java-side rescue bookkeeping after the flattener has already deactivated the real
     * engine tickets during server shutdown. This method never reopens a closed runtime.
     */
    public static void clearAfterTicketDeactivation(ServerLevel world) {
        if (world == null) return;
        OceanCanvasServerRuntime.clearStateIfOpen(world.getServer(), State.class);
    }

    /** Process-wide diagnostic total only; contains no world/session references. */
    public static int activeTicketCount() { return Math.max(0, ACTIVE_TICKETS.get()); }

    private static State state(ServerLevel world) {
        return OceanCanvasServerRuntime.get(world.getServer()).state(State.class, State::new);
    }

    private record RescueTicket(long anchorPacked, int radius, long installedNs) {}

    private static final class Lane {
        final Map<BlockPos, RescueTicket> tickets = new ConcurrentHashMap<>();
        final Map<BlockPos, Long> firstSeenNs = new ConcurrentHashMap<>();
        final AtomicLong installs = new AtomicLong();
        final AtomicLong releases = new AtomicLong();
        final AtomicLong completions = new AtomicLong();
        final AtomicLong startMisses = new AtomicLong();
        long lastLogNs;
    }

    private static final class State implements AutoCloseable {
        private final IdentityHashMap<ServerLevel, Lane> lanes = new IdentityHashMap<>();
        private boolean closed;

        synchronized void tick(ServerLevel world, Hooks hooks) {
            if (closed) return;
            Lane lane = lanes.computeIfAbsent(world, ignored -> new Lane());
            serviceLane(world, lane, hooks);
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            int abandoned = 0;
            for (Lane lane : lanes.values()) {
                abandoned += lane.tickets.size();
                lane.tickets.clear();
                lane.firstSeenNs.clear();
            }
            lanes.clear();
            if (abandoned != 0) ACTIVE_TICKETS.addAndGet(-abandoned);
        }
    }

    private static void serviceLane(ServerLevel world, Lane lane, Hooks hooks) {
        OceanCanvasProtectedData protectedData = OceanCanvasProtectedData.get(world);
        Map<BlockPos, BoundingBox> pending = protectedData.getPendingOriginalProtections();
        long now = System.nanoTime();

        for (BlockPos origin : new ArrayList<>(lane.tickets.keySet())) {
            if (!pending.containsKey(origin)) releaseTicket(world, lane, origin);
        }
        lane.firstSeenNs.keySet().removeIf(origin -> !pending.containsKey(origin));
        if (pending.isEmpty()) return;

        int ready = 0;
        BlockPos oldestOrigin = null;
        long oldestAgeNs = -1L;
        for (Map.Entry<BlockPos, BoundingBox> entry : pending.entrySet()) {
            BlockPos origin = entry.getKey();
            BoundingBox box = entry.getValue();
            long firstSeen = lane.firstSeenNs.computeIfAbsent(origin, ignored -> now);
            long ageNs = Math.max(0L, now - firstSeen);
            if (ageNs > oldestAgeNs) { oldestAgeNs = ageNs; oldestOrigin = origin; }

            RescueTicket ticket = lane.tickets.get(origin);
            if (ticket == null) ticket = installTicket(world, lane, origin, box, now);
            if (ticket == null) continue;
            if (!hooks.allChunksReadyForRelocation(world, box)) continue;
            ready++;

            StructureStart start = findPendingShipwreckStart(world, origin, box);
            if (start == null) {
                lane.startMisses.incrementAndGet();
                continue;
            }

            BoundingBox relocated = hooks.relocateShipwreckIfNeeded(world, start);
            // Persisted pending-original protection is the completion authority. The relocation
            // method clears it only after the relocated copy is safely placed/persisted.
            if (!protectedData.hasPendingOriginalProtection(origin)) {
                if (relocated != null) {
                    BlockPos relocatedMin = new BlockPos(relocated.minX(), relocated.minY(), relocated.minZ());
                    BlockPos relocatedMax = new BlockPos(relocated.maxX(), relocated.maxY(), relocated.maxZ());
                    hooks.revalidatePlacedStructureEnvironment(world, relocatedMin, relocatedMax);
                    if (!structureRingPhysicallyVerified(world, relocated, 1))
                        protectedData.markPendingRevalidation(relocatedMin, relocated);
                    hooks.rearmLightingAfterStructureMutation(world, box);
                    hooks.rearmLightingAfterStructureMutation(world, relocated);
                }
                releaseTicket(world, lane, origin);
                lane.firstSeenNs.remove(origin);
                long completed = lane.completions.incrementAndGet();
                int structureBlockedLighting = countPendingLightEntriesTouchingBox(hooks.pendingLightChunks(), box, 1);
                OceanCanvas.LOGGER.info("(Ocean Canvas) STRUCTURE-RESCUE build={} state=COMPLETED origin={} ageMs={} pendingStructures={} blockedLighting={} activeTickets={} completions={}",
                        OceanCanvas.VERSION, origin, ageNs / 1_000_000L, Math.max(0, pending.size() - 1),
                        structureBlockedLighting, lane.tickets.size(), completed);
            }
        }

        if (oldestAgeNs >= WARN_NS && now - lane.lastLogNs >= LOG_INTERVAL_NS) {
            lane.lastLogNs = now;
            int blockedLighting = countLightingBlockedByPendingStructures(hooks.pendingLightChunks(), pending);
            OceanCanvas.LOGGER.warn("(Ocean Canvas) STRUCTURE-BARRIER-DEADLOCK-GUARD build={} pendingStructures={} ready={} blockedLighting={} oldestOrigin={} oldestAgeMs={} activeRescueTickets={} installs={} releases={} startMisses={} pendingLighting={}. Rescue remains active even when ordinary Pregen terrain admission is closed.",
                    OceanCanvas.VERSION, pending.size(), ready, blockedLighting, oldestOrigin,
                    oldestAgeNs / 1_000_000L, lane.tickets.size(), lane.installs.get(), lane.releases.get(),
                    lane.startMisses.get(), hooks.pendingLightCount());
        }
    }

    private static RescueTicket installTicket(ServerLevel world, Lane lane, BlockPos origin, BoundingBox box, long now) {
        RescueTicket existing = lane.tickets.get(origin);
        if (existing != null) return existing;
        if (lane.tickets.size() >= TICKET_MAX) return null;

        OceanCanvasStructureRescuePolicy.TicketPlan plan = OceanCanvasStructureRescuePolicy.ticketPlan(
                box.minX(), box.minZ(), box.maxX(), box.maxZ());
        if (plan.radius() > RADIUS_MAX) {
            OceanCanvas.LOGGER.error("(Ocean Canvas) STRUCTURE-RESCUE build={} state=REFUSED origin={} requiredRadius={} cap={} bounds={} reason=pathological-footprint",
                    OceanCanvas.VERSION, origin, plan.radius(), RADIUS_MAX, box);
            return null;
        }
        long anchorPacked = ChunkPos.pack(plan.anchorChunkX(), plan.anchorChunkZ());
        try {
            OceanCanvasChunkRuntimeCompat.addForcedTicket(world, new ChunkPos(plan.anchorChunkX(), plan.anchorChunkZ()), plan.radius());
            RescueTicket created = new RescueTicket(anchorPacked, plan.radius(), now);
            RescueTicket raced = lane.tickets.putIfAbsent(origin, created);
            if (raced != null) {
                OceanCanvasChunkRuntimeCompat.removeForcedTicket(world, new ChunkPos(plan.anchorChunkX(), plan.anchorChunkZ()), plan.radius());
                return raced;
            }
            ACTIVE_TICKETS.incrementAndGet();
            lane.installs.incrementAndGet();
            OceanCanvas.LOGGER.info("(Ocean Canvas) STRUCTURE-RESCUE build={} state=TICKET_INSTALLED origin={} anchor={},{} radius={} bounds={} activeTickets={}",
                    OceanCanvas.VERSION, origin, plan.anchorChunkX(), plan.anchorChunkZ(), plan.radius(), box, lane.tickets.size());
            return created;
        } catch (Throwable t) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) STRUCTURE-RESCUE build={} state=TICKET_INSTALL_FAILED origin={} anchor={},{} radius={} error={}",
                    OceanCanvas.VERSION, origin, plan.anchorChunkX(), plan.anchorChunkZ(), plan.radius(), t.toString());
            return null;
        }
    }

    private static void releaseTicket(ServerLevel world, Lane lane, BlockPos origin) {
        RescueTicket ticket = lane.tickets.remove(origin);
        if (ticket == null || world == null) return;
        ACTIVE_TICKETS.decrementAndGet();
        int cx = ChunkPos.getX(ticket.anchorPacked()), cz = ChunkPos.getZ(ticket.anchorPacked());
        try {
            OceanCanvasChunkRuntimeCompat.removeForcedTicket(world, new ChunkPos(cx, cz), ticket.radius());
            lane.releases.incrementAndGet();
        } catch (Throwable t) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) STRUCTURE-RESCUE build={} state=TICKET_RELEASE_FAILED origin={} anchor={},{} radius={} error={}",
                    OceanCanvas.VERSION, origin, cx, cz, ticket.radius(), t.toString());
        }
    }

    private static StructureStart findPendingShipwreckStart(ServerLevel world, BlockPos origin, BoundingBox box) {
        int minCx = Math.floorDiv(box.minX(), 16) - 1;
        int maxCx = Math.floorDiv(box.maxX(), 16) + 1;
        int minCz = Math.floorDiv(box.minZ(), 16) - 1;
        int maxCz = Math.floorDiv(box.maxZ(), 16) + 1;
        Set<StructureStart> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int cz = minCz; cz <= maxCz; cz++) for (int cx = minCx; cx <= maxCx; cx++) {
            LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            for (StructureStart start : chunk.getAllStarts().values()) {
                if (start == null || !seen.add(start) || !start.isValid()
                        || !(start.getStructure() instanceof ShipwreckStructure)) continue;
                BoundingBox extent = OceanCanvasStructureGeometry.pieceExtent(start);
                if (extent != null && extent.minX() == origin.getX() && extent.minY() == origin.getY()
                        && extent.minZ() == origin.getZ()) return start;
            }
        }
        return null;
    }

    private static boolean structureRingPhysicallyVerified(ServerLevel world, BoundingBox box, int ringChunks) {
        int ring = Math.max(0, ringChunks);
        int minCx = Math.floorDiv(box.minX(), 16) - ring;
        int maxCx = Math.floorDiv(box.maxX(), 16) + ring;
        int minCz = Math.floorDiv(box.minZ(), 16) - ring;
        int maxCz = Math.floorDiv(box.maxZ(), 16) + ring;
        OceanCanvasProtectedData data = OceanCanvasProtectedData.get(world);
        for (int cz = minCz; cz <= maxCz; cz++) for (int cx = minCx; cx <= maxCx; cx++) {
            if (!data.isChunkProcessedPhysicallyVerified(new ChunkPos(cx, cz))) return false;
        }
        return true;
    }

    private static int countLightingBlockedByPendingStructures(Iterable<Long> pendingLightChunks,
            Map<BlockPos, BoundingBox> pending) {
        int count = 0;
        for (long packed : pendingLightChunks) {
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            int chunkMinX = (cx - 1) << 4, chunkMaxX = ((cx + 1) << 4) + 15;
            int chunkMinZ = (cz - 1) << 4, chunkMaxZ = ((cz + 1) << 4) + 15;
            for (BoundingBox box : pending.values()) {
                if (box.maxX() >= chunkMinX && box.minX() <= chunkMaxX
                        && box.maxZ() >= chunkMinZ && box.minZ() <= chunkMaxZ) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static int countPendingLightEntriesTouchingBox(Iterable<Long> pendingLightChunks,
            BoundingBox box, int ringChunks) {
        int ring = Math.max(0, ringChunks);
        int minCx = Math.floorDiv(box.minX(), 16) - ring;
        int maxCx = Math.floorDiv(box.maxX(), 16) + ring;
        int minCz = Math.floorDiv(box.minZ(), 16) - ring;
        int maxCz = Math.floorDiv(box.maxZ(), 16) + ring;
        int count = 0;
        for (long packed : pendingLightChunks) {
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) count++;
        }
        return count;
    }
}
