from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = 'private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS = 5L;'
new = 'private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS = 1L;'
if new not in s:
    if s.count(old) != 1:
        raise SystemExit(f'expected exactly one R1-159 drain cadence, found {s.count(old)}')
    s = s.replace(old, new, 1)
p.write_text(s)

s = p.read_text()
required = [
    'private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_TERRAIN_TICKS = 10L;',
    'private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_DRAIN_TICKS = 1L;',
    'private static final long LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_IDLE_TICKS = 2L;',
    'private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48;',
    'private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_WARM = 32;',
    'private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_CAP_HOT = 16;',
    'private static final double LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION = 0.88D;',
    'fastCertifyResidentPressureParkedInTicketNeighborhoods(world, residentProofBudget)',
    'getChunkSource().getChunkNow',
]
for needle in required:
    if needle not in s:
        raise SystemExit(f'missing unchanged R1-161 safety invariant: {needle}')
for forbidden in [
    'LIGHT_HISTORICAL_RESIDENCY_INSTALL_INTERVAL_TERRAIN_TICKS = 1L',
    'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 64',
    'LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION = 1.0',
]:
    if forbidden in s:
        raise SystemExit(f'R1-161 widened a forbidden safety surface: {forbidden}')
print('R1-161 deterministic cadence regression PASS')
