from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = '''\tprivate static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_TERRAIN_TICKS = 10L;\n\tprivate static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS = 5L;\n\tprivate static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_IDLE_TICKS = 2L;'''
new = '''\tprivate static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_TERRAIN_TICKS = 10L;\n\t// R1-201: exact v253.125.80 throughput retired all 4,356 terrain targets with\n\t// thousands of strict LIGHT_ONLY obligations still parked, while the bounded\n\t// residency pool fell to single digits and the 5-tick drain admission gate\n\t// accumulated >300k historical install deferrals. During drain only, admit one\n\t// historical residency anchor every two ticks. The 48-ticket hard cap, 88% heap\n\t// hold, radius-1 ownership, strict proof oracle and 900-second contract remain\n\t// unchanged; terrain-phase pacing remains 10 ticks.\n\tprivate static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS = 2L;\n\tprivate static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_IDLE_TICKS = 2L;'''
if s.count(new) == 1:
    pass  # Already persisted by a previous successful validation.
elif s.count(old) != 1:
    raise SystemExit(f'expected one drain pacing anchor, found {s.count(old)}')
s = s.replace(old, new)
p.write_text(s)

s = p.read_text()
assert 'LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_TERRAIN_TICKS = 10L' in s
assert 'LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS = 2L' in s
assert 'LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_IDLE_TICKS = 2L' in s
assert 'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48' in s
assert 'LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION = 0.88D' in s
assert 'LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L' in s
print('R1-201 source transform/regression PASS')
