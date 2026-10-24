package net.oceancanvas.mod;

import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.config.StructureOverride;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OceanCanvasConfigTest {

	@Test
	void radiusIsHalfOfCanvasSize() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertEquals(10_000, config.radius());
	}

	@Test
	void originIsInsideACenteredCanvas() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertTrue(config.isInsideCanvas(0, 0));
		assertTrue(config.isInsideCanvas(9_999, -9_999));
	}

	@Test
	void pointsOutsideTheRadiusAreExcluded() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertFalse(config.isInsideCanvas(10_000, 0));
		assertFalse(config.isInsideCanvas(0, -10_001));
	}

	@Test
	void boundsAreRelativeToAnOffCenterOrigin() {
		OceanCanvasConfig config = OceanCanvasConfig.of(1_000, 5_000, -5_000, false);
		assertTrue(config.isInsideCanvas(5_000, -5_000));
		assertFalse(config.isInsideCanvas(0, 0));
	}

	// --- New future-work fields, drafted off by default this round ---
	// These use Builder#build() (in-memory only), not #save() - #save()
	// touches FabricLoader.getInstance(), which isn't available outside
	// a running Fabric environment, matching the same constraint every
	// other test in this file already works within via
	// OceanCanvasConfig#of instead of the real load()/save() path.

	@Test
	void biomeMaskIsOnByDefaultForVanillaOceanEcology() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertTrue(config.biomeMaskEnabled());
	}

	// Real fix for an actual failing test: pregenEnabled's default was
	// intentionally flipped to true - see OceanCanvasConfig's own
	// DEFAULT_PREGEN_ENABLED doc comment for why (the user's explicit
	// request that "/oceancanvas expand" proactively pregen its new
	// ring by default). This test previously asserted the old false
	// default and never got updated alongside that change until this
	// failing build caught it.
	@Test
	void pregenIsOnByDefault() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertTrue(config.pregenEnabled());
	}

	@Test
	void pregenChunksPerTickIsNeverLessThanOne() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.pregenChunksPerTick(0)
				.build();
		assertTrue(config.pregenChunksPerTick() >= 1);
	}

	@Test
	void blankBiomeMaskBiomeFallsBackToDefault() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.biomeMaskBiome("")
				.build();
		assertEquals("minecraft:ocean", config.biomeMaskBiome());
	}

	@Test
	void builderChangesDoNotAffectUnrelatedFields() {
		OceanCanvasConfig original = OceanCanvasConfig.of(20_000, 0, 0, true);
		OceanCanvasConfig updated = original.toBuilder()
				.pregenEnabled(true)
				.pregenChunksPerTick(8)
				.biomeMaskEnabled(true)
				.build();

		assertTrue(updated.pregenEnabled());
		assertEquals(8, updated.pregenChunksPerTick());
		assertTrue(updated.biomeMaskEnabled());
		// Unrelated fields carried over unchanged from the original.
		assertEquals(original.canvasSize(), updated.canvasSize());
		assertEquals(original.oceanFloorY(), updated.oceanFloorY());
	}

	// --- Round 2 fields (configurable starter-structure list + HUD) - same
	// off-by-default posture and Builder#build()-only test constraint as
	// the block above. ---

	@Test
	void guaranteeSpawnOceanRuinAndHudAreOffByDefault() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertFalse(config.guaranteeSpawnOceanRuin());
		assertFalse(config.hudEnabled());
	}

	@Test
	void builderCanFlipGuaranteeSpawnOceanRuinAndHudIndependently() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.guaranteeSpawnOceanRuin(true)
				.build();

		assertTrue(config.guaranteeSpawnOceanRuin());
		assertFalse(config.hudEnabled()); // untouched, still default

		OceanCanvasConfig hudOnly = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.hudEnabled(true)
				.build();

		assertTrue(hudOnly.hudEnabled());
		assertFalse(hudOnly.guaranteeSpawnOceanRuin()); // untouched, still default
	}

	// --- Shipwreck toggle + JourneyMap minimap integration fields -
	// same off/on-by-default posture pattern as every block above. ---

	@Test
	void shipwrecksAreOnByDefaultButJourneyMapOverlayIsOff() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertEquals(StructureOverride.INHERIT, config.shipwrecksRule());
		assertFalse(config.journeyMapOverlayEnabled());
	}

	@Test
	void builderCanFlipShipwrecksAndJourneyMapOverlayIndependently() {
		OceanCanvasConfig shipwrecksOff = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.shipwrecksRule(StructureOverride.FORCE_OFF)
				.build();

		assertEquals(StructureOverride.FORCE_OFF, shipwrecksOff.shipwrecksRule());
		assertFalse(shipwrecksOff.journeyMapOverlayEnabled()); // untouched, still default

		OceanCanvasConfig journeyMapOnly = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.journeyMapOverlayEnabled(true)
				.build();

		assertTrue(journeyMapOnly.journeyMapOverlayEnabled());
		assertEquals(StructureOverride.INHERIT, journeyMapOnly.shipwrecksRule()); // untouched, still default
	}

	// --- Ordinary-flattener throttle, automatic backups, and the soft
	// edge transition (taper) - same off/on-by-default posture pattern as
	// every block above. ---

	@Test
	void flattenerChunksPerTickDefaultsTo16AndIsNeverLessThanOne() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertEquals(16, config.flattenerChunksPerTick());

		OceanCanvasConfig floored = config.toBuilder().flattenerChunksPerTick(0).build();
		assertTrue(floored.flattenerChunksPerTick() >= 1);
	}

	@Test
	void backupIsOnByDefaultWithExpectedThresholdAndRetention() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertTrue(config.backupEnabled());
		assertEquals(500, config.backupThresholdChunks());
		assertEquals(5, config.backupRetentionCount());
	}

	@Test
	void backupRetentionCountIsNeverLessThanOne() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.backupRetentionCount(0)
				.build();
		assertTrue(config.backupRetentionCount() >= 1);
	}

	@Test
	void taperIsOffByDefaultAndWidthIsNeverLessThanOne() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertFalse(config.taperEnabled());
		assertEquals(8, config.taperWidthChunks());
		assertEquals(128, config.taperWidthBlocks());

		OceanCanvasConfig floored = config.toBuilder().taperWidthChunks(0).build();
		assertTrue(floored.taperWidthChunks() >= 1);
	}

	@Test
	void canvasZoneIsOutsideEvenInTaperRingWhenTaperDisabled() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertEquals(OceanCanvasConfig.CanvasZone.INSIDE, config.canvasZone(0, 0));
		// Just past the edge - would be TAPER if enabled, but taper is off
		// by default, so this must be OUTSIDE, matching the M1 boundary
		// guarantee unless a player explicitly opts in.
		assertEquals(OceanCanvasConfig.CanvasZone.OUTSIDE, config.canvasZone(10_010, 0));
	}

	@Test
	void canvasZoneIsTaperWithinTheConfiguredRingWhenEnabled() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.taperEnabled(true)
				.taperWidthChunks(8) // 128 blocks
				.build();

		assertEquals(OceanCanvasConfig.CanvasZone.INSIDE, config.canvasZone(9_999, 0));
		assertEquals(OceanCanvasConfig.CanvasZone.TAPER, config.canvasZone(10_010, 0));
		assertEquals(OceanCanvasConfig.CanvasZone.TAPER, config.canvasZone(10_127, 0));
		assertEquals(OceanCanvasConfig.CanvasZone.OUTSIDE, config.canvasZone(10_128, 0));
	}

	@Test
	void taperBlendRampsFromZeroAtTheEdgeToJustUnderOneAtTheOuterRing() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.taperEnabled(true)
				.taperWidthChunks(8) // 128 blocks
				.build();

		assertEquals(0.0, config.taperBlend(10_000, 0), 0.0001);
		assertEquals(0.5, config.taperBlend(10_064, 0), 0.01);
		assertTrue(config.taperBlend(10_127, 0) < 1.0);
	}

	// --- Natural ocean ruin protection - drafted alongside the in-game
	// map screen's per-zone shipwreck/ocean-ruin override toggle. Same
	// off-by-default posture pattern as every block above. ---

	@Test
	void naturalOceanRuinsAreNotProtectedByDefault() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		assertEquals(StructureOverride.FORCE_OFF, config.naturalOceanRuinsRule());
	}

	@Test
	void builderCanFlipNaturalOceanRuinsProtectedIndependently() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.naturalOceanRuinsRule(StructureOverride.FORCE_ON)
				.build();
		assertEquals(StructureOverride.FORCE_ON, config.naturalOceanRuinsRule());
		assertEquals(StructureOverride.INHERIT, config.shipwrecksRule()); // untouched, still default
	}

	// --- The two structure kinds added when per-region structure rules
	// were generalized. Their defaults are load-bearing: they are what
	// every region's INHERIT rule resolves to, so getting one wrong would
	// silently change what an existing canvas preserves. ---

	@Test
	void newStructureKindDefaultsMatchTheirDocumentedPosture() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true);
		// On: buried treasure has been relocated since that feature shipped.
		assertEquals(StructureOverride.INHERIT, config.buriedTreasureRule());
		// Off: monuments were previously carved away like any other
		// non-shipwreck structure, and quietly starting to preserve them
		// would change every existing world.
		assertEquals(StructureOverride.FORCE_OFF, config.naturalOceanMonumentsRule());
		// Off: ruined portals are not natural ocean content and are carved
		// away inside the canvas unless explicitly opted back in.
		assertEquals(StructureOverride.FORCE_OFF, config.naturalRuinedPortalsRule());
	}

	@Test
	void builderCanFlipTheNewStructureKindsIndependently() {
		OceanCanvasConfig config = OceanCanvasConfig.of(20_000, 0, 0, true).toBuilder()
				.buriedTreasureRule(StructureOverride.FORCE_OFF)
				.naturalOceanMonumentsRule(StructureOverride.FORCE_ON)
				.naturalRuinedPortalsRule(StructureOverride.FORCE_ON)
				.build();
		assertEquals(StructureOverride.FORCE_OFF, config.buriedTreasureRule());
		assertEquals(StructureOverride.FORCE_ON, config.naturalOceanMonumentsRule());
		assertEquals(StructureOverride.FORCE_ON, config.naturalRuinedPortalsRule());
		// Neighbouring structure flags untouched.
		assertEquals(StructureOverride.INHERIT, config.shipwrecksRule());
		assertEquals(StructureOverride.FORCE_OFF, config.naturalOceanRuinsRule());
	}
}
