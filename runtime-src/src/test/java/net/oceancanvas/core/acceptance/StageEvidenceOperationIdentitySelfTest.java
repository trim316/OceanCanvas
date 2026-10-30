package net.oceancanvas.core.acceptance;

/** Deterministic regression for explicit operation identity carried by stage receipts. */
public final class StageEvidenceOperationIdentitySelfTest {
    private StageEvidenceOperationIdentitySelfTest() {}

    public static void main(String[] args) {
        String expected = "operation-a";

        require(StageEvidenceCertificate.operationIdentityCompatible("cells=1", expected),
                "legacy receipt without operation token must remain compatible");
        require(StageEvidenceCertificate.operationIdentityCompatible("cells=1;operation=operation-a", expected),
                "matching explicit operation identity must remain compatible");
        require(!StageEvidenceCertificate.operationIdentityCompatible("cells=1;operation=operation-b", expected),
                "explicit cross-operation receipt must be rejected");
        require(!StageEvidenceCertificate.operationIdentityCompatible("operation=operation-a;operation=operation-a", expected),
                "duplicate operation tokens must be rejected as ambiguous");
        require(!StageEvidenceCertificate.operationIdentityCompatible("operation=", expected),
                "empty explicit operation identity must be rejected");

        System.out.println("StageEvidenceOperationIdentitySelfTest PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
