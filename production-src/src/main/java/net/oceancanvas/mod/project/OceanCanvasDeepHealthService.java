package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Conservative integrity scan over Ocean Canvas' explicit terrain ledger.
 * It does not force-load/generate chunks and does not infer terrain from old
 * processed-only saves. That makes every reported contradiction actionable.
 */
public final class OceanCanvasDeepHealthService {
    private OceanCanvasDeepHealthService(){}

    /**
     * Last explicitly requested deep scan per live world. Weak keys prevent a health
     * diagnostic from retaining an integrated-server world after disconnect. Periodic
     * scorecards/API publication read this cache instead of turning a five-second
     * telemetry tick into an O(world) ledger walk.
     */
    private record CachedReport(long terrainRevision, long sealRevision, int maxFindings, Report report) {}
    private static final Map<ServerLevel, CachedReport> LAST_REPORT =
            Collections.synchronizedMap(new WeakHashMap<>());

    public enum Classification { HEALTHY_EXPLICIT, STATE_MISMATCH, LEGACY_UNVERIFIED }
    public record Finding(long chunkKey, int chunkX, int chunkZ, Classification classification,
                          String terrainState, boolean processed, String detail, boolean repairable) {}
    public record Report(long explicitStates, long healthyExplicit, long mismatches,
                         long legacyUnverified, List<Finding> findings, boolean truncated) {
        public boolean healthy(){ return mismatches==0; }
    }
    public record RepairResult(int repaired, int skipped, String detail) {}

    public static Report scan(ServerLevel world, int maxFindings) {
        var terrain=OceanCanvasTerrainStateData.get(world);
        var seals=OceanCanvasProtectedData.get(world);
        int limit=Math.max(0,maxFindings);
        long terrainRevision=terrain.membershipRevision(), sealRevision=seals.processedMembershipRevision();

        // v253.125.38: callers such as the health inbox, milestone capture, release
        // readiness and diagnostic bundles can request the same world-wide integrity
        // scan repeatedly within one unchanged metadata epoch. Reuse the authoritative
        // prior result when it was captured with at least as large a finding budget.
        CachedReport cached=LAST_REPORT.get(world);
        if(cached!=null && cached.terrainRevision()==terrainRevision && cached.sealRevision()==sealRevision
                && cached.maxFindings()>=limit){
            return limitReport(cached.report(),limit);
        }

        List<Finding> findings=new ArrayList<>();
        final long[] counts=new long[4]; // explicit, healthy, mismatch, legacy
        terrain.forEachExplicitState((chunkKey,state)->{
            counts[0]++;
            int cx=ChunkPos.getX(chunkKey), cz=ChunkPos.getZ(chunkKey);
            boolean processed=seals.isChunkProcessed(chunkKey);
            // v107: CUSTOM_OR_MODIFIED is the one explicit state that is expected to
            // NEVER carry a processed seal - it exists specifically to mark chunks
            // Ocean Canvas itself has never carved (player-built terrain), matching
            // OceanCanvasSurfaceFlattener's own explicit exemption.
            if(processed || state==OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED){
                counts[1]++;
            } else {
                counts[2]++;
                if(findings.size()<limit) findings.add(new Finding(chunkKey,cx,cz,Classification.STATE_MISMATCH,
                        state.name(),false,
                        "Explicit "+state.name()+" terrain is missing its processed seal; ordinary chunk-load processing could touch it again.",
                        true));
            }
        });

        seals.forEachProcessedChunk(key->{
            if(terrain.get(key)!=OceanCanvasTerrainStateData.TerrainState.UNKNOWN)return;
            counts[3]++;
            if(findings.size()<limit){
                int cx=ChunkPos.getX(key),cz=ChunkPos.getZ(key);
                findings.add(new Finding(key,cx,cz,Classification.LEGACY_UNVERIFIED,"UNKNOWN",true,
                        "Processed by an older Ocean Canvas version before explicit terrain-state tracking; state is intentionally not guessed.",false));
            }
        });
        Report report = new Report(counts[0],counts[1],counts[2],counts[3],List.copyOf(findings),(counts[2]+counts[3])>findings.size());
        // Cache only if the scan observed one stable metadata epoch. If something
        // changed concurrently, the returned conservative report is still useful but
        // must not suppress a later authoritative rescan.
        if(terrain.membershipRevision()==terrainRevision && seals.processedMembershipRevision()==sealRevision){
            LAST_REPORT.put(world, new CachedReport(terrainRevision,sealRevision,limit,report));
        }
        return report;
    }

    private static Report limitReport(Report report,int limit){
        if(report==null)return null;
        int n=Math.min(Math.max(0,limit),report.findings().size());
        if(n==report.findings().size())return report;
        List<Finding> trimmed=List.copyOf(report.findings().subList(0,n));
        return new Report(report.explicitStates(),report.healthyExplicit(),report.mismatches(),report.legacyUnverified(),
                trimmed,(report.mismatches()+report.legacyUnverified())>trimmed.size());
    }

    /** Returns the most recent explicit deep scan without performing any world-wide work. */
    public static Report lastReport(ServerLevel world) {
        CachedReport cached = LAST_REPORT.get(world);
        if (cached == null) return null;
        var terrain = OceanCanvasTerrainStateData.get(world);
        var seals = OceanCanvasProtectedData.get(world);
        return cached.terrainRevision() == terrain.membershipRevision()
                && cached.sealRevision() == seals.processedMembershipRevision() ? cached.report() : null;
    }

    /** Explicit lifecycle hook; weak keys are the backstop, not the primary cleanup path. */
    public static void onServerStopping() {
        LAST_REPORT.clear();
    }

    /**
     * Repairs only a provable ledger contradiction: explicit intended terrain state
     * exists but the anti-reprocessing seal is absent. No terrain blocks are changed.
     */
    public static RepairResult repairMetadata(ServerLevel world) {
        var terrain=OceanCanvasTerrainStateData.get(world);
        var seals=OceanCanvasProtectedData.get(world);
        final int[] result=new int[2]; // repaired, skipped
        terrain.forEachExplicitState((chunkKey,state)->{
            // v107: see the matching note in scan() - CUSTOM_OR_MODIFIED is expected
            // to never carry a processed seal, so it is not a contradiction to
            // repair. Stamping one on would falsely claim Ocean Canvas itself
            // carved a chunk it is explicitly recorded as never having touched.
            if(state==OceanCanvasTerrainStateData.TerrainState.UNKNOWN
                    || state==OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED){result[1]++;return;}
            if(!seals.isChunkProcessed(chunkKey)){ seals.markChunkProcessed(chunkKey); result[0]++; }
        });
        int repaired=result[0], skipped=result[1];
        // A repair changes the relationship the cached report describes. Force the
        // next explicit health request to establish a new authoritative report.
        LAST_REPORT.remove(world);
        return new RepairResult(repaired,skipped,
                "Repaired only processed-seal metadata for chunks with an explicit intended terrain state; no blocks were modified.");
    }
}