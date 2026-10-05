package net.oceancanvas.core.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Orders server startup so immutable operation/target authority is established
 * before any downstream durable evidence or world-facing initialization runs.
 */
public final class StartupAuthorityGuard {
    private static final List<String> RECOVERY_EVIDENCE_SIBLINGS = List.of(
            "transitions.journal",
            "runtime-receipts.log",
            "acceptance-state.properties",
            "block-state-registry.identity",
            "preimage-blockstates.bin",
            "preimage-blockstates.bin.completed.archive",
            "preimage-blockentities.ocbe",
            "preimage-blockentities.ocbe.completed.archive");

    @FunctionalInterface
    public interface CheckedAction {
        void run() throws Exception;
    }

    private StartupAuthorityGuard() {}

    public static void runAfterManifestAuthority(
            Path manifestFile,
            SingleChunkOperationSpec expected,
            CheckedAction downstream) throws Exception {
        Objects.requireNonNull(manifestFile, "manifestFile");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(downstream, "downstream");

        // A genuinely fresh operation may publish its first manifest. An old
        // operation whose manifest disappeared may not reconstruct authority
        // around leftover journal/receipt/preimage evidence, even when the new
        // config happens to describe the same chunk. Refuse before downstream
        // registry initialization, ticket acquisition, or any world-facing work.
        if (!Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = manifestFile.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("operation manifest has no parent directory");
            }
            for (String siblingName : RECOVERY_EVIDENCE_SIBLINGS) {
                Path sibling = parent.resolve(siblingName);
                if (Files.exists(sibling, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("operation manifest missing while prior recovery evidence exists: "
                            + siblingName + "; refuse authority reconstruction");
                }
            }
        }

        OperationManifestStore.ensureExact(manifestFile, expected);
        downstream.run();
    }
}
