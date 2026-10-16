package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * R1-58 regression: a process may die after atomic preimage publication but
 * before PREIMAGE_CAPTURED is durably journaled. Restart must retain the exact
 * canonical backup, replay capture idempotently, and grant stage credit only
 * after the replay is acknowledged.
 */
public final class PreimageJournalGapRestartSelfTest {
    private PreimageJournalGapRestartSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("PreimageJournalGapRestartSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-r1-58-");
        try {
            ChunkKey key = new ChunkKey(-17, 31);
            String operation = "r1-58-preimage-gap";
            Path journalPath = dir.resolve("transitions.journal");
            Path preimagePath = dir.resolve("preimage.bin");
            int[] states = new int[256];
            for (int i = 0; i < states.length; i++) states[i] = 1000 + i;
            BlockStatePreimageStore.Preimage preimage =
                    new BlockStatePreimageStore.Preimage(operation, key, 0, 0, states);

            CrashGapPorts ports = new CrashGapPorts(preimagePath, preimage);
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            long now = 100L;

            check(pipeline.tick(ports, now++), "load commits");
            check(pipeline.record().stage() == ChunkStage.LOADED, "fixture reaches loaded stage");

            boolean crashed = false;
            try {
                pipeline.tick(ports, now++);
            } catch (SimulatedProcessDeath expected) {
                crashed = true;
            }
            check(crashed, "simulated process death occurs after canonical preimage publication");
            check(Files.exists(preimagePath), "canonical preimage exists before journal acknowledgement");
            byte[] originalBytes = Files.readAllBytes(preimagePath);
            String originalSha = BlockStatePreimageStore.sha256Hex(preimagePath);
            check(new CoreJournal(journalPath).replaySingleChunk(key).record().stage() == ChunkStage.LOADED,
                    "journal grants no PREIMAGE_CAPTURED credit across crash gap");
            check(new CoreJournal(journalPath).replaySingleChunk(key).nextSequence() == 1L,
                    "crash gap appends no capture transition");

            pipeline = SingleChunkPipeline.open(new CoreJournal(journalPath), key);
            ports.crashAfterPublish = false;
            check(pipeline.record().stage() == ChunkStage.LOADED,
                    "restart replays capture from durable predecessor");
            check(pipeline.tick(ports, now++), "idempotent preimage replay is acknowledged");
            check(pipeline.record().stage() == ChunkStage.PREIMAGE_CAPTURED,
                    "replay advances exactly once after canonical preimage proof");
            check(ports.captureCalls == 2, "capture adapter entered once before and once after restart");
            check(Arrays.equals(originalBytes, Files.readAllBytes(preimagePath)),
                    "restart preserves canonical preimage bytes exactly");
            check(originalSha.equals(BlockStatePreimageStore.sha256Hex(preimagePath)),
                    "restart preserves canonical preimage digest exactly");
            check(!Files.exists(preimagePath.resolveSibling(preimagePath.getFileName() + ".tmp")),
                    "idempotent replay creates no replacement staging file");
            check(new CoreJournal(journalPath).replaySingleChunk(key).nextSequence() == 2L,
                    "only acknowledged capture receives durable credit");

            BlockStatePreimageStore.Preimage reopened =
                    BlockStatePreimageStore.readVerified(preimagePath, operation, key);
            check(reopened.count() == states.length, "reopened preimage retains exact state count");
            check(reopened.stateIdAt(255) == states[255], "reopened preimage retains final state identity");
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
        private SimulatedProcessDeath() {
            super("simulated crash after preimage publication before journal append");
        }
    }

    private static final class CrashGapPorts implements SingleChunkPorts {
        private final Path preimagePath;
        private final BlockStatePreimageStore.Preimage preimage;
        boolean crashAfterPublish = true;
        int captureCalls;

        private CrashGapPorts(Path preimagePath, BlockStatePreimageStore.Preimage preimage) {
            this.preimagePath = preimagePath;
            this.preimage = preimage;
        }

        private static StageActionResult ok(String evidence) {
            return StageActionResult.success(evidence);
        }

        @Override public StageActionResult load(ChunkRecord record) { return ok("load"); }

        @Override public StageActionResult capturePreimage(ChunkRecord record) {
            captureCalls++;
            try {
                BlockStatePreimageStore.writeExact(preimagePath, preimage);
            } catch (Exception e) {
                return StageActionResult.failure("preimage replay failed: " + e.getMessage());
            }
            if (crashAfterPublish) throw new SimulatedProcessDeath();
            return ok("exact canonical preimage retained; idempotent replay");
        }

        @Override public StageActionResult authorPhysical(ChunkRecord record) { return ok("author"); }
        @Override public StageActionResult settlePhysical(ChunkRecord record) { return ok("settle-physical"); }
        @Override public StageActionResult persist(ChunkRecord record) { return ok("persist"); }
        @Override public StageActionResult settleLighting(ChunkRecord record) { return ok("settle-lighting"); }
        @Override public StageActionResult verify(ChunkRecord record) { return ok("verify"); }
        @Override public StageActionResult restore(ChunkRecord record) { return ok("restore"); }
        @Override public StageActionResult verifyRestore(ChunkRecord record) { return ok("verify-restore"); }
        @Override public StageActionResult release(ChunkRecord record) { return ok("release"); }
    }
}
