package net.oceancanvas.core.pipeline;

/**
 * Runtime boundary for the v0.2 single-chunk pipeline.
 *
 * <p>Each method must be bounded and restart-idempotent. A successful side
 * effect can occur immediately before a process crash and therefore be replayed
 * after restart if its transition journal append did not complete.</p>
 */
public interface SingleChunkPorts extends AutoCloseable {
    StageActionResult load(ChunkRecord record);
    StageActionResult authorPhysical(ChunkRecord record);
    StageActionResult settlePhysical(ChunkRecord record);
    StageActionResult persist(ChunkRecord record);
    StageActionResult settleLighting(ChunkRecord record);
    StageActionResult verify(ChunkRecord record);
    StageActionResult release(ChunkRecord record);

    @Override
    default void close() {}
}
