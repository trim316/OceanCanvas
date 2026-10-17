package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class OceanCanvasBulkWaterPlacementPolicyTest {
    @Test void retainsEveryBoundaryCallback() {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = 20; y <= 63; y++) {
                boolean interior = x >= 1 && x <= 14 && z >= 1 && z <= 14 && y >= 22 && y <= 62;
                assertEquals(interior, OceanCanvasBulkWaterPlacementPolicy.skipOnPlace(true, true, x, z, y, 20, 63));
            }
        }
    }

    @Test void retainsOrdinaryPlacementAndProtectedOrPartialRequests() {
        assertFalse(OceanCanvasBulkWaterPlacementPolicy.skipOnPlace(false, true, 8, 8, 40, 20, 63));
        assertFalse(OceanCanvasBulkWaterPlacementPolicy.skipOnPlace(true, false, 8, 8, 40, 20, 63));
        assertFalse(OceanCanvasBulkWaterPlacementPolicy.skipOnPlace(true, true, 8, 8, 40, 60, 63));
    }
}
