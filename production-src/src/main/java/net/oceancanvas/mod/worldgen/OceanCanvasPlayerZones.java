package net.oceancanvas.mod.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.config.StructureOverride;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * Player-defined, named, individually toggleable "hands off this area"
 * zones - the direct answer to the user's explicit request: a safe way
 * to mark certain areas (their own builds, a boundary region ahead of a
 * canvas expansion, a desert being temporarily reserved for resource
 * gathering) as protected, then later un-protect the exact same area
 * again without having to redefine it, so it goes back to being ordinary
 * canvas the next time it's flattened/reset/expanded over.
 *
 * <p><b>Deliberately a completely separate class and persisted collection
 * from {@link OceanCanvasProtectedData}, not folded into it - same
 * reasoning already applied once before in this project when
 * {@code placedStarterStructureKeys} was added alongside the existing
 * {@code guaranteedShipwreckPlaced} rather than merged into it.</b> The
 * two protection mechanisms are semantically different in a way that
 * matters for correctness, not just organization:</p>
 * <ul>
 *   <li>{@link OceanCanvasProtectedData}'s protection is structure-aware
 *       and material-filtered - it protects a shipwreck's actual blocks
 *       inside its bounding box, but still lets ordinary sand/dirt/stone
 *       inside that same box get carved away (see
 *       {@code OceanCanvasSurfaceFlattener#flattenChunk}'s
 *       {@code isNaturalTerrainMaterial} check). That's correct for "this
 *       box happens to contain a structure amid natural terrain."</li>
 *   <li>A player-defined zone needs the OPPOSITE behavior: every block in
 *       the box must be left alone unconditionally, materials included -
 *       a player's build is very likely made of stone/dirt/sand/etc.,
 *       the exact materials structure-protection deliberately carves
 *       through. Reusing the material-filtered check here would silently
 *       carve away most of a real player build while leaving only
 *       non-terrain blocks (chests, doors, etc.) standing - a real, ugly
 *       bug if this had been folded into the existing mechanism instead
 *       of kept separate. See the flattener's integration of this class
 *       (an unconditional skip, checked before the structure-protection
 *       check) for where this distinction is actually enforced.</li>
 * </ul>
 *
 * <p><b>Toggle, don't delete-and-redefine</b> - the user's own stated
 * workflow ("temporarily protect a desert...then unprotect it and clear
 * once it's gathered") needs the bounds to survive being turned off, so
 * turning protection back on later doesn't require re-selecting the
 * area. {@link Zone#protectedNow} is a separate flag from the zone's
 * existence; {@link #setEnabled} flips it in place, {@link #remove}
 * deletes the zone entirely for when it's genuinely no longer wanted.</p>
 *
 * <p>Same {@code Codec}/{@code SavedDataType} persistence shape as
 * {@link OceanCanvasProtectedData} (see that class's doc for the
 * "Revision 2" history behind why this shape, not the older manual
 * {@code CompoundTag} one, is used) - still unconfirmed against this
 * exact 26.2 build for the same reason noted there (no network access to
 * a real Loom-generated build in this sandbox).</p>
 *
 * <p><b>Per-zone ownership (drafted this round) - deliberately scoped
 * narrower than the brainstormed idea's literal wording.</b> The
 * brainstormed item said "each player can only manage their OWN
 * protected zones" - taken completely literally, that would mean opening
 * zone creation to every player on the server, not just ops. That's a
 * real, separate design decision this round doesn't make: {@code
 * /oceancanvas protect}'s whole subtree stays exactly as op-gated
 * (permission level 2) as it already was, on purpose - letting any
 * regular player define arbitrary-sized protected regions would be a
 * genuinely new griefing/force-load-abuse surface (huge {@code here}
 * selections, permanently claiming land, blocking a future reset/expand)
 * that the original design deliberately avoided, and this round isn't
 * the place to casually open that door. What IS implemented is the
 * narrower, still-real problem the same brainstormed item's rationale
 * actually named: "a non-op disabling someone ELSE's protected build" -
 * reinterpreted here as one OP being able to silently disable/delete
 * ANOTHER op's zone, which the pre-this-round model allowed. Each zone
 * now records who created it ({@link Zone#owner}/{@link Zone#ownerName}),
 * and {@link #setEnabled}/{@link #remove} refuse to touch a zone owned by
 * someone else unless the requester passes {@code canOverride} (wired to
 * permission level 3 in {@code ProtectCommand} - one tier above the
 * level-2 gate this whole subtree already requires, a real, standard
 * vanilla permission level, not an invented one). A zone with no recorded
 * owner (created by console, or persisted from before this feature
 * shipped) stays manageable by any op exactly as before - a deliberate
 * backward-compatibility choice so an already-running world's existing
 * zones don't suddenly lock out the ops who aren't their original
 * creator.</p>
 *
 * <p><b>Per-region rules - what this class actually became.</b> A zone
 * started life as "don't carve here". It is now the carrier for every
 * rule the map screen can attach to an area, which is the whole editing
 * model of this mod: Ocean Canvas does not place blocks, sculpt terrain
 * or build anything. You draw a region and say what should be true
 * inside it. Today that is four things:</p>
 * <ul>
 *   <li>{@link Zone#protectedNow} - leave the terrain here alone
 *       entirely.</li>
 *   <li>{@link Zone#structureOverrides} - per structure kind (see
 *       {@link OceanCanvasStructureKind}), keep them, clear them, or
 *       follow the world default. Stored as a map rather than a field per
 *       kind specifically so adding a kind is adding one enum constant
 *       rather than a change to the record, the codec, the packet, the
 *       carve pass, the screen and the command tree.</li>
 *   <li>{@link Zone#biomeOverride} - paint a biome across the region,
 *       the lightweight "change the biome here" edit, applied by
 *       {@link OceanCanvasBiomeMasker} at the same 4-block resolution
 *       vanilla itself stores biomes at.</li>
 *   <li>{@link Zone#suppressHostileMobs} - keep hostile mobs out, so a
 *       build site stays workable without turning the whole world
 *       peaceful. Enforced by {@link OceanCanvasMobSuppressor}, which
 *       removes monsters and nothing else, and never touches a block.</li>
 * </ul>
 *
 * <p><b>Backward compatibility is explicit, not hoped for.</b> The two
 * original hardcoded fields ({@code shipwreckOverride}/{@code
 * oceanRuinOverride}) are still READ by the codec, from any world saved
 * before the map became a rule editor, and folded into the new map on
 * load. They are never written again - see {@link #ZONE_CODEC}. A zone
 * from an older save therefore keeps exactly the rules it had, without
 * the file format carrying two dead fields forever.</p>
 */
public final class OceanCanvasPlayerZones extends SavedData {

	// v253.108: StructureOverride is a neutral config-level policy enum so configuration
	// no longer depends on this persisted worldgen container. Persisted enum names are unchanged.

	// Identifier, not a plain String - see OceanCanvasProtectedData's class
	// doc "Rev 4" note for why (SavedDataType's real constructor takes an
	// Identifier, confirmed via a real build).
	private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath("oceancanvas", "player_zones");

	/**
	 * The fixed palette a region's optional colour is chosen from - see
	 * {@link Zone#color}. A closed set, not an arbitrary RGB string,
	 * because the value is stored and re-sent forever: an arbitrary
	 * client-supplied string would mean validating length/content here
	 * for no real benefit, when eight names are already plenty to tell
	 * regions apart at a glance and every one of them reads cleanly next
	 * to a region's name in a command's output.
	 */
	static final java.util.Set<String> VALID_REGION_COLORS = java.util.Set.of(
			"RED", "ORANGE", "YELLOW", "LIME", "CYAN", "BLUE", "PURPLE", "PINK");

	private static final int MAX_EDITABLE_EXPLICIT_CHUNKS = 65_536;

	/** One persisted rule: which structure kind, and what this region says about it. */
	private record StructureOverrideEntry(String kind, String value) {
	}

	private static final Codec<StructureOverrideEntry> STRUCTURE_OVERRIDE_CODEC =
			RecordCodecBuilder.create(instance -> instance.group(
					Codec.STRING.fieldOf("kind").forGetter(StructureOverrideEntry::kind),
					Codec.STRING.fieldOf("value").forGetter(StructureOverrideEntry::value)
			).apply(instance, StructureOverrideEntry::new));

	/** Row run used to persist arbitrary chunk masks without millions of boxed Longs. */
	private record ChunkRun(int z, int minX, int maxX) {}

	/** Public immutable projection used by network sync without exposing persistence internals. */
	public record ChunkRunView(int z, int minX, int maxX) {
		public long sizeLong() { return Math.max(0L, (long) maxX - (long) minX + 1L); }
	}

	private static final Codec<ChunkRun> CHUNK_RUN_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("z").forGetter(ChunkRun::z),
			Codec.INT.fieldOf("minX").forGetter(ChunkRun::minX),
			Codec.INT.fieldOf("maxX").forGetter(ChunkRun::maxX)
	).apply(instance, ChunkRun::new));

	private static final Codec<Zone> ZONE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(Zone::name),
			OceanCanvasProtectedData.BOUNDING_BOX_CODEC.fieldOf("bounds").forGetter(Zone::bounds),
			// Legacy explicit masks are still read, but new saves use row runs below.
			Codec.LONG.listOf().optionalFieldOf("chunks", List.of()).forGetter(zone -> List.<Long>of()),
			CHUNK_RUN_CODEC.listOf().optionalFieldOf("chunkRuns", List.of()).forGetter(Zone::persistedChunkRuns),
			Codec.INT.listOf().optionalFieldOf("shapeVertices", List.of()).forGetter(Zone::shapeVertices),
			Codec.BOOL.fieldOf("protectedNow").forGetter(Zone::protectedNow),
			// Nullable UUID persisted as a 0-or-1-element string list, not a
			// real Optional<T>-shaped Codec - the exact same trick already
			// used for OceanCanvasJobState's nullable requestedBy, for the
			// same "keep this shape as simple/proven as possible" reasoning
			// documented there.
			Codec.STRING.listOf().optionalFieldOf("owner", List.of())
					.forGetter(zone -> zone.owner() == null ? List.of() : List.of(zone.owner().toString())),
			// Display-only, captured once at creation time (never a live
			// lookup) so /oceancanvas protect list can show a name even
			// for an owner who's currently offline - see #define's doc.
			Codec.STRING.optionalFieldOf("ownerName", "").forGetter(zone -> zone.ownerName() == null ? "" : zone.ownerName()),
			// The current shape: one entry per kind that actually has a
			// rule. Kinds set to INHERIT are simply absent, so the common
			// case (a zone with no structure rules at all) costs nothing.
			STRUCTURE_OVERRIDE_CODEC.listOf().optionalFieldOf("structureOverrides", List.of())
					.forGetter(Zone::persistedOverrides),
			Codec.STRING.optionalFieldOf("biomeOverride", "")
					.forGetter(zone -> zone.biomeOverride() == null ? "" : zone.biomeOverride()),
			Codec.BOOL.optionalFieldOf("suppressHostileMobs", false).forGetter(Zone::suppressHostileMobs),
			// Added this round - optionalFieldOf with a "" default means a
			// world saved before this existed loads every zone with no
			// custom colour, exactly as if the field had always been there
			// and every one of them had simply never been coloured.
			Codec.STRING.optionalFieldOf("color", "").forGetter(zone -> zone.color() == null ? "" : zone.color()),
			// LEGACY, read-only. optionalFieldOf WITHOUT a default gives an
			// Optional, and the getters below always return empty - so a
			// world saved before structure rules became a map still loads
			// its two old fields, and no world ever writes them again.
			Codec.STRING.optionalFieldOf("shipwreckOverride").forGetter(zone -> Optional.<String>empty()),
			Codec.STRING.optionalFieldOf("oceanRuinOverride").forGetter(zone -> Optional.<String>empty())
	).apply(instance, (name, bounds, chunkList, chunkRuns, shapeVertices, protectedNow, ownerList, ownerName, overrides, biomeOverride,
			suppressHostileMobs, color, legacyShipwreck, legacyOceanRuin) -> {
		Map<OceanCanvasStructureKind, StructureOverride> parsed = new EnumMap<>(OceanCanvasStructureKind.class);
		// Legacy first, so an explicit modern entry for the same kind wins
		// if a save somehow carries both.
		legacyShipwreck.ifPresent(value ->
				putIfMeaningful(parsed, OceanCanvasStructureKind.SHIPWRECK, parseOverride(value)));
		legacyOceanRuin.ifPresent(value ->
				putIfMeaningful(parsed, OceanCanvasStructureKind.OCEAN_RUIN, parseOverride(value)));
		for (StructureOverrideEntry entry : overrides) {
			OceanCanvasStructureKind kind = OceanCanvasStructureKind.byId(entry.kind());
			if (kind != null) {
				putIfMeaningful(parsed, kind, parseOverride(entry.value()));
			}
		}
		Set<Long> decodedChunks = chunkRuns.isEmpty()
				? (chunkList.isEmpty() ? Set.of() : new CompressedChunkSet(chunkList))
				: CompressedChunkSet.fromRuns(chunkRuns);
		return new Zone(name, bounds, decodedChunks, shapeVertices, protectedNow,
				ownerList.isEmpty() ? null : UUID.fromString(ownerList.get(0)),
				ownerName.isEmpty() ? null : ownerName,
				parsed,
				biomeOverride.isEmpty() ? null : biomeOverride,
				suppressHostileMobs,
				color.isEmpty() ? null : color);
	}));

	/**
	 * Parses a persisted rule value, falling back to {@code INHERIT} for
	 * anything unrecognised - a hand-edited save, or a value written by a
	 * future version this build does not know about. Failing one field
	 * softly beats refusing to decode the whole region over it, which
	 * would take the region's bounds and name down with the bad value.
	 */
	private static StructureOverride parseOverride(String raw) {
		if (raw == null) {
			return StructureOverride.INHERIT;
		}
		try {
			return StructureOverride.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return StructureOverride.INHERIT;
		}
	}

	private static void putIfMeaningful(Map<OceanCanvasStructureKind, StructureOverride> target,
			OceanCanvasStructureKind kind, StructureOverride value) {
		if (value != StructureOverride.INHERIT) {
			target.put(kind, value);
		}
	}

	private static final Codec<OceanCanvasPlayerZones> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ZONE_CODEC.listOf().fieldOf("zones").forGetter(data -> new ArrayList<>(data.zones.values()))
	).apply(instance, OceanCanvasPlayerZones::new));

	public static final SavedDataType<OceanCanvasPlayerZones> TYPE =
			new SavedDataType<>(DATA_ID, OceanCanvasPlayerZones::new, CODEC, null);

	// Keyed by lowercase name for case-insensitive lookup/uniqueness -
	// LinkedHashMap so #all() returns zones in the order they were
	// created, which reads better in "/oceancanvas protect list" than an
	// arbitrary hash order would.
	private final Map<String, Zone> zones;

	public OceanCanvasPlayerZones() {
		this(new ArrayList<>());
	}

	private OceanCanvasPlayerZones(List<Zone> zoneList) {
		this.zones = new LinkedHashMap<>();
		for (Zone zone : zoneList) {
			zones.put(zone.name().toLowerCase(Locale.ROOT), zone);
		}
	}

	public static OceanCanvasPlayerZones get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	/**
	 * Defines a new zone, protected by default the moment it's created -
	 * mirrors the intuitive "I just marked this off, it should already be
	 * safe" expectation rather than requiring a separate enable step
	 * right after create. Returns a human-readable error, or {@code null}
	 * on success, matching the same "message, not exception" convention
	 * {@link net.oceancanvas.mod.pregen.PregenManager} already uses for
	 * command-facing failures.
	 *
	 * <p>{@code ownerId}/{@code ownerName} are both {@code null} for a
	 * console/command-block-created zone - see the class doc's ownership
	 * paragraph for why an unowned zone stays manageable by any op rather
	 * than becoming unmanageable. {@code ownerName} is a plain snapshot of
	 * {@code ServerPlayer#getName()} at THIS moment, not re-looked-up
	 * later - a renamed account or an offline owner would otherwise mean
	 * either a stale-vs-live-name mismatch or a name {@code /oceancanvas
	 * protect list} can't show at all; the actual ownership check below
	 * only ever compares {@code ownerId}, never the name.</p>
	 */
	public String define(String name, BoundingBox bounds, UUID ownerId, String ownerName) {
		return defineInternal(name, bounds, ownerId, ownerName, true);
	}

	/**
	 * v106.1 map-workflow draft creation.
	 *
	 * <p>A map region is first a piece of project intent: the player needs to be
	 * able to draw the footprint, choose rules such as Shipwrecks=Always, and
	 * only then commit the terrain operation. Creating the map region as already
	 * protected made that ordering impossible. Command-created
	 * {@code /oceancanvas protect ...} zones still use {@link #define} and keep
	 * the historical protect-immediately behaviour.</p>
	 */
	public String defineDraft(String name, BoundingBox bounds, UUID ownerId, String ownerName) {
		return defineInternal(name, bounds, ownerId, ownerName, false);
	}

	private String defineInternal(String name, BoundingBox bounds, UUID ownerId, String ownerName,
			boolean protectedInitially) {
		String nameError = validateName(name);
		if (nameError != null) {
			return nameError;
		}
		String key = name.toLowerCase(Locale.ROOT);
		if (zones.containsKey(key)) {
			return "A zone named '" + name + "' already exists - remove it first (\"/oceancanvas protect remove "
					+ name + "\") or pick a different name.";
		}
		zones.put(key, new Zone(name, bounds, Set.of(), List.of(), protectedInitially, ownerId, ownerName,
				Map.of(), null, false, null));
		setDirty();
		return null;
	}

	/**
	 * Promotes an unprotected map draft only after its Region Pregen has passed
	 * physical completion. This is intentionally an internal operation transition
	 * rather than an ownership bypass exposed to commands/UI: callers must already
	 * have completed their normal permission/ownership checks.
	 */
	public boolean protectForPregen(String name) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null || existing.protectedNow()) {
			return false;
		}
		zones.put(existing.name().toLowerCase(Locale.ROOT), existing.withProtected(true));
		setDirty();
		return true;
	}

	/**
	 * Toggles an existing zone's protection on/off without forgetting its
	 * bounds - see the class doc for the toggle-vs-delete reasoning.
	 *
	 * <p>{@code requester} is {@code null} for a console source (always
	 * allowed, see the class doc); for a real player, this refuses to
	 * touch a zone owned by someone else unless {@code canOverride} is
	 * {@code true} (wired to permission level 3 in {@code ProtectCommand}).
	 * A zone with no recorded {@link Zone#owner} is always allowed
	 * through, matching the class doc's backward-compatibility note.</p>
	 */
	public String setEnabled(String name, boolean enabled, UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, enabled ? "enable" : "disable");
		if (ownershipError != null) {
			return ownershipError;
		}
		zones.put(existing.name().toLowerCase(Locale.ROOT),
				existing.withProtected(enabled));
		setDirty();
		return null;
	}

	/**
	 * Deletes a zone entirely (bounds forgotten, not just disabled) - see
	 * {@link #setEnabled} for the toggle-only option and the ownership
	 * check both methods share.
	 */
	public String remove(String name, UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, "remove");
		if (ownershipError != null) {
			return ownershipError;
		}
		zones.remove(name.toLowerCase(Locale.ROOT));
		setDirty();
		return null;
	}

	/**
	 * Hands a zone off to a new owner - drafted alongside {@link #all}'s
	 * ownership feature so a departing/demoted op's zones don't
	 * permanently require a level-3 override just to manage day to day;
	 * the new owner can be handed full normal (non-override) control
	 * instead. Same {@code requester}/{@code canOverride} ownership gate
	 * as {@link #setEnabled}/{@link #remove} - transferring is exactly as
	 * sensitive as disabling or deleting a zone (it changes who can act on
	 * it going forward), so it gets the same protection.
	 */
	public String transferOwnership(String name, UUID requester, boolean canOverride, UUID newOwnerId, String newOwnerName) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, "transfer");
		if (ownershipError != null) {
			return ownershipError;
		}
		zones.put(existing.name().toLowerCase(Locale.ROOT),
				existing.withOwner(newOwnerId, newOwnerName));
		setDirty();
		return null;
	}

	/**
	 * Sets what this region says about one structure kind - the
	 * generalized replacement for what used to be two near-identical
	 * per-kind setters. See {@link OceanCanvasStructureKind}. Same
	 * ownership gate as everything else that mutates a zone.
	 */
	public String setStructureOverride(String name, OceanCanvasStructureKind kind, StructureOverride override,
			UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride,
				"change the " + kind.displayName().toLowerCase(Locale.ROOT) + " rule on");
		if (ownershipError != null) {
			return ownershipError;
		}
		zones.put(existing.name().toLowerCase(Locale.ROOT), existing.withStructureOverride(kind, override));
		setDirty();
		return null;
	}

	/**
	 * Paints a biome across this region, or clears the override when
	 * {@code biomeId} is null/blank - the "change the biome here" edit.
	 *
	 * <p><b>The id is not validated against the biome registry here</b>,
	 * deliberately: this class is plain persisted data with no registry
	 * access, and {@link OceanCanvasBiomeMasker} already has to handle an
	 * unresolvable id at apply time anyway (a datapack can be removed
	 * between one session and the next, which no amount of validation at
	 * write time would catch). The caller that DOES have a registry - the
	 * command and the network handler - checks and reports before getting
	 * here, so a typo still fails loudly rather than silently doing
	 * nothing.</p>
	 */
	public String setBiomeOverride(String name, String biomeId, UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, "change the biome of");
		if (ownershipError != null) {
			return ownershipError;
		}
		String normalized = biomeId == null || biomeId.isBlank() ? null : biomeId.trim();
		zones.put(existing.name().toLowerCase(Locale.ROOT), existing.withBiomeOverride(normalized));
		setDirty();
		return null;
	}

	/**
	 * Sets or clears a region's display colour - purely cosmetic, see
	 * {@link Zone#color}'s doc. Unlike a structure/biome/mob rule, this
	 * has no effect on the world at all, so it gets the same ownership
	 * gate as those (a region's OWNER decides its own labelling) but not
	 * the "does it actually do anything" concerns that come with a real
	 * rule.
	 *
	 * @param colorId one of {@link #VALID_REGION_COLORS}, or {@code null}/blank to clear
	 */
	public String setColor(String name, String colorId, UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, "change the colour of");
		if (ownershipError != null) {
			return ownershipError;
		}
		String normalized = colorId == null || colorId.isBlank() ? null : colorId.trim().toUpperCase(Locale.ROOT);
		if (normalized != null && !VALID_REGION_COLORS.contains(normalized)) {
			return "Unrecognized colour '" + colorId + "' - choose one of " + VALID_REGION_COLORS + ".";
		}
		zones.put(existing.name().toLowerCase(Locale.ROOT), existing.withColor(normalized));
		setDirty();
		return null;
	}

	/**
	 * Creates a new zone at {@code bounds} that starts with an existing
	 * zone's rules instead of a blank slate - the server-side half of the
	 * map screen's "C" duplicate shortcut and the equivalent {@code
	 * /oceancanvas protect duplicate} command (see {@code
	 * OceanCanvasZoneDuplicateRequestPayload}'s class doc for the full
	 * design reasoning).
	 *
	 * <p>Only {@link Zone#structureOverrides}, {@link Zone#biomeOverride}
	 * and {@link Zone#suppressHostileMobs} come from the source zone.
	 * Everything else - name, bounds, protection state, owner - is exactly
	 * what {@link #define} would produce for a brand new zone, since this
	 * IS {@link #define} with a different starting rule set; the two share
	 * every validation and error message so a duplicate can never succeed
	 * where a plain create would have failed.</p>
	 *
	 * <p>No ownership check against {@code sourceName}: copying a rule set
	 * out of a region is not an action ON that region, and nothing about
	 * it changes. Only the new zone is created, owned by {@code ownerId}
	 * like any other fresh {@link #define}.</p>
	 *
	 * @return an error message for the player, or {@code null} on success
	 */
	public String duplicate(String sourceName, String name, BoundingBox bounds, UUID ownerId, String ownerName) {
		Zone source = zones.get(sourceName.toLowerCase(Locale.ROOT));
		if (source == null) {
			return "No zone named '" + sourceName + "' - see \"/oceancanvas protect list\".";
		}
		String nameError = validateName(name);
		if (nameError != null) {
			return nameError;
		}
		String key = name.toLowerCase(Locale.ROOT);
		if (zones.containsKey(key)) {
			return "A zone named '" + name + "' already exists - remove it first (\"/oceancanvas protect remove "
					+ name + "\") or pick a different name.";
		}
		// color does NOT travel from the source, unlike the three rule
		// fields above - it is a label on this specific region, the same
		// way its name and owner are labels the copy gets its own of, not
		// a rule the copy is supposed to inherit.
		zones.put(key, new Zone(name, bounds, Set.of(), List.of(), true, ownerId, ownerName,
				source.structureOverrides(), source.biomeOverride(), source.suppressHostileMobs(), null));
		setDirty();
		return null;
	}

	/**
	 * Renames a zone, keeping its bounds, protection state, owner and both
	 * structure overrides exactly as they were - drafted alongside the
	 * in-game map screen, where dragging out a rectangle creates a zone
	 * immediately under an auto-generated name rather than stopping to ask
	 * for one (see {@code OceanCanvasZoneRenameRequestPayload}'s class doc
	 * for why that ordering was chosen).
	 *
	 * <p>Re-keys the backing map rather than mutating in place, since the
	 * map is keyed by lowercased name - and rejects a rename onto a name
	 * already in use with the same message {@link #define} uses, EXCEPT
	 * when the only match is the zone renaming itself, which is what makes
	 * a pure change of capitalisation ({@code harbour} to {@code Harbour})
	 * work rather than failing against its own reflection.</p>
	 *
	 * <p>Same ownership gate as {@link #setEnabled}/{@link #remove} - a
	 * rename is not destructive, but it does change the name every other
	 * command and every existing note refers the zone by, which is
	 * disruptive enough to belong on the same footing.</p>
	 */
	public String rename(String name, String newName, UUID requester, boolean canOverride) {
		String oldKey = name.toLowerCase(Locale.ROOT);
		Zone existing = zones.get(oldKey);
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String trimmed = newName == null ? "" : newName.trim();
		String nameError = validateName(trimmed);
		if (nameError != null) {
			return nameError;
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, "rename");
		if (ownershipError != null) {
			return ownershipError;
		}

		String newKey = trimmed.toLowerCase(Locale.ROOT);
		if (!newKey.equals(oldKey) && zones.containsKey(newKey)) {
			return "A zone named '" + trimmed + "' already exists - pick a different name.";
		}

		// Rebuild the whole map rather than remove-then-put, purely to
		// preserve creation order: this is a LinkedHashMap specifically so
		// "/oceancanvas protect list" and the map screen's own list read in
		// the order zones were made, and a plain re-put would silently
		// shuffle a renamed zone to the bottom.
		Map<String, Zone> rebuilt = new LinkedHashMap<>();
		for (Map.Entry<String, Zone> entry : zones.entrySet()) {
			if (entry.getKey().equals(oldKey)) {
				rebuilt.put(newKey, existing.withName(trimmed));
			} else {
				rebuilt.put(entry.getKey(), entry.getValue());
			}
		}
		zones.clear();
		zones.putAll(rebuilt);
		setDirty();
		return null;
	}

	/**
	 * Moves a region so that it comes BEFORE another one in precedence
	 * order - the one operation that can resolve a rule conflict without
	 * deleting anything.
	 *
	 * <p><b>Why this exists.</b> Where two regions overlap and set the
	 * same rule differently, the one defined first wins (see {@link
	 * #structureOverrideAt}). The map screen now shows that plainly, which
	 * immediately raised the obvious question: and then what? Before this,
	 * the only way to change the answer was to delete the winning region
	 * and draw it again, losing its name, its owner and every other rule
	 * it carried - a destructive fix for an ordering problem.</p>
	 *
	 * <p><b>Move-before rather than move-up.</b> A raw "move one step
	 * earlier" would be simpler and much worse: precedence is only ever
	 * observed between two regions that actually overlap, and stepping a
	 * region past an unrelated one changes nothing the player can see. So
	 * the caller names the region to get in front of, and this puts it
	 * immediately there - one press, one visible result, however many
	 * unrelated regions happen to sit between them.</p>
	 *
	 * <p>Ownership is checked on the region being MOVED, not on the target.
	 * Moving ahead of someone else's region does not modify their region -
	 * its bounds, rules and owner are untouched - and requiring their
	 * permission would mean an op could be permanently blocked from fixing
	 * their own region's rules by a region they cannot edit.</p>
	 *
	 * @return an error message for the player, or {@code null} on success
	 */
	public String movePrecedenceBefore(String name, String targetName, UUID requester, boolean canOverride) {
		String key = name.toLowerCase(Locale.ROOT);
		String targetKey = targetName == null ? "" : targetName.toLowerCase(Locale.ROOT);
		Zone moving = zones.get(key);
		if (moving == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		if (!zones.containsKey(targetKey)) {
			return "No zone named '" + targetName + "' to move ahead of.";
		}
		if (key.equals(targetKey)) {
			return "A region cannot take precedence over itself.";
		}
		String ownershipError = checkOwnership(moving, requester, canOverride, "reorder");
		if (ownershipError != null) {
			return ownershipError;
		}

		// Already ahead of it? Say so rather than silently doing nothing -
		// a button that reports success and changes nothing is worse than
		// one that explains itself.
		int movingIndex = -1;
		int targetIndex = -1;
		int i = 0;
		for (String existing : zones.keySet()) {
			if (existing.equals(key)) {
				movingIndex = i;
			} else if (existing.equals(targetKey)) {
				targetIndex = i;
			}
			i++;
		}
		if (movingIndex < targetIndex) {
			return "'" + moving.name() + "' already takes precedence over '" + targetName + "'.";
		}

		// Same rebuild-the-whole-map approach as rename, and for the same
		// reason: this is a LinkedHashMap precisely because its iteration
		// order IS the precedence rule, so the order has to be rewritten
		// deliberately rather than nudged.
		Map<String, Zone> rebuilt = new LinkedHashMap<>();
		for (Map.Entry<String, Zone> entry : zones.entrySet()) {
			if (entry.getKey().equals(key)) {
				continue; // reinserted at its new position below
			}
			if (entry.getKey().equals(targetKey)) {
				rebuilt.put(key, moving);
			}
			rebuilt.put(entry.getKey(), entry.getValue());
		}
		zones.clear();
		zones.putAll(rebuilt);
		setDirty();
		return null;
	}

	/**
	 * Sets whether this region keeps hostile mobs out - see {@link
	 * OceanCanvasMobSuppressor} for what that actually does and, just as
	 * importantly, what it deliberately does not. Same ownership gate as
	 * everything else that mutates a region.
	 */
	public String setSuppressHostileMobs(String name, boolean suppress, UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, "change the mob rule of");
		if (ownershipError != null) {
			return ownershipError;
		}
		zones.put(existing.name().toLowerCase(Locale.ROOT), existing.withSuppressHostileMobs(suppress));
		setDirty();
		return null;
	}

	/**
	 * Changes a zone's horizontal extent, keeping its name, protection
	 * state, owner, both structure overrides and - importantly - its
	 * existing vertical range.
	 *
	 * <p><b>Y is deliberately preserved rather than replaced.</b> See
	 * {@code OceanCanvasZoneResizeRequestPayload}'s class doc: a top-down
	 * map cannot express a vertical range, and a zone made with {@code
	 * pos1}/{@code pos2} may cover only a build's real height on purpose.
	 * Silently expanding that to the whole build limit because someone
	 * nudged an edge on the map would be an invisible change to what is
	 * actually protected.</p>
	 *
	 * <p>Corners are normalised here rather than trusted, so dragging an
	 * edge past the opposite one flips the box instead of producing an
	 * inside-out one. Same ownership gate as everything else that mutates
	 * a zone.</p>
	 */

	/** Replaces/adds/subtracts exact chunks in a region footprint. Used by the map polygon/brush editor. */
	public String editChunks(String name, java.util.Collection<Long> changedChunks, String operation, UUID requester, boolean canOverride) {
		return editChunks(name, changedChunks, operation, List.of(), requester, canOverride);
	}

	public String editChunks(String name, java.util.Collection<Long> changedChunks, String operation, java.util.List<Integer> shapeVertices, UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) return "No zone named '" + name + "'.";
		String ownershipError = checkOwnership(existing, requester, canOverride, "reshape");
		if (ownershipError != null) return ownershipError;
		String op = operation == null ? "ADD" : operation.toUpperCase(Locale.ROOT);
		List<Integer> cleanedVertices = net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.cleanVertices(shapeVertices);

		// Polygon vertices are the canonical representation. REPLACE no longer rasterizes
		// the entire polygon into a boxed Set<Long> merely to store the region.
		if ("REPLACE".equals(op) && cleanedVertices.size() >= 6) {
			zones.put(name.toLowerCase(Locale.ROOT), existing.withChunks(Set.of(), cleanedVertices));
			setDirty();
			return null;
		}

		// Selection algebra over a million-chunk polygon would reintroduce the exact
		// materialization this P0 removes. Keep it fail-closed until the geometry engine
		// gains boolean polygon operations; full polygon replacement remains unlimited.
		if (existing.shapeVertices().size() >= 6 && !"REPLACE".equals(op)) {
			return "Large polygon regions are edited by replacing their vertices; ADD/SUBTRACT chunk-mask algebra is capped until compressed polygon boolean operations are available.";
		}
		if (existing.chunks().size() > MAX_EDITABLE_EXPLICIT_CHUNKS) {
			return "This legacy explicit chunk mask is too large for ADD/SUBTRACT editing. Redraw it as a polygon to migrate it to lazy geometry.";
		}
		LinkedHashSet<Long> next = new LinkedHashSet<>(existing.exactChunks());
		if ("REPLACE".equals(op)) next.clear();
		if ("SUBTRACT".equals(op)) next.removeAll(changedChunks); else next.addAll(changedChunks);
		if (next.isEmpty()) return "A region footprint cannot be empty.";
		if (next.size() > MAX_EDITABLE_EXPLICIT_CHUNKS) return "Explicit chunk-mask regions are capped at " + MAX_EDITABLE_EXPLICIT_CHUNKS + " chunks; use polygon vertices for larger regions.";
		zones.put(name.toLowerCase(Locale.ROOT), existing.withChunks(next, List.of()));
		setDirty();
		return null;
	}

	public String resize(String name, int minX, int minZ, int maxX, int maxZ, UUID requester, boolean canOverride) {
		Zone existing = zones.get(name.toLowerCase(Locale.ROOT));
		if (existing == null) {
			return "No zone named '" + name + "' - see \"/oceancanvas protect list\".";
		}
		String ownershipError = checkOwnership(existing, requester, canOverride, "resize");
		if (ownershipError != null) {
			return ownershipError;
		}

		if (existing.hasExplicitShape()) {
			return "Region '" + existing.name() + "' has an arbitrary chunk shape - edit it with the map shape tool instead of rectangular resize.";
		}

		int lowX = Math.min(minX, maxX);
		int highX = Math.max(minX, maxX);
		int lowZ = Math.min(minZ, maxZ);
		int highZ = Math.max(minZ, maxZ);
		if (highX - lowX < 1 || highZ - lowZ < 1) {
			return "That would make '" + existing.name() + "' too small to be a region.";
		}

		BoundingBox resized = new BoundingBox(lowX, existing.bounds().minY(), lowZ,
				highX, existing.bounds().maxY(), highZ);
		zones.put(existing.name().toLowerCase(Locale.ROOT), existing.withBounds(resized));
		setDirty();
		return null;
	}

	/**
	 * Rejects zone names the rest of the mod could not then address.
	 *
	 * <p><b>Enforced here rather than in either caller, because there are
	 * now two ways in and they must not disagree.</b> Every {@code name}
	 * argument in {@code ProtectCommand} is a Brigadier {@code
	 * StringArgumentType.word()}, which cannot express whitespace - so a
	 * zone named "my harbour" from the map screen's free-text rename field
	 * would be visible in the list, suggested by tab-completion, and then
	 * impossible to actually pass to {@code enable}/{@code disable}/{@code
	 * remove}/{@code override}. Putting the rule on the data model closes
	 * that off for the screen, the commands, and anything added later,
	 * instead of trusting each entry point to remember.</p>
	 *
	 * <p>Returns {@code null} when the name is usable, a human-readable
	 * refusal otherwise - the same "message, not exception" convention
	 * every other method on this class already uses.</p>
	 */
	private static String validateName(String name) {
		if (name == null || name.isBlank()) {
			return "A zone name can't be empty.";
		}
		for (int i = 0; i < name.length(); i++) {
			if (Character.isWhitespace(name.charAt(i))) {
				return "Zone names can't contain spaces - try '" + name.trim().replace(' ', '-')
						+ "' instead, so commands can refer to it too.";
			}
		}
		return null;
	}

	/**
	 * Shared ownership gate for {@link #setEnabled}/{@link #remove}/
	 * {@link #transferOwnership} - see the class doc's ownership paragraph
	 * for the full reasoning. Returns {@code null} when the action is
	 * allowed, a human-readable refusal otherwise.
	 */
	private static String checkOwnership(Zone zone, UUID requester, boolean canOverride, String verb) {
		if (requester == null || zone.owner() == null || zone.owner().equals(requester) || canOverride) {
			return null;
		}
		return "Zone '" + zone.name() + "' is owned by " + (zone.ownerName() != null ? zone.ownerName() : "another op")
				+ " - only they (or an op with permission level 3) can " + verb + " it.";
	}

	/**
	 * Re-protects repair zones that fit completely inside a completed reset
	 * rectangle. Partial overlaps are deliberately ignored so resetting one
	 * area cannot silently change the state of a larger, unrelated zone.
	 */
	public int reprotectContainedZones(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		int minX = minChunkX * 16;
		int maxX = maxChunkX * 16 + 15;
		int minZ = minChunkZ * 16;
		int maxZ = maxChunkZ * 16 + 15;
		int changed = 0;
		for (java.util.Map.Entry<String, Zone> entry : new ArrayList<>(zones.entrySet())) {
			Zone zone = entry.getValue();
			BoundingBox box = zone.bounds();
			if (!zone.protectedNow()
					&& box.minX() >= minX && box.maxX() <= maxX
					&& box.minZ() >= minZ && box.maxZ() <= maxZ) {
				zones.put(entry.getKey(), zone.withProtected(true));
				changed++;
			}
		}
		if (changed > 0) {
			setDirty();
		}
		return changed;
	}

	/** Returns a named zone without mutating it, or {@code null} when it does not exist. */
	public Zone zoneByName(String name) {
		if (name == null) {
			return null;
		}
		return zones.get(name.toLowerCase(Locale.ROOT));
	}

	/** All zones, in creation order, for {@code /oceancanvas protect list}. */
	public List<Zone> all() {
		return new ArrayList<>(zones.values());
	}

	/**
	 * True if the given position falls inside any CURRENTLY-ENABLED zone.
	 * Disabled zones are skipped entirely here - not just ignored by
	 * callers - so a temporarily-disabled zone genuinely has zero effect
	 * on carving, matching the "gather the resources, then unprotect it
	 * and let it get cleared" workflow exactly.
	 */
	public boolean isProtected(int x, int y, int z) {
		if (zones.isEmpty()) {
			return false; // common case - avoid constructing a BlockPos for nothing
		}
		for (Zone zone : zones.values()) {
			if (zone.protectedNow() && zone.contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Chunk-scoped protection view for destructive hot loops. Region count is paid once
	 * per chunk instead of once per changed block; exact polygon membership remains
	 * block-level inside the much smaller candidate list.
	 */
	public ProtectionLookup protectionLookupForChunk(ChunkPos chunk) {
		if (zones.isEmpty() || chunk == null) return ProtectionLookup.EMPTY;
		int minX = chunk.getMinBlockX(), maxX = minX + 15;
		int minZ = chunk.getMinBlockZ(), maxZ = minZ + 15;
		java.util.ArrayList<Zone> candidates = new java.util.ArrayList<>();
		for (Zone zone : zones.values()) {
			if (!zone.protectedNow()) continue;
			BoundingBox b = zone.bounds();
			if (b.maxX() < minX || b.minX() > maxX || b.maxZ() < minZ || b.minZ() > maxZ) continue;
			candidates.add(zone);
		}
		return candidates.isEmpty() ? ProtectionLookup.EMPTY : new ProtectionLookup(List.copyOf(candidates));
	}

	public static final class ProtectionLookup {
		private static final ProtectionLookup EMPTY = new ProtectionLookup(List.of());
		private final List<Zone> candidates;
		private ProtectionLookup(List<Zone> candidates) { this.candidates = candidates; }
		public boolean isProtected(int x, int y, int z) {
			for (Zone zone : candidates) if (zone.contains(x, y, z)) return true;
			return false;
		}
		public int candidateCount() { return candidates.size(); }
	}

	/**
	 * What the rules covering this position say about one structure kind:
	 * {@code INHERIT} unless some currently-ENABLED zone containing it
	 * says otherwise.
	 *
	 * <p><b>Deliberately independent of {@link Zone#protectedNow}</b>, for
	 * the same reason {@link #biomeOverrideAt} is. Requiring protection
	 * would make every structure rule inert in both directions, which is
	 * exactly what it did before this was fixed: a PROTECTED region skips
	 * the carve loop wholesale, so nothing inside it could ever be cleared
	 * away no matter what its rule said - and an UNPROTECTED region was
	 * skipped by this lookup, so "carve this area but keep the shipwrecks"
	 * silently fell back to the world default. Protection is about whether
	 * the terrain is touched; a structure rule is about what survives when
	 * it is. Both combinations are meaningful and both now work.</p>
	 *
	 * <p>Where two regions overlap and disagree, the one defined first wins
	 * ({@link LinkedHashMap} order): a real, acknowledged edge case not
	 * worth a precedence system for what should be a rare setup, and
	 * "whichever you drew first" is at least an explanation a person can
	 * hold in their head.</p>
	 */
	public StructureOverride structureOverrideAt(OceanCanvasStructureKind kind, int x, int y, int z) {
		if (!zones.isEmpty()) {
			for (Zone zone : zones.values()) {
				if (zone.contains(x, y, z)) {
					StructureOverride override = zone.overrideFor(kind);
					if (override != StructureOverride.INHERIT) {
						return override;
					}
				}
			}
		}
		// A plain radius-based pregen/rewipe/expand job may stage temporary
		// structure rules before it starts. Region rules above always win; the
		// neutral provider only fills the INHERIT gap inside the active job's own
		// scope. Keeping this dependency-neutral prevents worldgen rule resolution
		// from depending directly on the Pregen implementation.
		return OceanCanvasActiveStructureRuleProvider.resolve(
				kind, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
	}

	/**
	 * Resolves a structure kind at a position all the way to a yes/no,
	 * folding the region rule together with the world default so callers
	 * do not each re-implement that precedence. This is the single
	 * question the carve pass actually wants to ask.
	 */
	public boolean isStructureKindEnabledAt(OceanCanvasStructureKind kind, int x, int y, int z,
			net.oceancanvas.mod.config.OceanCanvasConfig config) {
		return switch (structureOverrideAt(kind, x, y, z)) {
			case FORCE_ON -> true;
			case FORCE_OFF -> false;
			// The world default is itself a three-state StructureOverride as of v125 (see
			// OceanCanvasStructureKind#globalDefault's doc) - FORCE_ON/FORCE_OFF there mean exactly
			// what they mean here (preserve/clear), and INHERIT is the world default's own base
			// case ("Default": preserve/relocate if found, force nothing) rather than pointing
			// anywhere further up, so it resolves to true here rather than recursing.
			case INHERIT -> kind.globalDefault(config) != StructureOverride.FORCE_OFF;
		};
	}

	/**
	 * The biome painted over this position by a region rule, or {@code
	 * null} if none - see {@link #setBiomeOverride}.
	 *
	 * <p>Unlike the structure lookups above, this deliberately ignores
	 * {@link Zone#protectedNow}: "protected" means the terrain here is not
	 * to be carved, which is a completely separate question from what
	 * biome it should read as. Entangling them would rule out a real
	 * combination - marking out a future build area's biome while still
	 * letting the flattener finish carving it.</p>
	 */
	public String biomeOverrideAt(int x, int y, int z) {
		if (zones.isEmpty()) {
			return null;
		}
		BlockPos pos = new BlockPos(x, y, z);
		for (Zone zone : zones.values()) {
			if (zone.biomeOverride() != null && zone.contains(x, y, z)) {
				return zone.biomeOverride();
			}
		}
		return null;
	}

	/**
	 * True if any ENABLED region containing this position keeps hostile
	 * mobs out.
	 *
	 * <p>Unlike the structure and biome lookups, this one DOES require
	 * {@link Zone#protectedNow}. That is not an inconsistency - it is what
	 * the three rules actually mean. A structure rule and a biome rule
	 * describe the area regardless of whether the flattener may touch it.
	 * Keeping mobs out is closer in spirit to protection itself: it is an
	 * active, ongoing intervention in the world, and switching a region off
	 * should stop all of those, not leave one quietly running.</p>
	 */
	public boolean suppressesHostileMobsAt(int x, int y, int z) {
		if (zones.isEmpty()) {
			return false;
		}
		BlockPos pos = new BlockPos(x, y, z);
		for (Zone zone : zones.values()) {
			if (zone.protectedNow() && zone.suppressHostileMobs() && zone.contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/** True if any region keeps mobs out at all - lets the suppressor skip per-entity work entirely in the common case. */
	public boolean hasAnyMobSuppression() {
		for (Zone zone : zones.values()) {
			if (zone.protectedNow() && zone.suppressHostileMobs()) {
				return true;
			}
		}
		return false;
	}

	/** Every region containing this position, enabled or not - for {@code /oceancanvas here}'s "what applies where I'm standing" report. */
	public List<Zone> zonesAt(int x, int y, int z) {
		List<Zone> found = new ArrayList<>();
		if (zones.isEmpty()) {
			return found;
		}
		BlockPos pos = new BlockPos(x, y, z);
		for (Zone zone : zones.values()) {
			if (zone.contains(x, y, z)) {
				found.add(zone);
			}
		}
		return found;
	}

	/**
	 * A short, human-readable summary of every rule a region carries, or
	 * {@code "no rules"}.
	 *
	 * <p>Lives here rather than in any one command because three separate
	 * places report it - {@code protect list}, {@code here} and the admin
	 * dashboard - and three independently-written summaries would drift
	 * apart the first time a rule was added. Which is exactly what
	 * happened when structure rules and biomes shipped: none of the three
	 * mentioned them at all.</p>
	 */
	public static String describeRules(Zone zone) {
		List<String> parts = new ArrayList<>();
		for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
			StructureOverride override = zone.overrideFor(kind);
			if (override != StructureOverride.INHERIT) {
				parts.add(kind.displayName().toLowerCase(Locale.ROOT) + ": "
						+ (override == StructureOverride.FORCE_ON ? "always" : "never"));
			}
		}
		if (zone.biomeOverride() != null) {
			parts.add("biome: " + zone.biomeOverride());
		}
		if (zone.suppressHostileMobs()) {
			// Unlike the structure and biome rules, this one only applies
			// while the region is protected (see suppressesHostileMobsAt).
			// Reporting it identically either way would show a rule as live
			// when it is doing nothing at all.
			parts.add(zone.protectedNow()
					? "no hostile mobs"
					: "no hostile mobs (inactive - region unprotected)");
		}
		return parts.isEmpty() ? "no rules" : String.join(", ", parts);
	}

	/** True if any region paints a biome at all - lets the masker skip its per-cell work entirely in the common case. */
	public boolean hasAnyBiomeOverride() {
		for (Zone zone : zones.values()) {
			if (zone.biomeOverride() != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A single named region and every rule attached to it. Immutable - the
	 * {@code with...} methods return a replacement that the map entry is
	 * swapped for, rather than anything mutating in place.
	 *
	 * <p>{@code owner}/{@code ownerName} are both {@code null} for a
	 * console-created or pre-ownership-feature zone. {@code
	 * structureOverrides} holds only kinds that actually have a rule -
	 * absent means {@code INHERIT}. {@code biomeOverride} is {@code null}
	 * when the region does not repaint the biome. {@code color} is
	 * {@code null} for "no custom colour" and otherwise one of a small
	 * fixed palette (see {@link OceanCanvasPlayerZones#VALID_REGION_COLORS})
	 * - purely a client display choice with no gameplay meaning at all,
	 * the same way a name is a label rather than a rule; nothing here
	 * reads it to decide anything.</p>
	 */
	/** Primitive/run-backed immutable set for legacy/brush chunk masks. */
	private static final class CompressedChunkSet extends java.util.AbstractSet<Long> {
		private final int[] zs, minXs, maxXs;
		private final int size;

		CompressedChunkSet(java.util.Collection<Long> values) {
			if (values == null || values.isEmpty()) { zs=new int[0]; minXs=new int[0]; maxXs=new int[0]; size=0; return; }
			long[] ordered = new long[values.size()]; int n=0;
			for (long packed : values) {
				int x=net.minecraft.world.level.ChunkPos.getX(packed), z=net.minecraft.world.level.ChunkPos.getZ(packed);
				ordered[n++]=((long)z << 32) | ((x ^ Integer.MIN_VALUE) & 0xffffffffL);
			}
			if(n<ordered.length)ordered=java.util.Arrays.copyOf(ordered,n);
			java.util.Arrays.sort(ordered);
			java.util.ArrayList<ChunkRun> runs=new java.util.ArrayList<>();
			int i=0;
			while(i<ordered.length){
				int z=(int)(ordered[i]>>32), min=((int)ordered[i])^Integer.MIN_VALUE, max=min; i++;
				while(i<ordered.length && (int)(ordered[i]>>32)==z){
					int x=((int)ordered[i])^Integer.MIN_VALUE;
					if(x==max || (max!=Integer.MAX_VALUE && x==max+1)){max=x;i++;continue;}
					runs.add(new ChunkRun(z,min,max)); min=max=x; i++;
				}
				runs.add(new ChunkRun(z,min,max));
			}
			int m=runs.size(); zs=new int[m];minXs=new int[m];maxXs=new int[m]; long total=0;
			for(i=0;i<m;i++){ChunkRun r=runs.get(i);zs[i]=r.z();minXs[i]=r.minX();maxXs[i]=r.maxX();total+=((long)r.maxX()-r.minX()+1L);}
			size=(int)Math.min(Integer.MAX_VALUE,total);
		}

		private CompressedChunkSet(List<ChunkRun> rawRuns, boolean ignored) {
			java.util.ArrayList<ChunkRun> runs=new java.util.ArrayList<>();
			for(ChunkRun r:rawRuns)if(r!=null && r.minX()<=r.maxX())runs.add(r);
			runs.sort(java.util.Comparator.comparingInt(ChunkRun::z).thenComparingInt(ChunkRun::minX));
			java.util.ArrayList<ChunkRun> merged=new java.util.ArrayList<>();
			for(ChunkRun r:runs){
				if(!merged.isEmpty()){ChunkRun last=merged.get(merged.size()-1);if(last.z()==r.z() && (last.maxX()==Integer.MAX_VALUE || r.minX()<=last.maxX()+1)){merged.set(merged.size()-1,new ChunkRun(last.z(),last.minX(),Math.max(last.maxX(),r.maxX())));continue;}}
				merged.add(r);
			}
			int m=merged.size();zs=new int[m];minXs=new int[m];maxXs=new int[m];long total=0;
			for(int i=0;i<m;i++){ChunkRun r=merged.get(i);zs[i]=r.z();minXs[i]=r.minX();maxXs[i]=r.maxX();total+=((long)r.maxX()-r.minX()+1L);}
			size=(int)Math.min(Integer.MAX_VALUE,total);
		}

		static CompressedChunkSet fromRuns(List<ChunkRun> runs){return new CompressedChunkSet(runs==null?List.of():runs,true);}
		List<ChunkRun> runs(){java.util.ArrayList<ChunkRun> out=new java.util.ArrayList<>(zs.length);for(int i=0;i<zs.length;i++)out.add(new ChunkRun(zs[i],minXs[i],maxXs[i]));return List.copyOf(out);}
		@Override public int size(){return size;}
		@Override public boolean contains(Object value){
			if(!(value instanceof Long packed))return false;int x=net.minecraft.world.level.ChunkPos.getX(packed),z=net.minecraft.world.level.ChunkPos.getZ(packed);
			int lo=0,hi=zs.length-1;while(lo<=hi){int mid=(lo+hi)>>>1;if(zs[mid]<z || (zs[mid]==z && maxXs[mid]<x))lo=mid+1;else if(zs[mid]>z || minXs[mid]>x)hi=mid-1;else return true;}return false;
		}
		@Override public java.util.Iterator<Long> iterator(){return new java.util.Iterator<>(){int run=0;long x=zs.length==0?0:minXs[0];@Override public boolean hasNext(){return run<zs.length;}@Override public Long next(){if(!hasNext())throw new java.util.NoSuchElementException();long packed=net.minecraft.world.level.ChunkPos.pack((int)x,zs[run]);if(x<maxXs[run])x++;else{run++;if(run<zs.length)x=minXs[run];}return packed;}};}
	}

	public record Zone(String name, BoundingBox bounds, Set<Long> chunks, List<Integer> shapeVertices, boolean protectedNow, UUID owner, String ownerName,
			Map<OceanCanvasStructureKind, StructureOverride> structureOverrides, String biomeOverride,
			boolean suppressHostileMobs, String color) {

		/**
		 * Defensive copy on the way in. A record hands its components
		 * straight out to any caller, so without this the "immutable"
		 * claim above would be false for the one component that is a
		 * collection - and this map is read from the broadcast path while
		 * commands can be replacing zones underneath it.
		 */
		public Zone {
			shapeVertices = net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.cleanVertices(shapeVertices);
			if (shapeVertices.size() >= 6) {
				// Vertices are canonical; chunk coverage is a lazy derived view.
				chunks = net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.polygonChunkSet(shapeVertices);
			} else if (chunks == null || chunks.isEmpty()) {
				chunks = Set.of();
			} else if (!(chunks instanceof CompressedChunkSet)) {
				chunks = new CompressedChunkSet(chunks);
			}
			structureOverrides = structureOverrides == null || structureOverrides.isEmpty()
					? Map.of()
					: Map.copyOf(structureOverrides);
		}

		/** True when this zone uses polygon or explicit chunk geometry rather than rectangular bounds. */
		public boolean hasExplicitShape() {
			return shapeVertices.size() >= 6 || !chunks.isEmpty();
		}

		/** Membership is block-exact for polygons, chunk-exact for explicit masks, rectangular otherwise. */
		public boolean contains(int x, int y, int z) {
			if (y < bounds.minY() || y > bounds.maxY()) return false;
			if (shapeVertices.size() >= 6) return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.pointInPolygon(x + 0.5D, z + 0.5D, shapeVertices);
			if (chunks.isEmpty()) return x >= bounds.minX() && x <= bounds.maxX() && z >= bounds.minZ() && z <= bounds.maxZ();
			long packed = net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
			return chunks.contains(packed);
		}

		/**
		 * Exact chunk footprint.
		 *
		 * <p>Arbitrary shapes already own an immutable explicit set. Legacy/rectangular
		 * regions deliberately return a lazy immutable set view instead of eagerly
		 * allocating every packed chunk key. A 20k-scale rectangle can contain well
		 * over a million chunks; merely asking for {@code size()} or {@code contains()}
		 * must never allocate/copy that entire footprint on the server thread.</p>
		 */
		public Set<Long> exactChunks() {
			if (!chunks.isEmpty()) return chunks;
			var chunkBounds = net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.chunkBoundsForBlocks(
					bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
			return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.rectangularChunkSet(chunkBounds);
		}

		List<ChunkRun> persistedChunkRuns() {
			if (shapeVertices.size() >= 6 || chunks.isEmpty()) return List.of();
			return chunks instanceof CompressedChunkSet compressed ? compressed.runs() : new CompressedChunkSet(chunks).runs();
		}

		/** Compact explicit-mask geometry for client sync; polygons sync vertices instead. */
		public List<ChunkRunView> syncChunkRuns() {
			if (shapeVertices.size() >= 6 || chunks.isEmpty()) return List.of();
			List<ChunkRun> runs = chunks instanceof CompressedChunkSet compressed ? compressed.runs() : new CompressedChunkSet(chunks).runs();
			java.util.ArrayList<ChunkRunView> out = new java.util.ArrayList<>(runs.size());
			for (ChunkRun run : runs) out.add(new ChunkRunView(run.z(), run.minX(), run.maxX()));
			return List.copyOf(out);
		}

		Zone withChunks(Set<Long> newChunks) { return withChunks(newChunks, shapeVertices); }

		Zone withChunks(Set<Long> newChunks, List<Integer> newVertices) {
			List<Integer> vertices=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.cleanVertices(newVertices);
			if(vertices.size()>=6){
				var b=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.blockBounds(vertices);
				BoundingBox envelope=new BoundingBox(b.minX(),bounds.minY(),b.minZ(),b.maxX(),bounds.maxY(),b.maxZ());
				return new Zone(name,envelope,Set.of(),vertices,protectedNow,owner,ownerName,structureOverrides,biomeOverride,suppressHostileMobs,color);
			}
			if (newChunks == null || newChunks.isEmpty()) return this;
			int minCX = Integer.MAX_VALUE, minCZ = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE, maxCZ = Integer.MIN_VALUE;
			for (long packed : newChunks) {
				int cx = net.minecraft.world.level.ChunkPos.getX(packed);
				int cz = net.minecraft.world.level.ChunkPos.getZ(packed);
				minCX = Math.min(minCX, cx); maxCX = Math.max(maxCX, cx);
				minCZ = Math.min(minCZ, cz); maxCZ = Math.max(maxCZ, cz);
			}
			BoundingBox envelope = new BoundingBox(minCX * 16, bounds.minY(), minCZ * 16, maxCX * 16 + 15, bounds.maxY(), maxCZ * 16 + 15);
			return new Zone(name, envelope, new CompressedChunkSet(newChunks), List.of(), protectedNow, owner, ownerName, structureOverrides, biomeOverride, suppressHostileMobs, color);
		}

		public StructureOverride overrideFor(OceanCanvasStructureKind kind) {
			return structureOverrides.getOrDefault(kind, StructureOverride.INHERIT);
		}

		Zone withProtected(boolean enabled) {
			return new Zone(name, bounds, chunks, shapeVertices, enabled, owner, ownerName, structureOverrides, biomeOverride, suppressHostileMobs, color);
		}

		Zone withOwner(UUID newOwner, String newOwnerName) {
			return new Zone(name, bounds, chunks, shapeVertices, protectedNow, newOwner, newOwnerName, structureOverrides, biomeOverride, suppressHostileMobs, color);
		}

		Zone withBounds(BoundingBox newBounds) {
			return new Zone(name, newBounds, Set.of(), List.of(), protectedNow, owner, ownerName, structureOverrides, biomeOverride, suppressHostileMobs, color);
		}

		Zone withName(String newName) {
			return new Zone(newName, bounds, chunks, shapeVertices, protectedNow, owner, ownerName, structureOverrides, biomeOverride, suppressHostileMobs, color);
		}

		Zone withBiomeOverride(String newBiome) {
			return new Zone(name, bounds, chunks, shapeVertices, protectedNow, owner, ownerName, structureOverrides, newBiome, suppressHostileMobs, color);
		}

		Zone withStructureOverride(OceanCanvasStructureKind kind, StructureOverride override) {
			Map<OceanCanvasStructureKind, StructureOverride> updated =
					new EnumMap<>(OceanCanvasStructureKind.class);
			updated.putAll(structureOverrides);
			// INHERIT is stored as absence, not as an entry - so clearing a
			// rule genuinely removes it, rather than leaving a row that
			// means nothing in every save file, packet and list from then
			// on.
			if (override == StructureOverride.INHERIT) {
				updated.remove(kind);
			} else {
				updated.put(kind, override);
			}
			return new Zone(name, bounds, chunks, shapeVertices, protectedNow, owner, ownerName, updated, biomeOverride, suppressHostileMobs, color);
		}

		Zone withSuppressHostileMobs(boolean suppress) {
			return new Zone(name, bounds, chunks, shapeVertices, protectedNow, owner, ownerName, structureOverrides, biomeOverride, suppress, color);
		}

		Zone withColor(String newColor) {
			return new Zone(name, bounds, chunks, shapeVertices, protectedNow, owner, ownerName, structureOverrides, biomeOverride, suppressHostileMobs, newColor);
		}

		/** Flattens the rule map for persistence - see {@link OceanCanvasPlayerZones#ZONE_CODEC}. */
		List<StructureOverrideEntry> persistedOverrides() {
			List<StructureOverrideEntry> entries = new ArrayList<>();
			for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
				StructureOverride value = structureOverrides.get(kind);
				if (value != null && value != StructureOverride.INHERIT) {
					entries.add(new StructureOverrideEntry(kind.id(), value.name()));
				}
			}
			return entries;
		}
	}
}
