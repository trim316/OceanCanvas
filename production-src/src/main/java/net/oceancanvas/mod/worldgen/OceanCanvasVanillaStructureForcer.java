package net.oceancanvas.mod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * v234 strict FORCE_ON engine for vanilla random-spread structures.
 *
 * <p>The important rule here is that Ocean Canvas does not own spacing, separation,
 * salts, frequency reducers, exclusion zones, or structure generation. Those are read
 * from the live {@link ChunkGeneratorStructureState}. Candidate chunks are obtained from
 * Minecraft's active {@link RandomSpreadStructurePlacement}, filtered through
 * {@link StructurePlacement#isStructureChunk(ChunkGeneratorStructureState, int, int)},
 * and the actual piece graph is created by {@link Structure#generate}. This keeps
 * datapack/version placement changes authoritative.</p>
 *
 * <p>Only structures whose active placement is random-spread are handled here. Monument
 * remains on its already-staged procedural path. A non-random-spread placement is a hard
 * integrity failure instead of silently falling back to guessed constants.</p>
 */
public final class OceanCanvasVanillaStructureForcer {
    private static final java.util.Map<net.minecraft.server.MinecraftServer, Session> SESSIONS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final int CANDIDATES_PER_TICK = 4;

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

    private OceanCanvasVanillaStructureForcer() {}

    public record Progress(String stage, int completed, int total) {}

    public static Optional<Progress> progress(ServerLevel world, Set<String> scopes) {
        if (world == null || scopes == null || scopes.isEmpty()) return Optional.empty();
        Session session = existingSession(world.getServer());
        if (session == null) return Optional.empty();
        synchronized (session) {
            for (Task task : session.queue) {
                if (task.world != world || !scopes.contains(task.scope)) continue;
                return Optional.of(new Progress(task.kind.displayName() + " distribution", task.index, task.candidates.size()));
            }
        }
        return Optional.empty();
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
            OceanCanvasStructureIntegrity.placementFailure(task.scope, task.kind);
            net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.record(
                    "structures.vanilla-forcer",
                    "Forced " + task.kind.displayName() + " distribution aborted for scope '" + task.scope
                            + "' at candidate " + task.index + "/" + task.candidates.size(), ex);
            OceanCanvas.LOGGER.error("[OceanCanvas][Structure:{}] staged {} Always pass failed at candidate {}/{}; aborting task",
                    task.scope, task.kind.displayName(), task.index, task.candidates.size(), ex);
            OceanCanvasStructureIntegrity.complete(task.scope, task.kind);
        }
        synchronized (session) {
            if (session.queue.peekFirst() == task) session.queue.removeFirst(); else session.queue.remove(task);
        }
    }

    public static void queue(ServerLevel world, OceanCanvasPlayerZones.Zone zone,
            OceanCanvasStructureKind kind, List<OceanCanvasPlayerZones.Zone> excludedZones,
            String operation) {
        if (world == null || zone == null || kind == null) return;
        if (kind == OceanCanvasStructureKind.OCEAN_MONUMENT) {
            OceanCanvasStructureIntegrity.recordUnsupportedAlways(zone.name(), kind,
                    operation + " (monument must use staged monument engine)");
            return;
        }
        Session session = session(world);
        synchronized (session) {
            for (Task pending : session.queue) {
                if (pending.world == world && pending.scope.equals(zone.name()) && pending.kind == kind) return;
            }
        }
        Task task = new Task(world, zone, kind, excludedZones, operation);
        if (task.candidates.isEmpty()) {
            OceanCanvasStructureIntegrity.complete(zone.name(), kind);
            return;
        }
        synchronized (session) { session.queue.addLast(task); }
        OceanCanvas.LOGGER.info("[OceanCanvas][Structure:{}] queued strict {} Always pass with {} active vanilla candidates",
                zone.name(), kind.displayName(), task.candidates.size());
    }

    record Candidate(ChunkPos chunk, List<Holder<Structure>> alternatives) {}

    private static final class Task {
        final ServerLevel world;
        final OceanCanvasPlayerZones.Zone zone;
        final String scope;
        final OceanCanvasStructureKind kind;
        final List<OceanCanvasPlayerZones.Zone> excluded;
        final List<Candidate> candidates;
        int index;

        Task(ServerLevel world, OceanCanvasPlayerZones.Zone zone, OceanCanvasStructureKind kind,
                List<OceanCanvasPlayerZones.Zone> excludedZones, String operation) {
            this.world = world;
            this.zone = zone;
            this.scope = zone.name();
            this.kind = kind;
            this.excluded = excludedZones == null ? List.of() : List.copyOf(excludedZones);
            OceanCanvasStructureIntegrity.begin(scope, kind, operation + " / active vanilla placement", 0);
            this.candidates = discoverCandidates(world, zone, kind);
            OceanCanvasStructureIntegrity.setExpectedCandidates(scope, kind, candidates.size());
        }

        boolean tick() {
            int budget = CANDIDATES_PER_TICK;
            while (budget-- > 0 && index < candidates.size()) {
                Candidate candidate = candidates.get(index);
                if (!process(candidate)) return false;
                index++;
            }
            if (index < candidates.size()) return false;
            OceanCanvasStructureIntegrity.complete(scope, kind);
            return true;
        }

        private boolean process(Candidate candidate) {
            int centerX = candidate.chunk.x() * 16 + 8;
            int centerZ = candidate.chunk.z() * 16 + 8;
            for (OceanCanvasPlayerZones.Zone other : excluded) {
                if (other.contains(centerX, world.getSeaLevel(), centerZ)) {
                    OceanCanvasStructureIntegrity.excluded(scope, kind);
                    return true;
                }
            }

            var startChunk = world.getChunkSource().getChunkNow(candidate.chunk.x(), candidate.chunk.z());
            if (startChunk == null) {
                OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world, candidate.chunk.x(), candidate.chunk.z());
                return false;
            }
            for (Holder<Structure> holder : candidate.alternatives) {
                StructureStart existing = startChunk.getStartForStructure(holder.value());
                if (existing != null && existing.isValid()) {
                    OceanCanvasStructureIntegrity.naturalStart(scope, kind);
                    return true;
                }
            }

            StructureStart generated = null;
            Holder<Structure> selected = null;
            boolean generationException = false;
            var generator = world.getChunkSource().getGenerator();
            var state = world.getChunkSource().getGeneratorState();
            for (Holder<Structure> holder : candidate.alternatives) {
                Structure structure = holder.value();
                try {
                    StructureStart attempt = structure.generate(holder, world.dimension(), world.registryAccess(),
                            generator, generator.getBiomeSource(), state.randomState(), world.getStructureManager(),
                            world.getSeed(), candidate.chunk, 0, world, structure.biomes()::contains);
                    if (attempt != null && attempt.isValid()) {
                        generated = attempt;
                        selected = holder;
                        break;
                    }
                } catch (RuntimeException ex) {
                    generationException = true;
                    OceanCanvas.LOGGER.error("[OceanCanvas][Structure:{}] vanilla generation threw for {} candidate {}",
                            scope, kind.displayName(), candidate.chunk, ex);
                }
            }
            if (generated == null || selected == null) {
                if (generationException) OceanCanvasStructureIntegrity.placementFailure(scope, kind);
                else OceanCanvasStructureIntegrity.excluded(scope, kind); // placement candidate, but vanilla generation says ineligible
                return true;
            }

            alignToCanvasFloor(generated, kind, centerX, centerZ);
            BoundingBox box = generated.getBoundingBox();
            int minChunkX = Math.floorDiv(box.minX(), 16), maxChunkX = Math.floorDiv(box.maxX(), 16);
            int minChunkZ = Math.floorDiv(box.minZ(), 16), maxChunkZ = Math.floorDiv(box.maxZ(), 16);

            // The candidate centre being inside the region is not enough. A shipwreck/ruin/
            // portal can cross a polygon edge by multiple chunks. Always means "every eligible
            // candidate whose complete vanilla footprint fits the requested operation", never
            // "write outside the selection because the start chunk happened to fit".
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                int bx=cx*16+8,bz=cz*16+8;
                if (!zone.contains(bx, world.getSeaLevel(), bz)) {
                    OceanCanvasStructureIntegrity.excluded(scope, kind);
                    return true;
                }
                for (OceanCanvasPlayerZones.Zone other : excluded) {
                    if (other.contains(bx, world.getSeaLevel(), bz)) {
                        OceanCanvasStructureIntegrity.excluded(scope, kind);
                        return true;
                    }
                }
                if (world.getChunkSource().getChunkNow(cx, cz) == null) {
                    OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(world, cx, cz);
                    return false;
                }
            }

            Structure structure = selected.value();
            long startRef = ChunkPos.pack(candidate.chunk.x(), candidate.chunk.z());
            try {
                startChunk.setStartForStructure(structure, generated);
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        var chunk = world.getChunkSource().getChunkNow(cx, cz);
                        chunk.addReferenceForStructure(structure, startRef);
                        chunk.markUnsaved();
                        BoundingBox chunkBox = new BoundingBox(cx * 16, world.getMinY(), cz * 16,
                                cx * 16 + 15, world.getMaxY(), cz * 16 + 15);
                        generated.placeInChunk(world, world.structureManager(), generator,
                                RandomSource.create(world.getSeed() ^ startRef ^ ((long) cx << 32) ^ (cz & 0xffffffffL)),
                                chunkBox, new ChunkPos(cx, cz));
                    }
                }
                ProtectedRegions.protect(world, box);
                OceanCanvasProtectedData.get(world).markPendingRevalidation(
                        new BlockPos(box.minX(), box.minY(), box.minZ()), box);
                OceanCanvasStructureIntegrity.syntheticPlacement(scope, kind, true);
                return true;
            } catch (RuntimeException ex) {
                // Roll the metadata graph back across the entire footprint. The independent
                // scanner will still flag any partially placed physical blocks, but /locate and
                // structure references must never preserve a start Ocean Canvas knows failed.
                startChunk.setStartForStructure(structure, StructureStart.INVALID_START);
                startChunk.markUnsaved();
                for (int cz=minChunkZ;cz<=maxChunkZ;cz++) for(int cx=minChunkX;cx<=maxChunkX;cx++) {
                    var chunk=world.getChunkSource().getChunkNow(cx,cz);
                    if(chunk==null) continue;
                    var refs=chunk.getAllReferences().get(structure);
                    if(refs!=null && refs.remove(startRef)) chunk.markUnsaved();
                }
                OceanCanvasStructureIntegrity.placementFailure(scope, kind);
                OceanCanvas.LOGGER.error("[OceanCanvas][Structure:{}] failed to register/place genuine {} start at {}; metadata rolled back",
                        scope, kind.displayName(), candidate.chunk, ex);
                return true;
            }
        }
    }

    private static void alignToCanvasFloor(StructureStart start, OceanCanvasStructureKind kind, int x, int z) {
        BoundingBox box = start.getBoundingBox();
        OceanCanvasConfig cfg = OceanCanvasConfig.get();
        int floor = cfg.oceanFloorY() + OceanCanvasSurfaceFlattener.floorOffset(x, z, cfg.oceanFloorVariation());
        int targetMinY = switch (kind) {
            case BURIED_TREASURE -> floor - 1;
            case SHIPWRECK, OCEAN_RUIN, RUINED_PORTAL -> floor;
            case OCEAN_MONUMENT -> box.minY();
        };
        int dy = targetMinY - box.minY();
        if (dy == 0) return;
        for (var piece : start.getPieces()) piece.move(0, dy, 0);
    }

    static List<Candidate> discoverCandidates(ServerLevel world, OceanCanvasPlayerZones.Zone zone,
            OceanCanvasStructureKind kind) {
        return discoverCandidates(world, zone, kind, true);
    }

    static List<Candidate> discoverCandidates(ServerLevel world, OceanCanvasPlayerZones.Zone zone,
            OceanCanvasStructureKind kind, boolean recordIntegrity) {
        List<Holder<Structure>> structures = resolveStructures(world, kind);
        if (structures.isEmpty()) {
            if (recordIntegrity) OceanCanvasStructureIntegrity.recordUnsupportedAlways(zone.name(), kind,
                    "No matching vanilla structures in active registry");
            return List.of();
        }
        ChunkGeneratorStructureState state = world.getChunkSource().getGeneratorState();
        int minChunkX = Math.floorDiv(zone.bounds().minX(), 16), maxChunkX = Math.floorDiv(zone.bounds().maxX(), 16);
        int minChunkZ = Math.floorDiv(zone.bounds().minZ(), 16), maxChunkZ = Math.floorDiv(zone.bounds().maxZ(), 16);
        Map<Long, LinkedHashSet<Holder<Structure>>> byChunk = new LinkedHashMap<>();
        Set<String> seenPlacements = new LinkedHashSet<>();

        for (Holder<Structure> structure : structures) {
            List<StructurePlacement> placements = state.getPlacementsForStructure(structure);
            for (StructurePlacement placement : placements) {
                if (!(placement instanceof RandomSpreadStructurePlacement random)) {
                    if (recordIntegrity) OceanCanvasStructureIntegrity.recordDistributionApproximation(zone.name(), kind,
                            "Active placement is not random-spread: " + placement.getClass().getSimpleName());
                    continue;
                }
                String placementKey = random.spacing() + ":" + random.separation() + ":" + random.spreadType() + ":" + placement;
                // Multiple variants commonly share one placement. We still need to add each
                // holder as a generation alternative, but enumerate the placement grid once.
                int spacing = random.spacing();
                int minRegionX = Math.floorDiv(minChunkX, spacing) - 1, maxRegionX = Math.floorDiv(maxChunkX, spacing) + 1;
                int minRegionZ = Math.floorDiv(minChunkZ, spacing) - 1, maxRegionZ = Math.floorDiv(maxChunkZ, spacing) + 1;
                boolean firstForPlacement = seenPlacements.add(placementKey);
                if (firstForPlacement) {
                    for (int rz = minRegionZ; rz <= maxRegionZ; rz++) for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                        ChunkPos candidate = random.getPotentialStructureChunk(world.getSeed(), rx, rz);
                        if (candidate.x() < minChunkX || candidate.x() > maxChunkX || candidate.z() < minChunkZ || candidate.z() > maxChunkZ) continue;
                        if (!placement.isStructureChunk(state, candidate.x(), candidate.z())) continue;
                        int bx = candidate.x() * 16 + 8, bz = candidate.z() * 16 + 8;
                        if (!zone.contains(bx, world.getSeaLevel(), bz)) continue;
                        byChunk.computeIfAbsent(ChunkPos.pack(candidate.x(), candidate.z()), ignored -> new LinkedHashSet<>());
                    }
                }
                // Add this holder to every candidate governed by this placement. isStructureChunk
                // below is cheap and avoids depending on placement object identity/equality.
                for (var e : byChunk.entrySet()) {
                    int cx = ChunkPos.getX(e.getKey()), cz = ChunkPos.getZ(e.getKey());
                    if (placement.isStructureChunk(state, cx, cz)) e.getValue().add(structure);
                }
            }
        }
        List<Candidate> out = new ArrayList<>(byChunk.size());
        for (var e : byChunk.entrySet()) {
            if (!e.getValue().isEmpty()) out.add(new Candidate(new ChunkPos(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey())), List.copyOf(e.getValue())));
        }
        return List.copyOf(out);
    }

    static List<Holder<Structure>> resolveStructures(ServerLevel world, OceanCanvasStructureKind kind) {
        List<String> ids = switch (kind) {
            case SHIPWRECK -> List.of("shipwreck", "shipwreck_beached");
            case OCEAN_RUIN -> List.of("ocean_ruin_cold", "ocean_ruin_warm");
            case BURIED_TREASURE -> List.of("buried_treasure");
            case RUINED_PORTAL -> List.of("ruined_portal_ocean");
            case OCEAN_MONUMENT -> List.of("monument");
        };
        var registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> out = new ArrayList<>();
        for (String id : ids) {
            ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace(id));
            registry.get(key).ifPresent(out::add);
        }
        return List.copyOf(out);
    }
}
