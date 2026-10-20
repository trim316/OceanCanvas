package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasWaterShaftSectionAdvanceTest {
    @Test void sectionSkipsVisitExactlyTheSameCellsAsScalarScansAcrossNegativeHeightsAndYields() {
        Random random = new Random(4182026L);
        for (int trial = 0; trial < 10000; trial++) {
            int min = -128 + random.nextInt(256);
            int top = min + random.nextInt(97);
            int scalarY = top, fastY = top, scalarDepth = 0, fastDepth = 0;
            int stopY = min - 1 + random.nextInt(top - min + 2);
            while (scalarY >= min && scalarY > stopY) { scalarY--; scalarDepth++; }
            // Treat any section containing the obstacle as mixed: no shortcut may
            // cross it, even if the particular column has many water cells.
            while (fastY >= min && fastY > stopY) {
                int budget = 1 + random.nextInt(96);
                int used = 0;
                while (used < budget && fastY >= min && fastY > stopY) {
                    boolean sectionProvenUniform = Math.floorDiv(fastY, 16) != Math.floorDiv(stopY, 16);
                    int cells = sectionProvenUniform
                            ? OceanCanvasWaterShaftSectionAdvance.boundedCells(fastY, min, budget - used) : 1;
                    assertTrue(cells > 0 && cells <= budget - used);
                    fastY -= cells; fastDepth += cells; used += cells;
                }
            }
            assertEquals(scalarY, fastY);
            assertEquals(scalarDepth, fastDepth);
        }
    }

    @Test void unavailableBudgetAndOutOfRangePositionsNeverAdvance() {
        assertEquals(0, OceanCanvasWaterShaftSectionAdvance.boundedCells(-65, -64, 96));
        assertEquals(0, OceanCanvasWaterShaftSectionAdvance.boundedCells(64, -64, 0));
        assertEquals(0, OceanCanvasWaterShaftSectionAdvance.boundedCells(64, -64, -1));
        assertEquals(1, OceanCanvasWaterShaftSectionAdvance.boundedCells(Integer.MIN_VALUE, Integer.MIN_VALUE, 96));
        assertEquals(16, OceanCanvasWaterShaftSectionAdvance.boundedCells(Integer.MAX_VALUE, Integer.MIN_VALUE, 96));
    }
}
