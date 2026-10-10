from pathlib import Path
s=Path("production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
assert "LIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER = 449" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER - pendingPressureWork" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER - pendingPressureWork" not in s
assert "globalHeadroom = 1;" not in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER = 512" in s
assert "LIGHT_FINALIZATION_BACKPRESSURE_LOW_WATER = 256" in s
assert "while (woken < globalHeadroom" in s
print("R1-148 pressure resume-water refill regression PASS")
