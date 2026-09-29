package net.oceancanvas.core.journal;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

/** Durable transition fact. */
public record JournalEntry(
        long sequence,
        long epochMillis,
        ChunkKey chunk,
        ChunkStage from,
        ChunkStage to,
        long attempt,
        long revision,
        String reason) {
}
