package net.oceancanvas.core;

import net.oceancanvas.core.acceptance.AcceptanceHarness;
import net.oceancanvas.core.acceptance.PostCompleteRecoveryProof;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.expansion.SequentialChunkCoordinator;
import net.oceancanvas.core.expansion.TwoChunkCanaryPlan;
import net.oceancanvas.core.expansion.TwoChunkCanaryAdmission;
import net.oceancanvas.core.expansion.TwoChunkCanaryIdentityStore;
import net.oceancanvas.core.expansion.FourChunkCanaryPlan;
import net.oceancanvas.core.expansion.FourChunkCanaryAdmission;
import net.oceancanvas.core.expansion.FourChunkCanaryIdentityStore;
import net.oceancanvas.core.expansion.NineChunkCanaryPlan;
import net.oceancanvas.core.expansion.NineChunkCanaryAdmission;
import net.oceancanvas.core.expansion.NineChunkCanaryIdentityStore;
import net.oceancanvas.core.expansion.SixteenChunkCanaryPlan;
import net.oceancanvas.core.expansion.SixteenChunkCanaryAdmission;
import net.oceancanvas.core.expansion.SixteenChunkCanaryIdentityStore;
import net.oceancanvas.core.geometry.OceanFloorProfile;
import net.oceancanvas.core.geometry.ChunkColumnScanBounds;
import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.journal.JournalEntry;
import net.oceancanvas.core.pipeline.*;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;
import net.oceancanvas.core.receipt.PreimageReceiptContinuity;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.runtime.ResidencyReacquirePolicy;
import net.oceancanvas.core.restore.BlockStatePreimageStore;
import net.oceancanvas.core.restore.BlockStatePreimageArchive;
import net.oceancanvas.core.restore.BlockEntityBackupContract;
import net.oceancanvas.core.restore.BlockEntitySidecarStore;
import net.oceancanvas.core.restore.BlockEntityRecoveryAdmission;
import net.oceancanvas.core.restore.BlockEntitySidecarArchive;
import net.oceancanvas.core.restore.PreimageAdmissionPolicy;
import net.oceancanvas.core.restore.RestorePassPlan;
import net.oceancanvas.core.restore.RestoreWritePolicy;
import net.oceancanvas.core.restore.BlockStateRegistryIdentityStore;
import net.oceancanvas.mod.server.MinecraftBlockEntityNbtCodec;
import net.minecraft.nbt.CompoundTag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Arrays;

