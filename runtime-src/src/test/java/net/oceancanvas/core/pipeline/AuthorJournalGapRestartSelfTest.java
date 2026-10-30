package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.restore.AuthoringReplayPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Recovery regression for the physical-write / authoritative-journal gap.
 * Restart must replay authoring from PREIMAGE_CAPTURED, recognize only the
 * exact canonical target as an idempotent prior write, and never perform a
 * second destructive mutation merely because journal acknowledgement failed.
 */
public final class AuthorJournalGapRestartSelfTest {
    private AuthorJournalGapRestartSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("AuthorJournalGapRestartSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = runProcessDeathGap();
        checks += runJournalAppendFailureGap();
        return checks;
    }

    private static int runProcessDeathGap() throws Exception {
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
            deleteRecursively(dir);
        }
    }

    private static int runJournalAppendFailureGap() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-r1-319-");
        try {
            ChunkKey key = new ChunkKey(41, -7);
            Path journalPath = dir.resolve("transitions.journal");
            Path preservedJournal = dir.resolve("transitions.preserved");
            CrashGapPorts ports = new CrashGapPorts();
            ports.crashAfterWrite = false;
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            long now = 500L;

            check(pipeline.tick(ports, now++), "append-failure fixture load commits");
            check(pipeline.tick(ports, now++), "append-failure fixture capture commits");
            check(pipeline.record().stage() == ChunkStage.PREIMAGE_CAPTURED,
                    "append-failure fixture reaches preimage-captured stage");

            Files.move(journalPath, preservedJournal);
            Files.createDirectory(journalPath);
            boolean appendFailed = false;
            try {
                pipeline.tick(ports, now++);
            } catch (IOException expected) {
                appendFailed = true;
            }
            check(appendFailed, "successful physical action followed by journal append failure is surfaced");
            check(ports.physicalWritten && ports.destructiveWrites == 1,
                    "physical mutation completed exactly once before append failure");
            check(pipeline.record().stage() == ChunkStage.PREIMAGE_CAPTURED,
                    "in-memory authority does not advance when append fails");

            Files.delete(journalPath);
            Files.move(preservedJournal, journalPath);
            check(new CoreJournal(journalPath).replaySingleChunk(key).record().stage() == ChunkStage.PREIMAGE_CAPTURED,
                    "reopened authoritative journal remains at predecessor after append failure");

            pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            check(pipeline.tick(ports, now++), "reopen acknowledges already-canonical physical result");
            check(pipeline.record().stage() == ChunkStage.PHYSICAL_AUTHORED,
                    "reopen advances after exact canonical replay proof");
            check(ports.authorCalls == 2 && ports.destructiveWrites == 1,
                    "append failure plus reopen never performs a second destructive mutation");
            return 9;
        } finally {
            deleteRecursively(dir);
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

        private static StageActionResult ok(String evidence) { return StageActionResult.success(evidence); }

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

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }
}
