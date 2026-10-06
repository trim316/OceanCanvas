from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))

root = Path(__file__).resolve().parents[1]
props = root / "production-src/gradle.properties"
main = root / "production-src/src/main/java/net/oceancanvas/mod/OceanCanvas.java"
flattener = root / "production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java"
recovery = root / "production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasLightRecoverySession.java"
test = root / "production-src/src/test/java/net/oceancanvas/mod/worldgen/OceanCanvasFloorBandCycleBreakPolicyTest.java"

replace_once(props, "mod_version=26.2-v253.125.73", "mod_version=26.2-v253.125.74")
replace_once(main, 'public static final String VERSION = "v253.125.73";', 'public static final String VERSION = "v253.125.74";')

replace_once(
    recovery,
    "    final OceanCanvasPrimitiveLongIntMap visibleDeepClusterRepairCounts = new OceanCanvasPrimitiveLongIntMap();\n",
    "    final OceanCanvasPrimitiveLongIntMap visibleDeepClusterRepairCounts = new OceanCanvasPrimitiveLongIntMap();\n"
    "    /** v253.125.74 bounded mixed-floor-section cycle breaker attempts per physical epoch. */\n"
    "    final OceanCanvasPrimitiveLongIntMap floorBandCycleBreakCounts = new OceanCanvasPrimitiveLongIntMap();\n",
)

method_anchor = "\t/**\n\t * v253.36 source-table escalation. checkBlock() is a local graph nudge; it\n"
method = r'''	/** v253.125.74 policy for the final mixed-floor-section residual.
	 * A cycle break is eligible only when the strict oracle proves an overbright
	 * cell in the first four cells of water at the canonical ocean floor. */
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
	 * survivor had a canonical source table (lowest source Y=63) but retained SKY
	 * 1/2 at y=24/23 beside a canonical zero tail. Existing dense/cluster repair
	 * deliberately skips the mixed section that contains the ocean floor, so even
	 * very large safe-water-section checkBlock waves never touch the stale cells.
	 * This method targets only the already-proven floor anomaly and a tiny 9x9 local
	 * band. It never writes blocks or SKY storage: it rebuilds vanilla source tables,
	 * queues public checkBlock calls, propagates sources, and lets the ordinary
	 * strict verifier remain the sole certification authority.</p>
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
				long packed = ChunkPos.pack(cx, cz);
				if (touchedChunks.add(packed)) chunk.initializeLightSources();

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

		for (long packed : touchedChunks) {
			ChunkPos cp = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
			lightEngine.setLightEnabled(cp, true);
			lightEngine.propagateLightSources(cp);
			LevelChunk chunk = world.getChunkSource().getChunkNow(cp.x(), cp.z());
			if (chunk != null) chunk.markUnsaved();
		}
		return checks;
	}

'''
replace_once(flattener, method_anchor, method + method_anchor)

recovery_anchor = "\t\t\t\t// v253.125.20: correctness remains global, but background deep repair is\n"
recovery_stage = r'''				// v253.125.74: final mixed-floor-section cycle breaker. The v73 saved-world
				// regression left one quarantined chunk whose source tables were canonical
				// but whose floor band retained a 1/2 SKY lateral loop. Whole-section dense
				// repairs cannot enter a section containing the floor. Give that exact proven
				// class two tiny public-API local retries per physical epoch before spending
				// on the much larger section/cluster ladder. Failure remains fail-closed.
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
replace_once(flattener, recovery_anchor, recovery_stage + recovery_anchor)

replace_once(
    flattener,
    "\t\tlightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);\n\t\tclearIncrementalDeepRepairState(packed);\n",
    "\t\tlightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);\n"
    "\t\tlightRecoverySession().floorBandCycleBreakCounts.remove(packed);\n"
    "\t\tclearIncrementalDeepRepairState(packed);\n",
)

# The same per-physical-epoch counter must be cleared on successful retirement.
retire_anchor = "\t\tlightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);\n\t\tclearIncrementalDeepRepairState(packed);\n"
# The first identical anchor was resetSkyRecoveryForPhysicalMutation; replace the remaining one now.
text = flattener.read_text()
if retire_anchor not in text:
    raise SystemExit("retirement cleanup anchor missing after physical-reset patch")
flattener.write_text(text.replace(
    retire_anchor,
    "\t\tlightRecoverySession().visibleDeepClusterRepairCounts.remove(packed);\n"
    "\t\tlightRecoverySession().floorBandCycleBreakCounts.remove(packed);\n"
    "\t\tclearIncrementalDeepRepairState(packed);\n",
    1,
))

# Verify exactly two cleanup sites now contain the new counter: physical reset + retirement.
if flattener.read_text().count("floorBandCycleBreakCounts.remove(packed)") != 2:
    raise SystemExit("expected floorBandCycleBreakCounts cleanup at exactly two lifecycle sites")

test.write_text('''package net.oceancanvas.mod.worldgen;\n\nimport static org.junit.jupiter.api.Assertions.*;\nimport org.junit.jupiter.api.Test;\n\nfinal class OceanCanvasFloorBandCycleBreakPolicyTest {\n    @Test void acceptsOverbrightCellsInsideFloorBand() {\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(23, 2, 0, 23));\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 1, 0, 23));\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(26, 2, 1, 23));\n    }\n\n    @Test void rejectsCellsOutsideBoundedFloorBand() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(22, 2, 0, 23));\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(27, 2, 0, 23));\n    }\n\n    @Test void rejectsHealthyOrNonOverbrightCells() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 0, 0, 23));\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttemptFloorBandCycleBreak(24, 1, 1, 23));\n    }\n}\n''')

print("R1-119 source patch applied")