public final class CoreSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testCanvasGeometry();
        testRowMajorOrder();
        testFloorProfile();
        testCheckedColumnScanBounds();
        testDestructiveConfigGate();
        testLifecycleGuards();
        testJournalDurabilityContract();
        checks += net.oceancanvas.core.journal.CoreJournalBoundedReadSelfTest.run();
        testRestartAtEverySingleChunkStage();
        testWaitingAndFailureSemantics();
        testManifestFailClosed();
        testStartupAuthorityGuard();
        testReceiptIntegrity();
        testPreimageReceiptContinuity();
        testPostCompleteRecoveryProof();
        testAcceptanceRestartGate();
        testResidencyReacquirePolicy();
        testBlockStatePreimageStore();
        testImmutablePreimageArchive();
        testBlockStateRegistryIdentityStore();
        testBlockEntityAdmission();
        testBlockEntityRecoveryAdmission();
        testBlockEntityBackupContract();
        testBlockEntitySidecarStore();
        testBlockEntitySidecarArchive();
        testMinecraftBlockEntityNbtCodec();
        testTwoPassRestorePolicy();
        testTwoChunkCanaryPlan();
        testTwoChunkCanaryAdmission();
        testFourChunkCanaryPlan();
        testFourChunkCanaryAdmission();
        testNineChunkCanaryPlan();
        testNineChunkCanaryAdmission();
        testImmutableNineChunkPlan();
        testSixteenChunkCanaryPlan();
        testSixteenChunkCanaryAdmission();
        testImmutableSixteenChunkPlan();
        testImmutableFourChunkPlan();
        testImmutableTwoChunkPlan();
        testSequentialCanaryCoordinator();
        testFourChunkSequentialCoordinator();
        testNineChunkSequentialCoordinator();
        testSixteenChunkSequentialCoordinator();
        testSequentialAdapterLease();
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
        var centered = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(20_000, 0, 0);
        eq(bounds, centered, "centered Canvas reproduces existing 20k region");
        var negativeCenter = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(32, -32, -48);
        eq(-3, negativeCenter.minX(), "negative centered west chunk");
        eq(-2, negativeCenter.maxX(), "negative centered east chunk");
        eq(-4, negativeCenter.minZ(), "negative centered north chunk");
        eq(-3, negativeCenter.maxZ(), "negative centered south chunk");
        for (int[] invalid : new int[][] {
                {20_000, Integer.MAX_VALUE, 0},
                {20_000, 0, Integer.MIN_VALUE},
                {0, 0, 0},
                {31, 0, 0}
        }) {
            boolean refused = false;
            try {
                OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                        invalid[0], invalid[1], invalid[2]);
            } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "invalid Canvas centered bounds rejected before authority");
        }

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


    private static void testCheckedColumnScanBounds() {
        ChunkColumnScanBounds ordinary = ChunkColumnScanBounds.checked(-64, 319);
        eq(384, ordinary.height(), "normal build height");
        eq(98_304, ordinary.cells(), "normal chunk vertical scan count");
        ChunkColumnScanBounds negative = ChunkColumnScanBounds.checked(-120, -1);
        eq(120, negative.height(), "negative-world-y scan");
        eq(30_720, negative.cells(), "negative-world-y cells");
        for (int[] invalid : new int[][] {
                {0, -1}, {Integer.MIN_VALUE, Integer.MAX_VALUE},
                {0, 4096}, {Integer.MAX_VALUE - 3, Integer.MIN_VALUE + 3}
        }) {
            boolean refused = false;
            try { ChunkColumnScanBounds.checked(invalid[0], invalid[1]); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "invalid/overflow vertical scan rejected before world mutation");
        }
        eq(1_048_576, ChunkColumnScanBounds.checked(-64, 4031).cells(),
                "maximum admitted scan stays bounded");
        eq(100_096, ChunkColumnScanBounds.forOceanFloor(25, 5, 410).cells(),
                "configured ocean floor arithmetic checked");
        boolean configuredOverflow = false;
        try { ChunkColumnScanBounds.forOceanFloor(Integer.MIN_VALUE, 10, 320); }
        catch (IllegalArgumentException expected) { configuredOverflow = true; }
        check(configuredOverflow, "configured floor underflow rejected before mutation");
        boolean negativeVariation = false;
        try { ChunkColumnScanBounds.forOceanFloor(25, -1, 320); }
        catch (IllegalArgumentException expected) { negativeVariation = true; }
        check(negativeVariation, "negative floor variation rejected before mutation");
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
        // Valid original CRCs cannot make a duplicated or reordered record
        // acceptable recovery evidence. No new checksum failure is involved.
        List<String> signedJournalLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Path duplicatedJournal = dir.resolve("duplicated-valid.journal");
        Files.writeString(duplicatedJournal, String.join("\n",
                signedJournalLines.get(0), signedJournalLines.get(1),
                signedJournalLines.get(1), signedJournalLines.get(2)) + "\n",
                StandardCharsets.UTF_8);
        boolean journalDuplicateRejected = false;
        try { new CoreJournal(duplicatedJournal).readVerified(); }
        catch (java.io.IOException expected) { journalDuplicateRejected = true; }
        check(journalDuplicateRejected, "valid-CRC duplicate journal record fails closed");
        Path reorderedJournal = dir.resolve("reordered-valid.journal");
        Files.writeString(reorderedJournal, String.join("\n",
                signedJournalLines.get(1), signedJournalLines.get(0),
                signedJournalLines.get(2)) + "\n", StandardCharsets.UTF_8);
        boolean journalReorderRejected = false;
        try { new CoreJournal(reorderedJournal).readVerified(); }
        catch (java.io.IOException expected) { journalReorderRejected = true; }
        check(journalReorderRejected, "valid-CRC reordered journal records fail closed");


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
        // A corrupt record can carry a newly calculated valid CRC. Unknown
        // escape sequences must never be normalized into different evidence.
        Path malformedFile = dir.resolve("malformed-escape.journal");
        String malformedPayload = "0\t1000\t1\t2\tDISCOVERED\tLOADED\t1\t1\tinvalid\\q";
        java.util.zip.CRC32 malformedCrc = new java.util.zip.CRC32();
        malformedCrc.update(malformedPayload.getBytes(StandardCharsets.UTF_8));
        Files.writeString(malformedFile, malformedPayload + "\t"
                + Long.toUnsignedString(malformedCrc.getValue()) + "\n", StandardCharsets.UTF_8);
        boolean malformedEscapeRejected = false;
        try { new CoreJournal(malformedFile).readVerified(); }
        catch (java.io.IOException expected) { malformedEscapeRejected = true; }
        check(malformedEscapeRejected, "checksum-valid unknown journal reason escape rejected");

        Path trailingEscapeFile = dir.resolve("trailing-escape.journal");
        String trailingPayload = "0\t1000\t1\t2\tDISCOVERED\tLOADED\t1\t1\tinvalid\\";
        java.util.zip.CRC32 trailingCrc = new java.util.zip.CRC32();
        trailingCrc.update(trailingPayload.getBytes(StandardCharsets.UTF_8));
        Files.writeString(trailingEscapeFile, trailingPayload + "\t"
                + Long.toUnsignedString(trailingCrc.getValue()) + "\n", StandardCharsets.UTF_8);
        boolean trailingEscapeRejected = false;
        try { new CoreJournal(trailingEscapeFile).readVerified(); }
        catch (java.io.IOException expected) { trailingEscapeRejected = true; }
        check(trailingEscapeRejected, "checksum-valid trailing journal reason escape rejected");

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
        // Original first failure and authority cannot be superseded by a
        // success-looking adapter after reopening a terminal FAILED stage.
        SingleChunkPipeline reopenedFailed = SingleChunkPipeline.open(journal, key);
        long originalRevision = reopenedFailed.record().revision();
        eq("load refused", reopenedFailed.record().failureReason(),
                "first durable failure reason survives process restart");
        java.util.concurrent.atomic.AtomicInteger forbiddenCalls = new java.util.concurrent.atomic.AtomicInteger();
        SingleChunkPorts temptingSuccess = new DelegatingPorts() {
            @Override public StageActionResult load(ChunkRecord record) {
                forbiddenCalls.incrementAndGet();
                return StageActionResult.success("forged recovery");
            }
        };
        for (int restart = 0; restart < 3; restart++) {
            SingleChunkPipeline failedAgain = SingleChunkPipeline.open(journal, key);
            check(failedAgain.terminal(), "FAILED remains terminal on every restart");
            check(!failedAgain.tick(temptingSuccess, 100L + restart),
                    "durable FAILED stage cannot advance after restart");
            eq(originalRevision, failedAgain.record().revision(),
                    "terminal reopen cannot increment revision");
            eq("load refused", failedAgain.record().failureReason(),
                    "terminal reopen preserves original failure reason");
        }
        eq(0, forbiddenCalls.get(), "failed recovery never dispatches stale stage action");
        eq(1, journal.readVerified().size(), "failed recovery never fabricates later journal credit");
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
        // Another process or thread holding the same operation's authority
        // lease must not allow even an identical manifest-open to proceed.
        Path leaseFile = file.resolveSibling(file.getFileName().toString() + ".lock");
        try (var channel = java.nio.channels.FileChannel.open(leaseFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lease = channel.lock()) {
            boolean competingWriterRejected = false;
            try { OperationManifestStore.ensureExact(file, a); }
            catch (java.io.IOException expected) { competingWriterRejected = true; }
            check(competingWriterRejected, "simultaneous operation manifest writer refused");
        }
        OperationManifestStore.ensureExact(file, a);
        check(Files.size(file) > 0, "canonical operation identity unchanged after competing writer");

        // An interrupted staged manifest must not become canonical by accident.
        Path interrupted = dir.resolve("interrupted.properties");
        Path staged = dir.resolve("interrupted.properties.tmp");
        Files.writeString(staged, "partial", StandardCharsets.UTF_8);
        boolean stagedRejected = false;
        try { OperationManifestStore.ensureExact(interrupted, a); }
        catch (java.io.IOException expected) { stagedRejected = true; }
        check(stagedRejected, "stale staged manifest fails closed");
        check(!Files.exists(interrupted), "partial manifest never becomes canonical authority");
        eq("partial", Files.readString(staged), "interrupted manifest preserved for diagnosis");
        Files.delete(staged);
        OperationManifestStore.ensureExact(interrupted, a);
        OperationManifestStore.ensureExact(interrupted, a);
        check(Files.size(interrupted) > 0, "clean retry publishes verified manifest");
        // A stale staging file cannot displace a previously committed valid
        // canonical manifest. Nor may the reopen path quietly delete evidence.
        Path stagedCanonical = dir.resolve("canonical-with-orphan.properties");
        OperationManifestStore.ensureExact(stagedCanonical, a);
        byte[] validCanonical = Files.readAllBytes(stagedCanonical);
        Path orphan = dir.resolve("canonical-with-orphan.properties.tmp");
        byte[] orphanBytes = "conflicting interrupted candidate".getBytes(StandardCharsets.UTF_8);
        Files.write(orphan, orphanBytes);
        OperationManifestStore.ensureExact(stagedCanonical, a);
        check(java.util.Arrays.equals(validCanonical, Files.readAllBytes(stagedCanonical)),
                "reopening valid canonical cannot rewrite its contents");
        check(java.util.Arrays.equals(orphanBytes, Files.readAllBytes(orphan)),
                "reopening valid canonical preserves orphan staging evidence");
        boolean changedWithOrphanRejected = false;
        try {
            OperationManifestStore.ensureExact(stagedCanonical,
                    new SingleChunkOperationSpec(1, new ChunkKey(5, 6), 20_000, 0, 0, 62, 24, 5));
        } catch (java.io.IOException expected) { changedWithOrphanRejected = true; }
        check(changedWithOrphanRejected, "orphan staging file cannot override canonical identity");
        Files.writeString(stagedCanonical, "corrupted", StandardCharsets.UTF_8);
        boolean corruptedWithOrphanRejected = false;
        try { OperationManifestStore.ensureExact(stagedCanonical, a); }
        catch (java.io.IOException expected) { corruptedWithOrphanRejected = true; }
        check(corruptedWithOrphanRejected, "corrupted canonical cannot be rebuilt from orphan stage");
        check(java.util.Arrays.equals(orphanBytes, Files.readAllBytes(orphan)),
                "failed reopen still preserves forensic orphan bytes");

        // Java Properties.load normally trusts the last duplicate. Reject both
        // conflicting and identical repeated keys as ambiguous operation authority.
        Path conflicting = dir.resolve("conflicting.properties");
        OperationManifestStore.ensureExact(conflicting, a);
        Files.writeString(conflicting, "chunkX=999\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        boolean conflictingRejected = false;
        try { OperationManifestStore.ensureExact(conflicting, a); }
        catch (java.io.IOException expected) { conflictingRejected = true; }
        check(conflictingRejected, "manifest rejects conflicting duplicate geometry field");
        Path repeatedIdentity = dir.resolve("repeated-identity.properties");
        OperationManifestStore.ensureExact(repeatedIdentity, a);
        Files.writeString(repeatedIdentity, "operationId=" + a.operationId() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean repeatedRejected = false;
        try { OperationManifestStore.ensureExact(repeatedIdentity, a); }
        catch (java.io.IOException expected) { repeatedRejected = true; }
        check(repeatedRejected, "identical duplicate manifest identity still refused");

        deleteTree(dir);
    }

    private static void testStartupAuthorityGuard() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-startup-authority");
        try {
            Path manifest = dir.resolve("operation.properties");
            Path downstream = dir.resolve("downstream-registry.identity");
            Path downstreamJournal = dir.resolve("transitions.journal");
            Path downstreamReceipts = dir.resolve("runtime-receipts.log");
            SingleChunkOperationSpec canonical = new SingleChunkOperationSpec(
                    1, new ChunkKey(5, 6), 20_000, 0, 0, 62, 25, 5);
            SingleChunkOperationSpec redirected = new SingleChunkOperationSpec(
                    1, new ChunkKey(6, 6), 20_000, 0, 0, 62, 25, 5);
            OperationManifestStore.ensureExact(manifest, canonical);
            byte[] canonicalBytes = Files.readAllBytes(manifest);
            java.util.concurrent.atomic.AtomicInteger downstreamCalls =
                    new java.util.concurrent.atomic.AtomicInteger();

            boolean redirectedRejected = false;
            try {
                StartupAuthorityGuard.runAfterManifestAuthority(manifest, redirected, () -> {
                    downstreamCalls.incrementAndGet();
                    Files.writeString(downstream, "must-not-run", StandardCharsets.UTF_8);
                    Files.writeString(downstreamJournal, "must-not-run", StandardCharsets.UTF_8);
                    Files.writeString(downstreamReceipts, "must-not-run", StandardCharsets.UTF_8);
                });
            } catch (java.io.IOException expected) {
                redirectedRejected = true;
            }
            check(redirectedRejected, "startup guard rejects manifest/current-target mismatch");
            eq(0, downstreamCalls.get(), "target mismatch executes zero downstream startup side effects");
            check(!Files.exists(downstream)
                            && !Files.exists(downstreamJournal)
                            && !Files.exists(downstreamReceipts),
                    "target mismatch creates no registry, journal, or receipt startup evidence");
            check(java.util.Arrays.equals(canonicalBytes, Files.readAllBytes(manifest)),
                    "target mismatch preserves canonical operation authority byte-for-byte");

            StartupAuthorityGuard.runAfterManifestAuthority(manifest, canonical, () -> {
                downstreamCalls.incrementAndGet();
                Files.writeString(downstream, "authorized", StandardCharsets.UTF_8);
            });
            eq(1, downstreamCalls.get(), "exact startup authority permits downstream initialization once");
            eq("authorized", Files.readString(downstream), "authorized downstream initialization executes");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testReceiptIntegrity() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-receipts");
        Path file = dir.resolve("receipts.log");
        RuntimeReceiptLog log = new RuntimeReceiptLog(file);
        ChunkKey key = new ChunkKey(-8, 9);
        log.append(ReceiptKind.TICKET_INSTALLED, key, "forced radius=0");
        log.append(ReceiptKind.TICKET_RELEASED, key, "done");
        eq(2, log.readVerified().size(), "receipt count");
        // Receipt ordering must survive checksum-valid duplication/reordering;
        // forensic evidence is never silently renumbered to appear consistent.
        List<String> signedReceiptLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Path duplicatedReceipt = dir.resolve("duplicate-valid-receipt.log");
        Files.writeString(duplicatedReceipt, String.join("\n",
                signedReceiptLines.get(0), signedReceiptLines.get(1),
                signedReceiptLines.get(1)) + "\n", StandardCharsets.UTF_8);
        boolean duplicateReceiptRejected = false;
        try { new RuntimeReceiptLog(duplicatedReceipt).readVerified(); }
        catch (java.io.IOException expected) { duplicateReceiptRejected = true; }
        check(duplicateReceiptRejected, "valid-CRC duplicate receipt rejected");
        Path reorderedReceipt = dir.resolve("reordered-valid-receipt.log");
        Files.writeString(reorderedReceipt, String.join("\n",
                signedReceiptLines.get(1), signedReceiptLines.get(0)) + "\n", StandardCharsets.UTF_8);
        boolean reorderedReceiptRejected = false;
        try { new RuntimeReceiptLog(reorderedReceipt).readVerified(); }
        catch (java.io.IOException expected) { reorderedReceiptRejected = true; }
        check(reorderedReceiptRejected, "valid-CRC reordered receipts rejected");

        // Simulate an append failure on the same in-memory log after sequence
        // initialization. A later successful retry must not skip sequence 2.
        Path parkedReceiptFile = dir.resolve("receipts-parked.log");
        Files.move(file, parkedReceiptFile);
        Files.createDirectory(file);
        boolean appendRefused = false;
        try { log.append(ReceiptKind.TICKET_INSTALLED, key, "blocked destination"); }
        catch (java.io.IOException expected) { appendRefused = true; }
        check(appendRefused, "failed forensic append refuses directory destination");
        Files.delete(file);
        Files.move(parkedReceiptFile, file);
        log.append(ReceiptKind.TICKET_RELEASED, key, "retry after failed append");
        eq(3, log.readVerified().size(), "retry does not skip receipt sequence");
        Files.writeString(file, "bad", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean rejected = false;
        try { log.readVerified(); } catch (Exception expected) { rejected = true; }
        check(rejected, "receipt corruption fails closed");
        Path tornFile = dir.resolve("unterminated-receipt.log");
        RuntimeReceiptLog torn = new RuntimeReceiptLog(tornFile);
        torn.append(ReceiptKind.TICKET_INSTALLED, key, "forced radius=0");
        byte[] completed = Files.readAllBytes(tornFile);
        Files.write(tornFile, java.util.Arrays.copyOf(completed, completed.length - 1));
        boolean terminatorRejected = false;
        try { torn.readVerified(); }
        catch (java.io.IOException expected) { terminatorRejected = true; }
        check(terminatorRejected, "checksum-valid but unterminated receipt rejected");
        // A permissive UTF-8 decoder would replace 0xC3 followed by '(' with
        // U+FFFD + '('; forge the CRC for that normalized text. Even a matching
        // normalized checksum must never authenticate the malformed original.
        Path forgedEncoding = dir.resolve("forged-invalid-utf8.log");
        String prefix = "0\t1000\tTICKET_INSTALLED\t-8\t9\t";
        String normalizedPayload = prefix + "\uFFFD(";
        java.util.zip.CRC32 normalizedCrc = new java.util.zip.CRC32();
        normalizedCrc.update(normalizedPayload.getBytes(StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream forged = new java.io.ByteArrayOutputStream();
        forged.write(prefix.getBytes(StandardCharsets.UTF_8));
        forged.write(new byte[] {(byte) 0xC3, (byte) '('});
        forged.write(("\t" + Long.toUnsignedString(normalizedCrc.getValue()) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(forgedEncoding, forged.toByteArray());
        boolean invalidUtf8Rejected = false;
        try { new RuntimeReceiptLog(forgedEncoding).readVerified(); }
        catch (java.io.IOException expected) {
            invalidUtf8Rejected = expected.getMessage().contains("UTF-8");
        }
        check(invalidUtf8Rejected, "normalized-CRC forged malformed UTF-8 receipt rejected");

        Path validUnicode = dir.resolve("unicode-receipt.log");
        RuntimeReceiptLog unicodeLog = new RuntimeReceiptLog(validUnicode);
        unicodeLog.append(ReceiptKind.TICKET_INSTALLED, key, "snow \u2744 by the sea");
        eq("snow \u2744 by the sea", unicodeLog.readVerified().get(0).detail(),
                "valid UTF-8 forensic evidence survives strict decode");

        Path oversizedReceipt = dir.resolve("oversized-receipts.log");
        try (var raf = new java.io.RandomAccessFile(oversizedReceipt.toFile(), "rw")) {
            raf.setLength(8L * 1024L * 1024L + 1L);
        }
        RuntimeReceiptLog oversizedLog = new RuntimeReceiptLog(oversizedReceipt);
        boolean oversizedReadRejected = false;
        try { oversizedLog.readVerified(); }
        catch (java.io.IOException expected) {
            oversizedReadRejected = expected.getMessage().contains("size bound");
        }
        check(oversizedReadRejected, "oversized receipt rejects before heap allocation");
        boolean oversizedAppendRejected = false;
        try { oversizedLog.append(ReceiptKind.TICKET_RELEASED, key, "must not append"); }
        catch (java.io.IOException expected) {
            oversizedAppendRejected = expected.getMessage().contains("size bound");
        }
        check(oversizedAppendRejected, "oversized receipt rejects appends without mutation");
        eq(8L * 1024L * 1024L + 1L, Files.size(oversizedReceipt),
                "oversized refusal preserves forensic receipt file length");
        // A recomputed CRC cannot make an unknown or truncated escape safe.
        for (String invalidDetail : new String[] {"forged\\q", "forged\\"}) {
            Path malformed = dir.resolve("malformed-receipt-" + invalidDetail.length() + ".log");
            String payload = "0\t1000\tTICKET_INSTALLED\t-8\t9\t" + invalidDetail;
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(payload.getBytes(StandardCharsets.UTF_8));
            Files.writeString(malformed, payload + "\t"
                    + Long.toUnsignedString(crc.getValue()) + "\n", StandardCharsets.UTF_8);
            boolean malformedRejected = false;
            try { new RuntimeReceiptLog(malformed).readVerified(); }
            catch (java.io.IOException expected) { malformedRejected = true; }
            check(malformedRejected, "checksum-valid malformed receipt escape rejected");
        }
        deleteTree(dir);
    }

    private static void testPreimageReceiptContinuity() throws Exception {
        ChunkKey key = new ChunkKey(32, 32);
        String hash = "a".repeat(64);
        java.util.ArrayList<RuntimeReceipt> evidence = new java.util.ArrayList<>(List.of(
                new RuntimeReceipt(0, 1, ReceiptKind.TICKET_INSTALLED, key, "radius=0"),
                new RuntimeReceipt(1, 2, ReceiptKind.PREIMAGE_CAPTURED, key,
                        "operation=proof;states=1024;preimageSha256=" + hash),
                new RuntimeReceipt(2, 3, ReceiptKind.TICKET_RELEASED, key,
                        "session-close-before-terminal; restart will reacquire if needed"),
                new RuntimeReceipt(3, 4, ReceiptKind.RESTORE_COMPLETE, key,
                        "operation=proof;states=1024;preimageSha256=" + hash),
                new RuntimeReceipt(4, 5, ReceiptKind.RESTORE_VERIFIED, key,
                        "operation=proof;states=1024;preimageSha256=" + hash),
                new RuntimeReceipt(5, 6, ReceiptKind.TICKET_RELEASED, key,
                        "forced radius=0;restoreVerified=true;preimageArchiveSha256=" + hash)
        ));
        var verified = PreimageReceiptContinuity.verify(evidence, "proof", key, true);
        eq(hash, verified.preimageSha256(), "capture/restore/archival preimage digest continuity");
        check(verified.immutableArchiveVerified(), "immutable archived digest corroborates restored state");
        // Actual Minecraft restart after COMPLETE issues this documented
        // non-keyed prefix; the original verifier incorrectly rejected it.
        var restartedRelease = new java.util.ArrayList<>(evidence);
        restartedRelease.set(5, new RuntimeReceipt(5, 6, ReceiptKind.TICKET_RELEASED, key,
                "no live ticket after restart;restoreVerified=true;preimageArchiveSha256=" + hash));
        check(PreimageReceiptContinuity.verify(restartedRelease, "proof", key, true)
                .immutableArchiveVerified(),
                "actual post-restart release receipt is accepted with exact SHA");
        var unknownPrefix = new java.util.ArrayList<>(restartedRelease);
        unknownPrefix.set(5, new RuntimeReceipt(5, 6, ReceiptKind.TICKET_RELEASED, key,
                "forged unrelated ticket description;restoreVerified=true;preimageArchiveSha256=" + hash));
        boolean unknownPrefixRejected = false;
        try { PreimageReceiptContinuity.verify(unknownPrefix, "proof", key, true); }
        catch (java.io.IOException expected) { unknownPrefixRejected = true; }
        check(unknownPrefixRejected, "unknown non-keyed archive receipt prefix remains refused");

        eq(1, verified.restoreVerifications(), "exactly one restore-verification receipt");
        boolean wrongOperation = false;
        try { PreimageReceiptContinuity.verify(evidence, "another-op", key, true); }
        catch (java.io.IOException expected) { wrongOperation = true; }
        check(wrongOperation, "receipt proof cannot cross operation identity");
        boolean wrongChunk = false;
        try { PreimageReceiptContinuity.verify(evidence, "proof", new ChunkKey(31, 32), true); }
        catch (java.io.IOException expected) { wrongChunk = true; }
        check(wrongChunk, "receipt proof cannot mix chunks");
        var absentArchive = new java.util.ArrayList<>(evidence);
        absentArchive.remove(absentArchive.size() - 1);
        boolean missingArchiveRejected = false;
        try { PreimageReceiptContinuity.verify(absentArchive, "proof", key, true); }
        catch (java.io.IOException expected) { missingArchiveRejected = true; }
        check(missingArchiveRejected, "release certification requires actual archive receipt");
        var wrongRestore = new java.util.ArrayList<>(evidence);
        wrongRestore.set(4, new RuntimeReceipt(4, 5, ReceiptKind.RESTORE_VERIFIED, key,
                "operation=proof;states=1024;preimageSha256=" + "b".repeat(64)));
        boolean mismatchedRejected = false;
        try { PreimageReceiptContinuity.verify(wrongRestore, "proof", key, true); }
        catch (java.io.IOException expected) { mismatchedRejected = true; }
        check(mismatchedRejected, "restoration digest drift fails closed");
        var forgedArchive = new java.util.ArrayList<>(evidence);
        forgedArchive.set(5, new RuntimeReceipt(5, 6, ReceiptKind.TICKET_RELEASED, key,
                "restoreVerified=true;preimageArchiveSha256=" + "b".repeat(64)));
        boolean mismatchedArchiveRejected = false;
        try { PreimageReceiptContinuity.verify(forgedArchive, "proof", key, true); }
        catch (java.io.IOException expected) { mismatchedArchiveRejected = true; }
        check(mismatchedArchiveRejected, "archive digest drift fails closed");
        var duplicateKey = new java.util.ArrayList<>(evidence);
        duplicateKey.set(4, new RuntimeReceipt(4, 5, ReceiptKind.RESTORE_VERIFIED, key,
                "operation=proof;preimageSha256=" + hash + ";preimageSha256=" + hash));
        boolean duplicatedFieldRejected = false;
        try { PreimageReceiptContinuity.verify(duplicateKey, "proof", key, true); }
        catch (java.io.IOException expected) { duplicatedFieldRejected = true; }
        check(duplicatedFieldRejected, "duplicate evidence fields never gain authority");
        var wrongOrder = new java.util.ArrayList<>(evidence);
        wrongOrder.set(3, evidence.get(4));
        wrongOrder.set(4, evidence.get(3));
        boolean wrongOrderRejected = false;
        try { PreimageReceiptContinuity.verify(wrongOrder, "proof", key, true); }
        catch (java.io.IOException expected) { wrongOrderRejected = true; }
        check(wrongOrderRejected, "reordered receipt stages cannot certify restore");
    }

    private static void testPostCompleteRecoveryProof() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-final-restart-proof");
        Path live = dir.resolve("preimage.bin");
        Path archive = dir.resolve("preimage.bin.completed.archive");
        ChunkKey key = new ChunkKey(-2, 3);
        String operation = "final-restart-test";
        int[] ids = new int[256 * 2];
        for (int i = 0; i < ids.length; i++) ids[i] = i % 11;
        BlockStatePreimageStore.writeExact(live,
                new BlockStatePreimageStore.Preimage(operation, key, 0, 1, ids));
        String preimageHash = BlockStatePreimageArchive.archiveExact(live, archive, operation, key);
        RuntimeReceiptLog receipts = new RuntimeReceiptLog(dir.resolve("receipts.log"));
        receipts.append(ReceiptKind.PREIMAGE_CAPTURED, key,
                "operation=" + operation + ";preimageSha256=" + preimageHash);
        receipts.append(ReceiptKind.RESTORE_COMPLETE, key,
                "operation=" + operation + ";preimageSha256=" + preimageHash);
        receipts.append(ReceiptKind.RESTORE_VERIFIED, key,
                "operation=" + operation + ";preimageSha256=" + preimageHash);
        receipts.append(ReceiptKind.TICKET_RELEASED, key,
                "forced radius=0;restoreVerified=true;preimageArchiveSha256=" + preimageHash);
        eq(preimageHash, PostCompleteRecoveryProof.verify(
                archive, operation, key, receipts.readVerified()).preimageSha256(),
                "reopened completion requires matching actual archive and receipt chain");
        check(PostCompleteRecoveryProof.requiresArchiveOnReopen(ChunkStage.COMPLETE),
                "normal completed server reopen must verify immutable recovery archive");
        check(!PostCompleteRecoveryProof.requiresArchiveOnReopen(ChunkStage.RESTORE_VERIFIED),
                "pre-completion resume uses its normal stage-specific backup protection");
        check(!PostCompleteRecoveryProof.requiresArchiveOnReopen(ChunkStage.FAILED),
                "failed operation never gains completed-archive status");
        boolean missingRejected = false;
        try { PostCompleteRecoveryProof.verify(
                dir.resolve("missing.archive"), operation, key, receipts.readVerified()); }
        catch (java.io.IOException expected) { missingRejected = true; }
        check(missingRejected, "missing immutable archive refuses final-restart proof");
        boolean wrongOperationRejected = false;
        try { PostCompleteRecoveryProof.verify(
                archive, "another-operation", key, receipts.readVerified()); }
        catch (java.io.IOException expected) { wrongOperationRejected = true; }
        check(wrongOperationRejected, "wrong operation archive refuses final-restart proof");

        Path beDir = Files.createTempDirectory("oceancanvas-final-restart-be-proof");
        Path beLiveState = beDir.resolve("preimage-blockstates.bin");
        Path beStateArchive = beDir.resolve("preimage-blockstates.bin.completed.archive");
        Path beLiveSidecar = beDir.resolve("preimage-blockentities.ocbe");
        Path beSidecarArchive = beDir.resolve("preimage-blockentities.ocbe.completed.archive");
        ChunkKey beKey = new ChunkKey(5, -7);
        String beOperation = "final-restart-be-test";
        int[] beIds = new int[256 * 2];
        BlockStatePreimageStore.writeExact(beLiveState,
                new BlockStatePreimageStore.Preimage(beOperation, beKey, 0, 1, beIds));
        String bePreimageHash = BlockStatePreimageStore.sha256Hex(beLiveState);
        BlockEntityBackupContract.Envelope beEnvelope = new BlockEntityBackupContract.Envelope(
                beOperation, beKey, bePreimageHash, beIds.length,
                java.util.List.of(new BlockEntityBackupContract.Entry(
                        0, "minecraft:chest", new byte[] {10, 0, 0, 0})));
        BlockEntitySidecarStore.writeExact(beLiveSidecar, beEnvelope);
        String beEnvelopeHash = BlockEntityBackupContract.canonicalSha256(beEnvelope);
        String beStateArchivedHash = BlockStatePreimageArchive.archiveExact(
                beLiveState, beStateArchive, beOperation, beKey);
        String beSidecarArchivedHash = BlockEntitySidecarArchive.archiveExact(
                beLiveSidecar, beSidecarArchive, beOperation, beKey, beStateArchivedHash);
        RuntimeReceiptLog beReceipts = new RuntimeReceiptLog(beDir.resolve("receipts.log"));
        beReceipts.append(ReceiptKind.PREIMAGE_CAPTURED, beKey,
                "operation=" + beOperation + ";blockEntities=1;blockEntityRecoveryEnabled=true;preimageSha256="
                        + beStateArchivedHash);
        beReceipts.append(ReceiptKind.RESTORE_COMPLETE, beKey,
                "operation=" + beOperation + ";blockEntities=1;preimageSha256=" + beStateArchivedHash);
        beReceipts.append(ReceiptKind.RESTORE_VERIFIED, beKey,
                "operation=" + beOperation + ";blockEntities=1;preimageSha256=" + beStateArchivedHash
                        + ";blockEntityEnvelopeSha256=" + beEnvelopeHash);
        beReceipts.append(ReceiptKind.TICKET_RELEASED, beKey,
                "forced radius=0;restoreVerified=true;blockEntities=1;preimageArchiveSha256="
                        + beStateArchivedHash + ";blockEntityArchiveSha256=" + beSidecarArchivedHash
                        + ";blockEntityEnvelopeSha256=" + beEnvelopeHash);
        eq(beStateArchivedHash, PostCompleteRecoveryProof.verify(
                beStateArchive, beOperation, beKey, beReceipts.readVerified()).preimageSha256(),
                "completed block-entity recovery requires matching archived state and sidecar evidence");
        Files.move(beSidecarArchive, beDir.resolve("sidecar.hidden"));
        boolean missingSidecarRejected = false;
        try { PostCompleteRecoveryProof.verify(
                beStateArchive, beOperation, beKey, beReceipts.readVerified()); }
        catch (java.io.IOException expected) { missingSidecarRejected = true; }
        check(missingSidecarRejected,
                "missing completed block-entity sidecar refuses final-restart acceptance");
        Path hiddenSidecar = beDir.resolve("sidecar.hidden");
        Files.move(hiddenSidecar, beSidecarArchive);
        Files.writeString(beSidecarArchive, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean corruptSidecarRejected = false;
        try { PostCompleteRecoveryProof.verify(
                beStateArchive, beOperation, beKey, beReceipts.readVerified()); }
        catch (java.io.IOException expected) { corruptSidecarRejected = true; }
        check(corruptSidecarRejected,
                "corrupted completed block-entity sidecar refuses final-restart acceptance");
        deleteTree(beDir);

        Files.writeString(archive, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean corruptRejected = false;
        try { PostCompleteRecoveryProof.verify(
                archive, operation, key, receipts.readVerified()); }
        catch (java.io.IOException expected) { corruptRejected = true; }
        check(corruptRejected, "corrupted archive refuses final-restart acceptance credit");
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


    private static void testImmutablePreimageArchive() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-preimage-archive");
        Path live = dir.resolve("preimage.bin");
        Path archive = dir.resolve("preimage.bin.completed.archive");
        ChunkKey key = new ChunkKey(-11, 22);
        int[] ids = new int[256 * 4];
        for (int i = 0; i < ids.length; i++) ids[i] = i % 17;
        var original = new BlockStatePreimageStore.Preimage("archival-proof", key, -2, 1, ids);
        BlockStatePreimageStore.writeExact(live, original);
        String initial = BlockStatePreimageStore.sha256Hex(live);
        eq(initial, BlockStatePreimageArchive.archiveExact(live, archive, "archival-proof", key),
                "archival certifies original exact backup");
        check(!Files.exists(live), "completed archival retires only redundant live path");
        eq(initial, BlockStatePreimageStore.sha256Hex(archive),
                "immutable archived backup retains exact original bytes");
        eq(initial, BlockStatePreimageArchive.archiveExact(live, archive, "archival-proof", key),
                "replayed release reuses verified archive without a live backup");

        BlockStatePreimageStore.writeExact(live, original);
        eq(initial, BlockStatePreimageArchive.archiveExact(live, archive, "archival-proof", key),
                "identical duplicate capture is idempotently retired");
        check(!Files.exists(live), "verified duplicate is retired after immutable archive check");

        int[] conflicting = ids.clone();
        conflicting[conflicting.length - 1] ^= 1;
        BlockStatePreimageStore.writeExact(live,
                new BlockStatePreimageStore.Preimage("archival-proof", key, -2, 1, conflicting));
        String conflictDigest = BlockStatePreimageStore.sha256Hex(live);
        boolean conflictingRejected = false;
        try { BlockStatePreimageArchive.archiveExact(live, archive, "archival-proof", key); }
        catch (java.io.IOException expected) { conflictingRejected = true; }
        check(conflictingRejected, "conflicting backup cannot overwrite prior immutable archive");
        eq(initial, BlockStatePreimageStore.sha256Hex(archive),
                "conflict preserves original archive digest");
        eq(conflictDigest, BlockStatePreimageStore.sha256Hex(live),
                "conflict preserves new live evidence for diagnosis");

        Files.delete(live); // Explicit test-only reset.
        BlockStatePreimageStore.writeExact(live, original);
        Files.writeString(archive, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean corruptArchiveRejected = false;
        try { BlockStatePreimageArchive.archiveExact(live, archive, "archival-proof", key); }
        catch (java.io.IOException expected) { corruptArchiveRejected = true; }
        check(corruptArchiveRejected, "corrupt archive never silently repaired from live duplicate");
        eq(initial, BlockStatePreimageStore.sha256Hex(live),
                "live backup survives corrupt completed archive for forensic recovery");
        deleteTree(dir);
    }

    private static void testMinecraftBlockEntityNbtCodec() throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", "oceancanvas");
        tag.putInt("count", 42);
        byte[] first = MinecraftBlockEntityNbtCodec.canonicalBytes(tag);
        byte[] second = MinecraftBlockEntityNbtCodec.canonicalBytes(tag);
        check(Arrays.equals(first, second), "block-entity NBT codec bytes deterministic");
        CompoundTag decoded = MinecraftBlockEntityNbtCodec.decode(first);
        eq("oceancanvas", decoded.getStringOr("name", ""), "block-entity NBT string roundtrip");
        eq(42, decoded.getIntOr("count", -1), "block-entity NBT integer roundtrip");

        byte[] trailing = Arrays.copyOf(first, first.length + 1);
        boolean trailingRefused = false;
        try { MinecraftBlockEntityNbtCodec.decode(trailing); }
        catch (java.io.IOException expected) { trailingRefused = true; }
        check(trailingRefused, "block-entity NBT codec rejects trailing bytes");

        boolean emptyRefused = false;
        try { MinecraftBlockEntityNbtCodec.decode(new byte[0]); }
        catch (java.io.IOException expected) { emptyRefused = true; }
        check(emptyRefused, "block-entity NBT codec rejects empty payload");
    }

    private static void testTwoPassRestorePolicy() {
        int exact = RestoreWritePolicy.EXACT_SNAPSHOT_FLAGS;
        eq(50, exact, "exact snapshot writes preserve shape and suppress drops");
        check(RestoreWritePolicy.preservesSnapshotShapes(exact),
                "restoration writes suppress intermediate neighbor shape updates");
        check(!RestoreWritePolicy.preservesSnapshotShapes(2),
                "old client-only restore flags risk deleting saved vines");
        check(!RestoreWritePolicy.preservesSnapshotShapes(exact | 1),
                "full neighbor fanout never allowed during exact preimage replay");

        final int total = 256 * 384;
        // Real persisted seed-4182029 evidence: the south-attached vine is
        // captured before its supporting south neighbor in column-major order.
        int vine = (0 * 16 + 15) * 300 + (108 - 19);
        int southSupport = (1 * 16 + 15) * 300 + (108 - 19);
        eq(4589, vine, "captured south-facing vine source index");
        eq(9389, southSupport, "source supporting block is captured later");
        check(vine < southSupport, "single pass can place vine before its support");

        var first = RestorePassPlan.afterFullPass(0, total, total);
        eq(1, first.nextPass(), "first restore pass must schedule dependent-state reapplication");
        eq(0, first.nextCursor(), "second pass starts from original first cell");
        check(!first.readyToPersist(), "first restore pass cannot grant RESTORED journal credit");
        var second = RestorePassPlan.afterFullPass(first.nextPass(), total, total);
        eq(2, second.nextPass(), "second bounded pass is final");
        eq(total, second.nextCursor(), "second pass preserves exact completed scan");
        check(second.readyToPersist(), "only complete second pass permits durable save");
        for (int[] invalid : new int[][] {
                {0, total - 1, total}, {1, total - 1, total},
                {-1, total, total}, {2, total, total},
                {0, 0, 0}
        }) {
            boolean refused = false;
            try { RestorePassPlan.afterFullPass(invalid[0], invalid[1], invalid[2]); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "incomplete or invalid restoration pass never grants stage credit");
        }
    }

    private static void testBlockEntityRecoveryAdmission() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-be-admission");
        try {
            CoreConfig core = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 25, 5, false, true, 32, 32, "ERASE_CHUNK_32_32",
                    256, 1024, 3000, 40, 40, false);
            check(BlockEntityRecoveryAdmission.load(dir, core).isEmpty(),
                    "missing block-entity consent remains disabled");

            Path file = dir.resolve(BlockEntityRecoveryAdmission.FILE_NAME);
            Files.writeString(file,
                    "enabled=true\nchunkX=32\nchunkZ=32\n"
                    + "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n");
            eq(new ChunkKey(32, 32), BlockEntityRecoveryAdmission.load(dir, core).orElseThrow(),
                    "separate exact block-entity recovery consent admitted");

            Files.writeString(file,
                    "enabled=true\nchunkX=33\nchunkZ=32\n"
                    + "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_33_32\n");
            boolean wrongTarget = false;
            try { BlockEntityRecoveryAdmission.load(dir, core); }
            catch (java.io.IOException expected) { wrongTarget = true; }
            check(wrongTarget, "block-entity consent cannot redirect single-chunk authority");

            Files.writeString(file,
                    "enabled=true\nchunkX=32\nchunkZ=32\n"
                    + "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n"
                    + "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n");
            boolean duplicate = false;
            try { BlockEntityRecoveryAdmission.load(dir, core); }
            catch (java.io.IOException expected) { duplicate = true; }
            check(duplicate, "duplicate block-entity consent fields fail closed");

            CoreConfig expansion = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 25, 5, true, true, 32, 32, "ERASE_CHUNK_32_32",
                    256, 1024, 3000, 40, 40, false);
            Files.writeString(file,
                    "enabled=true\nchunkX=32\nchunkZ=32\n"
                    + "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n");
            boolean expansionRejected = false;
            try { BlockEntityRecoveryAdmission.load(dir, expansion); }
            catch (java.io.IOException expected) { expansionRejected = true; }
            check(expansionRejected, "block-entity recovery cannot inherit expansion authority");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testBlockEntityBackupContract() {
        String originalSha = "a".repeat(64);
        ChunkKey originalChunk = new ChunkKey(-8, 7);
        byte[] inventory = new byte[] {10, 0, 1, 2, 3};
        var first = new BlockEntityBackupContract.Entry(3, "minecraft:chest", inventory);
        inventory[0] ^= 0x7f;
        eq((byte) 10, first.nbt()[0],
                "candidate NBT source array cannot mutate authoritative backup");
        byte[] exposed = first.nbt();
        exposed[1] ^= 0x7f;
        eq((byte) 0, first.nbt()[1], "entry getter defensively copies NBT");
        var second = new BlockEntityBackupContract.Entry(2, "minecraft:barrel", new byte[] {5});
        var backup = new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, originalSha, 1024, List.of(first, second));
        eq(2, backup.entries().get(0).stateIndex(),
                "backup entries canonicalized to stable scan-index order");
        String digest = BlockEntityBackupContract.canonicalSha256(backup);
        var reversed = new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, originalSha, 1024, List.of(second, first));
        eq(digest, BlockEntityBackupContract.canonicalSha256(reversed),
                "reordered input produces identical canonical digest");
        byte[] publicCopy = backup.entries().get(1).nbt();
        publicCopy[2] ^= 1;
        eq(digest, BlockEntityBackupContract.canonicalSha256(backup),
                "mutating accessor result cannot change retained canonical digest");
        var changedOperation = new BlockEntityBackupContract.Envelope(
                "another-operation", originalChunk, originalSha, 1024, List.of(first, second));
        check(!digest.equals(BlockEntityBackupContract.canonicalSha256(changedOperation)),
                "serialized backup digest bound to operation identity");
        var changedChunk = new BlockEntityBackupContract.Envelope(
                "world-operation-1", new ChunkKey(-7, 7), originalSha, 1024, List.of(first, second));
        check(!digest.equals(BlockEntityBackupContract.canonicalSha256(changedChunk)),
                "serialized backup digest bound to exact chunk identity");
        var changedSource = new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, "b".repeat(64), 1024, List.of(first, second));
        check(!digest.equals(BlockEntityBackupContract.canonicalSha256(changedSource)),
                "serialized backup digest bound to exact immutable block-state preimage");
        boolean duplicateRejected = false;
        try { new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, originalSha, 1024, List.of(first, first)); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        check(duplicateRejected, "duplicate chunk-local block-entity location rejected");
        boolean overrunRejected = false;
        try { new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, originalSha, 1024,
                List.of(new BlockEntityBackupContract.Entry(1024, "minecraft:chest", new byte[]{10}))); }
        catch (IllegalArgumentException expected) { overrunRejected = true; }
        check(overrunRejected, "out-of-range block entity position rejected");
        boolean typeRejected = false;
        try { new BlockEntityBackupContract.Entry(0, "../../unknown", new byte[] {10}); }
        catch (IllegalArgumentException expected) { typeRejected = true; }
        check(typeRejected, "invalid block entity registry identity rejected");
        boolean oversizedNbtRejected = false;
        try { new BlockEntityBackupContract.Entry(0, "minecraft:chest",
                new byte[BlockEntityBackupContract.MAX_ENTRY_NBT_BYTES + 1]); }
        catch (IllegalArgumentException expected) { oversizedNbtRejected = true; }
        check(oversizedNbtRejected, "oversized entity NBT rejected before retention");
        java.util.ArrayList<BlockEntityBackupContract.Entry> tooManyBytes = new java.util.ArrayList<>();
        byte[] maxEntry = new byte[BlockEntityBackupContract.MAX_ENTRY_NBT_BYTES];
        for (int index = 0; index < 17; index++) {
            tooManyBytes.add(new BlockEntityBackupContract.Entry(
                    index, "minecraft:chest", maxEntry));
        }
        boolean totalSizeRejected = false;
        try { new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, originalSha, 1024, tooManyBytes); }
        catch (IllegalArgumentException expected) { totalSizeRejected = true; }
        check(totalSizeRejected, "total sidecar limit rejects otherwise individually valid NBT entries");
        var emptyProof = new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, originalSha, 1024, List.of());
        check(BlockEntityBackupContract.canonicalSha256(emptyProof).matches("[0-9a-f]{64}"),
                "empty block-entity snapshot has stable operation-bound positive digest");
        boolean unboundSourceRejected = false;
        try { new BlockEntityBackupContract.Envelope(
                "world-operation-1", originalChunk, "not-a-hash", 1024, List.of(first)); }
        catch (IllegalArgumentException expected) { unboundSourceRejected = true; }
        check(unboundSourceRejected, "block entity NBT cannot exist without exact source preimage SHA");
        // This is only the immutable format contract, not world-authoring
        // permission: the current Minecraft admission guard remains active.
        check(PreimageAdmissionPolicy.refuses(true, true),
                "draft block-entity backup contract does not enable destructive capture");
    }

    private static void testBlockEntitySidecarStore() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-be-sidecar");
        Path sidecar = dir.resolve("block-entities.ocbe");
        ChunkKey chunk = new ChunkKey(3, -9);
        String op = "exact-sidecar-proof";
        String blockStateSha = "c".repeat(64);
        var a = new BlockEntityBackupContract.Entry(2, "minecraft:chest", new byte[]{10,0,3,1});
        var b = new BlockEntityBackupContract.Entry(10, "minecraft:barrel", new byte[]{10,0,3,2});
        var snapshot = new BlockEntityBackupContract.Envelope(
                op, chunk, blockStateSha, 1024, List.of(b, a));
        String hash = BlockEntitySidecarStore.writeExact(sidecar, snapshot);
        check(hash.matches("[0-9a-f]{64}"), "published NBT sidecar has canonical SHA-256");
        var reread = BlockEntitySidecarStore.readVerified(sidecar, op, chunk, blockStateSha);
        eq(BlockEntityBackupContract.canonicalSha256(snapshot),
                BlockEntityBackupContract.canonicalSha256(reread),
                "durable NBT sidecar decodes exact ordered original snapshot");
        byte[] immutableFile = Files.readAllBytes(sidecar);
        eq(hash, BlockEntitySidecarStore.writeExact(sidecar, snapshot),
                "same original NBT backup is an idempotent restart replay");
        check(java.util.Arrays.equals(immutableFile, Files.readAllBytes(sidecar)),
                "identical replay cannot rewrite immutable original sidecar");
        for (int variant = 0; variant < 3; variant++) {
            boolean refused = false;
            try {
                BlockEntitySidecarStore.readVerified(sidecar,
                        variant == 0 ? "another-op" : op,
                        variant == 1 ? new ChunkKey(4, -9) : chunk,
                        variant == 2 ? "d".repeat(64) : blockStateSha);
            } catch (java.io.IOException expected) { refused = true; }
            check(refused, "sidecar cannot be reused across operation/chunk/preimage SHA");
        }
        var changed = new BlockEntityBackupContract.Envelope(
                op, chunk, blockStateSha, 1024,
                List.of(new BlockEntityBackupContract.Entry(
                        2, "minecraft:chest", new byte[]{10,0,3,9})));
        boolean overwriteRejected = false;
        try { BlockEntitySidecarStore.writeExact(sidecar, changed); }
        catch (java.io.IOException expected) { overwriteRejected = true; }
        check(overwriteRejected, "conflicting NBT capture cannot replace original canonical backup");
        check(java.util.Arrays.equals(immutableFile, Files.readAllBytes(sidecar)),
                "rejected conflicting capture preserves original NBT sidecar bytes");
        Path orphan = dir.resolve("interrupted.ocbe");
        Path orphanTemp = dir.resolve("interrupted.ocbe.tmp");
        byte[] priorEvidence = "partial NBT capture".getBytes(StandardCharsets.UTF_8);
        Files.write(orphanTemp, priorEvidence, StandardOpenOption.CREATE_NEW);
        boolean orphanRejected = false;
        try { BlockEntitySidecarStore.writeExact(orphan, snapshot); }
        catch (java.nio.file.FileAlreadyExistsException expected) { orphanRejected = true; }
        check(orphanRejected, "abandoned NBT stage never silently truncated");
        check(java.util.Arrays.equals(priorEvidence, Files.readAllBytes(orphanTemp)),
                "interrupted NBT evidence survives refusal exactly");
        check(!Files.exists(orphan), "orphan cannot gain new canonical sidecar authority");
        Path leasePath = dir.resolve("competing.ocbe");
        try (var channel = java.nio.channels.FileChannel.open(
                dir.resolve("competing.ocbe.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lease = channel.lock()) {
            boolean competingRejected = false;
            try { BlockEntitySidecarStore.writeExact(leasePath, snapshot); }
            catch (java.io.IOException expected) { competingRejected = true; }
            check(competingRejected, "concurrent NBT sidecar publisher refused by exclusive lease");
            check(!Files.exists(leasePath), "competing writer cannot publish sidecar");
        }
        byte[] tampered = immutableFile.clone();
        tampered[tampered.length / 2] ^= 1;
        Files.write(sidecar, tampered, StandardOpenOption.TRUNCATE_EXISTING);
        boolean checksumRejected = false;
        try { BlockEntitySidecarStore.readVerified(sidecar, op, chunk, blockStateSha); }
        catch (java.io.IOException expected) { checksumRejected = true; }
        check(checksumRejected, "corrupt original NBT sidecar refused");
        boolean corruptReplacementRejected = false;
        try { BlockEntitySidecarStore.writeExact(sidecar, snapshot); }
        catch (java.io.IOException expected) { corruptReplacementRejected = true; }
        check(corruptReplacementRejected, "corrupted canonical sidecar cannot be silently repaired");
        check(java.util.Arrays.equals(tampered, Files.readAllBytes(sidecar)),
                "corrupt original NBT evidence retained unchanged");

        byte[] unknownSchema = immutableFile.clone();
        unknownSchema[7] = 99;
        int payloadBytes = unknownSchema.length - 32;
        byte[] forgedDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.util.Arrays.copyOf(unknownSchema, payloadBytes));
        System.arraycopy(forgedDigest, 0, unknownSchema, payloadBytes, forgedDigest.length);
        Path unknown = dir.resolve("unknown-version.ocbe");
        Files.write(unknown, unknownSchema);
        boolean versionRejected = false;
        try { BlockEntitySidecarStore.readVerified(unknown, op, chunk, blockStateSha); }
        catch (java.io.IOException expected) { versionRejected = true; }
        check(versionRejected, "forged checksum cannot authorize unknown NBT sidecar schema");
        deleteTree(dir);
    }

    private static void testBlockEntitySidecarArchive() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-be-archive");
        Path live = dir.resolve("block-entities.ocbe");
        Path archive = dir.resolve("block-entities.ocbe.completed.archive");
        ChunkKey chunk = new ChunkKey(7, 11);
        String op = "be-archive-operation";
        String sourceSha = "a".repeat(64);
        var snapshot = new BlockEntityBackupContract.Envelope(
                op, chunk, sourceSha, 512,
                List.of(new BlockEntityBackupContract.Entry(
                        17, "minecraft:chest", new byte[]{10, 0, 3, 1, 0})));
        BlockEntitySidecarStore.writeExact(live, snapshot);
        byte[] original = Files.readAllBytes(live);
        String archivedSha = BlockEntitySidecarArchive.archiveExact(
                live, archive, op, chunk, sourceSha);
        check(archivedSha.matches("[0-9a-f]{64}"),
                "completed block-entity sidecar archive has file SHA-256");
        check(!Files.exists(live) && Files.exists(archive),
                "successful sidecar archive consumes live copy and retains immutable archive");
        check(java.util.Arrays.equals(original, Files.readAllBytes(archive)),
                "block-entity archive preserves exact sidecar bytes");
        BlockEntitySidecarStore.readVerified(archive, op, chunk, sourceSha);

        BlockEntitySidecarStore.writeExact(live, snapshot);
        eq(archivedSha, BlockEntitySidecarArchive.archiveExact(
                live, archive, op, chunk, sourceSha),
                "identical restarted sidecar archival is idempotent");
        check(!Files.exists(live) && java.util.Arrays.equals(original, Files.readAllBytes(archive)),
                "idempotent sidecar archival never rewrites canonical archive");

        var changed = new BlockEntityBackupContract.Envelope(
                op, chunk, sourceSha, 512,
                List.of(new BlockEntityBackupContract.Entry(
                        17, "minecraft:chest", new byte[]{10, 0, 3, 9, 0})));
        BlockEntitySidecarStore.writeExact(live, changed);
        boolean changedRejected = false;
        try { BlockEntitySidecarArchive.archiveExact(live, archive, op, chunk, sourceSha); }
        catch (java.io.IOException expected) { changedRejected = true; }
        check(changedRejected, "different restarted NBT sidecar cannot replace completed archive");
        check(Files.exists(live) && java.util.Arrays.equals(original, Files.readAllBytes(archive)),
                "rejected differing sidecar preserves both candidate and completed archive");
        deleteTree(dir);
    }

    private static void testBlockStateRegistryIdentityStore() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-registry-identity");
        Path file = dir.resolve("block-state-registry.identity");
        String first = "a".repeat(64);
        BlockStateRegistryIdentityStore.ensureExact(file, first, 12345);
        byte[] original = Files.readAllBytes(file);
        BlockStateRegistryIdentityStore.ensureExact(file, first, 12345);
        check(java.util.Arrays.equals(original, Files.readAllBytes(file)),
                "matching block-state registry identity reopens idempotently");

        boolean changedHashRejected = false;
        try { BlockStateRegistryIdentityStore.ensureExact(file, "b".repeat(64), 12345); }
        catch (java.io.IOException expected) { changedHashRejected = true; }
        check(changedHashRejected, "changed runtime block-state mapping refuses recovery");
        boolean changedCountRejected = false;
        try { BlockStateRegistryIdentityStore.ensureExact(file, first, 12346); }
        catch (java.io.IOException expected) { changedCountRejected = true; }
        check(changedCountRejected, "changed runtime block-state count refuses recovery");
        check(java.util.Arrays.equals(original, Files.readAllBytes(file)),
                "registry mismatch cannot rewrite canonical runtime identity");

        Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
        byte[] staged = "interrupted alternate registry identity".getBytes(StandardCharsets.UTF_8);
        Files.write(stage, staged);
        boolean ambiguousRejected = false;
        try { BlockStateRegistryIdentityStore.ensureExact(file, first, 12345); }
        catch (java.io.IOException expected) { ambiguousRejected = true; }
        check(ambiguousRejected, "canonical plus staged registry identity refuses ambiguous authority");
        check(java.util.Arrays.equals(original, Files.readAllBytes(file))
                        && java.util.Arrays.equals(staged, Files.readAllBytes(stage)),
                "ambiguous registry refusal preserves both evidence files");
        Files.delete(stage);

        Path orphanFile = dir.resolve("unpublished-registry.identity");
        Path orphanStage = dir.resolve("unpublished-registry.identity.tmp");
        byte[] orphan = "interrupted registry publication".getBytes(StandardCharsets.UTF_8);
        Files.write(orphanStage, orphan);
        boolean orphanRejected = false;
        try { BlockStateRegistryIdentityStore.ensureExact(orphanFile, first, 12345); }
        catch (java.nio.file.FileAlreadyExistsException expected) { orphanRejected = true; }
        check(orphanRejected && !Files.exists(orphanFile),
                "orphan staged registry identity cannot be silently promoted");
        check(java.util.Arrays.equals(orphan, Files.readAllBytes(orphanStage)),
                "orphan registry staging evidence remains byte-for-byte intact");
        deleteTree(dir);
    }

    private static void testBlockEntityAdmission() {
        check(!PreimageAdmissionPolicy.refuses(false, false),
                "ordinary block-state-only preimage is admissible");
        check(PreimageAdmissionPolicy.refuses(true, false),
                "latent block-entity state refused even when entity is not materialized");
        check(PreimageAdmissionPolicy.refuses(false, true),
                "unexpected live block entity refused even if state flag is absent");
        check(PreimageAdmissionPolicy.refuses(true, true),
                "materialized block entity and declared state refused");
        check(!PreimageAdmissionPolicy.refusesStateId(7, 7, true),
                "valid block-state ID and identical state accepted");
        check(PreimageAdmissionPolicy.refusesStateId(-1, -1, false),
                "negative unresolved source ID refused before backup");
        check(PreimageAdmissionPolicy.refusesStateId(7, 8, false),
                "registry ID round-trip mismatch refused before backup");
        check(PreimageAdmissionPolicy.refusesStateId(7, 7, false),
                "different resolved state with same ID refused before backup");
        check(!PreimageAdmissionPolicy.refusesRestoreGeometry(
                19, 318, 19, 318, -64, 319),
                "matching captured geometry within same world limits admitted");
        check(PreimageAdmissionPolicy.refusesRestoreGeometry(
                19, 318, 20, 318, -64, 319),
                "changed configured floor refuses every restore write");
        check(PreimageAdmissionPolicy.refusesRestoreGeometry(
                19, 318, 19, 319, -64, 320),
                "changed vertical world maximum refuses stale backup");
        check(PreimageAdmissionPolicy.refusesRestoreGeometry(
                19, 318, 19, 318, 20, 319),
                "raised world minimum refuses out-of-world captured cells");
        check(PreimageAdmissionPolicy.refusesRestoreGeometry(
                19, 318, 19, 318, -64, 318),
                "lowered world maximum refuses unreachable captured cells");

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
        // Simulate a competing capture process that already owns the stable
        // publication lease. Neither the canonical backup nor an orphan temp
        // may be created by another writer during that window.
        Path publicationLock = dir.resolve("preimage.bin.lock");
        try (var channel = java.nio.channels.FileChannel.open(publicationLock,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            boolean competingCaptureRejected = false;
            try { BlockStatePreimageStore.writeExact(file, original); }
            catch (java.io.IOException expected) { competingCaptureRejected = true; }
            check(competingCaptureRejected, "competing immutable preimage writer refused");
            check(!Files.exists(file), "competing writer cannot publish canonical backup");
            check(!Files.exists(dir.resolve("preimage.bin.tmp")),
                    "competing writer cannot stage another backup");
        }
        BlockStatePreimageStore.writeExact(file, original);
        check(Files.exists(publicationLock), "writer lease identity retained across restart");
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
        boolean corruptOverwriteRejected = false;
        try { BlockStatePreimageStore.writeExact(file, original); }
        catch (java.io.IOException expected) { corruptOverwriteRejected = true; }
        check(corruptOverwriteRejected, "corrupted canonical recovery backup cannot be silently replaced");
        Files.delete(file); // Explicit test reset, never implicit production repair.
        BlockStatePreimageStore.writeExact(file, original);
        boolean operationRejected = false;
        try { BlockStatePreimageStore.readVerified(file, "op-B", key); }
        catch (Exception expected) { operationRejected = true; }
        check(operationRejected, "preimage operation identity mismatch fails closed");

        boolean identityRejected = false;
        try { BlockStatePreimageStore.readVerified(file, "op-A", new ChunkKey(4, -4)); }
        catch (Exception expected) { identityRejected = true; }
        check(identityRejected, "preimage chunk identity mismatch fails closed");
        byte[] digestOracleBytes = Files.readAllBytes(file);
        String independentDigest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(digestOracleBytes));
        String immutableDigest = BlockStatePreimageStore.sha256Hex(file);
        eq(independentDigest, immutableDigest,
                "streamed preimage digest matches independent complete-file SHA-256");
        check(java.util.Arrays.equals(digestOracleBytes, Files.readAllBytes(file)),
                "streamed preimage digest never mutates immutable backup bytes");
        BlockStatePreimageStore.writeExact(file, original);
        eq(immutableDigest, BlockStatePreimageStore.sha256Hex(file),
                "identical replay cannot modify immutable durable backup");
        int[] differentIds = ids.clone();
        differentIds[0] ^= 1;
        boolean differingOverwriteRejected = false;
        try {
            BlockStatePreimageStore.writeExact(file,
                    new BlockStatePreimageStore.Preimage("op-A", key, minY, maxY, differentIds));
        } catch (java.io.IOException expected) { differingOverwriteRejected = true; }
        check(differingOverwriteRejected, "same-operation differing preimage is never overwritten");
        eq(immutableDigest, BlockStatePreimageStore.sha256Hex(file),
                "conflicting preimage cannot modify canonical backup");
        // The full indexed comparison must also inspect the final state rather
        // than accepting a matching prefix after a restart.
        int[] lastCellDifferent = ids.clone();
        lastCellDifferent[lastCellDifferent.length - 1] ^= 0x400;
        boolean finalCellRejected = false;
        try {
            BlockStatePreimageStore.writeExact(file,
                    new BlockStatePreimageStore.Preimage("op-A", key, minY, maxY, lastCellDifferent));
        } catch (java.io.IOException expected) {
            finalCellRejected = expected.getMessage().contains("index " + (ids.length - 1));
        }
        check(finalCellRejected, "immutable backup comparison checks final state without cloning");
        eq(immutableDigest, BlockStatePreimageStore.sha256Hex(file),
                "late conflicting state preserves original canonical digest");

        boolean oversizedOperationRejected = false;
        try {
            BlockStatePreimageStore.writeExact(dir.resolve("oversized-identity.bin"),
                    new BlockStatePreimageStore.Preimage("x".repeat(4097), key, minY, maxY, ids));
        } catch (java.io.IOException expected) { oversizedOperationRejected = true; }
        check(oversizedOperationRejected, "writer rejects operation identity longer than reader limit");
        // A crash can leave a staged backup before its canonical file exists.
        // A new capture must fail without overwriting that evidence.
        Path pendingCanonical = dir.resolve("orphan-canonical.bin");
        Path orphanStage = pendingCanonical.resolveSibling("orphan-canonical.bin.tmp");
        byte[] orphanEvidence = "prior interrupted capture evidence".getBytes(StandardCharsets.UTF_8);
        Files.write(orphanStage, orphanEvidence, StandardOpenOption.CREATE_NEW);
        boolean orphanRefused = false;
        try { BlockStatePreimageStore.writeExact(pendingCanonical, original); }
        catch (java.nio.file.FileAlreadyExistsException expected) { orphanRefused = true; }
        check(orphanRefused, "orphan preimage stage prevents implicit replacement");
        check(!Files.exists(pendingCanonical), "orphan refusal creates no canonical backup");
        check(java.util.Arrays.equals(orphanEvidence, Files.readAllBytes(orphanStage)),
                "orphan preimage bytes preserved exactly");
        Files.move(orphanStage, dir.resolve("archived-orphan-preimage.tmp"));
        BlockStatePreimageStore.writeExact(pendingCanonical, original);
        eq(ids.length, BlockStatePreimageStore.readVerified(pendingCanonical, "op-A", key).count(),
                "explicit orphan archival permits fresh capture");


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
        // Oversized attacker/corruption file must be refused based on metadata
        // BEFORE readAllBytes can allocate a large array.
        Path oversized = dir.resolve("oversized-preimage.bin");
        try (var raf = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            raf.setLength(16L * 1024L * 1024L + 1L);
        }
        boolean oversizedRejected = false;
        try { BlockStatePreimageStore.readVerified(oversized, "op-A", key); }
        catch (java.io.IOException expected) {
            oversizedRejected = expected.getMessage().contains("size bound");
        }
        check(oversizedRejected, "oversized serialized preimage rejected before heap allocation");
        boolean oversizedHashRejected = false;
        try { BlockStatePreimageStore.sha256Hex(oversized); }
        catch (java.io.IOException expected) {
            oversizedHashRejected = expected.getMessage().contains("size bound");
        }
        check(oversizedHashRejected, "preimage hash refuses oversized file before allocation");

        // Cross multiple streaming-buffer boundaries and compare against an
        // independent oracle without granting structural preimage authority.
        Path digestFixture = dir.resolve("digest-boundary-fixture.bin");
        byte[] digestFixtureBytes = new byte[3 * 8192 + 17];
        for (int i = 0; i < digestFixtureBytes.length; i++) {
            digestFixtureBytes[i] = (byte) (i * 31 + 7);
        }
        Files.write(digestFixture, digestFixtureBytes);
        String fixtureOracle = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(digestFixtureBytes));
        eq(fixtureOracle, BlockStatePreimageStore.sha256Hex(digestFixture),
                "streaming digest remains exact across buffer boundaries");
        check(java.util.Arrays.equals(digestFixtureBytes, Files.readAllBytes(digestFixture)),
                "streaming digest preserves arbitrary immutable evidence bytes");

        // A staged write that did not reach atomic replacement must not change
        // the canonical preimage, including its identity and checksum.
        Files.delete(file); // Explicit test reset after intentional corruption above.
        BlockStatePreimageStore.writeExact(file, original);
        String priorDigest = BlockStatePreimageStore.sha256Hex(file);
        Path interruptedTemp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(interruptedTemp, "interrupted-new-backup", StandardCharsets.UTF_8);
        eq(priorDigest, BlockStatePreimageStore.sha256Hex(file),
                "uncommitted staged backup cannot replace durable original");
        eq(ids.length, BlockStatePreimageStore.readVerified(file, "op-A", key).count(),
                "staged backup cannot invalidate original preimage");
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
        boolean xOverflowRejected = false;
        try { new TwoChunkCanaryPlan(
                new ChunkKey(Integer.MIN_VALUE, 0), new ChunkKey(Integer.MAX_VALUE, 0)); }
        catch (IllegalArgumentException expected) { xOverflowRejected = true; }
        check(xOverflowRejected, "wrapped extreme-X chunks cannot impersonate adjacent target");
        boolean zOverflowRejected = false;
        try { new TwoChunkCanaryPlan(
                new ChunkKey(0, Integer.MIN_VALUE), new ChunkKey(0, Integer.MAX_VALUE)); }
        catch (IllegalArgumentException expected) { zOverflowRejected = true; }
        check(zOverflowRejected, "wrapped extreme-Z chunks cannot impersonate adjacent target");
        boolean eastWrapRejected = false;
        try { TwoChunkCanaryPlan.eastOf(new ChunkKey(Integer.MAX_VALUE, 0)); }
        catch (ArithmeticException expected) { eastWrapRejected = true; }
        check(eastWrapRejected, "east-edge construction cannot wrap into distant world coordinates");
        eq(List.of(new ChunkKey(Integer.MIN_VALUE, -3), new ChunkKey(Integer.MIN_VALUE + 1, -3)),
                TwoChunkCanaryPlan.eastOf(new ChunkKey(Integer.MIN_VALUE, -3)).orderedChunks(),
                "safe negative extreme canary adjacency remains supported");
    }

    private static void testTwoChunkCanaryAdmission() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-twochunk-consent");
        Path file = dir.resolve(TwoChunkCanaryAdmission.FILE_NAME);
        CoreConfig allowed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        try {
            check(TwoChunkCanaryAdmission.load(dir, allowed).isEmpty(),
                    "missing separate two-chunk consent never authorizes world writes");
            String valid = "enabled=true\\nfirstX=32\\nfirstZ=32\\nsecondX=33\\nsecondZ=32\\n"
                    + "firstConfirm=ERASE_CHUNK_32_32\\nsecondConfirm=ERASE_CHUNK_33_32\\n";
            Files.writeString(file, valid.replace("\\n", "\n"), StandardCharsets.UTF_8);
            var plan = TwoChunkCanaryAdmission.load(dir, allowed).orElseThrow();
            eq(new ChunkKey(32, 32), plan.first(), "first explicit chunk admitted");
            eq(new ChunkKey(33, 32), plan.second(), "distinct adjacent second chunk admitted");
            eq("chunk_32_32", TwoChunkCanaryAdmission.isolatedChunkDirectory(plan.first()),
                    "first durable evidence isolated by chunk");
            eq("chunk_33_32", TwoChunkCanaryAdmission.isolatedChunkDirectory(plan.second()),
                    "second durable evidence isolated by chunk");

            for (String invalid : new String[] {
                    valid.replace("enabled=true", "enabled=false"),
                    valid.replace("secondConfirm=ERASE_CHUNK_33_32", "secondConfirm=ERASE_CHUNK_34_32"),
                    valid.replace("secondX=33", "secondX=34"),
                    valid.replace("firstX=32", "firstX=1000"),
                    valid.replace("firstZ=32", "firstZ=2147483648"),
                    valid + "secondX=33\\n"
            }) {
                Files.writeString(file, invalid.replace("\\n", "\n"), StandardCharsets.UTF_8);
                boolean refused;
                try { refused = TwoChunkCanaryAdmission.load(dir, allowed).isEmpty(); }
                catch (java.io.IOException expected) { refused = true; }
                check(refused, "invalid, duplicate, unconfirmed or outside-world pair refused");
            }
            Files.writeString(file, valid.replace("\\n", "\n"), StandardCharsets.UTF_8);
            CoreConfig singleArmed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 25, 5, true, true, 32, 32, "ERASE_CHUNK_32_32",
                    256, 1024, 3000, 40, 40, false);
            boolean overlappingRefused = false;
            try { TwoChunkCanaryAdmission.load(dir, singleArmed); }
            catch (java.io.IOException expected) { overlappingRefused = true; }
            check(overlappingRefused, "single-chunk authorization cannot overlap two-chunk canary");
            boolean safeHoldRefused = false;
            try { TwoChunkCanaryAdmission.load(dir, CoreConfig.defaults()); }
            catch (java.io.IOException expected) { safeHoldRefused = true; }
            check(safeHoldRefused, "SAFE_HOLD can never authorize a two-chunk operation");
            CoreConfig harnessArmed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, true);
            boolean acceptanceConflictRejected = false;
            try { TwoChunkCanaryAdmission.load(dir, harnessArmed); }
            catch (java.io.IOException expected) { acceptanceConflictRejected = true; }
            check(acceptanceConflictRejected, "single-chunk acceptance harness does not grant canary authority");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testFourChunkCanaryPlan() {
        FourChunkCanaryPlan plan = FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32));
        eq(List.of(new ChunkKey(32, 32), new ChunkKey(33, 32),
                        new ChunkKey(32, 33), new ChunkKey(33, 33)),
                plan.orderedChunks(), "four-chunk canary deterministic row-major square");
        boolean duplicateRejected = false;
        try {
            new FourChunkCanaryPlan(new ChunkKey(0, 0), new ChunkKey(1, 0),
                    new ChunkKey(0, 1), new ChunkKey(0, 1));
        } catch (IllegalArgumentException expected) { duplicateRejected = true; }
        check(duplicateRejected, "four-chunk canary rejects duplicate target");
        boolean malformedRejected = false;
        try {
            new FourChunkCanaryPlan(new ChunkKey(0, 0), new ChunkKey(1, 0),
                    new ChunkKey(0, 1), new ChunkKey(2, 1));
        } catch (IllegalArgumentException expected) { malformedRejected = true; }
        check(malformedRejected, "four-chunk canary rejects non-2x2 geometry");
        boolean xOverflowRejected = false;
        try { FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(Integer.MAX_VALUE, 0)); }
        catch (ArithmeticException expected) { xOverflowRejected = true; }
        check(xOverflowRejected, "four-chunk east edge construction cannot wrap");
        boolean zOverflowRejected = false;
        try { FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(0, Integer.MAX_VALUE)); }
        catch (ArithmeticException expected) { zOverflowRejected = true; }
        check(zOverflowRejected, "four-chunk south edge construction cannot wrap");
    }

    private static void testFourChunkCanaryAdmission() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-fourchunk-consent");
        Path file = dir.resolve(FourChunkCanaryAdmission.FILE_NAME);
        Path two = dir.resolve(TwoChunkCanaryAdmission.FILE_NAME);
        CoreConfig allowed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        String valid = "enabled=true\nnorthWestX=32\nnorthWestZ=32\n"
                + "northEastX=33\nnorthEastZ=32\nsouthWestX=32\nsouthWestZ=33\n"
                + "southEastX=33\nsouthEastZ=33\n"
                + "northWestConfirm=ERASE_CHUNK_32_32\n"
                + "northEastConfirm=ERASE_CHUNK_33_32\n"
                + "southWestConfirm=ERASE_CHUNK_32_33\n"
                + "southEastConfirm=ERASE_CHUNK_33_33\n";
        try {
            check(FourChunkCanaryAdmission.load(dir, allowed).isEmpty(),
                    "missing separate four-chunk consent never authorizes world writes");
            Files.writeString(file, valid, StandardCharsets.UTF_8);
            FourChunkCanaryPlan plan = FourChunkCanaryAdmission.load(dir, allowed).orElseThrow();
            eq(List.of(new ChunkKey(32, 32), new ChunkKey(33, 32),
                            new ChunkKey(32, 33), new ChunkKey(33, 33)),
                    plan.orderedChunks(), "four explicit confirmed chunks admitted in fixed order");

            for (String invalid : new String[] {
                    valid.replace("enabled=true", "enabled=false"),
                    valid.replace("southEastConfirm=ERASE_CHUNK_33_33",
                            "southEastConfirm=ERASE_CHUNK_34_33"),
                    valid.replace("southEastX=33", "southEastX=34"),
                    valid.replace("northWestX=32", "northWestX=1000"),
                    valid + "northEastX=33\n"
            }) {
                Files.writeString(file, invalid, StandardCharsets.UTF_8);
                boolean refused;
                try { refused = FourChunkCanaryAdmission.load(dir, allowed).isEmpty(); }
                catch (java.io.IOException expected) { refused = true; }
                check(refused, "invalid, duplicate, unconfirmed or outside-world four-chunk plan refused");
            }

            Files.writeString(file, valid, StandardCharsets.UTF_8);
            Files.writeString(two,
                    "enabled=true\nfirstX=32\nfirstZ=32\nsecondX=33\nsecondZ=32\n"
                            + "firstConfirm=ERASE_CHUNK_32_32\nsecondConfirm=ERASE_CHUNK_33_32\n",
                    StandardCharsets.UTF_8);
            boolean overlapRejected = false;
            try { FourChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expected) { overlapRejected = true; }
            check(overlapRejected, "four-chunk authority refuses simultaneous enabled two-chunk consent");
            Files.delete(two);

            CoreConfig singleArmed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 25, 5, true, true, 32, 32, "ERASE_CHUNK_32_32",
                    256, 1024, 3000, 40, 40, false);
            boolean singleRejected = false;
            try { FourChunkCanaryAdmission.load(dir, singleArmed); }
            catch (java.io.IOException expected) { singleRejected = true; }
            check(singleRejected, "single-chunk authorization cannot overlap four-chunk canary");

            boolean safeHoldRejected = false;
            try { FourChunkCanaryAdmission.load(dir, CoreConfig.defaults()); }
            catch (java.io.IOException expected) { safeHoldRejected = true; }
            check(safeHoldRejected, "SAFE_HOLD can never authorize a four-chunk operation");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testNineChunkCanaryPlan() {
        NineChunkCanaryPlan plan = NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(-1, -1));
        eq(List.of(
                new ChunkKey(-1, -1), new ChunkKey(0, -1), new ChunkKey(1, -1),
                new ChunkKey(-1, 0), new ChunkKey(0, 0), new ChunkKey(1, 0),
                new ChunkKey(-1, 1), new ChunkKey(0, 1), new ChunkKey(1, 1)),
                plan.orderedChunks(), "nine-chunk canary deterministic row-major 3x3 square");

        boolean wrongCountRejected = false;
        try { new NineChunkCanaryPlan(plan.orderedChunks().subList(0, 8)); }
        catch (IllegalArgumentException expected) { wrongCountRejected = true; }
        check(wrongCountRejected, "nine-chunk plan rejects wrong chunk count");

        java.util.ArrayList<ChunkKey> duplicate = new java.util.ArrayList<>(plan.orderedChunks());
        duplicate.set(8, duplicate.get(7));
        boolean duplicateRejected = false;
        try { new NineChunkCanaryPlan(duplicate); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        check(duplicateRejected, "nine-chunk plan rejects duplicate chunk");

        java.util.ArrayList<ChunkKey> reordered = new java.util.ArrayList<>(plan.orderedChunks());
        java.util.Collections.swap(reordered, 1, 2);
        boolean reorderedRejected = false;
        try { new NineChunkCanaryPlan(reordered); }
        catch (IllegalArgumentException expected) { reorderedRejected = true; }
        check(reorderedRejected, "nine-chunk plan rejects non-row-major geometry");

        boolean xOverflowRejected = false;
        try { NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(Integer.MAX_VALUE - 1, 0)); }
        catch (ArithmeticException expected) { xOverflowRejected = true; }
        check(xOverflowRejected, "nine-chunk east extent cannot wrap coordinates");

        boolean zOverflowRejected = false;
        try { NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(0, Integer.MAX_VALUE - 1)); }
        catch (ArithmeticException expected) { zOverflowRejected = true; }
        check(zOverflowRejected, "nine-chunk south extent cannot wrap coordinates");
    }

    private static void testNineChunkCanaryAdmission() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-ninechunk-consent");
        Path file = dir.resolve(NineChunkCanaryAdmission.FILE_NAME);
        Path two = dir.resolve(TwoChunkCanaryAdmission.FILE_NAME);
        CoreConfig allowed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        StringBuilder valid = new StringBuilder("enabled=true\n");
        for (int i = 0; i < 9; i++) {
            int x = 32 + (i % 3);
            int z = 32 + (i / 3);
            valid.append("chunk").append(i).append("X=").append(x).append("\n")
                    .append("chunk").append(i).append("Z=").append(z).append("\n")
                    .append("chunk").append(i).append("Confirm=ERASE_CHUNK_")
                    .append(x).append("_").append(z).append("\n");
        }
        try {
            check(NineChunkCanaryAdmission.load(dir, allowed).isEmpty(),
                    "missing separate nine-chunk consent never authorizes world writes");
            Files.writeString(file, valid.toString(), StandardCharsets.UTF_8);
            NineChunkCanaryPlan plan = NineChunkCanaryAdmission.load(dir, allowed).orElseThrow();
            eq(NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32)).orderedChunks(),
                    plan.orderedChunks(), "nine explicit confirmed chunks admitted row-major");

            Files.writeString(file, valid.toString().replace(
                    "chunk8Confirm=ERASE_CHUNK_34_34",
                    "chunk8Confirm=ERASE_CHUNK_35_34"), StandardCharsets.UTF_8);
            boolean tokenRejected = false;
            try { NineChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expected) { tokenRejected = true; }
            check(tokenRejected, "nine-chunk admission refuses mismatched destructive token");

            Files.writeString(file, valid.toString().replace(
                    "chunk8X=34", "chunk8X=35"), StandardCharsets.UTF_8);
            boolean geometryRejected = false;
            try { NineChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expected) { geometryRejected = true; }
            check(geometryRejected, "nine-chunk admission refuses non-3x3 geometry");

            Files.writeString(file, valid.toString() + "chunk0X=32\n", StandardCharsets.UTF_8);
            boolean duplicateRejected = false;
            try { NineChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expected) { duplicateRejected = true; }
            check(duplicateRejected, "nine-chunk admission refuses duplicate properties");

            Files.writeString(file, valid.toString(), StandardCharsets.UTF_8);
            Files.writeString(two,
                    "enabled=true\nfirstX=32\nfirstZ=32\nsecondX=33\nsecondZ=32\n"
                            + "firstConfirm=ERASE_CHUNK_32_32\nsecondConfirm=ERASE_CHUNK_33_32\n",
                    StandardCharsets.UTF_8);
            boolean overlapRejected = false;
            try { NineChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expected) { overlapRejected = true; }
            check(overlapRejected, "nine-chunk authority refuses simultaneous enabled two-chunk consent");
            Files.delete(two);

            CoreConfig singleArmed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 25, 5, true, true, 32, 32, "ERASE_CHUNK_32_32",
                    256, 1024, 3000, 40, 40, false);
            boolean singleRejected = false;
            try { NineChunkCanaryAdmission.load(dir, singleArmed); }
            catch (java.io.IOException expected) { singleRejected = true; }
            check(singleRejected, "single-chunk authorization cannot overlap nine-chunk canary");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testImmutableNineChunkPlan() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-ninechunk-identity");
        Path file = dir.resolve("nine-operation.identity");
        CoreConfig config = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        NineChunkCanaryPlan accepted =
                NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32));
        try {
            NineChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            byte[] original = Files.readAllBytes(file);
            String text = Files.readString(file);
            check(text.contains("chunk0X=32") && text.contains("chunk8Z=34"),
                    "published nine-chunk identity binds all nine targets");
            NineChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            check(Arrays.equals(original, Files.readAllBytes(file)),
                    "nine-chunk restart cannot rewrite immutable plan bytes");

            boolean changedRejected = false;
            NineChunkCanaryPlan changed =
                    NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(33, 32));
            try { NineChunkCanaryIdentityStore.ensureExact(file, changed, config); }
            catch (java.io.IOException expected) { changedRejected = true; }
            check(changedRejected, "nine-chunk restart refuses redirected plan");
            check(Arrays.equals(original, Files.readAllBytes(file)),
                    "refused nine-chunk redirect preserves canonical identity");

            Path stage = dir.resolve("nine-operation.identity.tmp");
            Files.writeString(stage, "orphan nine chunk plan", StandardCharsets.UTF_8);
            boolean ambiguousRejected = false;
            try { NineChunkCanaryIdentityStore.ensureExact(file, accepted, config); }
            catch (java.io.IOException expected) { ambiguousRejected = true; }
            check(ambiguousRejected, "canonical plus orphan staged nine-chunk plan refuses ambiguity");
            check(Files.exists(stage) && Arrays.equals(original, Files.readAllBytes(file)),
                    "ambiguous nine-chunk evidence preserved without rewrite");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testSixteenChunkCanaryPlan() {
        SixteenChunkCanaryPlan plan = SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(-1, -1));
        java.util.ArrayList<ChunkKey> expected = new java.util.ArrayList<>();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                expected.add(new ChunkKey(-1 + column, -1 + row));
            }
        }
        eq(expected, plan.orderedChunks(), "sixteen-chunk canary deterministic row-major 4x4 square");

        boolean wrongCountRejected = false;
        try { new SixteenChunkCanaryPlan(plan.orderedChunks().subList(0, 15)); }
        catch (IllegalArgumentException expectedFailure) { wrongCountRejected = true; }
        check(wrongCountRejected, "sixteen-chunk plan rejects wrong chunk count");

        java.util.ArrayList<ChunkKey> duplicate = new java.util.ArrayList<>(plan.orderedChunks());
        duplicate.set(15, duplicate.get(14));
        boolean duplicateRejected = false;
        try { new SixteenChunkCanaryPlan(duplicate); }
        catch (IllegalArgumentException expectedFailure) { duplicateRejected = true; }
        check(duplicateRejected, "sixteen-chunk plan rejects duplicate chunk");

        java.util.ArrayList<ChunkKey> reordered = new java.util.ArrayList<>(plan.orderedChunks());
        java.util.Collections.swap(reordered, 2, 3);
        boolean reorderedRejected = false;
        try { new SixteenChunkCanaryPlan(reordered); }
        catch (IllegalArgumentException expectedFailure) { reorderedRejected = true; }
        check(reorderedRejected, "sixteen-chunk plan rejects non-row-major geometry");

        boolean xOverflowRejected = false;
        try { SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(Integer.MAX_VALUE - 2, 0)); }
        catch (ArithmeticException expectedFailure) { xOverflowRejected = true; }
        check(xOverflowRejected, "sixteen-chunk east extent cannot wrap coordinates");

        boolean zOverflowRejected = false;
        try { SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(0, Integer.MAX_VALUE - 2)); }
        catch (ArithmeticException expectedFailure) { zOverflowRejected = true; }
        check(zOverflowRejected, "sixteen-chunk south extent cannot wrap coordinates");
    }

    private static void testSixteenChunkCanaryAdmission() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-sixteenchunk-consent");
        Path file = dir.resolve(SixteenChunkCanaryAdmission.FILE_NAME);
        Path nine = dir.resolve(NineChunkCanaryAdmission.FILE_NAME);
        CoreConfig allowed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        StringBuilder valid = new StringBuilder("enabled=true\n");
        for (int i = 0; i < 16; i++) {
            int x = 32 + (i % 4);
            int z = 32 + (i / 4);
            valid.append("chunk").append(i).append("X=").append(x).append("\n")
                    .append("chunk").append(i).append("Z=").append(z).append("\n")
                    .append("chunk").append(i).append("Confirm=ERASE_CHUNK_")
                    .append(x).append("_").append(z).append("\n");
        }
        try {
            check(SixteenChunkCanaryAdmission.load(dir, allowed).isEmpty(),
                    "missing separate sixteen-chunk consent never authorizes world writes");
            Files.writeString(file, valid.toString(), StandardCharsets.UTF_8);
            SixteenChunkCanaryPlan plan = SixteenChunkCanaryAdmission.load(dir, allowed).orElseThrow();
            eq(SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32)).orderedChunks(),
                    plan.orderedChunks(), "sixteen explicit confirmed chunks admitted row-major");

            Files.writeString(file, valid.toString().replace(
                    "chunk15Confirm=ERASE_CHUNK_35_35",
                    "chunk15Confirm=ERASE_CHUNK_36_35"), StandardCharsets.UTF_8);
            boolean tokenRejected = false;
            try { SixteenChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expectedFailure) { tokenRejected = true; }
            check(tokenRejected, "sixteen-chunk admission refuses mismatched destructive token");

            Files.writeString(file, valid.toString().replace(
                    "chunk15X=35", "chunk15X=36"), StandardCharsets.UTF_8);
            boolean geometryRejected = false;
            try { SixteenChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expectedFailure) { geometryRejected = true; }
            check(geometryRejected, "sixteen-chunk admission refuses non-4x4 geometry");

            Files.writeString(file, valid.toString() + "chunk0X=32\n", StandardCharsets.UTF_8);
            boolean duplicateRejected = false;
            try { SixteenChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expectedFailure) { duplicateRejected = true; }
            check(duplicateRejected, "sixteen-chunk admission refuses duplicate properties");

            Files.writeString(file, valid.toString(), StandardCharsets.UTF_8);
            StringBuilder nineValid = new StringBuilder("enabled=true\n");
            for (int i = 0; i < 9; i++) {
                int x = 32 + (i % 3);
                int z = 32 + (i / 3);
                nineValid.append("chunk").append(i).append("X=").append(x).append("\n")
                        .append("chunk").append(i).append("Z=").append(z).append("\n")
                        .append("chunk").append(i).append("Confirm=ERASE_CHUNK_")
                        .append(x).append("_").append(z).append("\n");
            }
            Files.writeString(nine, nineValid.toString(), StandardCharsets.UTF_8);
            boolean overlapRejected = false;
            try { SixteenChunkCanaryAdmission.load(dir, allowed); }
            catch (java.io.IOException expectedFailure) { overlapRejected = true; }
            check(overlapRejected, "sixteen-chunk authority refuses simultaneous enabled nine-chunk consent");
            Files.delete(nine);

            CoreConfig singleArmed = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 25, 5, true, true, 32, 32, "ERASE_CHUNK_32_32",
                    256, 1024, 3000, 40, 40, false);
            boolean singleRejected = false;
            try { SixteenChunkCanaryAdmission.load(dir, singleArmed); }
            catch (java.io.IOException expectedFailure) { singleRejected = true; }
            check(singleRejected, "single-chunk authorization cannot overlap sixteen-chunk canary");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testImmutableSixteenChunkPlan() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-sixteenchunk-identity");
        Path file = dir.resolve("sixteen-operation.identity");
        CoreConfig config = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        SixteenChunkCanaryPlan accepted =
                SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32));
        try {
            SixteenChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            byte[] original = Files.readAllBytes(file);
            String text = Files.readString(file);
            check(text.contains("chunk0X=32") && text.contains("chunk15Z=35"),
                    "published sixteen-chunk identity binds all sixteen targets");
            SixteenChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            check(Arrays.equals(original, Files.readAllBytes(file)),
                    "sixteen-chunk restart cannot rewrite immutable plan bytes");

            boolean changedRejected = false;
            SixteenChunkCanaryPlan changed =
                    SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(33, 32));
            try { SixteenChunkCanaryIdentityStore.ensureExact(file, changed, config); }
            catch (java.io.IOException expectedFailure) { changedRejected = true; }
            check(changedRejected, "sixteen-chunk restart refuses redirected plan");
            check(Arrays.equals(original, Files.readAllBytes(file)),
                    "refused sixteen-chunk redirect preserves canonical identity");

            Path stage = dir.resolve("sixteen-operation.identity.tmp");
            Files.writeString(stage, "orphan sixteen chunk plan", StandardCharsets.UTF_8);
            boolean ambiguousRejected = false;
            try { SixteenChunkCanaryIdentityStore.ensureExact(file, accepted, config); }
            catch (java.io.IOException expectedFailure) { ambiguousRejected = true; }
            check(ambiguousRejected, "canonical plus orphan staged sixteen-chunk plan refuses ambiguity");
            check(Files.exists(stage) && Arrays.equals(original, Files.readAllBytes(file)),
                    "ambiguous sixteen-chunk evidence preserved without rewrite");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testImmutableFourChunkPlan() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-fourchunk-identity");
        Path file = dir.resolve("quad-operation.identity");
        CoreConfig config = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        FourChunkCanaryPlan accepted =
                FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(32, 32));
        try {
            FourChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            byte[] original = Files.readAllBytes(file);
            String text = Files.readString(file);
            check(text.contains("chunk0X=32") && text.contains("chunk3Z=33"),
                    "published four-chunk identity binds all four targets");
            FourChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            check(Arrays.equals(original, Files.readAllBytes(file)),
                    "four-chunk restart cannot rewrite immutable plan bytes");

            boolean changedRejected = false;
            FourChunkCanaryPlan changed = FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(33, 32));
            try { FourChunkCanaryIdentityStore.ensureExact(file, changed, config); }
            catch (java.io.IOException expected) { changedRejected = true; }
            check(changedRejected, "four-chunk restart refuses redirected plan");
            check(Arrays.equals(original, Files.readAllBytes(file)),
                    "refused four-chunk redirect preserves canonical identity");

            Path stage = dir.resolve("quad-operation.identity.tmp");
            Files.writeString(stage, "orphan four chunk plan", StandardCharsets.UTF_8);
            boolean ambiguousRejected = false;
            try { FourChunkCanaryIdentityStore.ensureExact(file, accepted, config); }
            catch (java.io.IOException expected) { ambiguousRejected = true; }
            check(ambiguousRejected, "canonical plus orphan staged four-chunk plan refuses ambiguity");
            check(Files.exists(stage) && Arrays.equals(original, Files.readAllBytes(file)),
                    "ambiguous four-chunk evidence preserved without rewrite");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testImmutableTwoChunkPlan() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-pair-identity");
        Path file = dir.resolve("pair-operation.identity");
        CoreConfig config = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                62, 25, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
        TwoChunkCanaryPlan accepted = TwoChunkCanaryPlan.eastOf(new ChunkKey(32, 32));
        try {
            TwoChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            byte[] original = Files.readAllBytes(file);
            check(Files.readString(file).contains("secondX=33"),
                    "published pair binds second target before either chunk opens");
            TwoChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            check(java.util.Arrays.equals(original, Files.readAllBytes(file)),
                    "pair restart cannot rewrite immutable plan bytes");

            boolean redirectedRejected = false;
            try {
                TwoChunkCanaryIdentityStore.ensureExact(file,
                        new TwoChunkCanaryPlan(new ChunkKey(32, 32), new ChunkKey(32, 33)), config);
            } catch (java.io.IOException expected) { redirectedRejected = true; }
            check(redirectedRejected, "completed first chunk cannot resume under new second target");
            CoreConfig changedFloor = new CoreConfig(OperationMode.CORE_AUTHORING, 20_000, 0, 0,
                    62, 24, 5, true, false, 0, 0, "", 256, 1024, 3000, 40, 40, false);
            boolean changedGeometryRejected = false;
            try { TwoChunkCanaryIdentityStore.ensureExact(file, accepted, changedFloor); }
            catch (java.io.IOException expected) { changedGeometryRejected = true; }
            check(changedGeometryRejected, "pair cannot resume under changed ocean floor");
            check(java.util.Arrays.equals(original, Files.readAllBytes(file)),
                    "refused pair changes preserve original canonical authority");

            // A valid canonical plan does not excuse a contradictory staged
            // publication. Both evidence files must remain untouched.
            Path ambiguousStage = dir.resolve("pair-operation.identity.tmp");
            byte[] ambiguous = "different unpublished plan".getBytes(StandardCharsets.UTF_8);
            Files.write(ambiguousStage, ambiguous);
            boolean ambiguousRejected = false;
            try { TwoChunkCanaryIdentityStore.ensureExact(file, accepted, config); }
            catch (java.io.IOException expected) { ambiguousRejected = true; }
            check(ambiguousRejected,
                    "canonical plus orphan staged pair identity refuses ambiguous authority");
            check(java.util.Arrays.equals(original, Files.readAllBytes(file))
                    && java.util.Arrays.equals(ambiguous, Files.readAllBytes(ambiguousStage)),
                    "ambiguous refusal preserves canonical and orphan bytes without rewrite");
            Files.delete(ambiguousStage);
            TwoChunkCanaryIdentityStore.ensureExact(file, accepted, config);
            check(java.util.Arrays.equals(original, Files.readAllBytes(file)),
                    "normal idempotent restart resumes after staged evidence is explicitly cleared");

            Path orphanFile = dir.resolve("unpublished.identity");
            Path orphanStage = dir.resolve("unpublished.identity.tmp");
            byte[] interrupted = "original interrupted plan evidence".getBytes(StandardCharsets.UTF_8);
            Files.write(orphanStage, interrupted);
            boolean orphanRejected = false;
            try { TwoChunkCanaryIdentityStore.ensureExact(orphanFile, accepted, config); }
            catch (java.nio.file.FileAlreadyExistsException expected) { orphanRejected = true; }
            check(orphanRejected && !Files.exists(orphanFile),
                    "orphan staging evidence cannot be silently promoted or overwritten");
            check(java.util.Arrays.equals(interrupted, Files.readAllBytes(orphanStage)),
                    "failed pair publication retains its original crash evidence");
            Files.writeString(file, "tamper", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            boolean tamperedRefused = false;
            try { TwoChunkCanaryIdentityStore.ensureExact(file, accepted, config); }
            catch (java.io.IOException expected) { tamperedRefused = true; }
            check(tamperedRefused, "corrupt canonical pair cannot be silently replaced");
        } finally { deleteTree(dir); }
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
        eq(1, firstPorts.closes, "first canary adapter closes only on terminal COMPLETE");

        for (int i = 0; i < 10; i++) check(coordinator.tick(2000 + i), "second canary advances transition " + i);
        var done = coordinator.snapshot();
        check(done.complete(), "two-chunk coordinator complete");
        eq(2, done.completeCount(), "both canary chunks complete");
        eq(1, ports.get(new ChunkKey(1, 0)).closes, "second adapter closed on terminal COMPLETE");
        check(!coordinator.tick(3000), "complete coordinator is inert");
        deleteTree(dir);
    }

    private static void testFourChunkSequentialCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-four-chunk");
        FourChunkCanaryPlan plan = FourChunkCanaryPlan.squareEastSouthOf(new ChunkKey(0, 0));
        java.util.Map<ChunkKey, CountingPorts> ports = new java.util.HashMap<>();
        SequentialChunkCoordinator.PipelineOpener opener = key ->
                SingleChunkPipeline.open(new CoreJournal(dir.resolve(key.x() + "_" + key.z() + ".journal")), key);
        SequentialChunkCoordinator coordinator = new SequentialChunkCoordinator(
                plan.orderedChunks(), opener, key ->
                        ports.computeIfAbsent(key, ignored -> new CountingPorts()));
        try {
            long epoch = 10_000;
            for (int chunkIndex = 0; chunkIndex < 4; chunkIndex++) {
                ChunkKey expected = plan.orderedChunks().get(chunkIndex);
                var before = coordinator.snapshot();
                eq(chunkIndex, before.completeCount(),
                        "four-chunk coordinator complete count before chunk " + chunkIndex);
                eq(expected, before.activeChunk(),
                        "four-chunk coordinator deterministic active chunk " + chunkIndex);
                for (int stage = 0; stage < 10; stage++) {
                    check(coordinator.tick(epoch++),
                            "four-chunk coordinator advances chunk " + chunkIndex + " stage " + stage);
                }
                eq(1, ports.get(expected).closes,
                        "completed four-chunk adapter closes exactly once " + chunkIndex);
            }
            var done = coordinator.snapshot();
            check(done.complete(), "four-chunk coordinator reaches complete");
            eq(4, done.completeCount(), "all four canary chunks complete");
            check(!coordinator.tick(epoch), "completed four-chunk coordinator remains inert");
            for (ChunkKey key : plan.orderedChunks()) {
                eq(1, ports.get(key).loads, "each four-chunk target loads once " + key);
                eq(1, ports.get(key).releases, "each four-chunk target releases once " + key);
            }
        } finally {
            coordinator.close();
            deleteTree(dir);
        }
    }

    private static void testNineChunkSequentialCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-nine-chunk");
        NineChunkCanaryPlan plan = NineChunkCanaryPlan.squareEastSouthOf(new ChunkKey(0, 0));
        java.util.Map<ChunkKey, CountingPorts> ports = new java.util.HashMap<>();
        SequentialChunkCoordinator.PipelineOpener opener = key ->
                SingleChunkPipeline.open(new CoreJournal(dir.resolve(key.x() + "_" + key.z() + ".journal")), key);
        SequentialChunkCoordinator coordinator = new SequentialChunkCoordinator(
                plan.orderedChunks(), opener, key ->
                        ports.computeIfAbsent(key, ignored -> new CountingPorts()));
        try {
            long epoch = 20_000;
            for (int chunkIndex = 0; chunkIndex < 9; chunkIndex++) {
                ChunkKey expected = plan.orderedChunks().get(chunkIndex);
                var before = coordinator.snapshot();
                eq(chunkIndex, before.completeCount(),
                        "nine-chunk coordinator complete count before chunk " + chunkIndex);
                eq(expected, before.activeChunk(),
                        "nine-chunk coordinator deterministic active chunk " + chunkIndex);
                for (int stage = 0; stage < 10; stage++) {
                    check(coordinator.tick(epoch++),
                            "nine-chunk coordinator advances chunk " + chunkIndex + " stage " + stage);
                }
                eq(1, ports.get(expected).closes,
                        "completed nine-chunk adapter closes exactly once " + chunkIndex);
            }
            var done = coordinator.snapshot();
            check(done.complete(), "nine-chunk coordinator reaches complete");
            eq(9, done.completeCount(), "all nine canary chunks complete");
            check(!coordinator.tick(epoch), "completed nine-chunk coordinator remains inert");
            for (ChunkKey key : plan.orderedChunks()) {
                eq(1, ports.get(key).loads, "each nine-chunk target loads once " + key);
                eq(1, ports.get(key).releases, "each nine-chunk target releases once " + key);
            }
        } finally {
            coordinator.close();
            deleteTree(dir);
        }
    }

    private static void testSixteenChunkSequentialCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-sixteen-chunk");
        SixteenChunkCanaryPlan plan = SixteenChunkCanaryPlan.squareEastSouthOf(new ChunkKey(0, 0));
        java.util.Map<ChunkKey, CountingPorts> ports = new java.util.HashMap<>();
        SequentialChunkCoordinator.PipelineOpener opener = key ->
                SingleChunkPipeline.open(new CoreJournal(dir.resolve(key.x() + "_" + key.z() + ".journal")), key);
        SequentialChunkCoordinator coordinator = new SequentialChunkCoordinator(
                plan.orderedChunks(), opener, key ->
                        ports.computeIfAbsent(key, ignored -> new CountingPorts()));
        try {
            long epoch = 30_000;
            for (int chunkIndex = 0; chunkIndex < 16; chunkIndex++) {
                ChunkKey expected = plan.orderedChunks().get(chunkIndex);
                var before = coordinator.snapshot();
                eq(chunkIndex, before.completeCount(),
                        "sixteen-chunk coordinator complete count before chunk " + chunkIndex);
                eq(expected, before.activeChunk(),
                        "sixteen-chunk coordinator deterministic active chunk " + chunkIndex);
                for (int stage = 0; stage < 10; stage++) {
                    check(coordinator.tick(epoch++),
                            "sixteen-chunk coordinator advances chunk " + chunkIndex + " stage " + stage);
                }
                eq(1, ports.get(expected).closes,
                        "completed sixteen-chunk adapter closes exactly once " + chunkIndex);
            }
            var done = coordinator.snapshot();
            check(done.complete(), "sixteen-chunk coordinator reaches complete");
            eq(16, done.completeCount(), "all sixteen canary chunks complete");
            check(!coordinator.tick(epoch), "completed sixteen-chunk coordinator remains inert");
            for (ChunkKey key : plan.orderedChunks()) {
                eq(1, ports.get(key).loads, "each sixteen-chunk target loads once " + key);
                eq(1, ports.get(key).releases, "each sixteen-chunk target releases once " + key);
            }
        } finally {
            coordinator.close();
            deleteTree(dir);
        }
    }

    private static void testSequentialAdapterLease() throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-core-adapter-lease");
        ChunkKey first = new ChunkKey(2, 2);
        ChunkKey second = new ChunkKey(3, 2);
        var created = new java.util.concurrent.atomic.AtomicInteger();
        var current = new java.util.ArrayList<CountingPorts>();
        SequentialChunkCoordinator coordinator = new SequentialChunkCoordinator(
                List.of(first, second),
                key -> SingleChunkPipeline.open(
                        new CoreJournal(dir.resolve(key.x() + "_" + key.z() + ".journal")), key),
                key -> {
                    created.incrementAndGet();
                    CountingPorts ports = new CountingPorts();
                    current.add(ports);
                    return ports;
                });
        try {
            for (int i = 0; i < 9; i++) {
                check(coordinator.tick(1000 + i), "first sequential chunk durably advances");
                eq(1, created.get(), "pending stages never reconstruct the resident adapter");
                eq(0, current.get(0).closes, "pending stages retain the radius-zero adapter lease");
            }
            check(coordinator.tick(1010), "first chunk durably completes");
            eq(1, current.get(0).closes, "completed adapter closes exactly once");
            check(coordinator.tick(1011), "second chunk may open after first completion only");
            eq(2, created.get(), "second chunk opens its own separate adapter");
            eq(0, current.get(1).closes, "second adapter retained while active");
            coordinator.close(); // Simulate safe Save & Quit before second completes.
            eq(1, current.get(1).closes, "explicit shutdown releases only active second adapter");
            coordinator.close();
            eq(1, current.get(1).closes, "repeated shutdown never double-releases ticket");
            check(coordinator.tick(1012), "restart rebuilds adapter from durable second journal");
            eq(3, created.get(), "restarted second chunk reacquires one adapter without first reopen");
            eq(0, current.get(2).closes, "reopened active chunk retained through waiting ticks");
        } finally {
            coordinator.close();
            deleteTree(dir);
        }
        eq(1, current.get(2).closes, "last active adapter closed during teardown");
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
        int loads, preimages, authors, settles, persists, lights, verifies, restores, restoreVerifies, releases, closes;
        @Override public void close() { closes++; }
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
