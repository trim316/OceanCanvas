package net.oceancanvas.core.restore;

import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic publication-contract regression for R1-79. */
public final class BlockStatePreimageAtomicMoveSelfTest {
    private BlockStatePreimageAtomicMoveSelfTest() {}

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-preimage-atomic-publish");
        try {
            ChunkKey key = new ChunkKey(7, -9);
            int[] ids = new int[256 * 2];
            for (int i = 0; i < ids.length; i++) ids[i] = (i * 17) ^ (i >>> 3);
            BlockStatePreimageStore.Preimage original =
                    new BlockStatePreimageStore.Preimage("atomic-publish-test", key, 10, 11, ids);

            Path success = dir.resolve("success.bin");
            AtomicInteger successfulMoves = new AtomicInteger();
            AtomicBoolean sameDirectoryObserved = new AtomicBoolean();
            BlockStatePreimageStore.writeExact(success, original, (source, target) -> {
                successfulMoves.incrementAndGet();
                sameDirectoryObserved.set(
                        source.toAbsolutePath().normalize().getParent().equals(
                                target.toAbsolutePath().normalize().getParent()));
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            });
            checks += check(successfulMoves.get() == 1,
                    "successful publication performs exactly one atomic move");
            checks += check(sameDirectoryObserved.get(),
                    "staging source and canonical target share one directory/filesystem boundary");
            checks += check(Files.exists(success),
                    "successful atomic publication creates canonical backup");
            checks += check(!Files.exists(dir.resolve("success.bin.tmp")),
                    "successful atomic publication consumes staging file");
            checks += check(BlockStatePreimageStore.readVerified(
                    success, "atomic-publish-test", key).count() == ids.length,
                    "successfully published canonical backup verifies");
            byte[] expectedCanonicalBytes = Files.readAllBytes(success);

            Path unsupported = dir.resolve("unsupported.bin");
            Path unsupportedStage = dir.resolve("unsupported.bin.tmp");
            AtomicInteger refusedMoves = new AtomicInteger();
            AtomicBoolean refusedSameDirectoryObserved = new AtomicBoolean();
            boolean unsupportedRefused = false;
            try {
                BlockStatePreimageStore.writeExact(unsupported, original, (source, target) -> {
                    refusedMoves.incrementAndGet();
                    refusedSameDirectoryObserved.set(
                            source.toAbsolutePath().normalize().getParent().equals(
                                    target.toAbsolutePath().normalize().getParent()));
                    throw new AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "injected unsupported atomic move");
                });
            } catch (IOException expected) {
                unsupportedRefused = expected.getMessage().contains(
                        "atomic preimage replacement unavailable; canonical backup preserved");
            }
            checks += check(unsupportedRefused,
                    "unsupported atomic move fails closed with explicit publication refusal");
            checks += check(refusedMoves.get() == 1,
                    "unsupported path attempts exactly one atomic publication");
            checks += check(refusedSameDirectoryObserved.get(),
                    "unsupported path is still staged beside the canonical target");
            checks += check(!Files.exists(unsupported),
                    "unsupported atomic move never fabricates canonical backup");
            checks += check(Files.exists(unsupportedStage),
                    "unsupported atomic move preserves staged recovery evidence");
            checks += check(Arrays.equals(expectedCanonicalBytes, Files.readAllBytes(unsupportedStage)),
                    "preserved stage bytes exactly match the candidate that would have been canonical");
            checks += check(BlockStatePreimageStore.readVerified(
                    unsupportedStage, "atomic-publish-test", key).count() == ids.length,
                    "preserved unsupported-move stage remains independently verifiable");

            byte[] stagedBeforeRetry = Files.readAllBytes(unsupportedStage);
            boolean retryRefused = false;
            try {
                BlockStatePreimageStore.writeExact(unsupported, original);
            } catch (java.nio.file.FileAlreadyExistsException expected) {
                retryRefused = true;
            }
            checks += check(retryRefused,
                    "retry cannot truncate surviving unsupported-move stage");
            checks += check(!Files.exists(unsupported),
                    "retry after unsupported move still creates no canonical backup");
            checks += check(Arrays.equals(stagedBeforeRetry, Files.readAllBytes(unsupportedStage)),
                    "retry preserves unsupported-move forensic stage byte-for-byte");
        } finally {
            deleteTree(dir);
        }
        return checks;
    }

    private static int check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        return 1;
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
