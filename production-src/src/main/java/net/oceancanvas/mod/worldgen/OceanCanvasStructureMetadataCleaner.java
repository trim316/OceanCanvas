package net.oceancanvas.mod.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Deferred metadata cleanup for structures governed by a Never rule.
 * Physical blocks are removed by the normal flatten/Rewipe pass; this class makes
 * Minecraft's structure graph agree by removing the owning start and every loaded
 * reference to that owner, requesting missing chunks non-blockingly until the cleanup
 * can be completed atomically.
 */
public final class OceanCanvasStructureMetadataCleaner {
    private static final java.util.Map<net.minecraft.server.MinecraftServer, Session> SESSIONS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final class Session {
        final Deque<Task> queue = new ArrayDeque<>();
    }

    private static Session session(ServerLevel world) {
        synchronized (SESSIONS) {
            return SESSIONS.computeIfAbsent(world.getServer(), ignored -> new Session());
        }
    }

    private static Session existingSession(net.minecraft.server.MinecraftServer server) {
        synchronized (SESSIONS) { return SESSIONS.get(server); }
    }

    private OceanCanvasStructureMetadataCleaner() {}

    public static void queue(ServerLevel world, StructureStart start, OceanCanvasStructureKind kind) {
        if (world == null || start == null || !start.isValid() || kind == null) return;
        Task task = new Task(world, start, kind);
        Session session = session(world);
        synchronized (session) {
            for (Task pending : session.queue) {
                if (pending.same(task)) return;
            }
            session.queue.addLast(task);
        }
    }

    public static void clear(net.minecraft.server.MinecraftServer server) {
        synchronized (SESSIONS) { SESSIONS.remove(server); }
    }

    public static void tick(net.minecraft.server.MinecraftServer server) {
        Session session = existingSession(server);
        if (session == null) return;
        Task task;
        synchronized (session) { task = session.queue.peekFirst(); }
        if (task == null) return;
        try {
            if (!task.tick()) return;
        } catch (RuntimeException ex) {
            net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.record(
                    "structures.metadata-cleaner",
                    "Deferred Never-rule metadata cleanup aborted for " + task.kind.displayName() + " owner " + task.owner, ex);
            OceanCanvas.LOGGER.error("[OceanCanvas][StructureMetadata] deferred cleanup failed for {} start {}; dropping task",
                    task.kind.displayName(), task.owner, ex);
        }
        synchronized (session) {
            if (session.queue.peekFirst() == task) session.queue.removeFirst(); else session.queue.remove(task);
        }
    }

    private static final class Task {
        final ServerLevel world;
        final StructureStart start;
        final Structure structure;
        final OceanCanvasStructureKind kind;
        final ChunkPos owner;
        final BoundingBox box;
        final long ownerRef;

        Task(ServerLevel world, StructureStart start, OceanCanvasStructureKind kind) {
            this.world = world;
            this.start = start;
            this.structure = start.getStructure();
            this.kind = kind;
            this.owner = start.getChunkPos();
            this.box = start.getBoundingBox();
            this.ownerRef = ChunkPos.pack(owner.x(), owner.z());
        }

        boolean same(Task other) {
            return world == other.world && structure == other.structure && owner.equals(other.owner);
        }

        boolean tick() {
            int minCX = Math.floorDiv(box.minX(), 16), maxCX = Math.floorDiv(box.maxX(), 16);
            int minCZ = Math.floorDiv(box.minZ(), 16), maxCZ = Math.floorDiv(box.maxZ(), 16);
            // Load everything that can contain a reference before mutating any metadata.
            if (world.getChunkSource().getChunkNow(owner.x(), owner.z()) == null) {
                OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world, owner.x(), owner.z());
                return false;
            }
            for (int cz = minCZ; cz <= maxCZ; cz++) for (int cx = minCX; cx <= maxCX; cx++) {
                if (world.getChunkSource().getChunkNow(cx, cz) == null) {
                    OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world, cx, cz);
                    return false;
                }
            }

            var ownerChunk = world.getChunkSource().getChunkNow(owner.x(), owner.z());
            ownerChunk.setStartForStructure(structure, StructureStart.INVALID_START);
            ownerChunk.markUnsaved();
            int referencesRemoved = 0;
            for (int cz = minCZ; cz <= maxCZ; cz++) for (int cx = minCX; cx <= maxCX; cx++) {
                var chunk = world.getChunkSource().getChunkNow(cx, cz);
                var refs = chunk.getAllReferences().get(structure);
                if (refs != null && refs.remove(ownerRef)) {
                    referencesRemoved++;
                    chunk.markUnsaved();
                }
            }
            OceanCanvas.LOGGER.info("[OceanCanvas][StructureMetadata] Never removed {} start {} and {} references",
                    kind.displayName(), owner, referencesRemoved);
            return true;
        }
    }
}
