package net.oceancanvas.core.expansion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Deterministic regression for bounded immutable campaign-identity replay. */
public final class BoundedCampaignIdentityBoundedReadSelfTest {
    private BoundedCampaignIdentityBoundedReadSelfTest() {}

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("oceancanvas-bounded-campaign-identity-read");
        try {
            Path file = dir.resolve("campaign.identity");
            byte[] expected = "canonical-campaign-identity".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(file, expected);
            require(BoundedCampaignIdentityStore.matchesExactBounded(file, expected),
                    "exact immutable identity must match");
            checks++;

            byte[] changed = expected.clone();
            changed[changed.length / 2] ^= 1;
            Files.write(file, changed, StandardOpenOption.TRUNCATE_EXISTING);
            require(!BoundedCampaignIdentityStore.matchesExactBounded(file, expected),
                    "same-length changed identity must fail closed");
            checks++;

            Files.write(file, expected, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(file, new byte[]{0}, StandardOpenOption.APPEND);
            require(!BoundedCampaignIdentityStore.matchesExactBounded(file, expected),
                    "oversized identity must fail before replay acceptance");
            checks++;

            Files.write(file, java.util.Arrays.copyOf(expected, expected.length - 1),
                    StandardOpenOption.TRUNCATE_EXISTING);
            require(!BoundedCampaignIdentityStore.matchesExactBounded(file, expected),
                    "truncated identity must fail closed");
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
