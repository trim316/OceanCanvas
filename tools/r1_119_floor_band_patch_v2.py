from pathlib import Path


def replace_exact(path: Path, old: str, new: str, expected: int = 1) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} anchors, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new))

root = Path(__file__).resolve().parents[1]
props = root / "production-src/gradle.properties"
main = root / "production-src/src/main/java/net/oceancanvas/mod/OceanCanvas.java"
flattener = root / "production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java"
recovery = root / "production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasLightRecoverySession.java"
test = root / "production-src/src/test/java/net/oceancanvas/mod/worldgen/OceanCanvasFloorBandCycleBreakPolicyTest.java"

replace_exact(props, "mod_version=26.2-v253.125.73", "mod_version=26.2-v253.125.74")
replace_exact(main, 'public static final String VERSION = "v253.125.73";', 'public static final String VERSION = "v253.125.74";')
replace_exact(
    recovery,
    "    final OceanCanvasPrimitiveLongIntMap visibleDeepClusterRepairCounts = new OceanCanvasPrimitiveLongIntMap();\n",
    "    final OceanCanvasPrimitiveLongIntMap visibleDeepClusterRepairCounts = new OceanCanvasPrimitiveLongIntMap();\n"
    "    /** v253.125.74 bounded mixed-floor-section cycle breaker attempts per physical epoch. */\n"
    "    final OceanCanvasPrimitiveLongIntMap floorBandCycleBreakCounts = new OceanCanvasPrimitiveLongIntMap();\n",
)

method_anchor = "\t/**\n\t * v253.36 source-table escalation. checkBlock() is a local graph nudge; it\n"
method = r'''	/** v253.125.74 policy for the final mixed-floor-section residual. */
	static boolean shouldAttemptFloorBandCycleBreak(
			int anomalyY, int actualSky, int requiredMaxSky, int canonicalFloorY) {
		return actualSky > requiredMaxSky
				&& anomalyY >= canonicalFloorY
				&& anomalyY <= canonicalFloorY + 3;
	}

	/**
	 * v253.125.74 bounded mixed-floor-section SKY cycle breaker.
	 *
	 * <p>The saved 2k v73 regression reduced quarantine from 79 chunks to one. The
	 * survivor had canonical source tables but retained a SKY 1/2 lateral loop at
	 * the ocean floor. Existing dense/cluster repair deliberately skips the mixed
	 * section containing the floor. Target only the proven anomaly and a 9x9 local
	 * band using public light-engine operations. No blocks or SKY storage are ever
	 * replaced; the ordinary strict verifier remains the certification authority.</p>
	 */
	private static int queueFloorBandCycleBreak(ServerLevel world, SkyLightDiag sky) {
		BlockPos anomaly = sky.firstDeepAnomaly();
		if (world == null || anomaly == null) return 0;
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int anomalyFloorY = config.oceanFloorY()
				+ floorOffset(anomaly.getX(), anomaly.getZ(), config.oceanFloorVariation());
		if (!shouldAttemptFloorBandCycleBreak(
				anomaly.getY(), sky.firstDeepActual(), sky.firstDeepRequiredMax(), anomalyFloorY)) return 0;

		net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
		java.util.HashSet<Long> touchedChunks = new java.util.HashSet<>();
		OceanCanvasPlayerZones zones = OceanCanvasPlayerZones.get(world);
		final int radius = 4;
		int checks = 0;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return checks;
				int x = anomaly.getX() + dx, z = anomaly.getZ() + dz;
				int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
				LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null || !strictCanvasColumnSelected(chunk, config, x, z)) continue;
				long touchedPacked = ChunkPos.pack(cx, cz);
				if (touchedChunks.add(touchedPacked)) chunk.initializeLightSources();
				int floorY = config.oceanFloorY() + floorOffset(x, z, config.oceanFloorVariation());
				int minY = Math.max(world.getMinY() + 1, floorY - 1);
				int maxY = Math.min(OceanCanvasConfig.WATER_SURFACE_Y, floorY + 4);
				for (int y = minY; y <= maxY; y++) {
					if (zones.isProtected(x, y, z)) continue;
					lightEngine.checkBlock(new BlockPos(x, y, z));
					checks++;
				}
			}
		}
		for (long touchedPacked : touchedChunks) {
			ChunkPos cp = new ChunkPos(ChunkPos.getX(touchedPacked), ChunkPos.getZ(touchedPacked));
			lightEngine.setLightEnabled(cp, true);
			lightEngine.propagateLightSources(cp);
			LevelChunk chunk = world.getChunkSource().getChunkNow(cp.x(), cp.z());
			if (chunk != null) chunk.markUnsaved();
		}
		return checks;
	}

'''
replace_exact(flattener, method_anchor, method + method_anchor)

