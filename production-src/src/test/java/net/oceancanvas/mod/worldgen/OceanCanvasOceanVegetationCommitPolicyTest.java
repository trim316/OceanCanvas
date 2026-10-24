package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for the post-proof aquatic-decoration boundary. */
final class OceanCanvasOceanVegetationCommitPolicyTest {
    @Test
    void anyRealDecorationMutationWithholdsAuthoritativeCommit() {
        assertFalse(OceanCanvasOceanVegetation.commitAllowedAfterDecoration(true),
                "a block write after lighting proof must force reproof before durable commit");
    }

    @Test
    void mutationFreeDeterministicRetryMayCommit() {
        assertTrue(OceanCanvasOceanVegetation.commitAllowedAfterDecoration(false),
                "once deterministic retry performs no write, the re-proved chunk may commit");
    }
}
