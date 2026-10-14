from pathlib import Path

path = Path("production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java")
source = path.read_text()
old = """\t\tint pressureWakeCeiling = Math.min(LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER - 1,
\t\t\t\tLIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER + 8);
\t\tint globalHeadroom = Math.max(0, pressureWakeCeiling - pendingPressureWork);
"""
new = """\t\tint pressureWakeCeiling = Math.min(LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER - 1,
\t\t\t\tLIGHT_FINALIZATION_BACKPRESSURE_RESUME_WATER + 8);
\t\t// R1-183: R1-176 keeps delayed proof debt in pendingTicks while releasing
\t\t// its residency ticket. Countdown entries are authoritative correctness debt,
\t\t// but they are not executable work this tick. Counting them against the 457
\t\t// executable-pressure ceiling can deadlock the due pressure lane. Preserve both
\t\t// safety bounds: executable work stays below pressureWakeCeiling and the raw
\t\t// pending map stays below HIGH_WATER, so the 512-cycle churn cannot reopen.
\t\tint delayedPressureWork = lightFinalizerSession().pendingTicks.countValuesAtLeast(1);
\t\tint executablePressureWork = Math.max(0, pendingPressureWork - delayedPressureWork);
\t\tint executablePressureHeadroom = Math.max(0, pressureWakeCeiling - executablePressureWork);
\t\tint rawPressureHeadroom = Math.max(0,
\t\t\t\t(LIGHT_FINALIZATION_BACKPRESSURE_HIGH_WATER - 1) - pendingPressureWork);
\t\tint globalHeadroom = Math.min(executablePressureHeadroom, rawPressureHeadroom);
"""
count = source.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one R1-151 pressure wake block, found {count}")
path.write_text(source.replace(old, new))
