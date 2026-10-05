package net.oceancanvas.mod.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent, read-only verifier for the physical + metadata structure graph.
 *
 * <p>This deliberately does not trust the placement ledger. It asks loaded Minecraft
 * chunks for their real StructureStart/reference maps and compares those facts with
 * Ocean Canvas' resolved Never/Default/Always rule. It never loads or generates a
 * chunk; unloaded evidence is reported as incomplete rather than guessed.</p>
 *
 * <p>v237 tightens three invariants that are easy to miss if the placement code audits
 * itself: monuments participate in the same Always-candidate coverage check as every
 * other managed kind, orphan/stale references are independently detected, and valid
 * starts outside the active vanilla candidate set are surfaced rather than silently
 * counted as success.</p>
 */
public final class OceanCanvasStructureIntegrityScanner {
    public enum Rule { NEVER, DEFAULT, ALWAYS }
    public record Finding(String severity, String code, OceanCanvasStructureKind kind, int chunkX, int chunkZ, String detail) {}
    public record KindReport(OceanCanvasStructureKind kind, Rule rule, int expectedCandidates, int verifiedCandidates,
            int validStarts, int ineligibleCandidates, int forbiddenStarts, int missingStarts, int metadataErrors,
            int verticalErrors, int orphanReferences, int forbiddenReferences, int offGridStarts,
            int unloadedEvidence, boolean truncated, List<Finding> findings) {
        public boolean completeEvidence() { return unloadedEvidence == 0 && !truncated; }
        public boolean healthy() {
            return completeEvidence() && forbiddenStarts == 0 && missingStarts == 0 && metadataErrors == 0
                    && verticalErrors == 0 && orphanReferences == 0 && forbiddenReferences == 0 && offGridStarts == 0;
        }
    }
    public record Report(String scope, List<KindReport> kinds) {
        public boolean healthy() { return kinds.stream().allMatch(KindReport::healthy); }
        public int issues() { return kinds.stream().mapToInt(k -> k.forbiddenStarts()+k.missingStarts()+k.metadataErrors()
                +k.verticalErrors()+k.orphanReferences()+k.forbiddenReferences()+k.offGridStarts()).sum(); }
        public int incompleteKinds() { return (int) kinds.stream().filter(k -> !k.completeEvidence()).count(); }
    }

    private static final int MAX_EXISTING_SCAN_CHUNKS = 32768;
    private static final int MAX_FINDINGS_PER_KIND = 48;

    private OceanCanvasStructureIntegrityScanner() {}

