package net.oceancanvas.core.restore;

/** Deterministic interrupted-authoring replay regression. */
public final class AuthoringReplayPolicySelfTest {
    private static int checks;

    private AuthoringReplayPolicySelfTest() {}

    public static int run() {
        checks = 0;

        eq(AuthoringReplayPolicy.Decision.STATE_ONLY_SAFE,
                AuthoringReplayPolicy.classify(false, true, false, false, false),
                "ordinary captured state remains safe");
        eq(AuthoringReplayPolicy.Decision.STATE_ONLY_SAFE,
                AuthoringReplayPolicy.classify(false, false, false, false, true),
                "state-only cell may already be canonical after partial authoring");
        eq(AuthoringReplayPolicy.Decision.REFUSE,
                AuthoringReplayPolicy.classify(false, false, true, true, false),
                "unbacked block entity is always refused");

        eq(AuthoringReplayPolicy.Decision.VERIFY_CAPTURED_ENTITY,
                AuthoringReplayPolicy.classify(true, true, true, true, false),
                "unchanged backed entity requires exact NBT verification");
        eq(AuthoringReplayPolicy.Decision.ALREADY_CANONICAL,
                AuthoringReplayPolicy.classify(true, false, false, false, true),
                "backed entity cell may resume when prior authoring left exact canonical target");
        eq(AuthoringReplayPolicy.Decision.REFUSE,
                AuthoringReplayPolicy.classify(true, false, false, false, false),
                "missing backed entity at arbitrary noncanonical state is refused");
        eq(AuthoringReplayPolicy.Decision.REFUSE,
                AuthoringReplayPolicy.classify(true, false, true, true, true),
                "canonical-looking cell with a live entity is refused");
        eq(AuthoringReplayPolicy.Decision.REFUSE,
                AuthoringReplayPolicy.classify(true, true, true, false, false),
                "captured entity state without materialized entity is refused");
        eq(AuthoringReplayPolicy.Decision.REFUSE,
                AuthoringReplayPolicy.classify(true, false, true, true, false),
                "different live entity-bearing state is refused before NBT comparison");

        // Simulate a restart after two cells of a four-cell authoring batch:
        // state-only cell 0 and backed-entity cell 1 are already canonical;
        // cells 2 and 3 still contain their captured source state.
        AuthoringReplayPolicy.Decision[] resumed = {
                AuthoringReplayPolicy.classify(false, false, false, false, true),
                AuthoringReplayPolicy.classify(true, false, false, false, true),
                AuthoringReplayPolicy.classify(false, true, false, false, false),
                AuthoringReplayPolicy.classify(true, true, true, true, false)
        };
        eq(AuthoringReplayPolicy.Decision.STATE_ONLY_SAFE, resumed[0],
                "partial replay accepts already-authored ordinary cell");
        eq(AuthoringReplayPolicy.Decision.ALREADY_CANONICAL, resumed[1],
                "partial replay accepts already-authored backed-entity cell");
        eq(AuthoringReplayPolicy.Decision.STATE_ONLY_SAFE, resumed[2],
                "partial replay accepts untouched ordinary source cell");
        eq(AuthoringReplayPolicy.Decision.VERIFY_CAPTURED_ENTITY, resumed[3],
                "partial replay requires exact NBT proof for untouched backed entity");

        return checks;
    }

    private static void eq(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
        checks++;
    }
}
