package net.oceancanvas.core.receipt;

import net.oceancanvas.core.pipeline.ChunkKey;

public record RuntimeReceipt(long sequence, long epochMillis, ReceiptKind kind, ChunkKey chunk, String detail) {
    public RuntimeReceipt {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be >= 0");
        if (kind == null || chunk == null) throw new IllegalArgumentException("kind/chunk required");
        detail = detail == null ? "" : detail;
    }
}
