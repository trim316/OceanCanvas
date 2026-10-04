package net.oceancanvas.core.runtime;

/**
 * Primitive-only one-chunk resource accounting. Hot-path updates never allocate;
 * strings are created only when an existing durable stage receipt is emitted.
 */
public final class StageResourceCounters {
    public static final long MAX_COUNT = 1_000_000_000L;

    private long preimageChecks;
    private long authorPreflightChecks;
    private long authorChecks;
    private long authorWrites;
    private long settlementChecks;
    private long settlementRepairs;
    private long lightRequests;
    private long finalPhysicalChecks;
    private long finalLightChecks;
    private long restorePreflightChecks;
    private long restoreChecks;
    private long restoreWrites;
    private long restoreVerifyChecks;

    public void addPreimageChecks(long delta) { preimageChecks = boundedAdd(preimageChecks, delta); }
    public void addAuthorPreflightChecks(long delta) { authorPreflightChecks = boundedAdd(authorPreflightChecks, delta); }
    public void addAuthorChecks(long delta) { authorChecks = boundedAdd(authorChecks, delta); }
    public void addAuthorWrites(long delta) { authorWrites = boundedAdd(authorWrites, delta); }
    public void addSettlementChecks(long delta) { settlementChecks = boundedAdd(settlementChecks, delta); }
    public void addSettlementRepairs(long delta) { settlementRepairs = boundedAdd(settlementRepairs, delta); }
    public void addLightRequests(long delta) { lightRequests = boundedAdd(lightRequests, delta); }
    public void addFinalPhysicalChecks(long delta) { finalPhysicalChecks = boundedAdd(finalPhysicalChecks, delta); }
    public void addFinalLightChecks(long delta) { finalLightChecks = boundedAdd(finalLightChecks, delta); }
    public void addRestorePreflightChecks(long delta) { restorePreflightChecks = boundedAdd(restorePreflightChecks, delta); }
    public void addRestoreChecks(long delta) { restoreChecks = boundedAdd(restoreChecks, delta); }
    public void addRestoreWrites(long delta) { restoreWrites = boundedAdd(restoreWrites, delta); }
    public void addRestoreVerifyChecks(long delta) { restoreVerifyChecks = boundedAdd(restoreVerifyChecks, delta); }

    public long preimageChecks() { return preimageChecks; }
    public long authorPreflightChecks() { return authorPreflightChecks; }
    public long authorChecks() { return authorChecks; }
    public long authorWrites() { return authorWrites; }
    public long settlementChecks() { return settlementChecks; }
    public long settlementRepairs() { return settlementRepairs; }
    public long lightRequests() { return lightRequests; }
    public long finalPhysicalChecks() { return finalPhysicalChecks; }
    public long finalLightChecks() { return finalLightChecks; }
    public long restorePreflightChecks() { return restorePreflightChecks; }
    public long restoreChecks() { return restoreChecks; }
    public long restoreWrites() { return restoreWrites; }
    public long restoreVerifyChecks() { return restoreVerifyChecks; }

    public String preimageReport() {
        return "resourcePreimageChecks=" + preimageChecks;
    }

    public String authoringReport() {
        return "resourceAuthorPreflightChecks=" + authorPreflightChecks
                + ";resourceAuthorChecks=" + authorChecks
                + ";resourceAuthorWrites=" + authorWrites;
    }

    public String settlementReport() {
        return "resourceSettlementChecks=" + settlementChecks
                + ";resourceSettlementRepairs=" + settlementRepairs;
    }

    public String lightingReport() {
        return "resourceLightRequests=" + lightRequests;
    }

    public String verificationReport() {
        return "resourceFinalPhysicalChecks=" + finalPhysicalChecks
                + ";resourceFinalLightChecks=" + finalLightChecks;
    }

    public String restoreReport() {
        return "resourceRestorePreflightChecks=" + restorePreflightChecks
                + ";resourceRestoreChecks=" + restoreChecks
                + ";resourceRestoreWrites=" + restoreWrites;
    }

    public String restoreVerificationReport() {
        return "resourceRestoreVerifyChecks=" + restoreVerifyChecks;
    }

    private static long boundedAdd(long current, long delta) {
        if (delta <= 0 || current >= MAX_COUNT) return current;
        long remaining = MAX_COUNT - current;
        return delta >= remaining ? MAX_COUNT : current + delta;
    }
}
