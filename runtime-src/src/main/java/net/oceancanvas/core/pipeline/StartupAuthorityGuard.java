package net.oceancanvas.core.pipeline;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Orders server startup so immutable operation/target authority is established
 * before any downstream durable evidence or world-facing initialization runs.
 */
public final class StartupAuthorityGuard {
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
        OperationManifestStore.ensureExact(manifestFile, expected);
        downstream.run();
    }
}
