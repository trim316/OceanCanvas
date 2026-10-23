#!/usr/bin/env python3
from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')
required = [
    'pressureRetryQueued={} pressureTail={} action=read-only-terminal-debt-attribution',
    'OceanCanvasPressureTailDiagnosticLine.format(tick, lightRecoverySession().pressureParkUntilTick, session.retryLedger)',
]
for needle in required:
    if s.count(needle) != 1:
        raise SystemExit(f'expected exactly one product wiring anchor: {needle!r}; found {s.count(needle)}')

# The wiring must remain telemetry-only: the new composition call is allowed only in the
# existing LIGHT-TAIL attribution line and must not replace any scheduler/debt mutation.
idx = s.index(required[1])
window = s[max(0, idx - 1800):idx + 600]
if 'LIGHT-TAIL-ATTRIBUTION' not in window:
    raise SystemExit('pressure-tail composition escaped the existing LIGHT-TAIL diagnostic boundary')
for forbidden in ('pressureParkUntilTick.put(', 'pressureParkUntilTick.remove(', 'offerPressurePark(', 'pollDuePressurePark('):
    if forbidden in window:
        raise SystemExit(f'diagnostic wiring window unexpectedly contains scheduler/debt mutation: {forbidden}')

print('R1-253 PASS: authoritative pressure-tail sample is emitted only through existing read-only LIGHT-TAIL telemetry')
