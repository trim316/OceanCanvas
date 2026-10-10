from pathlib import Path

src = Path("production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
method = src.split("public static int wakePressureParkedRecoveryForPressure(", 1)[1].split("public static", 1)[0]
assert "terrainCompleteForPressureWake" in method
assert "outstandingPregenTargets() == 0" in method
assert "? LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER" in method
assert ": LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER" in method
assert "pressureWakeCeiling - lightFinalizerSession().pendingTicks.size()" in method
assert "while (woken < globalHeadroom" in method
assert "maxLightOnlyWake" in method and "maxPhysicalWake" in method
# Fail closed during terrain authoring: low-water remains the selected ceiling.
assert method.index("outstandingPregenTargets() == 0") < method.index(": LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER")
# Do not weaken strict certification or turn pressure wake into completion credit.
assert "LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER - lightFinalizerSession().pendingTicks.size()" not in src
print("R1-147 post-terrain pressure wake contract PASS")
