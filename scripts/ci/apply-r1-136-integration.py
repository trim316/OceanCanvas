#!/usr/bin/env python3
from pathlib import Path
import re

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')

# R1-136 applies ONLY to generic, non-visible, non-physical LIGHT_ONLY debt.
# The prior integration attempt searched for LIGHT_PRESSURE_PARK_RETRY_TICKS,
# but the two production generic park sites actually use
# LIGHT_GLOBAL_BACKGROUND_PARK_TICKS (+ deterministic jitter). Match those
# concrete expressions and leave the recovery/physical park paths untouched.
expressions = (
    'now + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
    'world.getGameTime() + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
)
for ordinary in expressions:
    count = s.count(ordinary)
    if count != 1:
        raise SystemExit(f'expected exactly 1 generic background deadline {ordinary!r}, found {count}')
    replacement = (
        'OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline('
        f'{ordinary}, outstandingPregenTargets(), false, false)'
    )
    s = s.replace(ordinary, replacement, 1)

# A dormant Long.MAX_VALUE deadline must become eligible through the existing
# bounded pressure-park drain once terrain reaches zero. Do not add an unbounded
# scan or delete debt membership. Replace only drains whose first argument is the
# current game tick; their existing per-tick cap remains the second argument.
pat = r'(pressureParkUntilTick\.drainKeysAtOrBelow\(\s*)now(\s*,)'
matches = list(re.finditer(pat, s))
if len(matches) != 2:
    raise SystemExit(f'expected exactly 2 bounded pressure-park drain sites, found {len(matches)}')
s = re.sub(
    pat,
    r'\1(outstandingPregenTargets() == 0 ? Long.MAX_VALUE : now)\2',
    s,
)

# Fail closed if integration drifted outside the intended four scheduler sites.
if s.count('OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(') != 2:
    raise SystemExit('expected exactly 2 integrated generic dormancy deadlines')
if s.count('outstandingPregenTargets() == 0 ? Long.MAX_VALUE : now') != 2:
    raise SystemExit('expected exactly 2 bounded zero-terrain wake drains')

p.write_text(s, encoding='utf-8')
print('R1-136 integrated: 2 stable generic terrain-dormancy sites + 2 bounded zero-terrain wake drains')
