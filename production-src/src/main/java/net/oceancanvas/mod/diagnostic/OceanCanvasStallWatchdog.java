package net.oceancanvas.mod.diagnostic;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics;

/**
 * v228.5 diagnostic watchdog for severe integrated-server stalls, including main-loop gaps between Fabric tick callbacks.
 * Observation only: never interrupts, cancels, force-loads, or mutates server state.
 */
public final class OceanCanvasStallWatchdog {
    private static final long STALL_NS = 8_000_000_000L;
    private static final long POLL_MS = 250L;
    private static final AtomicLong TICK_STARTED_NS = new AtomicLong();
    private static final AtomicLong TICK_SEQUENCE = new AtomicLong();
    private static final AtomicLong LAST_SERVER_PROGRESS_NS = new AtomicLong();
    private static final AtomicBoolean IN_TICK = new AtomicBoolean();
    private static final AtomicBoolean DUMPED_THIS_TICK = new AtomicBoolean();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile Thread watcher;
    private static volatile MinecraftServer activeServer;

    private OceanCanvasStallWatchdog() {}

    public record CauseSnapshot(String id,String owner,String state,double ageSeconds,String detail) {}
    public record MatrixSnapshot(String state, String phase, long tickSequence, double secondsSinceProgress, String detail, java.util.List<CauseSnapshot> causes) {}

    /**
     * Independent cause-owned watchdog matrix. Every row has one owner and one admission signal;
     * none of the rows mutates world/controller state. This avoids one generic timeout claiming to
     * know whether the real cause was loading, neighbors, cleanup, futures, audits, or server cadence.
     */
    public static java.util.List<CauseSnapshot> causeMatrix() {
        if(!RUNNING.get()) return java.util.List.of(new CauseSnapshot("server-cadence","OceanCanvasStallWatchdog","IDLE",0.0D,"Watchdog is not active; no runtime claim is made."));
        long now=System.nanoTime(); boolean inTick=IN_TICK.get(); long start=inTick?TICK_STARTED_NS.get():LAST_SERVER_PROGRESS_NS.get();
        double serverSeconds=start<=0L?0.0D:Math.max(0.0D,(now-start)/1_000_000_000.0D);
        int targets=OceanCanvasTerrainOperationActivity.outstandingPregenTargets();
        MinecraftServer server=activeServer;
        if(server==null) return java.util.List.of(new CauseSnapshot("server-cadence","OceanCanvasStallWatchdog","IDLE",serverSeconds,"Server reference is unavailable; queue diagnostics are intentionally not guessed."));
        var q=OceanCanvasTerrainRuntimeDiagnostics.queueSnapshot(server.overworld()); var t=OceanCanvasTerrainRuntimeDiagnostics.pregenTicketSnapshot();
        var out=new java.util.ArrayList<CauseSnapshot>();
        out.add(new CauseSnapshot("server-cadence","OceanCanvasStallWatchdog",targets>0&&serverSeconds>=8.0D?"ATTENTION":"GOOD",serverSeconds,
                String.format(java.util.Locale.ROOT,"%s; %.2fs since server progress; targets=%d",inTick?"inside-tick":"between-ticks/main-loop",serverSeconds,targets)));
        out.add(new CauseSnapshot("load-starvation","Pregen load scheduler",q.loadingStale()>0&&q.oldestLoadingMs()>=8000L?"ATTENTION":"GOOD",q.oldestLoadingMs()/1000.0D,
                "loadingStale="+q.loadingStale()+", oldestLoadMs="+q.oldestLoadingMs()+", loading="+q.loadingNotQueued()));
        out.add(new CauseSnapshot("neighbor-starvation","SurfaceFlattener neighbor gate",q.missingNeighborStale()>0?"ATTENTION":"GOOD",0.0D,
                "missingNeighbor="+q.missingNeighbor()+", stale="+q.missingNeighborStale()+", ownerMisses="+q.ownedNeighborMisses()));
        boolean cleanupLeak=targets==0&&(t.selfActive()>0||t.processingLeaseActive()>0||t.finalDrainActive()>0||t.loadRescueActive()>0);
        out.add(new CauseSnapshot("cleanup-ownership","Pregen ticket lifecycle",cleanupLeak?"ATTENTION":"GOOD",0.0D,
                "targets="+targets+", self="+t.selfActive()+", leases="+t.processingLeaseActive()+", finalDrain="+t.finalDrainActive()+", rescue="+t.loadRescueActive()));
        boolean futureLeak=t.supportFuturesDone()>0||t.fullDemandFailures()>0;
        out.add(new CauseSnapshot("future-backlog","Chunk future lifecycle",futureLeak?"ATTENTION":"GOOD",0.0D,
                "targetFutures="+t.targetFutures()+", supportFutures="+t.supportFutures()+", doneStillTracked="+t.supportFuturesDone()+", demandFailures="+t.fullDemandFailures()+", demandCancellations="+t.fullDemandCancellations()));
        boolean auditFail=t.auditRepeatPending()>0||t.auditRepeatFailures()>0;
        out.add(new CauseSnapshot("physical-audit","Physical completion audit",auditFail?"ATTENTION":"GOOD",0.0D,
                "pending="+t.auditPending()+", repeatPending="+t.auditRepeatPending()+", rejects="+t.auditRejections()+", repairs="+t.auditRepairs()+", repeatFailures="+t.auditRepeatFailures()));
        return java.util.List.copyOf(out);
    }

