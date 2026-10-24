from pathlib import Path

p = Path("production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java")
s = p.read_text()
legacy = "pressureParkUntilTick.put(packed, due)"
fixed = "pressureParkUntilTick.putIfAbsent(packed, due)"
preserve = "if (previous != OceanCanvasPrimitiveLongLongMap.ABSENT) due = previous;"
assert legacy not in s, "pressure parking may not overwrite an authoritative deadline without a matching heap node"
assert s.count(fixed) == 4, f"expected four fail-closed pressure publications, got {s.count(fixed)}"
assert s.count(preserve) == 4, f"expected four earliest-deadline preservation guards, got {s.count(preserve)}"
assert "pressureParkUntilTick.replace(packed, due, postponed)" in s, "due-entry retry rescheduling must remain compare-and-replace guarded"
print("R1-146 pressure deadline liveness regression PASS")
