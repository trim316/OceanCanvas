package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.restore.AuthoringReplayPolicy;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * R1-39 regression: a process may die after the physical write is complete but
 * before PHYSICAL_AUTHORED is durably appended. Restart must replay authoring
 * from PREIMAGE_CAPTURED, recognize only the exact canonical target as an
 * idempotent prior write, and refuse ambiguous drift instead of granting stage
 * credit.
 */
public final class AuthorJournalGapRestartSelfTest {
    private AuthorJournalGapRestartSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("AuthorJournalGapRestartSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-r1-39-");
        try {
            ChunkKey key = new ChunkKey(-13, 29);
            Path journalPath = dir.resolve("transitions.journal");
            CrashGapPorts ports = new CrashGapPorts();
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            long now = 100L;

            check(pipeline.tick(ports, now++), "load commits");
            check(pipeline.tick(ports, now++), "capture commits");
            check(pipeline.record().stage() == ChunkStage.PREIMAGE_CAPTURED,
                    "fixture reaches preimage-captured stage");

            boolean crashed = false;
            try {
                pipeline.tick(ports, now++);
            } catch (SimulatedProcessDeath expected) {
                crashed = true;
            }
            check(crashed, "simulated process death occurs after physical write");
            check(ports.physicalWritten, "physical target exists before journal acknowledgement");
            check(new CoreJournal(journalPath).replaySingleChunk(key).record().stage() == ChunkStage.PREIMAGE_CAPTURED,
                    "journal grants no PHYSICAL_AUTHORED credit across crash gap");
            check(new CoreJournal(journalPath).replaySingleChunk(key).nextSequence() == 2L,
                    "crash gap appends no authoritative transition");

            pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            check(pipeline.record().stage() == ChunkStage.PREIMAGE_CAPTURED,
                    "restart replays authoring from durable predecessor");
            ports.crashAfterWrite = false;
            check(pipeline.tick(ports, now++), "exact canonical prior write is acknowledged on replay");
            check(pipeline.record().stage() == ChunkStage.PHYSICAL_AUTHORED,
                    "replay advances exactly once after canonical proof");
            check(ports.authorCalls == 2, "authoring adapter was entered once before and once after restart");
            check(ports.destructiveWrites == 1, "replay does not perform a second destructive write");
            check(new CoreJournal(journalPath).replaySingleChunk(key).nextSequence() == 3L,
                    "only acknowledged authoring receives durable credit");

            check(AuthoringReplayPolicy.classify(true, false, false, false, true)
                            == AuthoringReplayPolicy.Decision.ALREADY_CANONICAL,
                    "backed exact canonical target is the sole completed-write replay form");
            check(AuthoringReplayPolicy.classify(true, false, true, false, false)
                            == AuthoringReplayPolicy.Decision.REFUSE,
                    "entity-declaring drift without live entity is refused");
            check(AuthoringReplayPolicy.classify(true, false, false, true, false)
                            == AuthoringReplayPolicy.Decision.REFUSE,
                    "unexpected live entity drift is refused");
            check(AuthoringReplayPolicy.classify(true, false, false, false, false)
                            == AuthoringReplayPolicy.Decision.REFUSE,
                    "noncanonical entity-free drift is refused");

            return 16;
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class SimulatedProcessDeath extends RuntimeException {
        private SimulatedProcessDeath() { super("simulated crash after physical write before journal append"); }
    }

    private static final class CrashGapPorts implements SingleChunkPorts {
        boolean physicalWritten;
        boolean crashAfterWrite = true;
        int authorCalls;
        int destructiveWrites;

        private static StageActionResult ok(String evidence) {
            return StageActionResult.success(evidence);
        }

        @Override public StageActionResult load(ChunkRecord record) { return ok("load"); }
        @Override public StageActionResult capturePreimage(ChunkRecord record) { return ok("capture"); }

        @Override public StageActionResult authorPhysical(ChunkRecord record) {
            authorCalls++;
            if (!physicalWritten) {
                physicalWritten = true;
                destructiveWrites++;
                if (crashAfterWrite) throw new SimulatedProcessDeath();
            }
            AuthoringReplayPolicy.Decision replay = AuthoringReplayPolicy.classify(
                    true, false, false, false, physicalWritten);
            if (replay != AuthoringReplayPolicy.Decision.ALREADY_CANONICAL) {
                return StageActionResult.failure("ambiguous physical replay state");
            }
            return ok("exact canonical target already present; idempotent replay");
        }

        @Override public StageActionResult settlePhysical(ChunkRecord record) { return ok("settle-physical"); }
        @Override public StageActionResult persist(ChunkRecord record) { return ok("persist"); }
        @Override public StageActionResult settleLighting(ChunkRecord record) { return ok("settle-lighting"); }
        @Override public StageActionResult verify(ChunkRecord record) { return ok("verify"); }
        @Override public StageActionResult restore(ChunkRecord record) { return ok("restore"); }
        @Override public StageActionResult verifyRestore(ChunkRecord record) { return ok("verify-restore"); }
        @Override public StageActionResult release(ChunkRecord record) { return ok("release"); }
    }
}
