package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class OceanCanvasDeepSeamOwnershipTest {
    @Test void canonicalCenterDoesNotOwnBadNeighborSeam() {
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 0, 4, false));
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(24, 0, 7, false));
    }

    @Test void noncanonicalCenterOwnsItsSideOfBadSeam() {
        assertTrue(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 4, 0, false));
    }

    @Test void provenLateralSourceDoesNotCreateFalseCenterDebt() {
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 4, 0, true));
    }

    @Test void smallSeamGradientIsAlwaysAcceptable() {
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 0, 1, false));
        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 1, 0, false));
    }
}
