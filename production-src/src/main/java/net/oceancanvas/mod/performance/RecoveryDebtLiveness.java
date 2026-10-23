package net.oceancanvas.mod.performance;

/** Session-local observer; never retires debt or grants completion. */
public final class RecoveryDebtLiveness {
    private int previous = -1;
    public void arm(int currentDebt) { previous = Math.max(0, currentDebt); }
    public boolean observe(int currentDebt) {
        int current = Math.max(0, currentDebt);
        boolean decreased = previous >= 0 && current < previous;
        previous = current;
        return decreased;
    }
}
