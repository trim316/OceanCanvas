package net.oceancanvas.mod.pregen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists {@link PregenManager}'s single in-progress pregen/reset/expand
 * job (if any), so a server restart or crash mid-job doesn't just silently
 * lose it - the "job persistence" half of this round's follow-up request
 * (alongside the broadcast tuning in the various command classes and the
 * still-in-progress undo/particle-feedback work).
 *
 * <p><b>Deliberately scoped to "did the server actually stop and come
 * back", not "did the job merely pause".</b> If {@code pregenEnabled} is
 * turned off mid-job (see {@code PregenManager#onServerTick}) or the job is
 * explicitly cancelled, the persisted snapshot is cleared at that exact
 * moment, the same as the in-memory job - a deliberate design choice so a
 * job stopped on purpose never surprises anyone by silently resuming on a
 * later, unrelated restart. The ONLY path that writes a snapshot and then
 * later reads it back is: job running -&gt; server stops (restart or
 * crash) before it finished -&gt; server starts back up -&gt; the level
 * housing the job loads -&gt; the snapshot is still there, so it resumes
 * from where the cursor left off. Exception since v64: a nonempty
 * {@code queueEntryId} holds the snapshot without auto-resume. The queue
 * requires explicit Run after restart and replays the region from its
 * beginning, allowing existing Pregen seals to skip completed work.</p>
 *
 * <p><b>Represented as a 0-or-1-element list, not an {@code Optional}
 * field</b> - deliberately mirrors the already-proven pattern this
 * project already uses for "maybe nothing here" collections ({@link
 * net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones}'s zone map, {@link
 * net.oceancanvas.mod.worldgen.OceanCanvasProtectedData}'s lists) rather
 * than introducing a new, not-yet-used-anywhere-in-this-project {@code
 * Codec<Optional<T>>} shape purely to save one tiny bit of code - keeping
 * every persisted class in the mod shaped the same way is worth more here
 * than the marginal elegance of an Optional field.</p>
 *
 * <p>Same "Codec/SavedDataType-driven, not the older manual CompoundTag
 * shape" persistence model as the other two persisted classes in this mod
 * - see {@code OceanCanvasProtectedData}'s class doc for the full history
 * of why, and the same "not yet confirmed against your exact 26.2 build"
 * caveat applies here too.</p>
 */
public final class OceanCanvasJobState extends SavedData {

	// Identifier, not a plain String - see OceanCanvasProtectedData's class
	// doc "Rev 4" note for why (SavedDataType's real constructor takes an
	// Identifier, confirmed via a real build).
	private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath("oceancanvas", "job_state");

	private static final Codec<Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("kind").forGetter(Snapshot::kind),
			Codec.INT.fieldOf("minChunkX").forGetter(Snapshot::minChunkX),
			Codec.INT.fieldOf("maxChunkX").forGetter(Snapshot::maxChunkX),
			Codec.INT.fieldOf("minChunkZ").forGetter(Snapshot::minChunkZ),
			Codec.INT.fieldOf("maxChunkZ").forGetter(Snapshot::maxChunkZ),
			Codec.INT.fieldOf("excludeMinChunkX").forGetter(Snapshot::excludeMinChunkX),
			Codec.INT.fieldOf("excludeMaxChunkX").forGetter(Snapshot::excludeMaxChunkX),
			Codec.INT.fieldOf("excludeMinChunkZ").forGetter(Snapshot::excludeMinChunkZ),
			Codec.INT.fieldOf("excludeMaxChunkZ").forGetter(Snapshot::excludeMaxChunkZ),
			Codec.LONG.fieldOf("nextIndex").forGetter(Snapshot::nextIndex),
			Codec.LONG.fieldOf("submittedCount").forGetter(Snapshot::submittedCount),
			Codec.LONG.listOf().optionalFieldOf("explicitChunks", List.of()).forGetter(Snapshot::explicitChunks),
			// v96: bounded replay list for async Pregen targets that had been submitted
			// but not authoritatively retired when the snapshot was written. This is
			// deliberately separate from explicitChunks (the exact Region mask). On
			// restart these chunks are re-requested before the main cursor continues,
			// so Save & Quit can cancel transient C2ME futures without losing work.
			Codec.LONG.listOf().optionalFieldOf("replayChunks", List.of()).forGetter(Snapshot::replayChunks),
			// 0-or-1-element, same reasoning as the outer job list below -
			// a console/command-block-initiated job has no player to
			// notify on resume, exactly like the in-memory Job's own
			// nullable `requestedBy` UUID field already handles.
			Codec.STRING.listOf().optionalFieldOf("requestedBy", List.of()).forGetter(Snapshot::requestedByList),
            Codec.STRING.optionalFieldOf("queueEntryId", "").forGetter(Snapshot::queueEntryId),
			// v120 two-phase pregen. Defaults to "CARVE" for any snapshot written
			// before this field existed, which is exactly the old single-phase
			// behavior - a job already mid-flight when a server upgrades to v120
			// resumes exactly as it would have under the pre-v120 build, never
			// silently restarting into a Phase 1 it never asked for.
			Codec.STRING.optionalFieldOf("phase", "CARVE").forGetter(Snapshot::phase)
	).apply(instance, Snapshot::new));

	private static final Codec<OceanCanvasJobState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			SNAPSHOT_CODEC.listOf().fieldOf("job")
					.forGetter(data -> data.snapshot == null ? List.of() : List.of(data.snapshot))
	).apply(instance, OceanCanvasJobState::new));

	public static final SavedDataType<OceanCanvasJobState> TYPE =
			new SavedDataType<>(DATA_ID, OceanCanvasJobState::new, CODEC, null);

	// null = no in-progress job persisted right now (the common case -
	// almost always empty, since a job only spends a short time in
	// existence relative to a world's whole lifetime).
	private Snapshot snapshot;

	public OceanCanvasJobState() {
		this(List.of());
	}

	private OceanCanvasJobState(List<Snapshot> list) {
		this.snapshot = list.isEmpty() ? null : list.get(0);
	}

	public static OceanCanvasJobState get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	/** The currently-persisted job snapshot, or {@code null} if none. */
	public Snapshot get() {
		return snapshot;
	}

	/** Overwrites the persisted snapshot - called periodically while a job runs, see {@code PregenManager.Job#persist}. */
	public void save(Snapshot snapshot) {
		if (Objects.equals(this.snapshot, snapshot)) return;
		this.snapshot = snapshot;
		setDirty();
	}

	/** Clears any persisted snapshot - called on cancel and on normal completion, see the class doc for why. */
	public void clear() {
		if (snapshot != null) {
			snapshot = null;
			setDirty();
		}
	}

	/**
	 * A point-in-time copy of everything {@code PregenManager.Job} needs to
	 * pick back up exactly where it left off - the bounds, the exclusion
	 * rectangle (expand-only; a sentinel elsewhere, see {@code Job}'s own
	 * field docs), the row-major cursor, and enough context ({@code kind},
	 * {@code requestedByList}) to report a sensible "resumed" message.
	 */
	public record Snapshot(String kind, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
			int excludeMinChunkX, int excludeMaxChunkX, int excludeMinChunkZ, int excludeMaxChunkZ,
			long nextIndex, long submittedCount, List<Long> explicitChunks, List<Long> replayChunks, List<String> requestedByList, String queueEntryId, String phase) {

		public Snapshot {
			explicitChunks = primitiveLongCopy(explicitChunks);
			replayChunks = primitiveLongCopy(replayChunks);
			requestedByList = new ArrayList<>(requestedByList);
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

		/** Convenience accessor mirroring {@code Job}'s own nullable {@code UUID requestedBy} field. */
		public UUID requestedByUuid() {
			return requestedByList.isEmpty() ? null : UUID.fromString(requestedByList.get(0));
		}
	}
}
