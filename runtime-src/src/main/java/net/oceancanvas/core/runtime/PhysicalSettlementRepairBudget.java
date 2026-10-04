package net.oceancanvas.core.runtime;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceipt;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Reconstructs the durable repair-pass budget for physical settlement.
 *
 * <p>A pass is reserved before its first repair write. On restart, any reserved
 * pass remains consumed even if the process died before the pass completed.
 * Legacy completed-pass receipts are also counted so upgrading an in-flight
 * recovery cannot silently reset the historical budget.</p>
 */
public final class PhysicalSettlementRepairBudget {
    private PhysicalSettlementRepairBudget() {}

    public static String startDetail(int pass, int maxPasses) {
        if (pass < 1 || maxPasses < 1 || pass > maxPasses) {
            throw new IllegalArgumentException("invalid settlement repair pass");
        }
        return "pass=" + pass + ";max=" + maxPasses;
    }

    public static int recoverReservedPasses(
            List<RuntimeReceipt> receipts, ChunkKey key, int maxPasses) throws IOException {
        Objects.requireNonNull(receipts, "receipts");
        Objects.requireNonNull(key, "key");
        if (maxPasses < 1 || maxPasses > 64) {
            throw new IOException("invalid settlement repair pass bound");
        }

        boolean[] started = new boolean[maxPasses + 1];
        boolean[] completed = new boolean[maxPasses + 1];
        for (RuntimeReceipt receipt : receipts) {
            if (!receipt.chunk().equals(key)) continue;
            if (receipt.kind() == ReceiptKind.PHYSICAL_SETTLEMENT_REPAIR_PASS_STARTED) {
                int pass = parseStarted(receipt.detail(), maxPasses);
                if (started[pass]) {
                    throw new IOException("duplicate settlement repair-pass reservation " + pass);
                }
                started[pass] = true;
            } else if (receipt.kind() == ReceiptKind.PHYSICAL_SETTLEMENT_RECONCILED) {
                int pass = parseCompleted(receipt.detail(), maxPasses);
                if (completed[pass]) {
                    throw new IOException("duplicate settlement reconciliation completion " + pass);
                }
                completed[pass] = true;
            }
        }

        int highest = 0;
        for (int pass = 1; pass <= maxPasses; pass++) {
            boolean seen = started[pass] || completed[pass];
            if (!seen) {
                for (int later = pass + 1; later <= maxPasses; later++) {
                    if (started[later] || completed[later]) {
                        throw new IOException("settlement repair-pass evidence has a gap before pass " + later);
                    }
                }
                break;
            }
            highest = pass;
        }
        return highest;
    }

    private static int parseStarted(String detail, int maxPasses) throws IOException {
        String prefix = "pass=";
        String marker = ";max=";
        if (detail == null || !detail.startsWith(prefix)) {
            throw new IOException("malformed settlement repair-pass reservation");
        }
        int split = detail.indexOf(marker, prefix.length());
        if (split < 0 || detail.indexOf(';', split + marker.length()) >= 0) {
            throw new IOException("malformed settlement repair-pass reservation");
        }
        int pass = parsePositive(detail.substring(prefix.length(), split),
                "settlement repair-pass reservation");
        int encodedMax = parsePositive(detail.substring(split + marker.length()),
                "settlement repair-pass max");
        if (encodedMax != maxPasses) {
            throw new IOException("settlement repair-pass bound changed: recorded="
                    + encodedMax + " current=" + maxPasses);
        }
        if (pass > maxPasses) {
            throw new IOException("settlement repair pass exceeds bound: " + pass);
        }
        return pass;
    }

    private static int parseCompleted(String detail, int maxPasses) throws IOException {
        if (detail == null || !detail.startsWith("pass=")) {
            throw new IOException("malformed settlement reconciliation receipt");
        }
        int end = detail.indexOf(';', 5);
        if (end < 0) {
            throw new IOException("malformed settlement reconciliation receipt");
        }
        int pass = parsePositive(detail.substring(5, end), "settlement reconciliation pass");
        if (pass > maxPasses) {
            throw new IOException("settlement reconciliation pass exceeds bound: " + pass);
        }
        return pass;
    }

    private static int parsePositive(String value, String label) throws IOException {
        try {
            if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
                throw new NumberFormatException("non-canonical");
            }
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException("not positive");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("invalid " + label, e);
        }
    }
}
