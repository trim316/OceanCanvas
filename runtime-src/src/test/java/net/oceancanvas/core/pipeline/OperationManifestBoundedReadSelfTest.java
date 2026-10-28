package net.oceancanvas.core.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic regression for bounded immutable operation-authority replay. */
public final class OperationManifestBoundedReadSelfTest {
    private OperationManifestBoundedReadSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oceancanvas-manifest-bound-");
        Path manifest = dir.resolve("operation.properties");
        SingleChunkOperationSpec expected = new SingleChunkOperationSpec(
                1, new ChunkKey(32, 32), 512, 512, 512, 63, -32, 3);

        OperationManifestStore.ensureExact(manifest, expected);
        if (!expected.equals(OperationManifestStore.readVerified(manifest))) {
            throw new AssertionError("canonical bounded manifest replay changed identity");
        }

        byte[] oversized = new byte[OperationManifestStore.MAX_MANIFEST_BYTES + 1];
        oversized[0] = 'x';
        Files.write(manifest, oversized);
        expectRefusal(manifest, "oversized manifest must fail closed");

        Files.writeString(manifest, "schemaVersion=1\n");
        expectRefusal(manifest, "truncated manifest must fail closed");

        Path nonRegularManifest = dir.resolve("non-regular-operation.properties");
        Files.createDirectory(nonRegularManifest);
        try {
            OperationManifestStore.ensureExact(nonRegularManifest, expected);
            throw new AssertionError("non-regular existing manifest authority must fail closed");
        } catch (IOException expectedRefusal) {
            if (!Files.isDirectory(nonRegularManifest)) {
                throw new AssertionError("non-regular manifest evidence was altered during refusal");
            }
        }

        Path canonicalTarget = dir.resolve("canonical-target.properties");
        OperationManifestStore.ensureExact(canonicalTarget, expected);
        byte[] targetBefore = Files.readAllBytes(canonicalTarget);
        Path symlinkManifest = dir.resolve("symlink-operation.properties");
        boolean symlinkSupported = true;
        try {
            Files.createSymbolicLink(symlinkManifest, canonicalTarget.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            symlinkSupported = false;
        }
        if (symlinkSupported) {
            expectRefusal(symlinkManifest, "symlink manifest replay must fail closed");
            try {
                OperationManifestStore.ensureExact(symlinkManifest, expected);
                throw new AssertionError("symlink manifest writer authority must fail closed");
            } catch (IOException expectedRefusal) {
                // fail-closed is the contract
            }
            if (!Files.isSymbolicLink(symlinkManifest)) {
                throw new AssertionError("symlink manifest authority was altered during refusal");
            }
            if (!java.util.Arrays.equals(targetBefore, Files.readAllBytes(canonicalTarget))) {
                throw new AssertionError("symlink target evidence was altered during refusal");
            }
        }

        Path publication = dir.resolve("publication.properties");
        Path externalLockTarget = dir.resolve("external-lock-target.bin");
        byte[] externalLockBytes = "external lock authority must remain unchanged".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(externalLockTarget, externalLockBytes);
        Path lockLink = publication.resolveSibling(publication.getFileName().toString() + ".lock");
        boolean lockSymlinkSupported = true;
        try {
            Files.createSymbolicLink(lockLink, externalLockTarget.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            lockSymlinkSupported = false;
        }
        if (lockSymlinkSupported) {
            try {
                OperationManifestStore.ensureExact(publication, expected);
                throw new AssertionError("symlink manifest lock authority must fail closed");
            } catch (IOException expectedRefusal) {
                // fail-closed is the contract
            }
            if (!Files.isSymbolicLink(lockLink)) {
                throw new AssertionError("symlink manifest lock evidence was altered during refusal");
            }
            if (!java.util.Arrays.equals(externalLockBytes, Files.readAllBytes(externalLockTarget))) {
                throw new AssertionError("external manifest lock target was altered during refusal");
            }
            if (Files.exists(publication)) {
                throw new AssertionError("symlink lock refusal must not publish canonical manifest");
            }
        }

        if (lockSymlinkSupported) {
            Files.delete(lockLink);
        }
        Path externalStageTarget = dir.resolve("external-stage-target.bin");
        byte[] externalStageBytes = "external staged authority must remain unchanged".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(externalStageTarget, externalStageBytes);
        Path stageLink = publication.resolveSibling(publication.getFileName().toString() + ".tmp");
        boolean stageSymlinkSupported = true;
        try {
            Files.createSymbolicLink(stageLink, externalStageTarget.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            stageSymlinkSupported = false;
        }
        if (stageSymlinkSupported) {
            try {
                OperationManifestStore.ensureExact(publication, expected);
                throw new AssertionError("symlink manifest staged authority must fail closed");
            } catch (IOException expectedRefusal) {
                // fail-closed is the contract
            }
            if (!Files.isSymbolicLink(stageLink)) {
                throw new AssertionError("symlink manifest staged evidence was altered during refusal");
            }
            if (!java.util.Arrays.equals(externalStageBytes, Files.readAllBytes(externalStageTarget))) {
                throw new AssertionError("external manifest stage target was altered during refusal");
            }
            if (Files.exists(publication)) {
                throw new AssertionError("symlink stage refusal must not publish canonical manifest");
            }
        }

        int campaignChecks = net.oceancanvas.core.expansion.BoundedCampaignIdentityBoundedReadSelfTest.run();
        if (campaignChecks != 4) {
            throw new AssertionError("bounded campaign identity regression count changed: " + campaignChecks);
        }
        System.out.println("OperationManifestBoundedReadSelfTest PASS (+ " + campaignChecks
                + " bounded campaign identity checks; non-regular and symlink authority refused)");
    }

    private static void expectRefusal(Path manifest, String message) throws Exception {
        try {
            OperationManifestStore.readVerified(manifest);
            throw new AssertionError(message);
        } catch (IOException expected) {
            // fail-closed is the contract
        }
    }
}
