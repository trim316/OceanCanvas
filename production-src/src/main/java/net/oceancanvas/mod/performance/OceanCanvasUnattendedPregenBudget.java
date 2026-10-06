package net.oceancanvas.mod.performance;

import net.oceancanvas.mod.project.OceanCanvasProjectData;

/**
 * Pure policy for the destructive terrain-authoring wall budget.
 *
 * <p>The interactive/default path remains the proven 20 ms ceiling.  A wider
 * budget is available only for an explicitly selected OVERNIGHT profile while
 * the server has no connected players.  The policy never changes lighting,
 * restore, block-entity, ticket, or certification semantics; it only controls
 * whether another already-authorized atomic flatten may start in the current
 * server tick.</p>
 */
public final class OceanCanvasUnattendedPregenBudget {
    public static final long INTERACTIVE_BUDGET_NS = 20_000_000L;
    public static final long UNATTENDED_OVERNIGHT_BUDGET_NS = 32_000_000L;

    private OceanCanvasUnattendedPregenBudget() {}

    public static long terrainAuthoringBudgetNs(OceanCanvasProjectData.PregenProfile profile,
                                                 int connectedPlayers) {
        if (profile == OceanCanvasProjectData.PregenProfile.OVERNIGHT && connectedPlayers == 0) {
            return UNATTENDED_OVERNIGHT_BUDGET_NS;
        }
        return INTERACTIVE_BUDGET_NS;
    }
}
