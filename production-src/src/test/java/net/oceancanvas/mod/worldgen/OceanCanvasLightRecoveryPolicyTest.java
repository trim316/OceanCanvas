package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OceanCanvasLightRecoveryPolicyTest {
    private static final int FIRST = 8;
    private static final int INTERVAL = 8;
    private static final int MAX = 2;

    @Test
    void firstResetRemainsDueAfterExactMilestoneWasSkipped() {
        assertFalse(OceanCanvasLightRecoveryPolicy.hardResetDue(7, 0, FIRST, INTERVAL, MAX));
        assertTrue(OceanCanvasLightRecoveryPolicy.hardResetDue(8, 0, FIRST, INTERVAL, MAX));
        assertTrue(OceanCanvasLightRecoveryPolicy.hardResetDue(15, 0, FIRST, INTERVAL, MAX));
        assertTrue(OceanCanvasLightRecoveryPolicy.hardResetDue(62, 0, FIRST, INTERVAL, MAX));
    }

    @Test
    void secondResetIsKeyedToActuallyConsumedResetCount() {
        assertFalse(OceanCanvasLightRecoveryPolicy.hardResetDue(15, 1, FIRST, INTERVAL, MAX));
        assertTrue(OceanCanvasLightRecoveryPolicy.hardResetDue(16, 1, FIRST, INTERVAL, MAX));
        assertTrue(OceanCanvasLightRecoveryPolicy.hardResetDue(62, 1, FIRST, INTERVAL, MAX));
    }

    @Test
    void fixedBudgetStillFailsClosedAfterTwoConsumedResets() {
        assertFalse(OceanCanvasLightRecoveryPolicy.hardResetDue(Integer.MAX_VALUE, 2, FIRST, INTERVAL, MAX));
        assertFalse(OceanCanvasLightRecoveryPolicy.hardResetDue(Integer.MAX_VALUE, 3, FIRST, INTERVAL, MAX));
    }

    @Test
    void invalidPolicyCannotCreateAnUnboundedOrBackwardsThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> OceanCanvasLightRecoveryPolicy.hardResetDue(8, 0, FIRST, 0, MAX));
        assertThrows(IllegalArgumentException.class,
                () -> OceanCanvasLightRecoveryPolicy.hardResetDue(8, -1, FIRST, INTERVAL, MAX));
    }
}