recovery_anchor = "\t\t\t\t// v253.125.20: correctness remains global, but background deep repair is\n"
recovery_stage = r'''				// v253.125.74: the final v73 residual was a local lateral SKY loop in the
				// mixed floor section, which whole-section repair cannot safely enter.
				// Permit two tiny public-API floor-band cycle breaks per physical epoch;
				// failure then falls through unchanged to the existing fail-closed ladder.
				if (sky.onlyDeepZeroTailOverbright() && sky.firstDeepAnomaly() != null) {
					OceanCanvasConfig cycleConfig = OceanCanvasConfig.get();
					BlockPos cycleAnomaly = sky.firstDeepAnomaly();
					int cycleFloorY = cycleConfig.oceanFloorY()
							+ floorOffset(cycleAnomaly.getX(), cycleAnomaly.getZ(), cycleConfig.oceanFloorVariation());
					int cycleAttempts = lightRecoverySession().floorBandCycleBreakCounts.getOrDefault(packed, 0);
					if (cycleAttempts < 2 && shouldAttemptFloorBandCycleBreak(
							cycleAnomaly.getY(), sky.firstDeepActual(), sky.firstDeepRequiredMax(), cycleFloorY)) {
						int checks = queueFloorBandCycleBreak(world, sky);
						if (checks > 0) {
							lightRecoverySession().floorBandCycleBreakCounts.put(packed, cycleAttempts + 1);
							OceanCanvas.LOGGER.warn("(Ocean Canvas) LIGHT-FLOOR-BAND-CYCLE-BREAK build={} chunk={},{} attempt={} checks={} firstDeep={} actual={} requiredMax={} floorY={} action=public-checkBlock-local-mixed-floor-band-and-propagate-before-section-repair",
									net.oceancanvas.mod.OceanCanvas.VERSION, cx, cz, cycleAttempts + 1, checks,
									cycleAnomaly, sky.firstDeepActual(), sky.firstDeepRequiredMax(), cycleFloorY);
							lightFinalizerSession().pendingTicks.put(packed, LIGHT_VISIBLE_DEEP_DENSE_REPAIR_SETTLE_TICKS);
							continue;
						}
					}
				}

'''
replace_exact(flattener, recovery_anchor, recovery_stage + recovery_anchor)

cleanup_old = "\t\tlightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);\n\t\tclearIncrementalDeepRepairState(packed);\n"
cleanup_new = "\t\tlightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);\n\t\tlightRecoverySession().floorBandCycleBreakCounts.remove(packed);\n\t\tclearIncrementalDeepRepairState(packed);\n"
replace_exact(flattener, cleanup_old, cleanup_new, expected=2)
if flattener.read_text().count("floorBandCycleBreakCounts.remove(packed)") != 2:
    raise SystemExit("floor-band counter must clear at physical reset and successful retirement only")

test.write_text('''package net.oceancanvas.mod.worldgen;\n\nimport static org.junit.jupiter.api.Assertions.*;\nimport org.junit.jupiter.api.Test;\n\nfinal class OceanCanvasFloorBandCycleBreakPolicyTest {\n    @Test void acceptsOverbrightCellsInsideFloorBand() {\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(23, 2, 0, 23));\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 1, 0, 23));\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(26, 2, 1, 23));\n    }\n\n    @Test void rejectsCellsOutsideBoundedFloorBand() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(22, 2, 0, 23));\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(27, 2, 0, 23));\n    }\n\n    @Test void rejectsHealthyOrNonOverbrightCells() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 0, 0, 23));\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 1, 1, 23));\n    }\n}\n''')
print("R1-119 v74 source patch applied")
