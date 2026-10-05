package net.oceancanvas.core.restore;

/**
 * In-memory gate for bounded restore preflight. A caller may only enter the
 * destructive restore-write phase after every captured cell has been accepted.
 * The gate is intentionally reset by process restart so the full immutable
 * preimage is revalidated before any resumed write.
 */
public final class RestorePreflightGate {
    private final int total;
    private int cursor;

    public RestorePreflightGate(int total) {
        if (total <= 0) throw new IllegalArgumentException("total must be positive");
        this.total = total;
    }

    public int currentIndex() {
        if (complete()) throw new IllegalStateException("restore preflight already complete");
        return cursor;
    }

    public void acceptCurrent() {
        if (complete()) throw new IllegalStateException("restore preflight already complete");
        cursor++;
    }

    public boolean complete() {
        return cursor == total;
    }

    public int cursor() {
        return cursor;
    }

    public int total() {
        return total;
    }

    /** Hard boundary immediately before any restore block write. */
    public void requireCompleteBeforeWrite() {
        if (!complete()) {
            throw new IllegalStateException("restore writes refused before full state-id/entity preflight: "
                    + cursor + "/" + total);
        }
    }
}
