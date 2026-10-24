package net.oceancanvas.mod.worldgen;

/**
 * Pure policy helper for the strict Canvas skylight certificate.
 *
 * <p>Vanilla skylight in a variable-depth open ocean is not a set of independent
 * vertical columns. A cell may legitimately be brighter than the vertical-only
 * plain-water attenuation profile when a horizontally adjacent water cell is one
 * light level brighter. The v253.125.60 exact 500-block run captured this shape in
 * all three terminal quarantine chunks: source tables agreed with recomputation,
 * while every first "overbright" cell was supported by a brighter cardinal peer.
 * Treating that shape as stale storage creates false fail-closed debt forever.</p>
 *
 * <p>This helper is intentionally conservative. It does not certify a chunk, does
 * not inspect or mutate Minecraft state, and does not weaken source-table checks.
 * It only answers whether a single value that exceeds the vertical-only reference
 * has the immediate cardinal predecessor required by ordinary light propagation.
 * Callers must still prove canonical water geometry, source-table agreement, and
 * the rest of the global certificate.</p>
 */
final class OceanCanvasSkyPropagationOracle {
    private OceanCanvasSkyPropagationOracle() { }

    static boolean locallySupportedByCardinalPropagation(
            int actualSky, int verticalOnlyMaximum, int brightestCardinalSky) {
        if (actualSky < 0 || actualSky > 15) return false;
        if (actualSky <= verticalOnlyMaximum) return true;
        // A lateral path can support this cell only if an immediate cardinal peer
        // is at least one level brighter. Equality is deliberately insufficient.
        return brightestCardinalSky >= actualSky + 1;
    }
}
