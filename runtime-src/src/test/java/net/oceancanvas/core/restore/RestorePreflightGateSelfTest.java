package net.oceancanvas.core.restore;

/** Deterministic proof that a bad final state ID cannot permit any restore-write phase. */
public final class RestorePreflightGateSelfTest {
    private RestorePreflightGateSelfTest() {}

    public static void main(String[] args) {
        int checks = run();
        System.out.println("RestorePreflightGateSelfTest PASS (" + checks + " checks)");
    }

    public static int run() {
        int checks = 0;
        RestorePreflightGate gate = new RestorePreflightGate(4);

        for (int i = 0; i < 3; i++) {
            check(gate.currentIndex() == i, "preflight visits prefix index " + i); checks++;
            check(!PreimageAdmissionPolicy.refusesStateId(i + 1, i + 1, true),
                    "valid prefix state ID accepted at index " + i); checks++;
            gate.acceptCurrent();
        }

        check(gate.currentIndex() == 3, "final preimage index is explicitly visited"); checks++;
        check(PreimageAdmissionPolicy.refusesStateId(99, 0, false),
                "invalid final state ID is refused"); checks++;
        check(!gate.complete(), "refused final index cannot mark preflight complete"); checks++;
        boolean writesRefused = false;
        try { gate.requireCompleteBeforeWrite(); }
        catch (IllegalStateException expected) { writesRefused = true; }
        check(writesRefused, "every restore write remains blocked after final-index refusal"); checks++;
        check(gate.cursor() == 3, "refusal cannot advance past invalid final index"); checks++;

        check(!PreimageAdmissionPolicy.refusesStateId(99, 99, true),
                "same final ID becomes admissible only when registry resolution is exact"); checks++;
        gate.acceptCurrent();
        check(gate.complete(), "all four indices are required for completion"); checks++;
        gate.requireCompleteBeforeWrite();
        checks++;

        boolean overAdvanceRefused = false;
        try { gate.acceptCurrent(); }
        catch (IllegalStateException expected) { overAdvanceRefused = true; }
        check(overAdvanceRefused, "completed preflight cannot silently widen its index range"); checks++;
        return checks;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
