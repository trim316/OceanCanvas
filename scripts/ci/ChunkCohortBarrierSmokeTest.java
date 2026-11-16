package net.oceancanvas.mod.server;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
public final class ChunkCohortBarrierSmokeTest {
    public static void main(String[] args) {
        var cohort = new ChunkCohortBarrier(List.of("0,0", "0,1", "1,0", "1,1"));
        var saves = new AtomicInteger();
        for (String key : cohort.members()) cohort.markReady(key, true, true);
        cohort.flushOnce(saves::incrementAndGet);
        if (saves.get() != 1 || cohort.state() != ChunkCohortBarrier.State.DURABLE_UNVERIFIED)
            throw new AssertionError("single save barrier must not imply verified");
        for (String key : cohort.members()) cohort.markRestartVerified(key, true, true);
        if (cohort.state() != ChunkCohortBarrier.State.VERIFIED)
            throw new AssertionError("all physical members must verify");
        var failed = new ChunkCohortBarrier(List.of("0,0"));
        failed.markReady("0,0", true, true);
        failed.flushOnce(() -> {});
        failed.markRestartVerified("0,0", true, false);
        if (failed.state() != ChunkCohortBarrier.State.QUARANTINED)
            throw new AssertionError("mismatch must quarantine");
        var interrupted = new ChunkCohortBarrier(List.of("0,0"));
        interrupted.markReady("0,0", true, true);
        if (!interrupted.requiresRecoveryAfterCrash())
            throw new AssertionError("unflushed cohort must recover");
        var saveFailure = new ChunkCohortBarrier(List.of("2,2"));
        saveFailure.markReady("2,2", true, true);
        try {
            saveFailure.flushOnce(() -> { throw new IllegalStateException("simulated partial save"); });
            throw new AssertionError("save failure must propagate");
        } catch (IllegalStateException expected) {
            if (!"simulated partial save".equals(expected.getMessage())) throw expected;
        }
        if (saveFailure.state() != ChunkCohortBarrier.State.QUARANTINED)
            throw new AssertionError("partial save must quarantine");
        try {
            saveFailure.flushOnce(() -> {});
            throw new AssertionError("partial save must not retry without recovery");
        } catch (IllegalStateException expected) { }
        System.out.println("COHORT_BARRIER_SMOKE_PASS");
    }
}
