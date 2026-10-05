package net.oceancanvas.core.pipeline;

import net.oceancanvas.core.journal.CoreJournal;
import net.oceancanvas.core.journal.JournalEntry;

import java.io.IOException;
import java.util.Objects;

/**
 * One-chunk orchestrator. Exactly one adapter stage is serviced per tick call.
 * Durable advancement occurs only after that stage reports success.
 */
public final class SingleChunkPipeline {
    private final CoreJournal journal;
    private ChunkRecord record;
    private long nextSequence;

    private SingleChunkPipeline(CoreJournal journal, ChunkRecord record, long nextSequence) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.record = Objects.requireNonNull(record, "record");
        this.nextSequence = nextSequence;
    }

    public static SingleChunkPipeline open(CoreJournal journal, ChunkKey chunk) throws IOException {
        CoreJournal.ReplayState replay = journal.replaySingleChunk(chunk);
        return new SingleChunkPipeline(journal, replay.record(), replay.nextSequence());
    }

    public ChunkRecord record() { return record; }
    public boolean terminal() { return record.stage().terminal(); }

    /** Returns true only when a durable stage transition was committed. */
    public boolean tick(SingleChunkPorts ports, long epochMillis) throws IOException {
        Objects.requireNonNull(ports, "ports");
        if (terminal()) return false;

        StageActionResult result = switch (record.stage()) {
            case DISCOVERED -> ports.load(record);
            case LOADED -> ports.capturePreimage(record);
            case PREIMAGE_CAPTURED -> ports.authorPhysical(record);
            case PHYSICAL_AUTHORED -> ports.settlePhysical(record);
            case PHYSICAL_SETTLED -> ports.persist(record);
            case PERSISTED -> ports.settleLighting(record);
            case LIGHTING_SETTLED -> ports.verify(record);
            case VERIFIED -> ports.restore(record);
            case RESTORED -> ports.verifyRestore(record);
            case RESTORE_VERIFIED -> ports.release(record);
            case COMPLETE, FAILED -> throw new IllegalStateException("terminal stage reached dispatch: " + record.stage());
        };

        if (result.status() == StageActionResult.Status.WAITING) return false;

        ChunkRecord next;
        if (result.status() == StageActionResult.Status.FAILED) {
            next = record.fail(result.evidence());
        } else {
            ChunkStage nextStage = switch (record.stage()) {
                case DISCOVERED -> ChunkStage.LOADED;
                case LOADED -> ChunkStage.PREIMAGE_CAPTURED;
                case PREIMAGE_CAPTURED -> ChunkStage.PHYSICAL_AUTHORED;
                case PHYSICAL_AUTHORED -> ChunkStage.PHYSICAL_SETTLED;
                case PHYSICAL_SETTLED -> ChunkStage.PERSISTED;
                case PERSISTED -> ChunkStage.LIGHTING_SETTLED;
                case LIGHTING_SETTLED -> ChunkStage.VERIFIED;
                case VERIFIED -> ChunkStage.RESTORED;
                case RESTORED -> ChunkStage.RESTORE_VERIFIED;
                case RESTORE_VERIFIED -> ChunkStage.COMPLETE;
                case COMPLETE, FAILED -> throw new IllegalStateException("terminal stage cannot advance");
            };
            next = record.advance(nextStage, result.evidence());
        }

        JournalEntry entry = new JournalEntry(nextSequence, epochMillis, record.chunk(),
                record.stage(), next.stage(), next.attempt(), next.revision(), result.evidence());
        // Append+fsync precedes the in-memory transition. If this throws, the
        // adapter action may replay after restart and therefore MUST be idempotent.
        journal.append(entry);
        nextSequence++;
        record = next;
        return true;
    }
}
