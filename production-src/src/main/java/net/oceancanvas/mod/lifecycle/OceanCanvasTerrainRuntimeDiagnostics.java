package net.oceancanvas.mod.lifecycle;

import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

/**
 * Read-only diagnostic surface for transient terrain-operation runtime state.
 *
 * <p>The very large flattener still owns all queues, tickets, recovery sets and
 * lighting state. This contract exposes immutable observations only, allowing
 * diagnostics/project health code to stop compiling against that god class.
 * The composition root is the sole adapter from flattener/Restore records into
 * these neutral records.</p>
 */
public final class OceanCanvasTerrainRuntimeDiagnostics {
    public record Queue(
            int queued,
            int ready,
            int missingNeighbor,
            int missingNeighborStale,
            int missingStructureOwner,
            int loadingNotQueued,
            int loadingStale,
            long oldestLoadingMs,
            int ownedNeighborMisses,
            int farthestMissingFromAnchor) {
        public String shortText() {
            return "queued=" + queued
                    + ", ready=" + ready
                    + ", neighbor=" + missingNeighbor
                    + ", neighborStale=" + missingNeighborStale
                    + ", structure=" + missingStructureOwner
                    + ", loading=" + loadingNotQueued
                    + ", loadingStale=" + loadingStale
                    + ", oldestLoadMs=" + oldestLoadingMs
                    + ", ownedNeighborMiss=" + ownedNeighborMisses
                    + ", missAnchorDist=" + farthestMissingFromAnchor;
        }
        public static Queue idle() { return new Queue(0,0,0,0,0,0,0,0L,0,0); }
    }

    public record PregenTickets(
            int selfActive, long selfInstalls, long selfReleases,
            int carveLaneActive,
            int processingLeaseActive, long processingLeaseInstalls, long processingLeaseReleases,
            int finalDrainActive, long finalDrainInstalls, long finalDrainReleases,
            int targetFutures, int supportFutures, int supportFuturesDone,
            long fullDemandRequests, long fullDemandCompletions, long fullDemandFailures, long fullDemandCancellations,
            int loadRescueActive, long loadRescueRequests, long loadRescueSuccesses,
            int auditPending, int auditRepeatPending, long auditRejections, long auditRepairs, long auditRepeatFailures) {
        public static PregenTickets idle() {
            return new PregenTickets(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
        }
    }

    public record TicketOwnership(String pool,int chunkX,int chunkZ,long ageMillis,
                                  String owner,String purpose,String releaseCondition) {
        public TicketOwnership {
            pool=safe(pool); owner=safe(owner); purpose=safe(purpose); releaseCondition=safe(releaseCondition);
            ageMillis=Math.max(0L,ageMillis);
        }
    }

    public record Lighting(int pendingSync, int activeSync, int persistentBackoff,
                           int residencyTickets, long finalPublishes,
                           int lightOnlyRecoveryActive, int lightOnlyRecoveryTracked,
                           int physicalRecoveryActive, int physicalRecoveryTracked,
                           long newTerrainRetirements) {
        public static Lighting idle() { return new Lighting(0,0,0,0,0L,0,0,0,0,0L); }
    }

    public interface Provider {
        Queue queueSnapshot(ServerLevel world);
        PregenTickets pregenTicketSnapshot();
        List<TicketOwnership> ticketOwnershipSnapshot(ServerLevel world);
        Lighting lightingSnapshot();
    }

    private static final Provider IDLE = new Provider() {
        @Override public Queue queueSnapshot(ServerLevel world) { return Queue.idle(); }
        @Override public PregenTickets pregenTicketSnapshot() { return PregenTickets.idle(); }
        @Override public List<TicketOwnership> ticketOwnershipSnapshot(ServerLevel world) { return List.of(); }
        @Override public Lighting lightingSnapshot() { return Lighting.idle(); }
    };

    private static volatile Provider provider = IDLE;

    private OceanCanvasTerrainRuntimeDiagnostics() { }

    public static void install(Provider next) {
        provider = Objects.requireNonNull(next, "terrain runtime diagnostics provider");
    }

    public static Queue queueSnapshot(ServerLevel world) {
        Queue value=provider.queueSnapshot(world); return value==null?Queue.idle():value;
    }
    public static PregenTickets pregenTicketSnapshot() {
        PregenTickets value=provider.pregenTicketSnapshot(); return value==null?PregenTickets.idle():value;
    }
    public static List<TicketOwnership> ticketOwnershipSnapshot(ServerLevel world) {
        List<TicketOwnership> value=provider.ticketOwnershipSnapshot(world); return value==null?List.of():List.copyOf(value);
    }
    public static Lighting lightingSnapshot() {
        Lighting value=provider.lightingSnapshot(); return value==null?Lighting.idle():value;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
