package net.oceancanvas.core.runtime;

/** Deterministic ownership/cleanup regression for the Minecraft ticket adapter. */
public final class OwnedTicketLeaseSelfTest {
    private static int checks;

    private OwnedTicketLeaseSelfTest() {}

    public static int run() {
        checks = 0;

        int[] installs = {0};
        int[] releases = {0};
        OwnedTicketLease lease = new OwnedTicketLease();

        check(!lease.isOwned(), "new lease starts absent");
        check(!lease.release(() -> releases[0]++), "absent lease never removes a ticket");
        eq(0, releases[0], "absent cleanup has no side effect");

        check(lease.acquire(() -> installs[0]++), "first acquire installs owned ticket");
        check(lease.isOwned(), "lease records owned ticket");
        check(!lease.acquire(() -> installs[0]++), "duplicate acquire is idempotent");
        eq(1, installs[0], "duplicate acquire cannot add a second ticket");

        check(lease.release(() -> releases[0]++), "owned cleanup invokes removal");
        check(!lease.isOwned(), "successful cleanup clears ownership");
        eq(1, releases[0], "successful cleanup removes exactly once");
        check(!lease.release(() -> releases[0]++), "repeated cleanup is inert");
        eq(1, releases[0], "repeated cleanup cannot remove another ticket");

        OwnedTicketLease installFailure = new OwnedTicketLease();
        boolean installThrew = false;
        try {
            installFailure.acquire(() -> { throw new IllegalStateException("synthetic install failure"); });
        } catch (IllegalStateException expected) {
            installThrew = true;
        }
        check(installThrew, "install failure propagates");
        check(!installFailure.isOwned(), "failed install never fabricates ownership");
        check(installFailure.acquire(() -> {}), "failed install can be retried safely");

        OwnedTicketLease ambiguousRelease = new OwnedTicketLease();
        int[] ambiguousRemovals = {0};
        check(ambiguousRelease.acquire(() -> {}), "ambiguous fixture acquires ownership");
        boolean releaseThrew = false;
        try {
            ambiguousRelease.release(() -> {
                ambiguousRemovals[0]++;
                throw new IllegalStateException("synthetic remove failure");
            });
        } catch (IllegalStateException expected) {
            releaseThrew = true;
        }
        check(releaseThrew, "remove failure propagates");
        check(!ambiguousRelease.isOwned(), "ambiguous removal no longer claims proven ownership");
        check(ambiguousRelease.releaseAttempted(), "ambiguous removal state is retained");
        eq(1, ambiguousRemovals[0], "failing removal callback runs once");
        check(!ambiguousRelease.release(() -> ambiguousRemovals[0]++),
                "fatal/shutdown retry cannot remove an equivalent ticket twice");
        eq(1, ambiguousRemovals[0], "ambiguous cleanup remains at-most-once");

        boolean reacquireRefused = false;
        try {
            ambiguousRelease.acquire(() -> {});
        } catch (IllegalStateException expected) {
            reacquireRefused = true;
        }
        check(reacquireRefused, "ambiguous cleanup cannot silently recreate ticket authority");

        ResidencyReacquirePolicy residency = new ResidencyReacquirePolicy(2, 4, 8);
        residency.futureRequested();
        check(residency.futureOutstanding(), "requested FULL future owns one recovery epoch");
        boolean overlappingFutureRefused = false;
        try {
            residency.futureRequested();
        } catch (IllegalStateException expected) {
            overlappingFutureRefused = true;
        }
        check(overlappingFutureRefused, "unresolved FULL future cannot be superseded by a second request");
        var grace = residency.onStaleFuture(10);
        check(!grace.reissueTicket(), "stale future grace cannot request another ticket");
        eq(0, grace.ownedTicketRadius(), "stale future grace remains radius zero");
        var retry = residency.onStaleFuture(14);
        check(retry.action() == ResidencyReacquirePolicy.Action.RETRY,
                "expired stale future epoch consumes one bounded retry");
        check(!retry.reissueTicket(), "bounded retry retains the existing ticket instead of duplicating it");
        eq(0, retry.ownedTicketRadius(), "bounded retry cannot widen ticket radius");
        check(!residency.futureOutstanding(), "retry closes the stale future epoch before replacement request");
        residency.futureRequested();
        check(residency.futureOutstanding(), "replacement future begins only after prior epoch closed");
        residency.reset();
        check(!residency.futureOutstanding(), "terminal cleanup clears future authority");

        return checks;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void eq(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        checks++;
    }
}