    /** Read-only health surface for the stewardship scorecard. Never mutates recovery state. */
    public static MatrixSnapshot matrixSnapshot() {
        var causes=causeMatrix();
        if(!RUNNING.get())return new MatrixSnapshot("IDLE","watchdog-off",TICK_SEQUENCE.get(),0.0D,causes.get(0).detail(),causes);
        long now=System.nanoTime();boolean inTick=IN_TICK.get();long start=inTick?TICK_STARTED_NS.get():LAST_SERVER_PROGRESS_NS.get();double seconds=start<=0L?0.0D:Math.max(0.0D,(now-start)/1_000_000_000.0D);
        long attention=causes.stream().filter(c->c.state().equals("ATTENTION")).count();String state=attention>0?"ATTENTION":"GOOD";String phase=inTick?"inside-tick":"between-ticks/main-loop";
        String detail=attention==0?"All "+causes.size()+" cause-owned watchdog rows are currently clear.":attention+" cause-owned watchdog row(s) need attention: "+causes.stream().filter(c->c.state().equals("ATTENTION")).map(CauseSnapshot::id).collect(java.util.stream.Collectors.joining(", "));
        return new MatrixSnapshot(state,phase,TICK_SEQUENCE.get(),seconds,detail,causes);
    }


    public static void register() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            long now = System.nanoTime();
            TICK_SEQUENCE.incrementAndGet();
            TICK_STARTED_NS.set(now);
            LAST_SERVER_PROGRESS_NS.set(now);
            DUMPED_THIS_TICK.set(false);
            IN_TICK.set(true);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LAST_SERVER_PROGRESS_NS.set(System.nanoTime());
            IN_TICK.set(false);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(OceanCanvasStallWatchdog::startThread);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopThread());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopThread());
    }

    private static synchronized void startThread(MinecraftServer server) {
        activeServer = server;
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread t = new Thread(OceanCanvasStallWatchdog::watchLoop, "OceanCanvas-Stall-Watchdog");
        t.setDaemon(true);
        watcher = t;
        LAST_SERVER_PROGRESS_NS.set(System.nanoTime());
        t.start();
        OceanCanvas.LOGGER.info("(Ocean Canvas) v230.5 stall watchdog armed at 8s for in-tick and between-tick server-loop stalls (diagnostic only).");
    }

    private static synchronized void stopThread() {
        RUNNING.set(false);
        activeServer = null;
        IN_TICK.set(false);
        Thread t = watcher;
        watcher = null;
        if (t != null) t.interrupt();
    }

    private static void watchLoop() {
        while (RUNNING.get()) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ignored) {
                if (!RUNNING.get()) return;
            }
            if (DUMPED_THIS_TICK.get()) continue;
            // v228.5 closes the v228.4 blind spot: getChunkFuture invoked from an
            // off-thread caller can enqueue heavy getChunkFutureMainThread work that
            // Minecraft executes between Fabric START/END_SERVER_TICK callbacks. The
            // 32-second v228.4 freeze therefore produced a Can't-keep-up warning but
            // no Ocean Canvas watchdog dump because IN_TICK was false. While Pregen
            // owns live targets, lack of either START or END progress is a stall too.
            if (OceanCanvasTerrainOperationActivity.outstandingPregenTargets() <= 0) continue;
            long now = System.nanoTime();
            boolean inTick = IN_TICK.get();
            long start = inTick ? TICK_STARTED_NS.get() : LAST_SERVER_PROGRESS_NS.get();
            if (start == 0L || now - start < STALL_NS) continue;
            if (!DUMPED_THIS_TICK.compareAndSet(false, true)) continue;
            dump(now - start, TICK_SEQUENCE.get(), inTick ? "inside-tick" : "between-ticks/main-loop");
        }
    }

    private static void dump(long elapsedNs, long tickSequence, String phase) {
        double elapsedSeconds = elapsedNs / 1_000_000_000.0D;
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("\n================ OCEAN CANVAS 8s STALL WATCHDOG ================\n")
           .append("Server progress has been absent for ").append(String.format(java.util.Locale.ROOT, "%.3f", elapsedSeconds))
           .append("s (phase=").append(phase).append(", watchdog tick sequence ").append(tickSequence).append(").\n")
           .append("This is a DIAGNOSTIC SNAPSHOT ONLY; Ocean Canvas did not interrupt or mutate the tick.\n");
        try {
            var d = OceanCanvasTerrainRuntimeDiagnostics.pregenTicketSnapshot();
            out.append("Ocean Canvas ticket snapshot: self=").append(d.selfActive())
               .append(" (+").append(d.selfInstalls()).append("/-").append(d.selfReleases()).append(')')
               .append(", lane=").append(d.carveLaneActive())
               .append(", leases=").append(d.processingLeaseActive())
               .append(" (+").append(d.processingLeaseInstalls()).append("/-").append(d.processingLeaseReleases()).append(')')
               .append(", finalDrain=").append(d.finalDrainActive())
               .append(" (+").append(d.finalDrainInstalls()).append("/-").append(d.finalDrainReleases()).append(')')
               .append(", futures=target:").append(d.targetFutures()).append("/support:").append(d.supportFutures())
               .append(", fullDemand=req:").append(d.fullDemandRequests()).append("/done:").append(d.fullDemandCompletions()).append("/fail:").append(d.fullDemandFailures()).append("/cancel:").append(d.fullDemandCancellations())
               .append(", loadRescue=").append(d.loadRescueActive()).append(" (+").append(d.loadRescueRequests()).append("/ok=").append(d.loadRescueSuccesses()).append(')')
               .append(", audit=pending:").append(d.auditPending()).append("/repeatPending:").append(d.auditRepeatPending()).append("/reject:").append(d.auditRejections()).append("/repair:").append(d.auditRepairs()).append("/repeat:").append(d.auditRepeatFailures()).append("\n");
        } catch (Throwable t) {
            out.append("Ocean Canvas ticket snapshot unavailable: ").append(t).append("\n");
        }

        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] infos = bean.dumpAllThreads(true, true);
            for (ThreadInfo info : infos) appendThread(out, info);
        } catch (Throwable t) {
            out.append("ThreadMXBean dump failed: ").append(t).append("\n");
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                out.append('\n').append('"').append(thread.getName()).append('"')
                   .append(" id=").append(thread.getId()).append(" state=").append(thread.getState()).append('\n');
                for (StackTraceElement frame : entry.getValue()) out.append("\tat ").append(frame).append('\n');
            }
        }
        out.append("================ END OCEAN CANVAS STALL WATCHDOG ================\n");
        net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.captureHardTickStall(out.toString());
        OceanCanvas.LOGGER.error(out.toString());
    }

    private static void appendThread(StringBuilder out, ThreadInfo info) {
        if (info == null) return;
        out.append('\n').append('"').append(info.getThreadName()).append('"')
           .append(" Id=").append(info.getThreadId()).append(' ').append(info.getThreadState());
        if (info.getLockName() != null) out.append(" on ").append(info.getLockName());
        if (info.getLockOwnerName() != null) out.append(" owned by \"").append(info.getLockOwnerName())
                .append("\" Id=").append(info.getLockOwnerId());
        out.append('\n');
        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        for (int i = 0; i < stack.length; i++) {
            out.append("\tat ").append(stack[i]).append('\n');
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) out.append("\t- locked <")
                        .append(Integer.toHexString(monitor.getIdentityHashCode())).append("> (a ")
                        .append(monitor.getClassName()).append(")\n");
            }
        }
        LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            out.append("\tLocked ownable synchronizers: ").append(synchronizers.length).append('\n');
            for (LockInfo lock : synchronizers) out.append("\t- <")
                    .append(Integer.toHexString(lock.getIdentityHashCode())).append("> (a ")
                    .append(lock.getClassName()).append(")\n");
        }
    }
}
