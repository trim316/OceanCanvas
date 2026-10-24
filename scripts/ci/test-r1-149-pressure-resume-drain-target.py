from pathlib import Path
s=Path("production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
assert "LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER = 512" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER = 256" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER = 449" in s
assert "pendingTicks.size() > LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER" in s
assert "parkExcessGenericLightOnlyForPressure(world, LIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER," in s
assert "parkExcessGenericLightOnlyForPressure(world, LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER," not in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER - pendingPressureWork" in s
print("R1-149 pressure resume drain-target regression PASS")
