package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OceanCanvasSkyPropagationOracleTest {
    @Test
    void verticalProfileAtOrBelowReferenceRemainsAcceptable() {
        assertTrue(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(3, 3, 0));
        assertTrue(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(2, 3, 0));
    }

    @Test
    void overbrightWaterRequiresAnImmediatelyBrighterCardinalPredecessor() {
        assertTrue(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(3, 2, 4));
        assertFalse(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(3, 2, 3));
        assertFalse(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(3, 2, 2));
    }

    @Test
    void maximumSkylightCannotClaimAStillBrighterPredecessor() {
        assertFalse(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(15, 14, 15));
    }

    @Test
    void invalidObservedSkylightFailsClosed() {
        assertFalse(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(-1, 0, 0));
        assertFalse(OceanCanvasSkyPropagationOracle.locallySupportedByCardinalPropagation(16, 15, 15));
    }
}
