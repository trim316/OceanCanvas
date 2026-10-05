package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.journal.CoreJournal;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * R1-52 regression: prolonged physical/light settlement WAITING responses are
 * not durable stage credit. A hard process restart must replay the same stage,
 * re-enter its wait, and advance only after a real success acknowledgement.
 */
public final class SettlementWaitRestartSelfTest {
    private SettlementWaitRestartSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("SettlementWaitRestartSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-r1-52-");
        try {
            ChunkKey key = new ChunkKey(7, -11);
            Path journalPath = dir.resolve("transitions.journal");
            Counters counters = new Counters();
            WaitingPorts ports = new WaitingPorts(counters);
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            long now = 10L;

            check(pipeline.tick(ports, now++), "load commits");
            check(pipeline.tick(ports, now++), "capture commits");
            check(pipeline.tick(ports, now++), "author commits");
            check(pipeline.record().stage() == ChunkStage.PHYSICAL_AUTHORED,
                    "fixture reaches physical settlement stage");

            for (int i = 0; i < 4; i++) {
                check(!pipeline.tick(ports, now++), "physical settle wait cannot commit stage credit");
            }
            check(pipeline.record().stage() == ChunkStage.PHYSICAL_AUTHORED,
                    "physical wait leaves stage unchanged");
            check(new CoreJournal(journalPath).replaySingleChunk(key).nextSequence() == 3L,
                    "physical wait appends no authoritative journal record");

            // Hard process restart while the adapter's quiet-window timer was in memory.
            pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            check(pipeline.record().stage() == ChunkStage.PHYSICAL_AUTHORED,
                    "restart resumes physical settlement rather than granting credit");
            check(!pipeline.tick(new WaitingPorts(counters), now++),
                    "fresh process rearms physical settlement wait");
            check(counters.physicalSuccess == 0,
                    "no physical settlement success fabricated by restart");

            WaitingPorts physicalReady = new WaitingPorts(counters);
            physicalReady.physicalReady = true;
            check(pipeline.tick(physicalReady, now++), "real physical settle success commits");
            check(pipeline.record().stage() == ChunkStage.PHYSICAL_SETTLED,
                    "physical success advances exactly one stage");
            check(pipeline.tick(physicalReady, now++), "persist commits");
            check(pipeline.record().stage() == ChunkStage.PERSISTED,
                    "fixture reaches lighting settlement stage");

            for (int i = 0; i < 4; i++) {
                check(!pipeline.tick(physicalReady, now++), "lighting wait cannot commit stage credit");
            }
            check(pipeline.record().stage() == ChunkStage.PERSISTED,
                    "lighting wait leaves stage unchanged");
            check(new CoreJournal(journalPath).replaySingleChunk(key).nextSequence() == 5L,
                    "lighting wait appends no authoritative journal record");

            // Second hard process restart while lighting settle time is only in memory.
            pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            check(pipeline.record().stage() == ChunkStage.PERSISTED,
                    "restart resumes lighting settlement rather than granting credit");
            WaitingPorts afterLightRestart = new WaitingPorts(counters);
            afterLightRestart.physicalReady = true;
            check(!pipeline.tick(afterLightRestart, now++),
                    "fresh process rearms lighting settlement wait");
            check(counters.lightSuccess == 0,
                    "no lighting settlement success fabricated by restart");

            afterLightRestart.lightReady = true;
            check(pipeline.tick(afterLightRestart, now++), "real lighting settle success commits");
            check(pipeline.record().stage() == ChunkStage.LIGHTING_SETTLED,
                    "lighting success advances exactly one stage");
            check(new CoreJournal(journalPath).replaySingleChunk(key).nextSequence() == 6L,
                    "only six actual successes are journaled");

            return 24;
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

    private static final class Counters {
        int physicalWait;
        int physicalSuccess;
        int lightWait;
        int lightSuccess;
    }

    private static final class WaitingPorts implements SingleChunkPorts {
        private final Counters counters;
        boolean physicalReady;
        boolean lightReady;

        private WaitingPorts(Counters counters) {
            this.counters = counters;
        }

        private static StageActionResult ok(String evidence) {
            return StageActionResult.success(evidence);
        }

        @Override public StageActionResult load(ChunkRecord record) { return ok("load"); }
        @Override public StageActionResult capturePreimage(ChunkRecord record) { return ok("capture"); }
        @Override public StageActionResult authorPhysical(ChunkRecord record) { return ok("author"); }

        @Override public StageActionResult settlePhysical(ChunkRecord record) {
            if (!physicalReady) {
                counters.physicalWait++;
                return StageActionResult.waiting("physical quiet window still pending");
            }
            counters.physicalSuccess++;
            return ok("physical settlement verified");
        }

        @Override public StageActionResult persist(ChunkRecord record) { return ok("persist"); }

        @Override public StageActionResult settleLighting(ChunkRecord record) {
            if (!lightReady) {
                counters.lightWait++;
                return StageActionResult.waiting("lighting quiet window still pending");
            }
            counters.lightSuccess++;
            return ok("lighting settled");
        }

        @Override public StageActionResult verify(ChunkRecord record) { return ok("verify"); }
        @Override public StageActionResult restore(ChunkRecord record) { return ok("restore"); }
        @Override public StageActionResult verifyRestore(ChunkRecord record) { return ok("verify-restore"); }
        @Override public StageActionResult release(ChunkRecord record) { return ok("release"); }
    }
}
