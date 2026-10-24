package net.oceancanvas.mod.worldgen;

import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.util.Locale;

/**
 * The naturally-generated structure kinds Ocean Canvas can be told to
 * preserve or clear away, per region.
 *
 * <p><b>This enum is the whole "structures" half of the mod's editing
 * model.</b> Ocean Canvas is deliberately not a block editor - it does not
 * sculpt terrain, raise land or place builds. What it does is let you draw
 * a region on the map and attach RULES to it, and this is the vocabulary
 * those rules are written in: for each kind below, a region can say
 * "keep these", "clear these", or "whatever the world default is".</p>
 *
 * <p><b>One shared definition, deliberately.</b> Before this existed, the
 * two structure rules the mod supported were two hardcoded fields threaded
 * separately through the persisted zone record, the sync packet, the
 * carve pass, the map screen and the command tree - so adding a third
 * meant touching all five and hoping they agreed on the spelling. Adding a
 * kind is now adding a constant here: persistence, networking, the UI's
 * button rows and the command's tab-completion all enumerate this type
 * rather than repeating a list of strings.</p>
 *
 * <p><b>"Preserve" includes physical Canvas integration.</b> A retained
 * shipwreck, buried treasure, ocean ruin, ocean monument, or ocean ruined
 * portal must remain a real Minecraft StructureStart while being vertically
 * integrated with the configured Canvas floor. v237 closes the last old
 * exception: natural ocean ruins and ruined portals are now relocated too,
 * rather than being protected at their original vanilla Y and left floating.
 * Turning a kind OFF removes both its physical footprint and vanilla start/
 * reference metadata through the deferred Never cleaner.</p>
 */
public enum OceanCanvasStructureKind {

	SHIPWRECK("shipwreck", "Shipwrecks", true),
	OCEAN_RUIN("ocean_ruin", "Ocean ruins", true),
	BURIED_TREASURE("buried_treasure", "Buried treasure", false),
	OCEAN_MONUMENT("ocean_monument", "Ocean monuments", true),
	RUINED_PORTAL("ruined_portal", "Ruined portals", true);

	private final String id;
	private final String displayName;

	/**
	 * Whether this kind is worth showing in a compact UI that cannot fit
	 * every kind. Buried treasure is a single hidden chest with no visible
	 * footprint - a real rule, worth having, but the last one anybody
	 * needs at a glance.
	 */
	private final boolean prominent;

	OceanCanvasStructureKind(String id, String displayName, boolean prominent) {
		this.id = id;
		this.displayName = displayName;
		this.prominent = prominent;
	}

	/** The stable string used in persistence, packets and commands. Never change one of these - saved worlds contain them. */
	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public boolean prominent() {
		return prominent;
	}

	/**
	 * The world-wide default for this kind, which a region's rule overrides. Kept here rather
	 * than at each call site so "what does INHERIT actually mean" has exactly one answer per kind.
	 *
	 * <p>Returns the same {@link net.oceancanvas.mod.config.StructureOverride} type a region's own
	 * rule uses (v125) - the world default is genuinely a 3-state choice now, not just a boolean
	 * "protect if found": {@code FORCE_ON} ("Always") actively places this kind throughout the
	 * canvas via the same vanilla-like distribution a region's own Always rule already uses (see
	 * {@code ModStarterStructures}), {@code FORCE_OFF} ("Never") clears it away everywhere, and
	 * {@code INHERIT} ("Default") is this mod's original behaviour - preserve/relocate whatever
	 * vanilla naturally generates, force nothing, clear nothing. There is nothing above "world" to
	 * inherit from, so a world default of {@code INHERIT} is never itself further resolved - it
	 * IS the base case, only reused as a value here so the region editor's precedence chain (region
	 * rule -&gt; staged job rule -&gt; world default) shares one vocabulary end to end instead of
	 * three separate ones that all mean almost the same thing.</p>
	 *
	 * <p>Shipwrecks and buried treasure default ON (world default {@code INHERIT}, meaning
	 * "preserve/relocate if found") because that is this mod's original, long-shipped behaviour
	 * and the stranded-in-the-ocean start depends on shipwreck loot being findable. Ocean ruins and
	 * monuments default {@code FORCE_OFF} ("Never") because before this they were simply carved
	 * away like any other structure, and quietly starting to preserve them in everyone's existing
	 * world would be a real, unrequested change to how an established canvas looks.</p>
	 */
	public net.oceancanvas.mod.config.StructureOverride globalDefault(OceanCanvasConfig config) {
		return switch (this) {
			case SHIPWRECK -> config.shipwrecksRule();
			case OCEAN_RUIN -> config.naturalOceanRuinsRule();
			case BURIED_TREASURE -> config.buriedTreasureRule();
			case OCEAN_MONUMENT -> config.naturalOceanMonumentsRule();
			case RUINED_PORTAL -> config.naturalRuinedPortalsRule();
		};
	}

	/**
	 * The {@code OceanCanvasConfig}/settings-sync key backing this kind's world-wide default -
	 * the same key {@link #globalDefault} reads and {@code OceanCanvasConfigUpdateRequestPayload}
	 * writes. Added so the global settings UI can generate its "world default per structure kind"
	 * rows directly from this enum, the same way the per-region rule editor already does, rather
	 * than keeping a second, hand-maintained copy of "which kind maps to which config field" only
	 * in the settings screen. See {@code OceanCanvasSettingsScreen}'s own doc for why that
	 * duplication was worth removing.
	 */
	public String configKey() {
		return switch (this) {
			case SHIPWRECK -> "shipwrecksEnabled";
			case OCEAN_RUIN -> "naturalOceanRuinsProtected";
			case BURIED_TREASURE -> "buriedTreasureEnabled";
			case OCEAN_MONUMENT -> "naturalOceanMonumentsProtected";
			case RUINED_PORTAL -> "naturalRuinedPortalsProtected";
		};
	}

	/** {@code null} for an unrecognised id - callers report that rather than guessing a kind. */
	public static OceanCanvasStructureKind byId(String id) {
		if (id == null) {
			return null;
		}
		String normalized = id.toLowerCase(Locale.ROOT);
		for (OceanCanvasStructureKind kind : values()) {
			if (kind.id.equals(normalized)) {
				return kind;
			}
		}
		return null;
	}
}
