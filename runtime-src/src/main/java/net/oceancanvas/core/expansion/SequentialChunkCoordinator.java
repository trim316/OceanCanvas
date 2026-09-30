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
 * - one active adapter retains its ticket across waiting ticks and closes at a
 *   durably terminal transition, failure, or explicit coordinator shutdown.
 *
 * This class does not grant Minecraft mutation authority. Runtime integration is
 * intentionally deferred until this coordinator has separate acceptance evidence.
 */
public final class SequentialChunkCoordinator implements AutoCloseable {
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
    // A chunk's resident adapter must survive waiting ticks. Previously a
    // try-with-resources around EVERY tick released its radius-zero ticket
    // even when no durable transition had yet completed.
    private ChunkKey leasedChunk;
    private SingleChunkPorts leasedPorts;

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
    public synchronized boolean tick(long epochMillis) throws Exception {
        final Located located;
        try {
            located = locate();
        } catch (Exception | Error e) {
            close(); // failed journal read can never leave a live ticket hidden
            throw e;
        }
        if (located.failed || located.complete) {
            close();
            return false;
        }
        if (leasedPorts != null && !located.chunk.equals(leasedChunk)) {
            close(); // only a durably completed prior chunk allows progress
        }
        if (leasedPorts == null) {
            SingleChunkPorts next = portsProvider.portsFor(located.chunk);
            if (next == null) throw new IllegalStateException("missing bounded chunk adapter");
            leasedPorts = next;
            leasedChunk = located.chunk;
        }
        try {
            boolean advanced = located.pipeline.tick(leasedPorts, epochMillis);
            if (located.pipeline.terminal()) close();
            return advanced;
        } catch (Exception | Error e) {
            close(); // crash/failure never leaks a runtime-only residency ticket
            throw e;
        }
    }

    /** Explicit shutdown closes only the currently owned one-chunk session. */
    @Override public synchronized void close() {
        SingleChunkPorts prior = leasedPorts;
        leasedPorts = null;
        leasedChunk = null;
        if (prior != null) prior.close();
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
