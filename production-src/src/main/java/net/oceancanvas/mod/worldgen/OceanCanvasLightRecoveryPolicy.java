package net.oceancanvas.mod.worldgen;

/** Pure recovery-policy helpers shared by the production finalizer and deterministic tests. */
final class OceanCanvasLightRecoveryPolicy {
    private OceanCanvasLightRecoveryPolicy() {}

    /**
     * A bounded hard reset becomes due once escalation reaches the threshold for the
     * number of hard resets actually consumed. Using >= is intentional: earlier
     * specialized recovery may consume the exact threshold proof, so a missed
     * milestone must remain due on later proofs instead of being lost forever.
     */
    static boolean hardResetDue(int escalation, int consumedHardResets,
            int firstEscalation, int interval, int maxHardResets) {
        if (interval <= 0) throw new IllegalArgumentException("interval must be positive");
        if (consumedHardResets < 0) throw new IllegalArgumentException("consumedHardResets must be non-negative");
        if (maxHardResets < 0) throw new IllegalArgumentException("maxHardResets must be non-negative");
        if (consumedHardResets >= maxHardResets) return false;
        long threshold = (long) firstEscalation + (long) consumedHardResets * (long) interval;
        return (long) escalation >= threshold;
    }
}
