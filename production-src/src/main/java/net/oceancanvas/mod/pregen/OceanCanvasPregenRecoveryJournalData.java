package net.oceancanvas.mod.pregen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * v253.72 crash-recovery companion journal for the single active Pregen job.
 *
 * <p>The ordinary JobState cursor is a submission cursor; it is intentionally
 * allowed to run ahead of terrain/light completion.  That is excellent for
 * throughput but cannot, by itself, be treated as a transaction boundary after
 * a JVM crash or power loss.  This tiny SavedData record therefore persists the
 * last contiguous <em>authoritatively committed</em> cursor plus the bounded set
 * of chunks that were still in an uncertain physical/light lifecycle at the
 * checkpoint.  On an unclean restart Pregen rewinds to the committed cursor and
 * revalidates the uncertain tail before admitting any new terrain.</p>
 *
 * <p>This is internal recovery metadata only.  It does not create a PREPARING
 * region state and it never changes a user's protection semantics.</p>
 */
public final class OceanCanvasPregenRecoveryJournalData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(
            OceanCanvas.MOD_ID, "pregen_recovery_journal");

    public record Journal(
            String kind,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            long checkpointNextIndex, long checkpointSubmittedCount,
            long committedNextIndex, long committedSubmittedCount,
            boolean cleanStop,
            long generation,
            List<Long> uncertainChunks,
            List<Long> physicalRepairChunks) {

        public Journal {
            kind = kind == null ? "" : kind;
            uncertainChunks = primitiveLongCopy(uncertainChunks);
            physicalRepairChunks = primitiveLongCopy(physicalRepairChunks);
        }

        private static List<Long> primitiveLongCopy(List<Long> values) {
            if (values == null || values.isEmpty()) return List.of();
            LongArrayList out = new LongArrayList(values.size());
            if (values instanceof LongList primitive) {
                for (int i = 0; i < primitive.size(); i++) out.add(primitive.getLong(i));
            } else {
                for (Long value : values) if (value != null) out.add(value.longValue());
            }
            return out;
        }

        public boolean sameOperation(OceanCanvasJobState.Snapshot snapshot) {
            return snapshot != null && kind.equals(snapshot.kind())
                    && minChunkX == snapshot.minChunkX() && maxChunkX == snapshot.maxChunkX()
                    && minChunkZ == snapshot.minChunkZ() && maxChunkZ == snapshot.maxChunkZ();
        }

        public boolean matchesCheckpoint(OceanCanvasJobState.Snapshot snapshot) {
            return sameOperation(snapshot)
                    && checkpointNextIndex == snapshot.nextIndex()
                    && checkpointSubmittedCount == snapshot.submittedCount();
        }

        /** Same durable recovery payload, deliberately ignoring the diagnostic generation. */
        public boolean sameRecoveryState(Journal other) {
            return other != null
                    && kind.equals(other.kind)
                    && minChunkX == other.minChunkX && maxChunkX == other.maxChunkX
                    && minChunkZ == other.minChunkZ && maxChunkZ == other.maxChunkZ
                    && checkpointNextIndex == other.checkpointNextIndex
                    && checkpointSubmittedCount == other.checkpointSubmittedCount
                    && committedNextIndex == other.committedNextIndex
                    && committedSubmittedCount == other.committedSubmittedCount
                    && cleanStop == other.cleanStop
                    && Objects.equals(uncertainChunks, other.uncertainChunks)
                    && Objects.equals(physicalRepairChunks, other.physicalRepairChunks);
        }
    }

    private static final Codec<Journal> JOURNAL_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("kind").forGetter(Journal::kind),
            Codec.INT.fieldOf("minChunkX").forGetter(Journal::minChunkX),
            Codec.INT.fieldOf("maxChunkX").forGetter(Journal::maxChunkX),
            Codec.INT.fieldOf("minChunkZ").forGetter(Journal::minChunkZ),
            Codec.INT.fieldOf("maxChunkZ").forGetter(Journal::maxChunkZ),
            Codec.LONG.fieldOf("checkpointNextIndex").forGetter(Journal::checkpointNextIndex),
            Codec.LONG.fieldOf("checkpointSubmittedCount").forGetter(Journal::checkpointSubmittedCount),
            Codec.LONG.optionalFieldOf("committedNextIndex", 0L).forGetter(Journal::committedNextIndex),
            Codec.LONG.optionalFieldOf("committedSubmittedCount", 0L).forGetter(Journal::committedSubmittedCount),
            Codec.BOOL.optionalFieldOf("cleanStop", false).forGetter(Journal::cleanStop),
            Codec.LONG.optionalFieldOf("generation", 0L).forGetter(Journal::generation),
            Codec.LONG.listOf().optionalFieldOf("uncertainChunks", List.of()).forGetter(Journal::uncertainChunks),
            Codec.LONG.listOf().optionalFieldOf("physicalRepairChunks", List.of()).forGetter(Journal::physicalRepairChunks)
    ).apply(i, Journal::new));

    private static final Codec<OceanCanvasPregenRecoveryJournalData> CODEC = RecordCodecBuilder.create(i -> i.group(
            JOURNAL_CODEC.listOf().optionalFieldOf("journal", List.of())
                    .forGetter(data -> data.journal == null ? List.of() : List.of(data.journal))
    ).apply(i, OceanCanvasPregenRecoveryJournalData::new));

    public static final SavedDataType<OceanCanvasPregenRecoveryJournalData> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasPregenRecoveryJournalData::new, CODEC, null);

    private Journal journal;

    public OceanCanvasPregenRecoveryJournalData() {
        this(List.of());
    }

    private OceanCanvasPregenRecoveryJournalData(List<Journal> values) {
        this.journal = values.isEmpty() ? null : values.get(0);
    }

    public static OceanCanvasPregenRecoveryJournalData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    public Journal get() {
        return journal;
    }

    /**
     * Saves only when durable recovery state changed. Repeated progress/reporting
     * checkpoints with identical cursors and debt no longer dirty the level merely
     * to advance a diagnostic generation number. Returns the generation actually
     * stored after the call.
     */
    public long saveIfChanged(Journal value) {
        if (journal != null && journal.sameRecoveryState(value)) return journal.generation();
        journal = value;
        setDirty();
        return value.generation();
    }

    public void save(Journal value) {
        saveIfChanged(value);
    }

    public void clear() {
        if (journal != null) {
            journal = null;
            setDirty();
        }
    }
}