    public static Report scanRegion(ServerLevel world, OceanCanvasPlayerZones.Zone zone) {
        List<KindReport> out = new ArrayList<>();
        for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) out.add(scanKind(world, zone, kind));
        return new Report(zone.name(), List.copyOf(out));
    }

    private static KindReport scanKind(ServerLevel world, OceanCanvasPlayerZones.Zone zone, OceanCanvasStructureKind kind) {
        Rule rule = resolvedRule(zone, kind);
        List<Finding> findings = new ArrayList<>();
        int expected = 0, verified = 0, valid = 0, ineligible = 0, forbidden = 0, missing = 0, metadata = 0, vertical = 0;
        int orphanRefs = 0, forbiddenRefs = 0, offGrid = 0, unloaded = 0;
        boolean truncated = false;

        List<Holder<Structure>> holders = OceanCanvasVanillaStructureForcer.resolveStructures(world, kind);
        Set<Structure> matching = new HashSet<>();
        for (Holder<Structure> h : holders) matching.add(h.value());

        Set<Long> expectedOwners = new HashSet<>();
        Set<String> validatedStarts = new HashSet<>();
        Set<String> orphanReferenceKeys = new HashSet<>();
        Set<String> forbiddenReferenceKeys = new HashSet<>();
        Set<String> unloadedReferenceOwners = new HashSet<>();

        // Every Always kind, including monuments, has the same active-vanilla candidate contract.
        if (rule == Rule.ALWAYS) {
            List<OceanCanvasVanillaStructureForcer.Candidate> candidates = OceanCanvasVanillaStructureForcer.discoverCandidates(world, zone, kind, false);
            expected = candidates.size();
            for (var candidate : candidates) expectedOwners.add(ChunkPos.pack(candidate.chunk().x(), candidate.chunk().z()));
            for (var candidate : candidates) {
                ChunkPos pos = candidate.chunk();
                var chunk = world.getChunkSource().getChunkNow(pos.x(), pos.z());
                if (chunk == null) { unloaded++; continue; }
                verified++;
                StructureStart start = firstValidStart(chunk, candidate.alternatives());
                if (start == null) {
                    int eligibility = generationEligibility(world, zone, kind, candidate);
                    if (eligibility == 0) { ineligible++; continue; }
                    if (eligibility < 0) {
                        missing++;
                        add(findings, new Finding("ERROR","OC-S102",kind,pos.x(),pos.z(),"Could not independently determine candidate eligibility because vanilla generation threw."));
                        continue;
                    }
                    missing++;
                    add(findings, new Finding("ERROR","OC-S101",kind,pos.x(),pos.z(),"Eligible Always candidate has no valid Minecraft StructureStart."));
                    continue;
                }
                valid++;
                if(!footprintFitsZone(world, zone, start.getBoundingBox())) {
                    offGrid++;
                    add(findings,new Finding("ERROR","OC-S104",kind,pos.x(),pos.z(),"Always structure footprint leaves the exact region selection."));
                }
                String key = startKey(start);
                if (validatedStarts.add(key)) {
                    int[] errors = verifyMetadataAndVertical(world, kind, start, findings);
                    metadata += errors[0]; vertical += errors[1]; unloaded += errors[2];
                }
            }
        }

        // Independently inspect loaded starts and references. This catches stale references,
        // Never remnants, duplicate/off-grid starts, and natural/default starts that the
        // candidate loop above would never see.
        int examined = 0;
        java.util.Iterator<Long> shape = boundedShapeIterator(zone);
        while (shape.hasNext()) {
            long packed = shape.next();
            if (examined++ >= MAX_EXISTING_SCAN_CHUNKS) { truncated = true; break; }
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            var chunk = world.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) { if (rule != Rule.ALWAYS) unloaded++; continue; }

            for (var e : chunk.getAllStarts().entrySet()) {
                StructureStart start = e.getValue();
                if (start == null || !start.isValid() || !matching.contains(e.getKey())) continue;
                if (start.getChunkPos().x() != cx || start.getChunkPos().z() != cz) continue; // owner only
                long ownerRef = ChunkPos.pack(cx, cz);
                if (rule == Rule.NEVER) {
                    forbidden++;
                    add(findings, new Finding("ERROR","OC-S001",kind,cx,cz,"Never rule still has a valid Minecraft StructureStart."));
                    continue;
                }
                if (rule == Rule.ALWAYS && !expectedOwners.contains(ownerRef)) {
                    offGrid++;
                    add(findings, new Finding("ERROR","OC-S103",kind,cx,cz,"Valid structure start is outside the active vanilla Always candidate set."));
                }
                String key = startKey(start);
                if (validatedStarts.add(key)) {
                    if (rule != Rule.ALWAYS) valid++;
                    int[] errors = verifyMetadataAndVertical(world, kind, start, findings);
                    metadata += errors[0]; vertical += errors[1]; unloaded += errors[2];
                }
            }

            for (var e : chunk.getAllReferences().entrySet()) {
                Structure structure = e.getKey();
                if (!matching.contains(structure)) continue;
                var it = e.getValue().iterator();
                while (it.hasNext()) {
                    long ownerRef = it.nextLong();
                    int ox = ChunkPos.getX(ownerRef), oz = ChunkPos.getZ(ownerRef);
                    String refKey = System.identityHashCode(structure)+":"+ownerRef;
                    if (rule == Rule.NEVER && forbiddenReferenceKeys.add(refKey)) {
                        forbiddenRefs++;
                        add(findings, new Finding("ERROR","OC-S002",kind,cx,cz,"Never rule still has reference to owner "+ox+","+oz+"."));
                    }
                    var ownerChunk = world.getChunkSource().getChunkNow(ox, oz);
                    if (ownerChunk == null) {
                        if (unloadedReferenceOwners.add(refKey)) unloaded++;
                        continue;
                    }
                    StructureStart ownerStart = ownerChunk.getStartForStructure(structure);
                    if (ownerStart == null || !ownerStart.isValid() || ownerStart.getChunkPos().x()!=ox || ownerStart.getChunkPos().z()!=oz) {
                        if (orphanReferenceKeys.add(refKey)) {
                            orphanRefs++;
                            add(findings, new Finding("ERROR","OC-S202",kind,cx,cz,"Structure reference points to missing/invalid owner "+ox+","+oz+"."));
                        }
                        continue;
                    }
                    var b=ownerStart.getBoundingBox();
                    int minCX=Math.floorDiv(b.minX(),16),maxCX=Math.floorDiv(b.maxX(),16),minCZ=Math.floorDiv(b.minZ(),16),maxCZ=Math.floorDiv(b.maxZ(),16);
                    if ((cx<minCX||cx>maxCX||cz<minCZ||cz>maxCZ) && orphanReferenceKeys.add(refKey+":"+cx+":"+cz)) {
                        orphanRefs++;
                        add(findings, new Finding("ERROR","OC-S203",kind,cx,cz,"Structure reference exists outside the owner's physical footprint."));
                    }
                }
            }
        }

        if (truncated) add(findings, new Finding("WARN","OC-S900",kind,0,0,"Existing-start/reference scan hit the bounded chunk cap; evidence is incomplete."));
        if (unloaded > 0) add(findings, new Finding("WARN","OC-S901",kind,0,0,unloaded+" required chunk observation(s) were unloaded; scanner did not load/generate them."));
        return new KindReport(kind, rule, expected, verified, valid, ineligible, forbidden, missing, metadata, vertical,
                orphanRefs, forbiddenRefs, offGrid, unloaded, truncated, List.copyOf(findings));
    }

    /** 1=eligible, 0=vanilla-ineligible, -1=probe failed. Creates only an in-memory piece graph. */
    private static int generationEligibility(ServerLevel world, OceanCanvasPlayerZones.Zone zone,
            OceanCanvasStructureKind kind, OceanCanvasVanillaStructureForcer.Candidate candidate) {
        var generator=world.getChunkSource().getGenerator();
        var state=world.getChunkSource().getGeneratorState();
        boolean threw=false;
        for (Holder<Structure> holder:candidate.alternatives()) {
            try {
                Structure structure=holder.value();
                StructureStart attempt=structure.generate(holder,world.dimension(),world.registryAccess(),generator,
                        generator.getBiomeSource(),state.randomState(),world.getStructureManager(),world.getSeed(),
                        candidate.chunk(),0,world,structure.biomes()::contains);
                if(attempt!=null && attempt.isValid()) {
                    return footprintFitsZone(world,zone,attempt.getBoundingBox())?1:0;
                }
            } catch (RuntimeException ex) { threw=true; }
        }
        return threw?-1:0;
    }

    private static boolean footprintFitsZone(ServerLevel world, OceanCanvasPlayerZones.Zone zone,
            net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        for(int cz=Math.floorDiv(box.minZ(),16);cz<=Math.floorDiv(box.maxZ(),16);cz++)
            for(int cx=Math.floorDiv(box.minX(),16);cx<=Math.floorDiv(box.maxX(),16);cx++)
                if(!zone.contains(cx*16+8,world.getSeaLevel(),cz*16+8)) return false;
        return true;
    }

    private static java.util.Iterator<Long> boundedShapeIterator(OceanCanvasPlayerZones.Zone zone) {
        if (zone.hasExplicitShape()) return zone.chunks().iterator();
        int minCX=Math.floorDiv(zone.bounds().minX(),16), maxCX=Math.floorDiv(zone.bounds().maxX(),16);
        int minCZ=Math.floorDiv(zone.bounds().minZ(),16), maxCZ=Math.floorDiv(zone.bounds().maxZ(),16);
        return new java.util.Iterator<>() {
            int cx=minCX, cz=minCZ; boolean has=minCX<=maxCX && minCZ<=maxCZ;
            public boolean hasNext(){return has;}
            public Long next(){
                if(!has) throw new java.util.NoSuchElementException();
                long out=ChunkPos.pack(cx,cz);
                if(++cx>maxCX){cx=minCX;if(++cz>maxCZ)has=false;}
                return out;
            }
        };
    }

    private static StructureStart firstValidStart(net.minecraft.world.level.chunk.LevelChunk chunk, List<Holder<Structure>> alternatives) {
        for (Holder<Structure> holder : alternatives) {
            StructureStart start = chunk.getStartForStructure(holder.value());
            if (start != null && start.isValid()) return start;
        }
        return null;
    }

    private static String startKey(StructureStart start) {
        ChunkPos owner=start.getChunkPos();
        return System.identityHashCode(start.getStructure())+":"+owner.x()+":"+owner.z();
    }

    /** returns metadataErrors, verticalErrors, unloadedEvidence */
    private static int[] verifyMetadataAndVertical(ServerLevel world, OceanCanvasStructureKind kind, StructureStart start, List<Finding> findings) {
        int metadata = 0, vertical = 0, unloaded = 0;
        Structure structure = start.getStructure();
        ChunkPos owner = start.getChunkPos();
        long ownerRef = ChunkPos.pack(owner.x(), owner.z());
        var box = start.getBoundingBox();
        for (int cz=Math.floorDiv(box.minZ(),16); cz<=Math.floorDiv(box.maxZ(),16); cz++) {
            for (int cx=Math.floorDiv(box.minX(),16); cx<=Math.floorDiv(box.maxX(),16); cx++) {
                var chunk = world.getChunkSource().getChunkNow(cx,cz);
                if (chunk == null) { unloaded++; continue; }
                var refs = chunk.getAllReferences().get(structure);
                if (refs == null || !refs.contains(ownerRef)) {
                    metadata++;
                    add(findings, new Finding("ERROR","OC-S201",kind,cx,cz,"Structure footprint chunk is missing reference to owner "+owner.x()+","+owner.z()+"."));
                }
            }
        }

        OceanCanvasConfig cfg=OceanCanvasConfig.get();
        int sampleX, sampleZ;
        if(kind==OceanCanvasStructureKind.OCEAN_MONUMENT){sampleX=(box.minX()+box.maxX())/2;sampleZ=(box.minZ()+box.maxZ())/2;}
        else {sampleX=owner.x()*16+8;sampleZ=owner.z()*16+8;}
        int floor=cfg.oceanFloorY()+OceanCanvasSurfaceFlattener.floorOffset(sampleX,sampleZ,cfg.oceanFloorVariation());
        int expectedMinY = kind == OceanCanvasStructureKind.BURIED_TREASURE ? floor-1 : floor;
        if (box.minY() != expectedMinY) {
            vertical++;
            add(findings, new Finding("ERROR","OC-S301",kind,owner.x(),owner.z(),"Structure minY="+box.minY()+" but Canvas integration expects "+expectedMinY+"."));
        }

        // Monuments require a sealed foundation, not merely a correctly shifted piece graph.
        // Probe the top foundation layer at the four corners + centre. This is deliberately
        // bounded; the Physical Health scanner owns exhaustive terrain sampling.
        if(kind==OceanCanvasStructureKind.OCEAN_MONUMENT && box.minY()>world.getMinY()) {
            int y=box.minY()-1;
            int[][] probes={{box.minX(),box.minZ()},{box.maxX(),box.minZ()},{box.minX(),box.maxZ()},{box.maxX(),box.maxZ()},{sampleX,sampleZ}};
            for(int[] p:probes){
                var c=world.getChunkSource().getChunkNow(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16));
                if(c==null){unloaded++;continue;}
                if(!world.getBlockState(new net.minecraft.core.BlockPos(p[0],y,p[1])).is(Blocks.STONE)){
                    vertical++;
                    add(findings,new Finding("ERROR","OC-S302",kind,owner.x(),owner.z(),"Monument foundation probe is not sealed stone at "+p[0]+","+y+","+p[1]+"."));
                }
            }
        }
        return new int[]{metadata,vertical,unloaded};
    }

    private static Rule resolvedRule(OceanCanvasPlayerZones.Zone zone, OceanCanvasStructureKind kind) {
        var r=zone.overrideFor(kind);
        if (r == net.oceancanvas.mod.config.StructureOverride.FORCE_ON) return Rule.ALWAYS;
        if (r == net.oceancanvas.mod.config.StructureOverride.FORCE_OFF) return Rule.NEVER;
        var global=kind.globalDefault(OceanCanvasConfig.get());
        if (global == net.oceancanvas.mod.config.StructureOverride.FORCE_ON) return Rule.ALWAYS;
        if (global == net.oceancanvas.mod.config.StructureOverride.FORCE_OFF) return Rule.NEVER;
        return Rule.DEFAULT;
    }

    private static void add(List<Finding> findings, Finding finding) {
        if (findings.size() < MAX_FINDINGS_PER_KIND) findings.add(finding);
    }
}
