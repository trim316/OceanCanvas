#!/usr/bin/env python3
from pathlib import Path
import re
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text(encoding='utf-8')

# Both generic pressure-wake paths currently postpone due debt by now+40. Replace
# only that deadline expression; retain the existing atomic put/replace mechanics.
pat=r'(pressureParkUntilTick\.(?:put|replace)\([^;]*?)(now\s*\+\s*LIGHT_PRESSURE_PARK_RETRY_TICKS)([^;]*;)'
def dormant(m):
    expr=('OceanCanvasTerrainPhaseLightDormancyPolicy.deadlineForGenericDebt('
          'now, LIGHT_PRESSURE_PARK_RETRY_TICKS, outstandingPregenTargets(), false, false)')
    return m.group(1)+expr+m.group(3)
s,n=re.subn(pat,dormant,s,flags=re.S)
if n!=2: raise SystemExit(f'expected 2 generic pressure-park deadline expressions, found {n}')

# At the terrain->zero phase boundary, make every parked deadline eligible for the
# existing bounded drain. This wakes Long.MAX_VALUE dormant entries without an
# unbounded scan and preserves the existing per-tick wake cap/fairness machinery.
pat=r'(pressureParkUntilTick\.drainKeysAtOrBelow\(\s*)now(\s*,)'
s,n=re.subn(pat,r'\1(outstandingPregenTargets() == 0 ? Long.MAX_VALUE : now)\2',s)
if n!=2: raise SystemExit(f'expected 2 pressure-park drain sites, found {n}')

p.write_text(s,encoding='utf-8')
print('R1-136 integrated: 2 stable terrain dormancy sites + 2 bounded zero-terrain wake drains')
