package net.oceancanvas.core.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable authority/configuration identity for one destructive test operation. */
public record SingleChunkOperationSpec(
        int schemaVersion,
        ChunkKey chunk,
        int canvasSize,
        int centerX,
        int centerZ,
        int waterSurfaceY,
        int oceanFloorY,
        int oceanFloorVariation) {

    public SingleChunkOperationSpec {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported single-chunk operation schema " + schemaVersion);
        Objects.requireNonNull(chunk, "chunk");
        if (canvasSize <= 0 || canvasSize % 16 != 0) throw new IllegalArgumentException("canvasSize must be positive and chunk-aligned");
        if (oceanFloorY >= waterSurfaceY) throw new IllegalArgumentException("floor must be below water surface");
        if (oceanFloorVariation < 0) throw new IllegalArgumentException("floor variation must be >= 0");
    }

    public String operationId() {
        String canonical = schemaVersion + ":" + chunk.x() + ":" + chunk.z() + ":" + canvasSize + ":"
                + centerX + ":" + centerZ + ":" + waterSurfaceY + ":" + oceanFloorY + ":" + oceanFloorVariation;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "single-chunk-" + chunk.x() + "-" + chunk.z() + "-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
