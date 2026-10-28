package net.oceancanvas.mod.worldgen;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
final class OceanCanvasCooperativeCarvePolicyTest {
    @Test void completedColumnBoundaryUsesExistingBudget() {
        assertFalse(OceanCanvasCooperativeCarvePolicy.shouldYield(true,1,19,20));
        assertTrue(OceanCanvasCooperativeCarvePolicy.shouldYield(true,1,20,20));
        assertTrue(OceanCanvasCooperativeCarvePolicy.shouldYield(true,255,21,20));
    }
    @Test void noEarlyEmptyOrTerminalYieldCanMasqueradeAsCompletion() {
        assertFalse(OceanCanvasCooperativeCarvePolicy.shouldYield(true,0,100,20));
        assertFalse(OceanCanvasCooperativeCarvePolicy.shouldYield(true,256,100,20));
    }
    @Test void structureOrProtectionScopeNeverTakesCooperativeReturn() {
        assertFalse(OceanCanvasCooperativeCarvePolicy.shouldYield(false,80,100,20));
    }
    @Test void invalidTimingCannotCreateAnUnboundedSchedulingLoop() {
        assertFalse(OceanCanvasCooperativeCarvePolicy.shouldYield(true,1,-1,20));
        assertFalse(OceanCanvasCooperativeCarvePolicy.shouldYield(true,1,20,0));
    }
}
