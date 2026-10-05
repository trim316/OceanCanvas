package net.oceancanvas.core.pipeline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** R1-53 deterministic refusal tests for missing operation authority. */
public final class StartupAuthorityOrphanEvidenceSelfTest {
    private StartupAuthorityOrphanEvidenceSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("StartupAuthorityOrphanEvidenceSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
        int checks = 0;
        SingleChunkOperationSpec spec = new SingleChunkOperationSpec(
                1, new ChunkKey(3, -5), 20_000, 0, 0, 62, 25, 5);
        List<String> evidenceNames = List.of(
                "transitions.journal",
                "runtime-receipts.log",
                "acceptance-state.properties",
                "block-state-registry.identity",
                "preimage-blockstates.bin",
                "preimage-blockstates.bin.completed.archive",
                "preimage-blockentities.ocbe",
                "preimage-blockentities.ocbe.completed.archive");

        for (String evidenceName : evidenceNames) {
            Path dir = Files.createTempDirectory("oceancanvas-r1-53-");
            try {
                Path manifest = dir.resolve("operation.properties");
                Path evidence = dir.resolve(evidenceName);
                byte[] original = ("orphan-" + evidenceName).getBytes(StandardCharsets.UTF_8);
                Files.write(evidence, original);
                AtomicInteger downstream = new AtomicInteger();
                boolean refused = false;
                try {
                    StartupAuthorityGuard.runAfterManifestAuthority(
                            manifest, spec, downstream::incrementAndGet);
                } catch (java.io.IOException expected) {
                    refused = true;
                }
                check(refused, "missing manifest refused beside " + evidenceName); checks++;
                check(!Files.exists(manifest), "refusal does not manufacture manifest for " + evidenceName); checks++;
                check(downstream.get() == 0, "refusal executes no downstream startup for " + evidenceName); checks++;
                check(java.util.Arrays.equals(original, Files.readAllBytes(evidence)),
                        "refusal preserves orphan evidence bytes for " + evidenceName); checks++;
            } finally {
                deleteTree(dir);
            }
        }

        Path clean = Files.createTempDirectory("oceancanvas-r1-53-clean-");
        try {
            Path manifest = clean.resolve("operation.properties");
            AtomicInteger downstream = new AtomicInteger();
            StartupAuthorityGuard.runAfterManifestAuthority(
                    manifest, spec, downstream::incrementAndGet);
            check(Files.isRegularFile(manifest), "genuinely fresh operation publishes manifest"); checks++;
            check(downstream.get() == 1, "genuinely fresh operation may initialize downstream"); checks++;

            byte[] canonical = Files.readAllBytes(manifest);
            SingleChunkOperationSpec redirected = new SingleChunkOperationSpec(
                    1, new ChunkKey(4, -5), 20_000, 0, 0, 62, 25, 5);
            boolean mismatchRefused = false;
            try {
                StartupAuthorityGuard.runAfterManifestAuthority(
                        manifest, redirected, downstream::incrementAndGet);
            } catch (java.io.IOException expected) {
                mismatchRefused = true;
            }
            check(mismatchRefused, "existing mismatched manifest remains refused"); checks++;
            check(downstream.get() == 1, "mismatch executes no additional downstream startup"); checks++;
            check(java.util.Arrays.equals(canonical, Files.readAllBytes(manifest)),
                    "mismatch preserves canonical manifest bytes"); checks++;
        } finally {
            deleteTree(clean);
        }
        return checks;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
