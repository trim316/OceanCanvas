package net.oceancanvas.core.expansion;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;
import net.oceancanvas.core.pipeline.SingleChunkPipeline;
import net.oceancanvas.core.pipeline.SingleChunkPorts;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Pure-core composition layer for cautiously expanding beyond one chunk.
 *
 * Invariants:
 * - exactly one chunk pipeline may be active at a time;
 * - chunk order is deterministic and immutable;
 * - the next chunk is never opened until the previous chunk is COMPLETE;
 * - a FAILED chunk halts the whole coordinator permanently;
 * - restart truth comes from each chunk's own durable SingleChunkPipeline journal.
 *
 * This class does not grant Minecraft mutation authority. Runtime integration is
 * intentionally deferred until this coordinator has separate acceptance evidence.
 */
public final class SequentialChunkCoordinator {
    @FunctionalInterface
    public interface PipelineOpener {
        SingleChunkPipeline open(ChunkKey key) throws IOException;
    }

    @FunctionalInterface
    public interface PortsProvider {
        SingleChunkPorts portsFor(ChunkKey key) throws Exception;
    }

    public record Snapshot(int activeIndex, int completeCount, boolean complete, boolean failed,
                           ChunkKey activeChunk, ChunkStage activeStage) {}

    private final List<ChunkKey> ordered;
    private final PipelineOpener opener;
    private final PortsProvider portsProvider;

    public SequentialChunkCoordinator(List<ChunkKey> ordered, PipelineOpener opener, PortsProvider portsProvider) {
        this.ordered = List.copyOf(Objects.requireNonNull(ordered, "ordered"));
        if (this.ordered.isEmpty()) throw new IllegalArgumentException("ordered chunks must not be empty");
        if (this.ordered.stream().distinct().count() != this.ordered.size()) {
            throw new IllegalArgumentException("ordered chunks must be unique");
        }
        this.opener = Objects.requireNonNull(opener, "opener");
        this.portsProvider = Objects.requireNonNull(portsProvider, "portsProvider");
    }

    /**
     * Services at most one durable transition in one chunk.
     * Returns true only if some underlying single-chunk pipeline advanced.
     */
    public boolean tick(long epochMillis) throws Exception {
        Located located = locate();
        if (located.failed || located.complete) return false;
        try (SingleChunkPorts ports = portsProvider.portsFor(located.chunk)) {
            return located.pipeline.tick(ports, epochMillis);
        }
    }

    public Snapshot snapshot() throws IOException {
        Located located = locate();
        return new Snapshot(located.index, located.completeCount, located.complete, located.failed,
                located.chunk, located.stage);
    }

    private Located locate() throws IOException {
        int completeCount = 0;
        for (int i = 0; i < ordered.size(); i++) {
            ChunkKey key = ordered.get(i);
            SingleChunkPipeline pipeline = opener.open(key);
            ChunkStage stage = pipeline.record().stage();
            if (stage == ChunkStage.COMPLETE) {
                completeCount++;
                continue;
            }
            if (stage == ChunkStage.FAILED) {
                return new Located(i, completeCount, false, true, key, stage, pipeline);
            }
            return new Located(i, completeCount, false, false, key, stage, pipeline);
        }
        return new Located(ordered.size(), completeCount, true, false, null, ChunkStage.COMPLETE, null);
    }

    private record Located(int index, int completeCount, boolean complete, boolean failed,
                           ChunkKey chunk, ChunkStage stage, SingleChunkPipeline pipeline) {}
}
