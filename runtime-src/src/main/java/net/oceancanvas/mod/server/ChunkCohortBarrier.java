package net.oceancanvas.mod.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Experimental server-thread cohort durability barrier. Not wired into production.
 *
 * This class never treats a successful flush as verification: callers must restart
 * Minecraft and independently verify each member before publishing VERIFIED.
 * It intentionally has no knowledge of block authoring, receipts or preimages.
 */
final class ChunkCohortBarrier {
    enum State { PREPARING, READY_TO_FLUSH, DURABLE_UNVERIFIED, VERIFIED, QUARANTINED }
    private final List<String> members;
    private final Set<String> ready = new HashSet<>();
    private final Set<String> physicallyVerified = new HashSet<>();
    private State state = State.PREPARING;

    ChunkCohortBarrier(List<String> memberKeys) {
        Objects.requireNonNull(memberKeys);
        if (memberKeys.isEmpty() || memberKeys.size() > 16
                || memberKeys.stream().anyMatch(Objects::isNull)
                || new HashSet<>(memberKeys).size() != memberKeys.size()) {
            throw new IllegalArgumentException("cohort requires 1..16 unique chunk keys");
        }
        members = List.copyOf(memberKeys);
    }

    State state() { return state; }
    List<String> members() { return members; }

    /** Must be called only after immutable preimage fsync, authoring and lighting settlement. */
    void markReady(String key, boolean preimageDurable, boolean lightingSettled) {
        requireMember(key);
        if (state != State.PREPARING || !preimageDurable || !lightingSettled)
            throw new IllegalStateException("chunk not safely ready");
        ready.add(key);
        if (ready.size() == members.size()) state = State.READY_TO_FLUSH;
    }

    /** Called on the Minecraft server thread, after one synchronous authoritative save. */
    void flushOnce(Runnable minecraftWorldFlush) {
        if (state != State.READY_TO_FLUSH) throw new IllegalStateException("cohort not ready");
        Objects.requireNonNull(minecraftWorldFlush).run();
        state = State.DURABLE_UNVERIFIED;
    }

    /** Only a restarted process may provide independent physical verification. */
    void markRestartVerified(String key, boolean freshProcess, boolean physicallyCorrect) {
        requireMember(key);
        if (state != State.DURABLE_UNVERIFIED || !freshProcess)
            throw new IllegalStateException("restart verification required");
        if (!physicallyCorrect) {
            state = State.QUARANTINED;
            physicallyVerified.clear();
            return;
        }
        physicallyVerified.add(key);
        if (physicallyVerified.size() == members.size()) state = State.VERIFIED;
    }

    /** Conservative replay of volatile state; persistent receipts are separate. */
    boolean requiresRecoveryAfterCrash() {
        return state != State.VERIFIED;
    }

    private void requireMember(String key) {
        if (!members.contains(key)) throw new IllegalArgumentException("not in cohort: " + key);
    }
}
