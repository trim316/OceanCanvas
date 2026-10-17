package net.oceancanvas.mod.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persists everything {@link ProtectedRegions} and the shipwreck-relocation
 * logic in {@link OceanCanvasSurfaceFlattener} need in order to survive a
 * server restart.
 *
 * <p><b>Root cause of the "shipwreck chest destroyed again" bug recurring
 * even after the previous fix:</b> both {@code ProtectedRegions} (a plain
 * static list) and the flattener's old {@code RELOCATED_SHIPWRECK_ORIGINS}
 * (a plain static set) lived only in JVM memory - zero persistence to the
 * world save. Every server restart wiped both clean, while vanilla's own
 * structure-start data survives a restart independently of actual block
 * data - so the flattener kept re-discovering an already-relocated natural
 * shipwreck as "not yet relocated" and pasting an empty (already-cleared)
 * capture directly on top of the real one. Full story in the discovery
 * notes in {@link OceanCanvasSurfaceFlattener#relocateShipwreckIfNeeded}.</p>
 *
 * <p><b>Revision history on THIS class, since it's needed two follow-up
 * fixes of its own to actually compile:</b></p>
 * <ul>
 * <li><b>Rev 1</b> - written against the pre-26.1 {@code SavedData} shape:
 * manual {@code save(CompoundTag)}/{@code load(CompoundTag)} overrides and
 * a nested {@code SavedData.Factory<T>}. Didn't compile - both were removed
 * in 26.1's saved-data overhaul.</li>
 * <li><b>Rev 2</b> - moved to {@code Codec}-driven persistence via
 * {@code SavedDataType<T>}, but guessed its constructor shape (a
 * {@code String} id, and a plain {@code Codec<T>}). Still didn't fully
 * match - confirmed via NeoForge's official 26.1 migration primer
 * (docs.neoforged.net/primer/docs/26.1, "Splitting the Primary Level Data
 * into Saved Data" section): {@code SavedDataType} actually takes an
 * {@code Identifier} (resolved against the world's data folder, allowing
 * subdirectories) and a {@code MapCodec<T>}, not a {@code Codec<T>}.</li>
 * <li><b>Rev 3</b> - matched the primer's documented shape with {@code new
 * SavedDataType<>(Identifier, Supplier<T>, MapCodec<T>, DataFixTypes-or-null)},
 * queried via {@code level.getDataStorage().computeIfAbsent(TYPE)}. Still
 * didn't compile: a real {@code gradle runClient} run in this sandbox
 * (see Rev 4) showed {@code SavedDataType}'s actual (record) constructor
 * takes a plain {@code Codec<T>}, not a {@code MapCodec<T>} - the primer's
 * wording was read too literally. {@code Identifier.withNamespaceAndPath}
 * was also wrong - real factory method is {@code Identifier.fromNamespaceAndPath}.
 * {@code DimensionDataStorage} really was renamed {@code SavedDataStorage}
 * as the primer said, but that's never referenced by name here regardless,
 * since {@code getDataStorage()} is called and chained directly.</li>
 * <li><b>Rev 4</b> - the first revision actually confirmed against a real
 * build. {@link #BOUNDING_BOX_CODEC} stays a plain {@code Codec},
 * package-private (not private) so {@link OceanCanvasPlayerZones} can
 * reuse it for zone bounds without duplicating the field list; {@link
 * #CODEC} stays a {@code MapCodec} (needed for {@code RecordCodecBuilder}
 * composition the way it's written) but is converted with {@code
 * MapCodec#codec()} at the one place - the {@link #TYPE} constant - that
 * actually needs a plain {@code Codec<T>}.</li>
 * <li><b>Rev 5 (this version)</b> - found from an actual "as the chunks
 * were cleared, parts of the boat, including the chests, were deleted"
 * report on a SECOND shipwreck, distinct from every earlier fix: this
 * class only ever recorded protection for a natural shipwreck's location
 * AFTER {@code relocateShipwreckIfNeeded} finished its full capture/paste
 * dance. That dance has its own precondition
 * ({@code allChunksReadyForRelocation}) that can genuinely take multiple
 * chunk-load cycles to satisfy for a structure spanning more than one
 * chunk - and in that entire waiting window, the ORIGINAL ship sat with
 * zero protection beyond the transient, per-call {@code
 * naturalStructureBounds} list in {@code flattenChunk}, which is only
 * ever populated for chunks within the 3x3 neighborhood of the ONE chunk
 * that owns the structure's {@code StructureStart}. Any OTHER chunk the
 * structure's own footprint touches - entirely normal for a
 * multi-chunk-wide wreck - could get flattened first and carve straight
 * through real, still-original ship material with no persisted record
 * anywhere that this position needed protecting. This is very plausibly
 * the deepest, most direct root cause behind every version of "shipwreck
 * chest destroyed" reported across this whole project - the previous
 * fixes were all real and needed, but they only ever protected the
 * RELOCATED copy, never the vulnerable window before relocation
 * succeeds. {@link #pendingOriginalProtections} closes that window:
 * registered the instant a {@code ShipwreckStructure} is discovered
 * (successful relocation or not), checked by {@link #isProtected} same
 * as the permanent list, and cleared once relocation actually
 * succeeds and the real, permanent protection takes over.</li>
 * </ul>
 */
public final class OceanCanvasProtectedData extends SavedData {

	private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath("oceancanvas", "protected_regions");

	/**
	 * Version of the physical Canvas completion contract. Increment whenever a
	 * previously accepted physical seal could hide terrain that the new contract
	 * rejects. v77.1 bumps this because v71-v77.0 incorrectly exempted pure
	 * water/lava inside persisted structure bounding boxes.
	 */
	private static final int PHYSICAL_PROFILE_VERSION = 2;

	/**
	 * Serialization/semantic generation for completion seals themselves.
	 * v253.1 bumps this to 2 because pre-v253.1 ambient CHUNK_LOAD work could
	 * manufacture verified/physical completion outside explicit job ownership.
	 * Those seals must be re-audited once under the authoritative ownership gate.
	 * Increment when the meaning/provenance requirements of verified seals change,
	 * even if the physical block profile is unchanged. Missing/older/future values
	 * deliberately invalidate verified + physical completion layers while retaining
	 * the weaker processed history so Pregen can migrate by re-auditing.
	 */
	private static final int COMPLETION_SEAL_FORMAT_VERSION = 2;

	/**
	 * Version of the persisted lighting-completion certificate. This is
	 * deliberately independent from the physical completion seal: v253.48 proved
	 * that a chunk can be physically canonical while its delayed light finalizer
	 * is still outstanding. Older saves therefore load with an empty lighting
	 * certificate set and are repaired non-destructively the next time Pregen (or
	 * a normal chunk load) encounters them. v253.125.12 bumps this to 2 because
	 * v253.125.11 strengthened the certificate contract with actual floor-adjacent
	 * deep-water zero-tail proof. v253.125.19 strengthens it again: every certificate
	 * now uses the geometry-gated sparse deep-overbright oracle globally, not only
	 * after a player happens to observe the chunk. Format 3 deliberately invalidates
	 * format-2 certificates so already-generated worlds are re-proved without visits. A v253.73.x/v253.125.10 certificate can therefore
	 * no longer be trusted merely because it was valid under the old fixed-depth
	 * oracle.
	 */
	private static final int LIGHTING_COMPLETION_FORMAT_VERSION = 3;

	// Package-private, not private - OceanCanvasPlayerZones' own ZONE_CODEC
	// reuses this exact field list for a zone's bounds rather than
	// duplicating it, see that class's ZONE_CODEC for the reuse site.
	static final Codec<BoundingBox> BOUNDING_BOX_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("minX").forGetter(BoundingBox::minX),
			Codec.INT.fieldOf("minY").forGetter(BoundingBox::minY),
			Codec.INT.fieldOf("minZ").forGetter(BoundingBox::minZ),
			Codec.INT.fieldOf("maxX").forGetter(BoundingBox::maxX),
			Codec.INT.fieldOf("maxY").forGetter(BoundingBox::maxY),
			Codec.INT.fieldOf("maxZ").forGetter(BoundingBox::maxZ)
	).apply(instance, BoundingBox::new));

	// One entry of pendingOriginalProtections, flattened to a (key, box)
	// pair for serialization - Codec#unboundedMap wants a key codec that
	// serializes to a JSON string/object key, which BlockPos doesn't
	// naturally have here, so this is stored as a plain list of pair
	// records instead and rebuilt into a Map in the constructor, same
	// general shape as every other collection in this class.
	private record PendingProtectionEntry(BlockPos origin, BoundingBox bounds) {
	}

	private static final Codec<PendingProtectionEntry> PENDING_PROTECTION_ENTRY_CODEC =
			RecordCodecBuilder.create(instance -> instance.group(
					BlockPos.CODEC.fieldOf("origin").forGetter(PendingProtectionEntry::origin),
					BOUNDING_BOX_CODEC.fieldOf("bounds").forGetter(PendingProtectionEntry::bounds)
			).apply(instance, PendingProtectionEntry::new));

	private record RelocatedStructureEntry(String kind, BlockPos origin, BoundingBox bounds) {
		String key() {
			return relocatedStructureKey(kind, origin);
		}
	}

	private static final Codec<RelocatedStructureEntry> RELOCATED_STRUCTURE_ENTRY_CODEC =
			RecordCodecBuilder.create(instance -> instance.group(
					Codec.STRING.fieldOf("kind").forGetter(RelocatedStructureEntry::kind),
					BlockPos.CODEC.fieldOf("origin").forGetter(RelocatedStructureEntry::origin),
					BOUNDING_BOX_CODEC.fieldOf("bounds").forGetter(RelocatedStructureEntry::bounds)
			).apply(instance, RelocatedStructureEntry::new));

	// v253.73.7 compact persistence for the four completion-seal layers. The
	// bit mask preserves their independent semantics while contiguous row runs
	// avoid serializing/boxing millions of duplicate chunk keys every autosave.
	private static final int SEAL_PROCESSED = 1;
	private static final int SEAL_VERIFIED = 2;
	private static final int SEAL_PHYSICAL = 4;
	private static final int SEAL_LIGHTING = 8;

	private record SealRun(int z, int minX, int maxX, int mask) { }

	private static final Codec<SealRun> SEAL_RUN_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("z").forGetter(SealRun::z),
			Codec.INT.fieldOf("minX").forGetter(SealRun::minX),
			Codec.INT.fieldOf("maxX").forGetter(SealRun::maxX),
			Codec.INT.fieldOf("mask").forGetter(SealRun::mask)
	).apply(instance, SealRun::new));

	private static long chunkRowSortKey(long packed) {
		int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
		return ((long) z << 32) | (((long) (x ^ Integer.MIN_VALUE)) & 0xffffffffL);
	}

	private static int sortKeyX(long key) { return ((int) key) ^ Integer.MIN_VALUE; }
	private static int sortKeyZ(long key) { return (int) (key >> 32); }

	// Still a MapCodec (RecordCodecBuilder#mapCodec, not #create) - this is
	// composed via RecordCodecBuilder same as everywhere else in this
	// class, and #codec() below converts it to the plain Codec<T> that
	// SavedDataType's real constructor (confirmed via a real build - see
	// the class doc's "Rev 4" note) actually wants.
	private static final MapCodec<OceanCanvasProtectedData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BOUNDING_BOX_CODEC.listOf().fieldOf("protectedRegions").forGetter(data -> data.protectedRegions),
			BlockPos.CODEC.listOf().fieldOf("relocatedShipwreckOrigins")
					.forGetter(data -> new ArrayList<>(data.relocatedShipwreckOrigins)),
			Codec.BOOL.fieldOf("guaranteedShipwreckPlaced").forGetter(data -> data.guaranteedShipwreckPlaced),
			// optionalFieldOf with a 0L/empty-list default - both added
			// after this class first shipped, same backward-compatibility
			// precedent as OceanCanvasJobState's requestedBy and
			// OceanCanvasPlayerZones' owner: a save file written before
			// these existed just loads as "0 flattened, no starter
			// structures marked" rather than failing to decode.
			Codec.LONG.optionalFieldOf("flattenedChunkCount", 0L).forGetter(data -> data.flattenedChunkCount),
			Codec.STRING.listOf().optionalFieldOf("starterStructuresPlaced", List.of())
					.forGetter(data -> new ArrayList<>(data.starterStructuresPlaced)),
			// Same optionalFieldOf-with-empty-default backward-compat
			// pattern as the two fields directly above - a save written
			// before Rev 5 just loads with no pending protections, which
			// is exactly correct (nothing was ever mid-relocation across
			// a save/load boundary that old data could meaningfully
			// describe anyway).
			PENDING_PROTECTION_ENTRY_CODEC.listOf().optionalFieldOf("pendingOriginalProtections", List.of())
					.forGetter(data -> data.pendingOriginalProtections.entrySet().stream()
							.map(e -> new PendingProtectionEntry(e.getKey(), e.getValue()))
							.collect(java.util.stream.Collectors.toList())),
			// Same optionalFieldOf-with-empty-default backward-compat
			// pattern, same reason: added after this class first shipped.
			// Real fix for "the stairs on the shipwreck still don't get
			// waterlogged" specifically for ModStarterStructures'
			// one-time, world-load-time guaranteed shipwreck placement -
			// see that class's call site and
			// OceanCanvasSurfaceFlattener#revalidatePlacedStructureEnvironment's
			// doc for the full story. Persisted (not just an in-memory
			// call) because there's no per-chunk carve loop to
			// synchronously defer to at world-load time the way the
			// natural-shipwreck path can - this has to survive until
			// whichever chunk actually carves the area around it.
			PENDING_PROTECTION_ENTRY_CODEC.listOf().optionalFieldOf("pendingRevalidations", List.of())
					.forGetter(data -> data.pendingRevalidations.entrySet().stream()
							.map(e -> new PendingProtectionEntry(e.getKey(), e.getValue()))
							.collect(java.util.stream.Collectors.toList())),
			// Once a canvas chunk has completed a flatten pass it is sealed from
			// ordinary chunk-load reprocessing. This is the persistence behind
			// the play-test rule: generation is destructive once, gameplay after
			// that is not. A rewipe job can explicitly bypass the seal.
			Codec.LONG.listOf().optionalFieldOf("processedChunks", List.of())
					.forGetter(data -> List.of()),
			// v67 correctness marker. Older builds could persist a processed seal and
			// later let Pregen trust it without ever re-checking the physical terrain.
			// New seals are added here only after a real flatten/physical verification.
			// Empty-by-default makes every pre-v67 seal "unverified" exactly once.
			Codec.LONG.listOf().optionalFieldOf("verifiedProcessedChunks", List.of())
					.forGetter(data -> List.of()),
			// v71 physical-completion marker. v67-v70 seals proved only that the
			// flatten pipeline ran; they did not prove the final block profile was
			// actually clean. Empty-by-default deliberately makes every older seal
			// undergo one physical audit before Pregen may trust it.
			Codec.LONG.listOf().optionalFieldOf("physicallyVerifiedProcessedChunks", List.of())
					.forGetter(data -> List.of()),
			// v253.49: physical completion and lighting completion are separate
			// persisted facts. v253.48 could save hundreds of thousands of physical
			// seals while their delayed light finalizers were still queued only in
			// JVM memory. Missing-by-default intentionally treats every older
			// physical seal as lighting-unverified until a non-destructive relight
			// reaches the authoritative publication boundary.
			Codec.LONG.listOf().optionalFieldOf("lightingVerifiedProcessedChunks", List.of())
					.forGetter(data -> List.of()),
			Codec.INT.optionalFieldOf("lightingCompletionFormatVersion", 0)
					.forGetter(data -> LIGHTING_COMPLETION_FORMAT_VERSION),
			// v81 independent completion-seal generation. Missing old metadata and
			// unknown future generations are never silently trusted as completion.
			Codec.INT.optionalFieldOf("completionSealFormatVersion", 0)
					.forGetter(data -> COMPLETION_SEAL_FORMAT_VERSION),
			// v77.1 profile-version gate. Old physical seals were produced by an
			// audit that could incorrectly exempt free-standing fluids inside
			// structure-protection boxes. A mismatched version invalidates ONLY the
			// physical layer; processed/verified history remains available for the
			// normal migration decision.
			Codec.INT.optionalFieldOf("physicalProfileVersion", 0)
					.forGetter(data -> PHYSICAL_PROFILE_VERSION),
			// v253.73.7 compact replacement for the four legacy per-chunk lists.
			// Legacy fields above remain decode-only so existing worlds migrate in one save.
			SEAL_RUN_CODEC.listOf().optionalFieldOf("sealRuns", List.of())
					.forGetter(OceanCanvasProtectedData::sealRunsForCodec),
			// Relocated structures other than shipwrecks need their new bounds
			// persisted because vanilla continues to remember only the stale
			// original StructureStart after our template move.
			RELOCATED_STRUCTURE_ENTRY_CODEC.listOf().optionalFieldOf("relocatedStructures", List.of())
					.forGetter(data -> new ArrayList<>(data.relocatedStructures.values()))
	).apply(instance, OceanCanvasProtectedData::new));

	public static final SavedDataType<OceanCanvasProtectedData> TYPE =
			new SavedDataType<>(DATA_ID, OceanCanvasProtectedData::new, CODEC.codec(), null);

	private final List<BoundingBox> protectedRegions;
	private final Set<BlockPos> relocatedShipwreckOrigins;
	private boolean guaranteedShipwreckPlaced;
	// Approximate, monotonically-increasing "chunks actually changed by a
	// flatten pass" counter - backs /oceancanvas stats' progress readout
	// (see StatusCommand#stats). Deliberately not "chunks visited" (every
	// chunk load calls flattenChunk, including no-op re-flattens of an
	// already-flat chunk) - see the increment call site in
	// OceanCanvasSurfaceFlattener#flattenChunk, gated on anyChange.
	private long flattenedChunkCount;
	// Generalized starter-structure-placed tracking, added alongside
	// ModStarterStructures#ensureGuaranteedStructure's generalization - see
	// that class's doc. guaranteedShipwreckPlaced stays its own dedicated
	// field rather than being folded into this set (zero behavior/schema
	// change risk to the always-on, already-proven shipwreck path); new
	// optional structure kinds (currently just "ocean_ruin") use this set.
	private final Set<String> starterStructuresPlaced;
	// The real fix this revision adds - see the class doc's "Rev 5" note
	// for the full story of the bug this closes. Keyed by the exact same
	// origin BlockPos relocatedShipwreckOrigins already uses, so the two
	// stay trivially in sync: register here the instant a structure is
	// discovered, remove here (via clearPendingOriginalProtection) the
	// instant markRelocated is also called for the same key.
	private final java.util.Map<BlockPos, BoundingBox> pendingOriginalProtections;
	// See the CODEC field's comment above for the full story. Keyed by
	// paste origin, same convention as pendingOriginalProtections.
	private final java.util.Map<BlockPos, BoundingBox> pendingRevalidations;
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet processedChunks;
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet verifiedProcessedChunks;
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet physicallyVerifiedProcessedChunks;
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet lightingVerifiedProcessedChunks;
	// v253.78: compact completion-seal rows are maintained on mutation. A dirty
	// world save now emits O(run-count) data instead of copying and sorting every
	// processed chunk key.
	private final net.oceancanvas.mod.util.OceanCanvasByteRowRunIndex sealRunIndex =
			new net.oceancanvas.mod.util.OceanCanvasByteRowRunIndex();
	// v253.77: O(1) processed-membership fingerprint for snapshots/status.
	private long processedMembershipXor;
	private long processedMembershipSum;
	private long processedMembershipRevision;
	private final java.util.Map<String, RelocatedStructureEntry> relocatedStructures;

	/** Fresh, empty data - used both for a brand-new world and as the codec's default-constructor supplier. */
	public OceanCanvasProtectedData() {
		this(new ArrayList<>(), new ArrayList<>(), false, 0L, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), LIGHTING_COMPLETION_FORMAT_VERSION, COMPLETION_SEAL_FORMAT_VERSION, PHYSICAL_PROFILE_VERSION, List.of(), List.of());
	}

	// Package-private constructor the codec decodes into.
	private OceanCanvasProtectedData(List<BoundingBox> protectedRegions, List<BlockPos> relocatedShipwreckOrigins,
			boolean guaranteedShipwreckPlaced, long flattenedChunkCount, List<String> starterStructuresPlaced,
			List<PendingProtectionEntry> pendingOriginalProtections, List<PendingProtectionEntry> pendingRevalidations,
			List<Long> processedChunks, List<Long> verifiedProcessedChunks, List<Long> physicallyVerifiedProcessedChunks,
			List<Long> lightingVerifiedProcessedChunks, int lightingCompletionFormatVersion,
			int completionSealFormatVersion, int physicalProfileVersion, List<SealRun> sealRuns,
			List<RelocatedStructureEntry> relocatedStructures) {
		this.protectedRegions = new ArrayList<>(protectedRegions);
		this.relocatedShipwreckOrigins = new HashSet<>(relocatedShipwreckOrigins);
		this.guaranteedShipwreckPlaced = guaranteedShipwreckPlaced;
		this.flattenedChunkCount = flattenedChunkCount;
		this.starterStructuresPlaced = new TreeSet<>(starterStructuresPlaced);
		this.pendingOriginalProtections = new java.util.HashMap<>();
		for (PendingProtectionEntry entry : pendingOriginalProtections) {
			this.pendingOriginalProtections.put(entry.origin(), entry.bounds());
		}
		this.pendingRevalidations = new java.util.HashMap<>();
		for (PendingProtectionEntry entry : pendingRevalidations) {
			this.pendingRevalidations.put(entry.origin(), entry.bounds());
		}
		this.processedChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (Long key : processedChunks) {
			if (key != null) {
				this.processedChunks.add(key.longValue());
			}
		}
		forEachSealRunChunk(sealRuns, SEAL_PROCESSED, this.processedChunks::add);
		this.verifiedProcessedChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		boolean sealFormatCompatible = completionSealFormatVersion == COMPLETION_SEAL_FORMAT_VERSION;
		if (sealFormatCompatible) {
			for (Long key : verifiedProcessedChunks) {
				if (key != null && this.processedChunks.contains(key.longValue())) {
					this.verifiedProcessedChunks.add(key.longValue());
				}
			}
			forEachSealRunChunk(sealRuns, SEAL_VERIFIED, packed -> {
				if (this.processedChunks.contains(packed)) this.verifiedProcessedChunks.add(packed);
			});
		}
		this.physicallyVerifiedProcessedChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		// Do not import stale physical seals across either a seal-format generation
		// or physical completion-contract bump.
		// This is intentionally all-or-nothing for the physical layer: keeping
		// even one v76.2 seal would allow exactly the already-observed fluid
		// columns to be silently trusted forever. Pregen will re-audit these
		// chunks and repopulate the set under PHYSICAL_PROFILE_VERSION.
		if (sealFormatCompatible && physicalProfileVersion == PHYSICAL_PROFILE_VERSION) {
			for (Long key : physicallyVerifiedProcessedChunks) {
				if (key != null && this.verifiedProcessedChunks.contains(key.longValue())) {
					this.physicallyVerifiedProcessedChunks.add(key.longValue());
				}
			}
			forEachSealRunChunk(sealRuns, SEAL_PHYSICAL, packed -> {
				if (this.verifiedProcessedChunks.contains(packed)) this.physicallyVerifiedProcessedChunks.add(packed);
			});
		}
		this.lightingVerifiedProcessedChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		boolean lightingFormatCompatible = lightingCompletionFormatVersion == LIGHTING_COMPLETION_FORMAT_VERSION;
		long serializedLightingCandidates = (lightingVerifiedProcessedChunks == null ? 0L : lightingVerifiedProcessedChunks.size())
				+ countSealRunChunks(sealRuns, SEAL_LIGHTING);
		if (!lightingFormatCompatible && serializedLightingCandidates > 0L) {
			net.oceancanvas.mod.OceanCanvas.LOGGER.warn(
					"(Ocean Canvas) LIGHTING-CERTIFICATE-MIGRATION build={} oldFormat={} newFormat={} serializedCandidates={} action=invalidate-stale-lighting-certificates-and-reprove-on-demand",
					net.oceancanvas.mod.OceanCanvas.VERSION, lightingCompletionFormatVersion, LIGHTING_COMPLETION_FORMAT_VERSION, serializedLightingCandidates);
		}
		if (lightingFormatCompatible) {
			for (Long key : lightingVerifiedProcessedChunks) {
				if (key != null && this.physicallyVerifiedProcessedChunks.contains(key.longValue())) {
					this.lightingVerifiedProcessedChunks.add(key.longValue());
				}
			}
			forEachSealRunChunk(sealRuns, SEAL_LIGHTING, packed -> {
				if (this.physicallyVerifiedProcessedChunks.contains(packed)) this.lightingVerifiedProcessedChunks.add(packed);
			});
		}
		this.relocatedStructures = new java.util.HashMap<>();
		for (RelocatedStructureEntry entry : relocatedStructures) {
			this.relocatedStructures.put(entry.key(), entry);
		}
		rebuildProcessedMembershipSummary();
		// v253.125.37: current saves already contain canonical, disjoint compact
		// seal runs. Rebuilding the same index point-by-point from up to 1.56M
		// processed keys turned world load into millions of TreeMap mutations. Fast
		// load the canonical run representation when it is self-consistent; malformed
		// or legacy/mixed inputs retain the exact old fail-closed rebuild path.
		if (!tryLoadSealRunIndexFromSerializedRuns(sealRuns, processedChunks, verifiedProcessedChunks,
				physicallyVerifiedProcessedChunks, lightingVerifiedProcessedChunks, sealFormatCompatible,
				physicalProfileVersion == PHYSICAL_PROFILE_VERSION, lightingFormatCompatible)) {
			rebuildSealRunIndex();
		}
		// Persist the new format generation even if the player only opens and closes
		// the world. Otherwise an old lighting format would be invalidated again on
		// every launch until some unrelated mutation happened to dirty SavedData.
		if (!lightingFormatCompatible) setDirty();
	}

	public static OceanCanvasProtectedData get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Registers a structure's bounding box for revalidation once real terrain has been carved around it. */
	public void markPendingRevalidation(BlockPos origin, BoundingBox box) {
		if (!box.equals(pendingRevalidations.get(origin))) {
			pendingRevalidations.put(origin, box);
			setDirty();
		}
	}

	/** Snapshot of every still-pending revalidation, keyed by origin - safe to iterate and mutate the source concurrently. */
	public java.util.Map<BlockPos, BoundingBox> getPendingRevalidations() {
		return new java.util.HashMap<>(pendingRevalidations);
	}

	public void clearPendingRevalidation(BlockPos origin) {
		if (pendingRevalidations.remove(origin) != null) {
			setDirty();
		}
	}

	/** Registers a region the flattener must leave completely untouched, persisted across restarts. */
	public void protect(BoundingBox box) {
		if (!protectedRegions.contains(box)) {
			protectedRegions.add(box);
			setDirty();
		}
	}

	/**
	 * Registers INTERIM protection for a natural structure's original
	 * position, the instant it's discovered - see the class doc's "Rev 5"
	 * note for exactly why this exists and what it fixes. Idempotent
	 * (safe to call every time the same structure is rediscovered across
	 * repeated chunk loads, which is the normal/expected case while
	 * relocation is still pending) and cheap (a single map put).
	 */
	public void protectPendingOriginal(BlockPos origin, BoundingBox box) {
		if (!box.equals(pendingOriginalProtections.get(origin))) {
			pendingOriginalProtections.put(origin, box);
			setDirty();
		}
	}

	/** Releases the interim original-position protection once real relocation has succeeded and taken over. */
	public void clearPendingOriginalProtection(BlockPos origin) {
		if (pendingOriginalProtections.remove(origin) != null) {
			setDirty();
		}
	}

	/**
	 * v253.69.6 structure-relocation rescue snapshot. The lighting finalizer and
	 * rescue lane need to reason about pending structure footprints without ever
	 * iterating the mutable persisted map directly. Callers receive an isolated
	 * snapshot, so completing a relocation may safely clear the underlying entry
	 * during the same server tick.
	 */
	public java.util.Map<BlockPos, BoundingBox> getPendingOriginalProtections() {
		return new java.util.HashMap<>(pendingOriginalProtections);
	}

	public boolean hasPendingOriginalProtection(BlockPos origin) {
		return pendingOriginalProtections.containsKey(origin);
	}

	public int pendingOriginalProtectionCount() {
		return pendingOriginalProtections.size();
	}

	/**
	 * v253.69.2 lighting/structure ordering guard. A pending-original box means a
	 * natural shipwreck has been discovered and protected but has not yet completed
	 * its floor relocation. That relocation is a known future physical mutation, so
	 * chunks touching the structure (plus a configurable propagation ring) must not
	 * receive a durable lighting certificate first.
	 */
	public boolean pendingOriginalProtectionTouchesChunkRing(ChunkPos chunk, int ringChunks) {
		int ring = Math.max(0, ringChunks);
		int minX = (chunk.x() - ring) << 4;
		int maxX = ((chunk.x() + ring) << 4) + 15;
		int minZ = (chunk.z() - ring) << 4;
		int maxZ = ((chunk.z() + ring) << 4) + 15;
		for (BoundingBox box : pendingOriginalProtections.values()) {
			if (box.maxX() >= minX && box.minX() <= maxX && box.maxZ() >= minZ && box.minZ() <= maxZ) {
				return true;
			}
		}
		return false;
	}

	/** True only for durable protected regions; excludes transient pending-original guards. */
	public boolean isPermanentlyProtected(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		for (BoundingBox box : protectedRegions) {
			if (box.isInside(pos)) return true;
		}
		return false;
	}

	/** True if the given block position falls inside any protected region - permanent OR pending-original (see class doc). */
	public boolean isProtected(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		for (BoundingBox box : protectedRegions) {
			if (box.isInside(pos)) {
				return true;
			}
		}
		for (BoundingBox box : pendingOriginalProtections.values()) {
			if (box.isInside(pos)) {
				return true;
			}
		}
		return false;
	}

	/** True if the natural shipwreck whose original min-corner is {@code origin} was already relocated. */
	public boolean isRelocated(BlockPos origin) {
		return relocatedShipwreckOrigins.contains(origin);
	}

	public void markRelocated(BlockPos origin) {
		if (relocatedShipwreckOrigins.add(origin)) setDirty();
	}

	private static void forEachSealRunChunk(List<SealRun> runs, int requiredMask, java.util.function.LongConsumer consumer) {
		if (runs == null || runs.isEmpty()) return;
		for (SealRun run : runs) {
			if ((run.mask() & requiredMask) == 0) continue;
			long width = (long) run.maxX() - (long) run.minX();
			if (width < 0L || width > 1_000_000L) continue;
			for (int x = run.minX();; x++) {
				consumer.accept(ChunkPos.pack(x, run.z()));
				if (x == run.maxX()) break;
			}
		}
	}

	private static long countSealRunChunks(List<SealRun> runs, int requiredMask) {
		if (runs == null || runs.isEmpty()) return 0L;
		long total = 0L;
		for (SealRun run : runs) {
			if ((run.mask() & requiredMask) == 0) continue;
			long width = (long) run.maxX() - (long) run.minX();
			if (width < 0L || width > 1_000_000L) continue;
			long add = width + 1L;
			if (Long.MAX_VALUE - total < add) return Long.MAX_VALUE;
			total += add;
		}
		return total;
	}

	private int sealMask(long packed) {
		int mask = SEAL_PROCESSED;
		if (verifiedProcessedChunks.contains(packed)) mask |= SEAL_VERIFIED;
		if (physicallyVerifiedProcessedChunks.contains(packed)) mask |= SEAL_PHYSICAL;
		if (lightingVerifiedProcessedChunks.contains(packed)) mask |= SEAL_LIGHTING;
		return mask;
	}

	private void refreshSealRunIndex(long packed) {
		byte mask = processedChunks.contains(packed) ? (byte) sealMask(packed) : 0;
		sealRunIndex.set(ChunkPos.getX(packed), ChunkPos.getZ(packed), mask);
	}

	private void rebuildSealRunIndex() {
		sealRunIndex.clear();
		for (it.unimi.dsi.fastutil.longs.LongIterator it = processedChunks.iterator(); it.hasNext();) {
			long key = it.nextLong();
			refreshSealRunIndex(key);
		}
	}

	/**
	 * Fast path for normal modern saves. It is deliberately conservative: only an
	 * all-run representation with no legacy per-key payload is accepted, and runs
	 * must already be sorted/disjoint exactly as our encoder emits them. Anything
	 * unusual falls back to rebuilding from the authoritative decoded sets above.
	 */
	private boolean tryLoadSealRunIndexFromSerializedRuns(List<SealRun> runs,
			List<Long> legacyProcessed, List<Long> legacyVerified, List<Long> legacyPhysical,
			List<Long> legacyLighting, boolean sealFormatCompatible,
			boolean physicalFormatCompatible, boolean lightingFormatCompatible) {
		if (runs == null || runs.isEmpty()) return false;
		if ((legacyProcessed != null && !legacyProcessed.isEmpty())
				|| (legacyVerified != null && !legacyVerified.isEmpty())
				|| (legacyPhysical != null && !legacyPhysical.isEmpty())
				|| (legacyLighting != null && !legacyLighting.isEmpty())) return false;

		int previousZ = Integer.MIN_VALUE;
		int previousMaxX = Integer.MIN_VALUE;
		boolean first = true;
		for (SealRun run : runs) {
			if (run == null || run.maxX() < run.minX()) return false;
			long width = (long) run.maxX() - (long) run.minX() + 1L;
			if (width <= 0L || width > 1_000_001L) return false;
			if (!first) {
				if (run.z() < previousZ) return false;
				if (run.z() == previousZ && run.minX() <= previousMaxX) return false;
			}
			first = false;
			previousZ = run.z();
			previousMaxX = run.maxX();
		}

		sealRunIndex.clear();
		for (SealRun run : runs) {
			int serialized = run.mask();
			int effective = 0;
			boolean processed = (serialized & SEAL_PROCESSED) != 0;
			boolean verified = processed && sealFormatCompatible && (serialized & SEAL_VERIFIED) != 0;
			boolean physical = verified && physicalFormatCompatible && (serialized & SEAL_PHYSICAL) != 0;
			boolean lighting = physical && lightingFormatCompatible && (serialized & SEAL_LIGHTING) != 0;
			if (processed) effective |= SEAL_PROCESSED;
			if (verified) effective |= SEAL_VERIFIED;
			if (physical) effective |= SEAL_PHYSICAL;
			if (lighting) effective |= SEAL_LIGHTING;
			if (effective != 0) sealRunIndex.setRange(run.minX(), run.maxX(), run.z(), (byte) effective);
		}
		return sealRunIndex.pointCount() == processedChunks.size();
	}

	private void rebuildProcessedMembershipSummary() {
		processedMembershipXor = 0L;
		processedMembershipSum = 0L;
		for (it.unimi.dsi.fastutil.longs.LongIterator it = processedChunks.iterator(); it.hasNext();) {
			long key = it.nextLong();
			long h = processedSummaryHash(key);
			processedMembershipXor ^= h;
			processedMembershipSum += Long.rotateLeft(h, 17);
		}
	}

	private boolean addProcessedKeyNoIndex(long key) {
		if (!processedChunks.add(key)) return false;
		long h = processedSummaryHash(key);
		processedMembershipXor ^= h;
		processedMembershipSum += Long.rotateLeft(h, 17);
		processedMembershipRevision++;
		return true;
	}

	private boolean addProcessedKey(long key) {
		if (!addProcessedKeyNoIndex(key)) return false;
		refreshSealRunIndex(key);
		return true;
	}

	private boolean removeProcessedKeyNoIndex(long key) {
		if (!processedChunks.remove(key)) return false;
		long h = processedSummaryHash(key);
		processedMembershipXor ^= h;
		processedMembershipSum -= Long.rotateLeft(h, 17);
		processedMembershipRevision++;
		return true;
	}

	private boolean removeProcessedKey(long key) {
		if (!removeProcessedKeyNoIndex(key)) return false;
		refreshSealRunIndex(key);
		return true;
	}

	private static long processedSummaryHash(long key) {
		long z = key ^ 0xD6E8FEB86659FD93L;
		z ^= z >>> 30; z *= 0xBF58476D1CE4E5B9L;
		z ^= z >>> 27; z *= 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Stable order-independent processed-seal fingerprint maintained in O(1). */
	public long processedMembershipRevision() { return processedMembershipRevision; }

	public String processedMembershipFingerprint() {
		return String.format(java.util.Locale.ROOT, "%016x%016x", processedMembershipXor, processedMembershipSum);
	}

	private List<SealRun> sealRunsForCodec() {
		if (sealRunIndex.isEmpty()) return List.of();
		List<SealRun> out = new ArrayList<>();
		// v253.125.37: avoid materializing a second intermediate Run list during
		// every SavedData encode; the maintained index can stream its runs directly.
		sealRunIndex.forEachRun((z, minX, maxX, value) ->
				out.add(new SealRun(z, minX, maxX, Byte.toUnsignedInt(value))));
		return out;
	}

	private List<Long> processedChunkKeysForCodec() {
		List<Long> keys = new ArrayList<>(processedChunks.size());
		for (it.unimi.dsi.fastutil.longs.LongIterator it = processedChunks.iterator(); it.hasNext();) {
			keys.add(it.nextLong());
		}
		return keys;
	}

    /** Defensive diagnostics snapshot; callers cannot mutate the persisted seal set. */
    public List<Long> processedChunkKeys() { return List.copyOf(processedChunkKeysForCodec()); }

    public int processedChunkCount() { return processedChunks.size(); }

    /** Primitive traversal for explicit diagnostics; avoids boxing/copying up to the
     * full 20k x 20k processed seal set. The consumer cannot mutate the ledger. */
    public void forEachProcessedChunk(java.util.function.LongConsumer consumer) {
        if (consumer == null) return;
        for (it.unimi.dsi.fastutil.longs.LongIterator it = processedChunks.iterator(); it.hasNext();) {
            consumer.accept(it.nextLong());
        }
    }

    public int verifiedProcessedChunkCount() { return verifiedProcessedChunks.size(); }
    public int physicallyVerifiedProcessedChunkCount() { return physicallyVerifiedProcessedChunks.size(); }
    public int lightingVerifiedProcessedChunkCount() { return lightingVerifiedProcessedChunks.size(); }

	private List<Long> verifiedProcessedChunkKeysForCodec() {
		List<Long> keys = new ArrayList<>(verifiedProcessedChunks.size());
		for (it.unimi.dsi.fastutil.longs.LongIterator it = verifiedProcessedChunks.iterator(); it.hasNext();) keys.add(it.nextLong());
		return keys;
	}

	private List<Long> physicallyVerifiedProcessedChunkKeysForCodec() {
		List<Long> keys = new ArrayList<>(physicallyVerifiedProcessedChunks.size());
		for (it.unimi.dsi.fastutil.longs.LongIterator it = physicallyVerifiedProcessedChunks.iterator(); it.hasNext();) keys.add(it.nextLong());
		return keys;
	}

	private List<Long> lightingVerifiedProcessedChunkKeysForCodec() {
		List<Long> keys = new ArrayList<>(lightingVerifiedProcessedChunks.size());
		for (it.unimi.dsi.fastutil.longs.LongIterator it = lightingVerifiedProcessedChunks.iterator(); it.hasNext();) keys.add(it.nextLong());
		return keys;
	}

	public static int currentCompletionSealFormatVersion() {
		return COMPLETION_SEAL_FORMAT_VERSION;
	}

	public static int currentPhysicalProfileVersion() {
		return PHYSICAL_PROFILE_VERSION;
	}

	public static int currentLightingCompletionFormatVersion() {
		return LIGHTING_COMPLETION_FORMAT_VERSION;
	}

	public boolean isChunkProcessedPhysicallyVerified(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		return processedChunks.contains(key) && verifiedProcessedChunks.contains(key)
				&& physicallyVerifiedProcessedChunks.contains(key);
	}

	public boolean isChunkLightingVerified(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		return processedChunks.contains(key) && verifiedProcessedChunks.contains(key)
				&& physicallyVerifiedProcessedChunks.contains(key)
				&& lightingVerifiedProcessedChunks.contains(key);
	}

	public void markChunkLightingDirty(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		if (lightingVerifiedProcessedChunks.remove(key)) { refreshSealRunIndex(key); setDirty(); }
	}

	public void markChunkLightingVerified(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		if (!processedChunks.contains(key) || !verifiedProcessedChunks.contains(key)
				|| !physicallyVerifiedProcessedChunks.contains(key)) return;
		if (lightingVerifiedProcessedChunks.add(key)) { refreshSealRunIndex(key); setDirty(); }
	}

	public boolean isChunkProcessedVerified(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		return processedChunks.contains(key) && verifiedProcessedChunks.contains(key);
	}

	public void markChunkProcessedVerified(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		// v253.125.37: one compact-index mutation for the whole state transition.
		boolean changed = addProcessedKeyNoIndex(key);
		changed |= verifiedProcessedChunks.add(key);
		if (changed) { refreshSealRunIndex(key); setDirty(); }
	}

	public void markChunkProcessedPhysicallyVerified(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		// v253.125.37: processed+verified+physical commonly become true together;
		// do not split that logical transition into multiple TreeMap rewrites.
		boolean changed = addProcessedKeyNoIndex(key);
		changed |= verifiedProcessedChunks.add(key);
		changed |= physicallyVerifiedProcessedChunks.add(key);
		if (changed) { refreshSealRunIndex(key); setDirty(); }
	}


	/**
	 * Clears Ocean-Canvas-generated structure protection/relocation state for an
	 * explicit region rewipe. Player regions live in OceanCanvasPlayerZones and are
	 * deliberately not stored here, so this cannot erase a player's region rules.
	 * The rewipe pass can then rediscover structures and rebuild protection from the
	 * region's CURRENT rules instead of inheriting stale decisions from an earlier
	 * generation pass.
	 */
	public void prepareRegionRewipe(java.util.Set<Long> chunkKeys) {
		if (chunkKeys == null || chunkKeys.isEmpty()) return;
		boolean changed = false;
		for (long key : chunkKeys) {
			boolean keyChanged = removeProcessedKeyNoIndex(key);
			keyChanged |= verifiedProcessedChunks.remove(key);
			keyChanged |= physicallyVerifiedProcessedChunks.remove(key);
			keyChanged |= lightingVerifiedProcessedChunks.remove(key);
			if (keyChanged) refreshSealRunIndex(key);
			changed |= keyChanged;
		}
		changed |= protectedRegions.removeIf(box -> boxTouchesChunks(box, chunkKeys));
		changed |= relocatedShipwreckOrigins.removeIf(origin -> chunkKeys.contains(ChunkPos.pack(Math.floorDiv(origin.getX(), 16), Math.floorDiv(origin.getZ(), 16))));
		changed |= pendingOriginalProtections.entrySet().removeIf(e -> boxTouchesChunks(e.getValue(), chunkKeys));
		changed |= pendingRevalidations.entrySet().removeIf(e -> boxTouchesChunks(e.getValue(), chunkKeys));
		changed |= relocatedStructures.entrySet().removeIf(e -> boxTouchesChunks(e.getValue().bounds(), chunkKeys));
		if (changed) setDirty();
	}

	private static boolean boxTouchesChunks(BoundingBox box, java.util.Set<Long> chunkKeys) {
		int minCx = Math.floorDiv(box.minX(), 16);
		int maxCx = Math.floorDiv(box.maxX(), 16);
		int minCz = Math.floorDiv(box.minZ(), 16);
		int maxCz = Math.floorDiv(box.maxZ(), 16);
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				if (chunkKeys.contains(ChunkPos.pack(cx, cz))) return true;
			}
		}
		return false;
	}

	public boolean isChunkProcessed(net.minecraft.world.level.ChunkPos pos) {
		return isChunkProcessed(ChunkPos.pack(pos.x(), pos.z()));
	}

	public boolean isChunkProcessed(long packed) {
		return processedChunks.contains(packed);
	}

	public void markChunkProcessed(net.minecraft.world.level.ChunkPos pos) {
		markChunkProcessed(ChunkPos.pack(pos.x(), pos.z()));
	}

	public void markChunkProcessed(long packed) {
		if (addProcessedKey(packed)) setDirty();
	}

	public void clearChunkProcessed(net.minecraft.world.level.ChunkPos pos) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		boolean changed = removeProcessedKeyNoIndex(key);
		changed |= verifiedProcessedChunks.remove(key);
		changed |= physicallyVerifiedProcessedChunks.remove(key);
		changed |= lightingVerifiedProcessedChunks.remove(key);
		if (changed) { refreshSealRunIndex(key); setDirty(); }
	}

	private static String relocatedStructureKey(String kind, BlockPos origin) {
		return kind + ":" + origin.getX() + "," + origin.getY() + "," + origin.getZ();
	}


	public record RelocatedStructureSnapshot(String kind, BoundingBox bounds) {}

	public List<RelocatedStructureSnapshot> relocatedStructureSnapshots() {
		List<RelocatedStructureSnapshot> out = new ArrayList<>(relocatedStructures.size());
		for (RelocatedStructureEntry entry : relocatedStructures.values()) {
			out.add(new RelocatedStructureSnapshot(entry.kind(), entry.bounds()));
		}
		return out;
	}

	public BoundingBox relocatedStructureBounds(OceanCanvasStructureKind kind, BlockPos origin) {
		RelocatedStructureEntry entry = relocatedStructures.get(relocatedStructureKey(kind.id(), origin));
		return entry == null ? null : entry.bounds();
	}

	public void markRelocatedStructure(OceanCanvasStructureKind kind, BlockPos origin, BoundingBox bounds) {
		RelocatedStructureEntry entry = new RelocatedStructureEntry(kind.id(), origin, bounds);
		if (!entry.equals(relocatedStructures.get(entry.key()))) {
			relocatedStructures.put(entry.key(), entry);
			setDirty();
		}
	}

	public void clearRelocatedStructure(OceanCanvasStructureKind kind, BlockPos origin) {
		if (relocatedStructures.remove(relocatedStructureKey(kind.id(), origin)) != null) {
			setDirty();
		}
	}

	/**
	 * True once the guaranteed spawn shipwreck has been placed. Needed for
	 * the same reason as everything else in this class: the paste doesn't
	 * register with vanilla's structure manager, so
	 * {@code findNearestMapStructure} alone can never tell a fresh load
	 * "yes, it's already there" - without this flag, every restart would
	 * paste another copy of the same ship directly on top of the first.
	 */
	public boolean isGuaranteedShipwreckPlaced() {
		return guaranteedShipwreckPlaced;
	}

	public void markGuaranteedShipwreckPlaced() {
		if (!guaranteedShipwreckPlaced) {
			guaranteedShipwreckPlaced = true;
			setDirty();
		}
	}

	/** Generalized companion to {@link #isGuaranteedShipwreckPlaced} for any OTHER starter structure kind - see {@link #starterStructuresPlaced}'s field doc. */
	public boolean isStarterStructurePlaced(String structureTagKey) {
		return starterStructuresPlaced.contains(structureTagKey);
	}

	public void markStarterStructurePlaced(String structureTagKey) {
		if (starterStructuresPlaced.add(structureTagKey)) {
			setDirty();
		}
	}

	/** Called once per chunk a flatten pass actually changed - see {@link #flattenedChunkCount}'s field doc. */
	public void incrementFlattenedChunks() {
		flattenedChunkCount++;
		setDirty();
	}

	/** Approximate count of chunks actually changed by a flatten pass so far - backs {@code /oceancanvas stats}. */
	public long flattenedChunkCount() {
		return flattenedChunkCount;
	}

	/** Number of shipwreck-shaped regions currently registered as protected - backs {@code /oceancanvas stats}. */
	/**
	 * A snapshot copy of every protected structure box, for the map
	 * screen's structures layer (see {@code OceanCanvasStructureSyncPayload}).
	 *
	 * <p>A defensive copy rather than the live list: this is read from the
	 * broadcast path while ordinary carving can still be appending to it,
	 * and handing out the real list would be an obvious way to acquire a
	 * {@code ConcurrentModificationException} in a place that is
	 * genuinely hard to reproduce.</p>
	 */
	public List<BoundingBox> protectedRegions() {
		return new ArrayList<>(protectedRegions);
	}

	public int protectedRegionCount() {
		return protectedRegions.size();
	}

	/** Number of natural shipwrecks relocated out of the flattener's way so far - backs {@code /oceancanvas stats}. */
	public int relocatedShipwreckCount() {
		return relocatedShipwreckOrigins.size();
	}
}
