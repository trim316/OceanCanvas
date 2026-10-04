package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.journal.CoreJournal;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * R1-47 regression: once a successful adapter acknowledgement is durably
 * journaled, a process restart must resume from the next stage rather than
 * dispatching the already-credited stage again.
 */
public final class DuplicateStageAcknowledgementSelfTest {
    private DuplicateStageAcknowledgementSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("DuplicateStageAcknowledgementSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-r1-47-");
        try {
            ChunkKey key = new ChunkKey(7, -11);
            CoreJournal journal = new CoreJournal(dir.resolve("journal.log"));
            Counts counts = new Counts();

            SingleChunkPipeline firstProcess = SingleChunkPipeline.open(journal, key);
            check(firstProcess.record().stage() == ChunkStage.DISCOVERED, "fresh stage");
            check(firstProcess.tick(new SuccessPorts(counts), 1000L), "load transition committed");
            check(counts.load == 1, "load dispatched once");
            check(firstProcess.record().stage() == ChunkStage.LOADED, "load credit durable in memory");

            // Simulate a hard process boundary by discarding the pipeline and ports.
            SingleChunkPipeline secondProcess = SingleChunkPipeline.open(new CoreJournal(dir.resolve("journal.log")), key);
            check(secondProcess.record().stage() == ChunkStage.LOADED, "restart replays durable load credit");
            check(secondProcess.tick(new SuccessPorts(counts), 2000L), "capture transition committed after restart");
            check(counts.load == 1, "restart did not duplicate successful load acknowledgement");
            check(counts.capture == 1, "restart dispatched exactly the next stage");
            check(secondProcess.record().stage() == ChunkStage.PREIMAGE_CAPTURED, "capture credit durable in memory");

            SingleChunkPipeline thirdProcess = SingleChunkPipeline.open(new CoreJournal(dir.resolve("journal.log")), key);
            check(thirdProcess.record().stage() == ChunkStage.PREIMAGE_CAPTURED, "second restart replays capture credit");
            check(thirdProcess.tick(new SuccessPorts(counts), 3000L), "author transition committed after second restart");
            check(counts.capture == 1, "second restart did not duplicate successful capture acknowledgement");
            check(counts.author == 1, "second restart dispatched author exactly once");

            CoreJournal.ReplayState replay = new CoreJournal(dir.resolve("journal.log")).replaySingleChunk(key);
            check(replay.record().stage() == ChunkStage.PHYSICAL_AUTHORED, "authoritative replay reaches expected stage");
            check(replay.nextSequence() == 3L, "exactly three durable acknowledgements exist");
            return 15;
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

    private static final class Counts {
        int load;
        int capture;
        int author;
    }

    private static final class SuccessPorts implements SingleChunkPorts {
        private final Counts counts;

        SuccessPorts(Counts counts) { this.counts = counts; }

        @Override public StageActionResult load(ChunkRecord record) { counts.load++; return ok("load"); }
        @Override public StageActionResult capturePreimage(ChunkRecord record) { counts.capture++; return ok("capture"); }
        @Override public StageActionResult authorPhysical(ChunkRecord record) { counts.author++; return ok("author"); }
        @Override public StageActionResult settlePhysical(ChunkRecord record) { return ok("settle"); }
        @Override public StageActionResult persist(ChunkRecord record) { return ok("persist"); }
        @Override public StageActionResult settleLighting(ChunkRecord record) { return ok("light"); }
        @Override public StageActionResult verify(ChunkRecord record) { return ok("verify"); }
        @Override public StageActionResult restore(ChunkRecord record) { return ok("restore"); }
        @Override public StageActionResult verifyRestore(ChunkRecord record) { return ok("verify-restore"); }
        @Override public StageActionResult release(ChunkRecord record) { return ok("release"); }

        private static StageActionResult ok(String evidence) {
            return StageActionResult.success(evidence);
        }
    }
}
