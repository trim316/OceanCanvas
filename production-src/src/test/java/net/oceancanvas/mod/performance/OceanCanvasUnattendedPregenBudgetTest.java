package net.oceancanvas.mod.performance;

import net.oceancanvas.mod.project.OceanCanvasProjectData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class OceanCanvasUnattendedPregenBudgetTest {
    @Test
    void onlyEmptyOvernightServerReceivesExpandedBudget() {
        assertEquals(32_000_000L, OceanCanvasUnattendedPregenBudget.terrainAuthoringBudgetNs(
                OceanCanvasProjectData.PregenProfile.OVERNIGHT, 0));

        assertEquals(20_000_000L, OceanCanvasUnattendedPregenBudget.terrainAuthoringBudgetNs(
                OceanCanvasProjectData.PregenProfile.OVERNIGHT, 1));
        assertEquals(20_000_000L, OceanCanvasUnattendedPregenBudget.terrainAuthoringBudgetNs(
                OceanCanvasProjectData.PregenProfile.BALANCED, 0));
        assertEquals(20_000_000L, OceanCanvasUnattendedPregenBudget.terrainAuthoringBudgetNs(
                OceanCanvasProjectData.PregenProfile.QUIET, 0));
        assertEquals(20_000_000L, OceanCanvasUnattendedPregenBudget.terrainAuthoringBudgetNs(
                OceanCanvasProjectData.PregenProfile.CUSTOM, 0));
        assertEquals(20_000_000L, OceanCanvasUnattendedPregenBudget.terrainAuthoringBudgetNs(null, 0));
    }

    @Test
    void anyConnectedPlayerImmediatelyRestoresInteractiveCeiling() {
        for (int players : new int[] {1, 2, 8, Integer.MAX_VALUE}) {
            assertEquals(20_000_000L, OceanCanvasUnattendedPregenBudget.terrainAuthoringBudgetNs(
                    OceanCanvasProjectData.PregenProfile.OVERNIGHT, players));
        }
    }
}
