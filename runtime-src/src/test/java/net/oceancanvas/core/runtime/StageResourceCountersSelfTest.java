package net.oceancanvas.core.runtime;

import java.lang.reflect.Modifier;

/** Deterministic primitive-only accounting regression for R1-87. */
public final class StageResourceCountersSelfTest {
    private StageResourceCountersSelfTest() {}

    public static int run() {
        int checks = 0;

        // Non-static storage must stay primitive-only so per-cell accounting
        // cannot accidentally grow arrays, maps, strings, or boxed counters.
        for (var field : StageResourceCounters.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                checks += check(field.getType() == long.class,
                        "hot-path counter storage remains primitive long: " + field.getName());
            }
        }

        StageResourceCounters counters = new StageResourceCounters();
        counters.addPreimageChecks(4096);
        counters.addAuthorPreflightChecks(76800);
        counters.addAuthorChecks(76800);
        counters.addAuthorWrites(1234);
        counters.addSettlementChecks(90000);
        counters.addSettlementRepairs(3);
        counters.addLightRequests(24576);
        counters.addFinalPhysicalChecks(90000);
        counters.addFinalLightChecks(24576);
        counters.addRestorePreflightChecks(76800);
        counters.addRestoreChecks(153600);
        counters.addRestoreWrites(4321);
        counters.addRestoreVerifyChecks(76800);

        checks += check(counters.preimageReport().equals(
                        "resourcePreimageChecks=4096"),
                "preimage report is deterministic");
        checks += check(counters.authoringReport().equals(
                        "resourceAuthorPreflightChecks=76800;resourceAuthorChecks=76800;resourceAuthorWrites=1234"),
                "authoring report is deterministic");
        checks += check(counters.settlementReport().equals(
                        "resourceSettlementChecks=90000;resourceSettlementRepairs=3"),
                "settlement report is deterministic");
        checks += check(counters.lightingReport().equals(
                        "resourceLightRequests=24576"),
                "lighting report is deterministic");
        checks += check(counters.verificationReport().equals(
                        "resourceFinalPhysicalChecks=90000;resourceFinalLightChecks=24576"),
                "verification report is deterministic");
        checks += check(counters.restoreReport().equals(
                        "resourceRestorePreflightChecks=76800;resourceRestoreChecks=153600;resourceRestoreWrites=4321"),
                "restore report is deterministic");
        checks += check(counters.restoreVerificationReport().equals(
                        "resourceRestoreVerifyChecks=76800"),
                "restore verification report is deterministic");

        StageResourceCounters withReporting = new StageResourceCounters();
        StageResourceCounters withoutReporting = new StageResourceCounters();
        withReporting.addAuthorChecks(17);
        withoutReporting.addAuthorChecks(17);
        String ignored = withReporting.authoringReport();
        checks += check(!ignored.isEmpty(), "report materializes only when requested");
        withReporting.addAuthorChecks(23);
        withoutReporting.addAuthorChecks(23);
        checks += check(withReporting.authorChecks() == withoutReporting.authorChecks(),
                "reporting cannot alter operation/accounting order");

        StageResourceCounters bounded = new StageResourceCounters();
        bounded.addRestoreChecks(Long.MAX_VALUE);
        bounded.addRestoreChecks(Long.MAX_VALUE);
        checks += check(bounded.restoreChecks() == StageResourceCounters.MAX_COUNT,
                "pathological resource counts saturate at deterministic bound");
        bounded.addRestoreChecks(-1);
        checks += check(bounded.restoreChecks() == StageResourceCounters.MAX_COUNT,
                "invalid negative accounting cannot reduce durable total");

        return checks;
    }

    private static int check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        return 1;
    }
}
