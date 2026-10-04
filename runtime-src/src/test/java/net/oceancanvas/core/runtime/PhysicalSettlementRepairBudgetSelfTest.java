package net.oceancanvas.core.runtime;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;

import java.io.IOException;
import java.util.List;

/** Deterministic crash/restart regression for the physical settlement repair budget. */
public final class PhysicalSettlementRepairBudgetSelfTest {
    private static int checks;

    private PhysicalSettlementRepairBudgetSelfTest() {}

    public static int run() throws Exception {
        checks = 0;
        ChunkKey key = new ChunkKey(4, -7);
        ChunkKey other = new ChunkKey(5, -7);

        eq(0, PhysicalSettlementRepairBudget.recoverReservedPasses(List.of(), key, 3),
                "fresh settlement has full repair budget");

        var start1 = receipt(0, ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED,
                key, PhysicalSettlementRepairBudget.startDetail(1, 3));
        eq(1, PhysicalSettlementRepairBudget.recoverReservedPasses(List.of(start1), key, 3),
                "crash after pass reservation still consumes pass one");

        var done1 = receipt(1, ReceiptKind.PHYSICAL_SETTLEMENT_RECONCILED,
                key, "pass=1;repairedCells=8;firstMismatch=synthetic");
        eq(1, PhysicalSettlementRepairBudget.recoverReservedPasses(List.of(start1, done1), key, 3),
                "completed pass is not double-counted with its reservation");

        var start2 = receipt(2, ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED,
                key, PhysicalSettlementRepairBudget.startDetail(2, 3));
        eq(2, PhysicalSettlementRepairBudget.recoverReservedPasses(
                        List.of(start1, done1, start2), key, 3),
                "restart after second pass reservation retains two consumed passes");

        var legacy1 = receipt(0, ReceiptKind.PHYSICAL_SETTLEMENT_RECONCILED,
                key, "pass=1;repairedCells=2;firstMismatch=legacy");
        var legacy2 = receipt(1, ReceiptKind.PHYSICAL_SETTLEMENT_RECONCILED,
                key, "pass=2;repairedCells=1;firstMismatch=legacy");
        eq(2, PhysicalSettlementRepairBudget.recoverReservedPasses(
                        List.of(legacy1, legacy2), key, 3),
                "pre-reservation completed receipts migrate without resetting budget");

        var unrelated = receipt(9, ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED,
                other, PhysicalSettlementRepairBudget.startDetail(3, 3));
        eq(1, PhysicalSettlementRepairBudget.recoverReservedPasses(
                        List.of(unrelated, start1), key, 3),
                "other chunk repair evidence cannot consume this chunk budget");

        expectFailure(List.of(
                receipt(0, ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED,
                        key, PhysicalSettlementRepairBudget.startDetail(2, 3))),
                key, "gap before pass two is refused");
        expectFailure(List.of(start1,
                receipt(1, ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED,
                        key, PhysicalSettlementRepairBudget.startDetail(1, 3))),
                key, "duplicate reservation is refused");
        expectFailure(List.of(
                receipt(0, ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED,
                        key, "pass=1;max=4")),
                key, "changed durable repair bound is refused");
        expectFailure(List.of(
                receipt(0, ReceiptKind.PHYSICAL_SETTLEMENT_RECONCILED,
                        key, "pass=4;repairedCells=1;firstMismatch=forged")),
                key, "completed pass above bound is refused");
        expectFailure(List.of(
                receipt(0, ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED,
                        key, "pass=01;max=3")),
                key, "non-canonical pass encoding is refused");

        return checks;
    }

    private static RuntimeReceipt receipt(
            long sequence, ReceiptKind kind, ChunkKey key, String detail) {
        return new RuntimeReceipt(sequence, 1_000L + sequence, kind, key, detail);
    }

    private static void expectFailure(List<RuntimeReceipt> receipts, ChunkKey key, String label) {
        boolean failed = false;
        try {
            PhysicalSettlementRepairBudget.recoverReservedPasses(receipts, key, 3);
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, label);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void eq(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
        checks++;
    }
}
