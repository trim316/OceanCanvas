package net.oceancanvas.core.runtime;

/**
 * Pure deterministic policy for recovering a single owned chunk after Minecraft
 * completes a FULL future without a resident LevelChunk.
 *
 * <p>The Minecraft adapter supplies only the current game tick. This class owns
 * grace-window and bounded retry accounting so recovery behavior can be proved
 * without launching Minecraft.</p>
 */
public final class ResidencyReacquirePolicy {
    public enum Action { GRACE, RETRY, FAIL }

    public record Decision(Action action, int attempt, long retryDelayTicks, long graceRemainingTicks,
                           int ownedTicketRadius, boolean reissueTicket) {
        public Decision {
            if (action == null) throw new IllegalArgumentException("action");
            if (attempt < 0) throw new IllegalArgumentException("attempt");
            if (retryDelayTicks < 0) throw new IllegalArgumentException("retryDelayTicks");
            if (graceRemainingTicks < 0) throw new IllegalArgumentException("graceRemainingTicks");
            if (ownedTicketRadius != 0) throw new IllegalArgumentException("ownedTicketRadius must remain zero");
            if (reissueTicket) throw new IllegalArgumentException("residency retry must retain, not duplicate, the owned ticket");
        }
    }

    private final int maxAttempts;
    private final long graceTicks;
    private final long maxRetryDelayTicks;

    private int attempts;
    private long graceUntilTick = Long.MIN_VALUE;
    private long retryNotBeforeTick = Long.MIN_VALUE;
    private boolean futureOutstanding;

    public ResidencyReacquirePolicy(int maxAttempts, long graceTicks, long maxRetryDelayTicks) {
        if (maxAttempts < 0) throw new IllegalArgumentException("maxAttempts");
        if (graceTicks < 0) throw new IllegalArgumentException("graceTicks");
        if (maxRetryDelayTicks < 1) throw new IllegalArgumentException("maxRetryDelayTicks");
        this.maxAttempts = maxAttempts;
        this.graceTicks = graceTicks;
        this.maxRetryDelayTicks = maxRetryDelayTicks;
    }

    public boolean retryBackoffActive(long nowTick) {
        return nowTick < retryNotBeforeTick;
    }

    public long retryBackoffRemaining(long nowTick) {
        return Math.max(0L, retryNotBeforeTick - nowTick);
    }

    public int attempts() { return attempts; }

    public boolean futureOutstanding() { return futureOutstanding; }

    /**
     * Called exactly once when a new FULL future is issued after any prior
     * backoff. A second request cannot supersede an unresolved future: doing so
     * would make a later completion ambiguous about which recovery epoch owns it.
     */
    public void futureRequested() {
        if (futureOutstanding) {
            throw new IllegalStateException("FULL residency future already outstanding");
        }
        futureOutstanding = true;
        graceUntilTick = Long.MIN_VALUE;
    }

    /**
     * Classifies one stale/unloaded FULL-future observation.
     *
     * <p>A completed stale future first receives a bounded grace window. Only
     * after that window expires is a retry attempt consumed. This prevents a
     * short ticket-graph lag from being miscounted as repeated failures. A retry
     * never asks the adapter to install another ticket: the already-owned radius-zero
     * ticket remains the sole residency authority until release or process shutdown.</p>
     */
    public Decision onStaleFuture(long nowTick) {
        // Pure-policy callers historically begin with the observed stale future
        // rather than the adapter's explicit futureRequested() hook. Treat that
        // first observation as the start of exactly one epoch; subsequent
        // futureRequested() calls are still fenced until RETRY/FAIL/reset ends it.
        if (!futureOutstanding) futureOutstanding = true;
        if (graceUntilTick == Long.MIN_VALUE) {
            graceUntilTick = saturatedAdd(nowTick, graceTicks);
            return new Decision(Action.GRACE, attempts, 0L, Math.max(0L, graceUntilTick - nowTick), 0, false);
        }
        if (nowTick < graceUntilTick) {
            return new Decision(Action.GRACE, attempts, 0L, graceUntilTick - nowTick, 0, false);
        }
        if (attempts >= maxAttempts) {
            futureOutstanding = false;
            return new Decision(Action.FAIL, attempts, 0L, 0L, 0, false);
        }

        attempts++;
        long delay = Math.min(maxRetryDelayTicks, 1L << Math.min(4, attempts - 1));
        retryNotBeforeTick = saturatedAdd(nowTick, delay);
        graceUntilTick = Long.MIN_VALUE;
        futureOutstanding = false;
        return new Decision(Action.RETRY, attempts, delay, 0L, 0, false);
    }

    /** Successful residency or terminal release starts a fresh recovery epoch. */
    public void reset() {
        attempts = 0;
        graceUntilTick = Long.MIN_VALUE;
        retryNotBeforeTick = Long.MIN_VALUE;
        futureOutstanding = false;
    }

    private static long saturatedAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }
}
