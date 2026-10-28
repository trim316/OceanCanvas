package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.ChunkKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic regressions for bounded campaign immutable identity path admission/publication. */
public final class BoundedCampaignIdentityRegularFileSelfTest {
    private BoundedCampaignIdentityRegularFileSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("BoundedCampaignIdentityRegularFileSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-bounded-campaign-identity-path");
        try {
            Path file = dir.resolve("campaign.identity");
            byte[] expected = "canonical-bounded-campaign-identity".getBytes(StandardCharsets.UTF_8);
            Files.write(file, expected);
            require(BoundedCampaignIdentityStore.matchesExactBounded(file, expected),
                    "exact regular immutable identity must match");
            checks++;

            Files.delete(file);
            Files.createDirectory(file);
            require(!BoundedCampaignIdentityStore.matchesExactBounded(file, expected),
                    "directory at canonical identity path must fail closed");
            checks++;

            require(Files.isDirectory(file),
                    "refusal must preserve non-regular canonical evidence");
            checks++;

            Files.delete(file);
            Path symlinkTarget = dir.resolve("external-campaign.identity");
            Files.write(symlinkTarget, expected);
            boolean symlinkSupported = true;
            try {
                Files.createSymbolicLink(file, symlinkTarget.getFileName());
            } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
                symlinkSupported = false;
            }
            if (symlinkSupported) {
                require(!BoundedCampaignIdentityStore.matchesExactBounded(file, expected),
                        "symlink campaign authority must not match canonical bytes through its target");
                checks++;
                byte[] targetBefore = Files.readAllBytes(symlinkTarget);
                BoundedCampaignPlan symlinkPlan = BoundedCampaignPlan.squareEastSouthOf(new ChunkKey(32, 32), 5);
                boolean symlinkRefused = false;
                try {
                    BoundedCampaignIdentityStore.ensureExact(file, symlinkPlan, CoreConfig.defaults());
                } catch (IOException expectedFailure) {
                    symlinkRefused = expectedFailure.getMessage().contains("immutable campaign plan changed");
                }
                require(symlinkRefused, "symlink canonical campaign authority must be refused");
                checks++;
                require(Files.isSymbolicLink(file), "symlink authority must be preserved for forensics");
                checks++;
                require(java.util.Arrays.equals(targetBefore, Files.readAllBytes(symlinkTarget)),
                        "symlink target bytes must remain unchanged after refusal");
                checks++;
                Files.delete(file);

                Path lock = file.resolveSibling(file.getFileName().toString() + ".lock");
                Path externalLock = dir.resolve("external-campaign.lock");
                byte[] externalLockBytes = "external-lock-authority".getBytes(StandardCharsets.UTF_8);
                Files.write(externalLock, externalLockBytes);
                Files.deleteIfExists(lock);
                Files.createSymbolicLink(lock, externalLock.getFileName());
                boolean lockRefused = false;
                try {
                    BoundedCampaignIdentityStore.ensureExact(file, symlinkPlan, CoreConfig.defaults());
                } catch (IOException expectedFailure) {
                    lockRefused = true;
                }
                require(lockRefused, "symlink campaign lock authority must be refused before publication");
                checks++;
                require(Files.isSymbolicLink(lock), "symlink lock authority must be preserved for forensics");
                checks++;
                require(java.util.Arrays.equals(externalLockBytes, Files.readAllBytes(externalLock)),
                        "symlink lock target bytes must remain unchanged after refusal");
                checks++;
                require(!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                        "symlink lock refusal must not publish canonical campaign authority");
                checks++;
                Files.delete(lock);

                Path stageLink = file.resolveSibling(file.getFileName().toString() + ".tmp");
                Path externalStage = dir.resolve("external-campaign.tmp");
                byte[] externalStageBytes = "external-stage-authority".getBytes(StandardCharsets.UTF_8);
                Files.write(externalStage, externalStageBytes);
                Files.deleteIfExists(stageLink);
                Files.createSymbolicLink(stageLink, externalStage.getFileName());
                boolean stageRefused = false;
                try {
                    BoundedCampaignIdentityStore.ensureExact(file, symlinkPlan, CoreConfig.defaults());
                } catch (IOException expectedFailure) {
                    stageRefused = true;
                }
                require(stageRefused, "preexisting symlink campaign stage must be refused");
                checks++;
                require(Files.isSymbolicLink(stageLink), "symlink stage authority must be preserved for forensics");
                checks++;
                require(java.util.Arrays.equals(externalStageBytes, Files.readAllBytes(externalStage)),
                        "symlink stage target bytes must remain unchanged after refusal");
                checks++;
                require(!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                        "symlink stage refusal must not publish canonical campaign authority");
                checks++;
                Files.delete(stageLink);
            }

            byte[] competing = "competing-campaign-authority".getBytes(StandardCharsets.UTF_8);
            BoundedCampaignPlan plan = BoundedCampaignPlan.squareEastSouthOf(new ChunkKey(32, 32), 5);
            boolean refused = false;
            try {
                BoundedCampaignIdentityStore.ensureExact(file, plan, CoreConfig.defaults(), channel -> {
                    channel.force(true);
                    Files.write(file, competing);
                });
            } catch (IOException expectedFailure) {
                refused = expectedFailure.getMessage().contains("appeared during staged publication");
            }
            require(refused, "competing canonical campaign authority must refuse staged publication");
            checks++;
            require(java.util.Arrays.equals(competing, Files.readAllBytes(file)),
                    "competing canonical campaign authority must be preserved byte-for-byte");
            checks++;
            Path stage = file.resolveSibling(file.getFileName().toString() + ".tmp");
            require(Files.isRegularFile(stage) && Files.size(stage) > 0,
                    "staged campaign candidate must survive ambiguous publication for forensics");
            checks++;
            return checks;
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception ignored) {}
                });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
