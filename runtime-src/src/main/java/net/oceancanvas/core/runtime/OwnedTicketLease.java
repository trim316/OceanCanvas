package net.oceancanvas.core.runtime;

/**
 * Tracks ownership of the one radius-zero chunk ticket installed by an adapter.
 *
 * <p>The release callback is deliberately at-most-once. If Minecraft throws
 * during removal, the outcome is ambiguous, so this lease permanently refuses
 * both another removal and another acquisition for the lifetime of the adapter.
 * A fresh adapter after server restart starts with a fresh ABSENT lease.</p>
 */
public final class OwnedTicketLease {
    private enum State { ABSENT, OWNED, RELEASE_ATTEMPTED }

    private State state = State.ABSENT;

    public boolean isOwned() {
        return state == State.OWNED;
    }

    public boolean releaseAttempted() {
        return state == State.RELEASE_ATTEMPTED;
    }

    /**
     * Installs authority exactly once while absent.
     *
     * @return true when the callback installed the ticket, false when already owned
     * @throws IllegalStateException when a prior removal had an ambiguous outcome
     */
    public boolean acquire(Runnable install) {
        if (state == State.OWNED) return false;
        if (state == State.RELEASE_ATTEMPTED) {
            throw new IllegalStateException("ticket release outcome is ambiguous; fresh adapter required");
        }
        install.run();
        state = State.OWNED;
        return true;
    }

    /**
     * Releases only a ticket this lease still proves it owns.
     *
     * <p>State moves to RELEASE_ATTEMPTED before invoking Minecraft so repeated
     * fatal/shutdown cleanup cannot accidentally remove an equivalent ticket
     * after an exception whose side effects are unknown.</p>
     *
     * @return true when a removal callback was invoked, false when no owned ticket existed
     */
    public boolean release(Runnable remove) {
        if (state != State.OWNED) return false;
        state = State.RELEASE_ATTEMPTED;
        remove.run();
        state = State.ABSENT;
        return true;
    }
}
