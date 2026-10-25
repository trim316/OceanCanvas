package net.oceancanvas.core.expansion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic regression for bounded campaign immutable identity path admission. */
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
