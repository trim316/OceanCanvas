from pathlib import Path
s=Path("production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
assert "int pendingPressureWork = lightFinalizerSession().pendingTicks.size();" in s
assert "pendingPressureWork < LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER" in s
assert "globalHeadroom = 1;" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER = 512" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER = 256" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER - lightFinalizerSession().pendingTicks.size()" not in s
print("R1-147 bounded low-water pressure-wake regression PASS")
