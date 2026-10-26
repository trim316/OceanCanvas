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
