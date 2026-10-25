package net.oceancanvas.core.restore;

import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic proof that a bad final state ID cannot permit any restore-write phase. */
public final class RestorePreflightGateSelfTest {
    private RestorePreflightGateSelfTest() {}

    public static void main(String[] args) throws Exception {
        int checks = run();
        System.out.println("RestorePreflightGateSelfTest PASS (" + checks + " checks)");
    }

    public static int run() throws Exception {
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

        Path identity = Files.createTempFile("oceancanvas-registry-identity", ".properties");
        byte[] canonical = "registry-identity".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(identity, canonical);
        check(java.util.Arrays.equals(canonical,
                BlockStateRegistryIdentityStore.readExactBounded(identity, canonical.length)),
                "exact registry identity is read through bounded path"); checks++;

        Files.write(identity, new byte[] {0x01}, java.nio.file.StandardOpenOption.APPEND);
        boolean oversizedRefused = false;
        try { BlockStateRegistryIdentityStore.readExactBounded(identity, canonical.length); }
        catch (java.io.IOException expected) { oversizedRefused = true; }
        check(oversizedRefused, "oversized registry identity is refused before replay comparison"); checks++;

        Files.write(identity, canonical);
        boolean wrongExpectedLengthRefused = false;
        try { BlockStateRegistryIdentityStore.readExactBounded(identity, canonical.length - 1); }
        catch (java.io.IOException expected) { wrongExpectedLengthRefused = true; }
        check(wrongExpectedLengthRefused, "registry identity length mismatch fails closed"); checks++;
        return checks;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
