package net.oceancanvas.core.restore;

/**
 * Pure restart-admission policy for a cell during physical authoring replay.
 *
 * <p>State-only cells retain existing vanilla-evolution semantics as long as no
 * unbacked block entity appears. A separately backed block-entity cell may be
 * either the exact captured entity (which still requires exact type/NBT
 * verification by the Minecraft adapter) or the exact canonical Ocean Canvas
 * target with no remaining entity, which is the only safe representation of a
 * write that may have completed before a crash. Everything else is refused.</p>
 */
public final class AuthoringReplayPolicy {
    public enum Decision {
        STATE_ONLY_SAFE,
        VERIFY_CAPTURED_ENTITY,
        ALREADY_CANONICAL,
        REFUSE
    }

    private AuthoringReplayPolicy() {}

    public static Decision classify(
            boolean capturedEntityBacked,
            boolean liveStateMatchesCaptured,
            boolean liveStateDeclaresEntity,
            boolean liveEntityPresent,
            boolean liveCanonicalTarget) {
        if (!capturedEntityBacked) {
            return (!liveStateDeclaresEntity && !liveEntityPresent)
                    ? Decision.STATE_ONLY_SAFE
                    : Decision.REFUSE;
        }

        if (liveCanonicalTarget && !liveStateDeclaresEntity && !liveEntityPresent) {
            return Decision.ALREADY_CANONICAL;
        }

        if (liveStateMatchesCaptured && liveStateDeclaresEntity && liveEntityPresent) {
            return Decision.VERIFY_CAPTURED_ENTITY;
        }

        return Decision.REFUSE;
    }
}
