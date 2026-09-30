package net.oceancanvas.core;

import net.oceancanvas.core.acceptance.AcceptanceHarness;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.expansion.SequentialChunkCoordinator;
import net.oceancanvas.core.expansion.TwoChunkCanaryPlan;
import net.oceancanvas.core.geometry.OceanFloorProfile;
import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.journal.JournalEntry;
import net.oceancanvas.core.pipeline.*;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.runtime.ResidencyReacquirePolicy;
import net.oceancanvas.core.restore.BlockStatePreimageStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class CoreSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testCanvasGeometry();
        testRowMajorOrder();
        testFloorProfile();
        testDestructiveConfigGate();
        testLifecycleGuards();
        testJournalDurabilityContract();
        testRestartAtEverySingleChunkStage();
        testWaitingAndFailureSemantics();
        testManifestFailClosed();
        testReceiptIntegrity();
        testAcceptanceRestartGate();
        testResidencyReacquirePolicy();
        testBlockStatePreimageStore();
        testTwoChunkCanaryPlan();
        testSequentialCanaryCoordinator();
        testSequentialCanaryFailureStopsExpansion();
        System.out.println("OceanCanvas Core self-test PASS (" + checks + " checks)");
    }

    private static void testCanvasGeometry() {
        var bounds = OceanCanvasRegionGeometry.chunkBoundsForBlocks(-10_000, -10_000, 9_999, 9_999);
        eq(-625, bounds.minX(), "min chunk x");
        eq(624, bounds.maxX(), "max chunk x");
        eq(-625, bounds.minZ(), "min chunk z");
        eq(624, bounds.maxZ(), "max chunk z");
        eq(1_562_500L, bounds.count(), "20k canvas chunk count");
        check(bounds.contains(0, 0), "origin inside");
        check(!bounds.contains(625, 0), "outside east rejected");
    }

    private static void testRowMajorOrder() {
        var bounds = new OceanCanvasRegionGeometry.ChunkBounds(-1, 1, -1, 0);
        RowMajorCursor c = new RowMajorCursor(bounds);
        List<ChunkKey> expected = List.of(
                new ChunkKey(-1, -1), new ChunkKey(0, -1), new ChunkKey(1, -1),
                new ChunkKey(-1, 0), new ChunkKey(0, 0), new ChunkKey(1, 0));
        for (ChunkKey key : expected) eq(key, c.take(), "row-major key");
        check(!c.hasNext(), "row-major exhausted");
        eq(0L, c.remaining(), "row-major remaining");
    }


    private static void testFloorProfile() {
        eq(0, OceanFloorProfile.floorOffset(123, -456, 0), "zero floor variation");
        for (int x = -200; x <= 200; x += 37) for (int z = -200; z <= 200; z += 43) {
            int v = OceanFloorProfile.floorOffset(x, z, 5);
            check(v >= -5 && v <= 5, "floor offset bounded");
            eq(v, OceanFloorProfile.floorOffset(x, z, 5), "floor profile deterministic");
        }
    }


    private static void testDestructiveConfigGate() {
        CoreConfig d = CoreConfig.defaults();
        check(!d.singleChunkAuthorityEnabled(), "default config is SAFE_HOLD");
        CoreConfig armed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0, 62, 25, 5, true,
                true, 12, -34, "ERASE_CHUNK_12_-34", 256, 1024, 3000, 40, 40, false);
        check(armed.singleChunkAuthorityEnabled(), "target-specific confirmation arms one-chunk authority");
        CoreConfig wrong = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0, 62, 25, 5, true,
                true, 12, -34, "ERASE_CHUNK_12_-33", 256, 1024, 3000, 40, 40, false);
        check(!wrong.singleChunkAuthorityEnabled(), "wrong target confirmation fails closed");
        eq("ERASE_CHUNK_12_-34", armed.expectedSingleChunkConfirm(), "confirmation token deterministic");
    }

    private static void testLifecycleGuards() {
        ChunkRecord r = ChunkRecord.discovered(new ChunkKey(4, -7));
        r = r.advance(ChunkStage.LOADED, "resident");
        r = r.advance(ChunkStage.PREIMAGE_CAPTURED, "preimage-durable");
        r = r.advance(ChunkStage.PHYSICAL_AUTHORED, "mutation-or-noop-proof");
        r = r.advance(ChunkStage.PHYSICAL_SETTLED, "gravity-fluid-settle-proof");
        r = r.advance(ChunkStage.PERSISTED, "durable-save-proof");
        r = r.advance(ChunkStage.LIGHTING_SETTLED, "authoritative-light-settle");
        r = r.advance(ChunkStage.VERIFIED, "strict-server-verification");
        r = r.advance(ChunkStage.RESTORED, "preimage-restored");
        r = r.advance(ChunkStage.RESTORE_VERIFIED, "restore-verified");
        r = r.advance(ChunkStage.COMPLETE, "complete");
        eq(ChunkStage.COMPLETE, r.stage(), "full lifecycle completes");

        boolean rejected = false;
        try {
            ChunkRecord.discovered(new ChunkKey(0, 0))
                    .advance(ChunkStage.LOADED, "loaded")
                    .advance(ChunkStage.LIGHTING_SETTLED, "illegal shortcut");
        } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "lighting cannot bypass physical+persistence stages");
    }

    private static void testJournalDurabilityContract() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-journal-test");
        Path file = dir.resolve("core.journal");
        CoreJournal journal = new CoreJournal(file);
        journal.append(new JournalEntry(0, 1_000, new ChunkKey(1, 2), ChunkStage.DISCOVERED, ChunkStage.LOADED, 1, 1, "load"));
        journal.append(new JournalEntry(1, 2_000, new ChunkKey(1, 2), ChunkStage.LOADED, ChunkStage.PREIMAGE_CAPTURED, 1, 2, "preimage"));
        journal.append(new JournalEntry(2, 3_000, new ChunkKey(1, 2), ChunkStage.PREIMAGE_CAPTURED, ChunkStage.PHYSICAL_AUTHORED, 1, 3, "author"));
        List<JournalEntry> entries = journal.readVerified();
        eq(3, entries.size(), "journal entry count");
        eq(2L, entries.get(2).sequence(), "journal sequence");
        eq(ChunkStage.PHYSICAL_AUTHORED, journal.replaySingleChunk(new ChunkKey(1, 2)).record().stage(), "journal replay stage");

        Files.writeString(file, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean rejected = false;
        try { journal.readVerified(); }
        catch (Exception expected) { rejected = true; }
        check(rejected, "journal corruption fails closed");
        // Crash-torn tail must not silently replay as a valid transition.
        Path tornFile = dir.resolve("torn.journal");
        CoreJournal tornJournal = new CoreJournal(tornFile);
        tornJournal.append(new JournalEntry(0, 1_000,
                new ChunkKey(1, 2), ChunkStage.DISCOVERED, ChunkStage.LOADED, 1, 1, "load"));
        Files.writeString(tornFile, "1\\t2000\\t1", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        boolean tornRejected = false;
        try { tornJournal.readVerified(); }
        catch (java.io.IOException expected) { tornRejected = true; }
        check(tornRejected, "torn journal tail must fail closed");
        // A valid CRC is not enough if the final append lost its line
        // terminator: no incomplete record may obtain durable credit.
        Path missingTerminatorFile = dir.resolve("unterminated.journal");
        CoreJournal missingTerminator = new CoreJournal(missingTerminatorFile);
        missingTerminator.append(new JournalEntry(0, 1_000,
                new ChunkKey(1, 2), ChunkStage.DISCOVERED, ChunkStage.LOADED, 1, 1, "load"));
        byte[] completeBytes = Files.readAllBytes(missingTerminatorFile);
        Files.write(missingTerminatorFile,
                java.util.Arrays.copyOf(completeBytes, completeBytes.length - 1));
        boolean unterminatedRejected = false;
        try { missingTerminator.readVerified(); }
        catch (java.io.IOException expected) { unterminatedRejected = true; }
        check(unterminatedRejected, "unterminated checksum-valid journal tail fails closed");
        deleteTree(dir);
    }

    private static void testRestartAtEverySingleChunkStage() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-pipeline-restart");
        CoreJournal journal = new CoreJournal(dir.resolve("transitions.journal"));
        ChunkKey key = new ChunkKey(12, -34);
        ChunkStage[] expectedBefore = {
                ChunkStage.DISCOVERED, ChunkStage.LOADED, ChunkStage.PREIMAGE_CAPTURED,
                ChunkStage.PHYSICAL_AUTHORED, ChunkStage.PHYSICAL_SETTLED, ChunkStage.PERSISTED,
                ChunkStage.LIGHTING_SETTLED, ChunkStage.VERIFIED, ChunkStage.RESTORED,
                ChunkStage.RESTORE_VERIFIED
        };
        CountingPorts ports = new CountingPorts();
        for (int i = 0; i < expectedBefore.length; i++) {
            SingleChunkPipeline pipeline = SingleChunkPipeline.open(journal, key); // simulate process restart every stage
            eq(expectedBefore[i], pipeline.record().stage(), "restart resumes exact stage " + i);
            check(pipeline.tick(ports, 10_000L + i), "stage " + expectedBefore[i] + " durably advances");
        }
        SingleChunkPipeline complete = SingleChunkPipeline.open(journal, key);
        eq(ChunkStage.COMPLETE, complete.record().stage(), "restart after release is complete");
        check(complete.terminal(), "complete pipeline terminal");
        eq(1, ports.loads, "load called once");
        eq(1, ports.preimages, "preimage called once");
        eq(1, ports.authors, "author called once");
        eq(1, ports.settles, "physical settle called once");
        eq(1, ports.persists, "persist called once");
        eq(1, ports.lights, "light settle called once");
        eq(1, ports.verifies, "verify called once");
        eq(1, ports.restores, "restore called once");
        eq(1, ports.restoreVerifies, "restore verify called once");
        eq(1, ports.releases, "release called once");
        eq(10, journal.readVerified().size(), "ten durable transitions");
        deleteTree(dir);
    }

    private static void testWaitingAndFailureSemantics() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-pipeline-wait-fail");
        CoreJournal journal = new CoreJournal(dir.resolve("transitions.journal"));
        ChunkKey key = new ChunkKey(2, 3);
        SingleChunkPipeline p = SingleChunkPipeline.open(journal, key);
        SingleChunkPorts waiting = new DelegatingPorts() {
            @Override public StageActionResult load(ChunkRecord record) { return StageActionResult.waiting("future pending"); }
        };
        check(!p.tick(waiting, 1), "WAITING does not advance");
        eq(0, journal.readVerified().size(), "WAITING writes no journal transition");

        SingleChunkPorts failing = new DelegatingPorts() {
            @Override public StageActionResult load(ChunkRecord record) { return StageActionResult.failure("load refused"); }
        };
        check(p.tick(failing, 2), "failure is durably recorded");
        eq(ChunkStage.FAILED, p.record().stage(), "failure terminal stage");
        eq(ChunkStage.FAILED, SingleChunkPipeline.open(journal, key).record().stage(), "failure survives restart");
        deleteTree(dir);
    }

    private static void testManifestFailClosed() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-manifest");
        Path file = dir.resolve("operation.properties");
        SingleChunkOperationSpec a = new SingleChunkOperationSpec(1, new ChunkKey(5, 6), 20_000, 0, 0, 62, 25, 5);
        OperationManifestStore.ensureExact(file, a);
        OperationManifestStore.ensureExact(file, a);
        check(Files.size(file) > 0, "manifest created");
        boolean rejected = false;
        try { OperationManifestStore.ensureExact(file, new SingleChunkOperationSpec(1, new ChunkKey(5, 6), 20_000, 0, 0, 62, 24, 5)); }
        catch (Exception expected) { rejected = true; }
        check(rejected, "geometry/config drift rejects resume");
        check(a.operationId().startsWith("single-chunk-5-6-"), "operation id stable prefix");
        deleteTree(dir);
    }

    private static void testReceiptIntegrity() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-receipts");
        Path file = dir.resolve("receipts.log");
        RuntimeReceiptLog log = new RuntimeReceiptLog(file);
        ChunkKey key = new ChunkKey(-8, 9);
        log.append(ReceiptKind.TICKET_INSTALLED, key, "forced radius=0");
        log.append(ReceiptKind.TICKET_RELEASED, key, "done");
        eq(2, log.readVerified().size(), "receipt count");
        Files.writeString(file, "bad", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean rejected = false;
        try { log.readVerified(); } catch (Exception expected) { rejected = true; }
        check(rejected, "receipt corruption fails closed");
        deleteTree(dir);
    }

    private static void testAcceptanceRestartGate() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-acceptance");
        Path file = dir.resolve("acceptance-state.properties");
        ChunkKey key = new ChunkKey(12, -34);
        String operation = "single-chunk-test";

        AcceptanceHarness.OpenResult first = AcceptanceHarness.open(file, operation, key, ChunkStage.DISCOVERED);
        check(!first.restartVerified(), "first acceptance open is not a restart proof");
        check(!first.harness().shouldHold(), "first acceptance session can advance");
        first.harness().holdAfterTransition(ChunkStage.LOADED);
        check(first.harness().shouldHold(), "transition arms restart hold");

        AcceptanceHarness.OpenResult second = AcceptanceHarness.open(file, operation, key, ChunkStage.LOADED);
        check(second.restartVerified(), "restart at held stage is verified");
        eq(1, second.harness().verifiedRestarts(), "restart count increments");
        check(!second.harness().shouldHold(), "verified restart clears hold");
        second.harness().holdAfterTransition(ChunkStage.COMPLETE);

        AcceptanceHarness.OpenResult finalOpen = AcceptanceHarness.open(file, operation, key, ChunkStage.COMPLETE);
        check(finalOpen.restartVerified(), "final complete restart verified");
        check(finalOpen.finalRestartVerifiedNow(), "final restart marks campaign pass");
        check(finalOpen.harness().finalRestartVerified(), "final pass persists");
        eq(2, finalOpen.harness().verifiedRestarts(), "final restart count");

        boolean mismatchRejected = false;
        try { AcceptanceHarness.open(file, operation + "-different", key, ChunkStage.COMPLETE); }
        catch (Exception expected) { mismatchRejected = true; }
        check(mismatchRejected, "acceptance harness identity mismatch fails closed");

        // An interrupted next write leaves the previous canonical acceptance
        // file intact. Startup must not mistake a torn temp for committed state.
        Path interrupted = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(interrupted, "schemaVersion=1\noperationId=forged\n",
                StandardCharsets.UTF_8);
        AcceptanceHarness.OpenResult survived = AcceptanceHarness.open(file, operation, key, ChunkStage.COMPLETE);
        check(survived.harness().finalRestartVerified(),
                "orphan interrupted temp cannot overwrite completed restart evidence");
        eq(2, survived.harness().verifiedRestarts(), "restart evidence survives torn temporary file");
        check(!Files.exists(interrupted),
                "successful atomic acceptance replacement consumes stale temporary file");

        // Canonical corruption must fail closed, not silently start a new
        // campaign whose counters might seem plausible.
        Files.writeString(file, "schemaVersion=1\nchunkX=12\n",
                StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        boolean tornRejected = false;
        try { AcceptanceHarness.open(file, operation, key, ChunkStage.COMPLETE); }
        catch (java.io.IOException expected) { tornRejected = true; }
        check(tornRejected, "torn canonical acceptance state fails closed");
        deleteTree(dir);
    }



    private static void testResidencyReacquirePolicy() {
        ResidencyReacquirePolicy policy = new ResidencyReacquirePolicy(2, 40, 20);

        var first = policy.onStaleFuture(100);
        eq(ResidencyReacquirePolicy.Action.GRACE, first.action(), "first stale future enters grace");
        eq(40L, first.graceRemainingTicks(), "full grace window begins");
        eq(0, policy.attempts(), "grace does not consume retry");

        var during = policy.onStaleFuture(120);
        eq(ResidencyReacquirePolicy.Action.GRACE, during.action(), "stale future stays in grace");
        eq(20L, during.graceRemainingTicks(), "grace counts down deterministically");
        eq(0, policy.attempts(), "grace still consumes no retry");

        var retry1 = policy.onStaleFuture(140);
        eq(ResidencyReacquirePolicy.Action.RETRY, retry1.action(), "expired grace consumes first retry");
        eq(1, retry1.attempt(), "first retry number");
        eq(1L, retry1.retryDelayTicks(), "first retry delay");
        check(policy.retryBackoffActive(140), "retry backoff active immediately");
        check(!policy.retryBackoffActive(141), "retry backoff expires exactly");

        policy.futureRequested();
        var secondGrace = policy.onStaleFuture(141);
        eq(ResidencyReacquirePolicy.Action.GRACE, secondGrace.action(), "new future receives a new grace window");
        var retry2 = policy.onStaleFuture(181);
        eq(ResidencyReacquirePolicy.Action.RETRY, retry2.action(), "second expired grace consumes second retry");
        eq(2, retry2.attempt(), "second retry number");
        eq(2L, retry2.retryDelayTicks(), "second retry exponential delay");

        policy.futureRequested();
        policy.onStaleFuture(183);
        var exhausted = policy.onStaleFuture(223);
        eq(ResidencyReacquirePolicy.Action.FAIL, exhausted.action(), "retry budget exhausts fail-closed");
        eq(2, policy.attempts(), "failure does not invent another retry");

        policy.reset();
        eq(0, policy.attempts(), "success/release reset clears attempts");
        check(!policy.retryBackoffActive(Long.MAX_VALUE), "reset clears retry backoff");

        ResidencyReacquirePolicy saturated = new ResidencyReacquirePolicy(1, 40, 20);
        var nearMax = saturated.onStaleFuture(Long.MAX_VALUE - 10);
        eq(ResidencyReacquirePolicy.Action.GRACE, nearMax.action(), "grace supports near-overflow game ticks");
        eq(10L, nearMax.graceRemainingTicks(), "grace saturates instead of overflowing");
    }


    private static void testBlockStatePreimageStore() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-preimage");
        Path file = dir.resolve("preimage.bin");
        ChunkKey key = new ChunkKey(3, -4);
        int minY = -2, maxY = 1;
        int[] ids = new int[256 * (maxY - minY + 1)];
        for (int i = 0; i < ids.length; i++) ids[i] = (i * 31) ^ (i >>> 2);

        BlockStatePreimageStore.Preimage original =
                new BlockStatePreimageStore.Preimage("op-A", key, minY, maxY, ids);
        BlockStatePreimageStore.writeExact(file, original);
        var loaded = BlockStatePreimageStore.readVerified(file, "op-A", key);
        eq(key, loaded.chunk(), "preimage chunk round-trip");
        eq(minY, loaded.minY(), "preimage minY round-trip");
        eq(maxY, loaded.maxY(), "preimage maxY round-trip");
        eq(ids.length, loaded.count(), "preimage count round-trip");
        for (int i = 0; i < ids.length; i += 113) {
            eq(ids[i], loaded.stateIdAt(i), "preimage state id round-trip " + i);
        }

        byte[] tampered = Files.readAllBytes(file);
        tampered[tampered.length / 2] ^= 0x01;
        Files.write(file, tampered, StandardOpenOption.TRUNCATE_EXISTING);
        boolean checksumRejected = false;
        try { BlockStatePreimageStore.readVerified(file, "op-A", key); }
        catch (Exception expected) { checksumRejected = true; }
        check(checksumRejected, "preimage corruption fails closed");

        BlockStatePreimageStore.writeExact(file, original);
        boolean operationRejected = false;
        try { BlockStatePreimageStore.readVerified(file, "op-B", key); }
        catch (Exception expected) { operationRejected = true; }
        check(operationRejected, "preimage operation identity mismatch fails closed");

        boolean identityRejected = false;
        try { BlockStatePreimageStore.readVerified(file, "op-A", new ChunkKey(4, -4)); }
        catch (Exception expected) { identityRejected = true; }
        check(identityRejected, "preimage chunk identity mismatch fails closed");

        // Valid SHA-256 is not enough: malformed dimensions/count must be
        // rejected before allocating a potentially gigabyte-scale int array.
        var oversizedBytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(oversizedBytes)) {
            out.writeInt(0x4F435031);
            out.writeInt(2);
            byte[] op = "op-A".getBytes(StandardCharsets.UTF_8);
            out.writeInt(op.length);
            out.write(op);
            out.writeInt(key.x());
            out.writeInt(key.z());
            out.writeInt(0);
            out.writeInt(4_194_303);
            out.writeInt(1_073_741_824); // times four would wrap to zero
        }
        byte[] malformedPayload = oversizedBytes.toByteArray();
        byte[] malformedDigest = java.security.MessageDigest.getInstance("SHA-256").digest(malformedPayload);
        var corrupt = new java.io.ByteArrayOutputStream();
        corrupt.write(malformedPayload);
        corrupt.write(malformedDigest);
        Files.write(file, corrupt.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
        boolean rejectedOverflow = false;
        try { BlockStatePreimageStore.readVerified(file, "op-A", key); }
        catch (java.io.IOException expected) {
            rejectedOverflow = expected.getMessage().contains("payload length overflow");
        }
        check(rejectedOverflow, "preimage byte count overflow rejects before allocation");
        deleteTree(dir);
    }

    private static void testTwoChunkCanaryPlan() {
        TwoChunkCanaryPlan plan = TwoChunkCanaryPlan.eastOf(new ChunkKey(0, 0));
        eq(List.of(new ChunkKey(0, 0), new ChunkKey(1, 0)), plan.orderedChunks(), "two-chunk canary deterministic east order");
        boolean duplicateRejected = false;
        try { new TwoChunkCanaryPlan(new ChunkKey(0, 0), new ChunkKey(0, 0)); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        check(duplicateRejected, "two-chunk canary rejects duplicate target");
        boolean diagonalRejected = false;
        try { new TwoChunkCanaryPlan(new ChunkKey(0, 0), new ChunkKey(1, 1)); }
        catch (IllegalArgumentException expected) { diagonalRejected = true; }
        check(diagonalRejected, "two-chunk canary rejects non-edge adjacency");
    }

    private static void testSequentialCanaryCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-two-chunk");
        TwoChunkCanaryPlan plan = TwoChunkCanaryPlan.eastOf(new ChunkKey(0, 0));
        java.util.Map<ChunkKey, CountingPorts> ports = new java.util.HashMap<>();
        SequentialChunkCoordinator.PipelineOpener opener = key ->
                SingleChunkPipeline.open(new CoreJournal(dir.resolve(key.x() + "_" + key.z() + ".journal")), key);
        SequentialChunkCoordinator coordinator = new SequentialChunkCoordinator(plan.orderedChunks(), opener, key -> {
            CountingPorts p = ports.computeIfAbsent(key, ignored -> new CountingPorts());
            return p;
        });

        for (int i = 0; i < 10; i++) {
            var snap = coordinator.snapshot();
            eq(new ChunkKey(0, 0), snap.activeChunk(), "first canary remains sole active chunk before completion " + i);
            eq(0, snap.completeCount(), "second canary blocked before first complete " + i);
            check(coordinator.tick(1000 + i), "first canary advances transition " + i);
        }
        var second = coordinator.snapshot();
        eq(1, second.completeCount(), "first canary complete before second opens");
        eq(new ChunkKey(1, 0), second.activeChunk(), "second canary activates only after first complete");
        CountingPorts firstPorts = ports.get(new ChunkKey(0, 0));
        eq(1, firstPorts.loads, "first canary load exactly once");
        eq(1, firstPorts.releases, "first canary release exactly once");

        for (int i = 0; i < 10; i++) check(coordinator.tick(2000 + i), "second canary advances transition " + i);
        var done = coordinator.snapshot();
        check(done.complete(), "two-chunk coordinator complete");
        eq(2, done.completeCount(), "both canary chunks complete");
        check(!coordinator.tick(3000), "complete coordinator is inert");
        deleteTree(dir);
    }

    private static void testSequentialCanaryFailureStopsExpansion() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-two-chunk-fail");
        TwoChunkCanaryPlan plan = TwoChunkCanaryPlan.eastOf(new ChunkKey(5, 5));
        SequentialChunkCoordinator.PipelineOpener opener = key ->
                SingleChunkPipeline.open(new CoreJournal(dir.resolve(key.x() + "_" + key.z() + ".journal")), key);
        SequentialChunkCoordinator coordinator = new SequentialChunkCoordinator(plan.orderedChunks(), opener, key -> new DelegatingPorts() {
            @Override public StageActionResult load(ChunkRecord record) {
                return record.chunk().equals(plan.first()) ? StageActionResult.failure("canary stop") : StageActionResult.success("unexpected");
            }
        });
        check(coordinator.tick(1), "canary failure durably records");
        var failed = coordinator.snapshot();
        check(failed.failed(), "coordinator fails closed on first chunk failure");
        eq(plan.first(), failed.activeChunk(), "failed chunk remains authoritative stop point");
        check(!coordinator.tick(2), "failed coordinator performs no later work");
        eq(ChunkStage.DISCOVERED, opener.open(plan.second()).record().stage(), "second chunk remains untouched after first failure");
        deleteTree(dir);
    }

    private static class DelegatingPorts implements SingleChunkPorts {
        public StageActionResult load(ChunkRecord record) { return StageActionResult.success("load"); }
        public StageActionResult capturePreimage(ChunkRecord record) { return StageActionResult.success("preimage"); }
        public StageActionResult authorPhysical(ChunkRecord record) { return StageActionResult.success("author"); }
        public StageActionResult settlePhysical(ChunkRecord record) { return StageActionResult.success("settle"); }
        public StageActionResult persist(ChunkRecord record) { return StageActionResult.success("persist"); }
        public StageActionResult settleLighting(ChunkRecord record) { return StageActionResult.success("light"); }
        public StageActionResult verify(ChunkRecord record) { return StageActionResult.success("verify"); }
        public StageActionResult restore(ChunkRecord record) { return StageActionResult.success("restore"); }
        public StageActionResult verifyRestore(ChunkRecord record) { return StageActionResult.success("restore-verify"); }
        public StageActionResult release(ChunkRecord record) { return StageActionResult.success("release"); }
    }

    private static final class CountingPorts extends DelegatingPorts {
        int loads, preimages, authors, settles, persists, lights, verifies, restores, restoreVerifies, releases;
        @Override public StageActionResult load(ChunkRecord r) { loads++; return super.load(r); }
        @Override public StageActionResult capturePreimage(ChunkRecord r) { preimages++; return super.capturePreimage(r); }
        @Override public StageActionResult authorPhysical(ChunkRecord r) { authors++; return super.authorPhysical(r); }
        @Override public StageActionResult settlePhysical(ChunkRecord r) { settles++; return super.settlePhysical(r); }
        @Override public StageActionResult persist(ChunkRecord r) { persists++; return super.persist(r); }
        @Override public StageActionResult settleLighting(ChunkRecord r) { lights++; return super.settleLighting(r); }
        @Override public StageActionResult verify(ChunkRecord r) { verifies++; return super.verify(r); }
        @Override public StageActionResult restore(ChunkRecord r) { restores++; return super.restore(r); }
        @Override public StageActionResult verifyRestore(ChunkRecord r) { restoreVerifies++; return super.verifyRestore(r); }
        @Override public StageActionResult release(ChunkRecord r) { releases++; return super.release(r); }
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void eq(long expected, long actual, String label) {
        checks++; if (expected != actual) throw new AssertionError(label + ": expected " + expected + " got " + actual);
    }
    private static void eq(int expected, int actual, String label) {
        checks++; if (expected != actual) throw new AssertionError(label + ": expected " + expected + " got " + actual);
    }
    private static void eq(Object expected, Object actual, String label) {
        checks++; if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + " got " + actual);
    }
}
