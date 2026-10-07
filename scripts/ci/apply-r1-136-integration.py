#!/usr/bin/env python3
from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')

# R1-136 applies ONLY to generic, non-visible, non-physical LIGHT_ONLY debt.
# Two sites initially park generic background work. Two additional sites are the
# old terrain-active wake/repark loop. All four must use the same fail-closed
# dormancy policy so an already-parked finite deadline is converted to the stable
# sentinel the first time it is encountered while terrain remains outstanding.
initial = (
    'now + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
    'world.getGameTime() + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
)
for ordinary in initial:
    count = s.count(ordinary)
    if count != 1:
        raise SystemExit(f'expected exactly 1 generic background deadline {ordinary!r}, found {count}')
    replacement = (
        'OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline('
        f'{ordinary}, outstandingPregenTargets(), false, false)'
    )
    s = s.replace(ordinary, replacement, 1)

# The two generic terrain-active wake paths historically requeued at now+40,
# creating the churn R1-136 removes. These occurrences are structurally guarded
# by `genericLightOnly && outstandingPregenTargets() > 0`; there are exactly two
# in production and both must become the stable dormant sentinel.
old = 'long postponed = now + 40L;'
count = s.count(old)
if count != 2:
    raise SystemExit(f'expected exactly 2 generic terrain repark sites, found {count}')
s = s.replace(
    old,
    'long postponed = OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(\n'
    '\t\t\t\t\tnow + 40L, outstandingPregenTargets(), false, false);'
)

# pressureParkUntilTick is indexed by retryLedger's deadline heap, not by a map
# drain. Therefore dormant Long.MAX_VALUE entries become eligible by widening the
# *existing bounded heap poll* only after terrain reaches zero. Both loops retain
# their existing maxProofs/headroom/budget caps; this is not an unbounded scan.
old_poll = 'lightFinalizerSession().retryLedger.pollDuePressurePark(now)'
count = s.count(old_poll)
if count != 2:
    raise SystemExit(f'expected exactly 2 bounded pressure-park heap poll sites, found {count}')
s = s.replace(
    old_poll,
    'lightFinalizerSession().retryLedger.pollDuePressurePark(\n'
    '\t\t\t\toutstandingPregenTargets() == 0 ? Long.MAX_VALUE : now)'
)

# Fail closed if integration drifted outside the intended scheduler sites.
if s.count('OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(') != 4:
    raise SystemExit('expected exactly 4 integrated generic dormancy deadlines')
if s.count('outstandingPregenTargets() == 0 ? Long.MAX_VALUE : now') != 2:
    raise SystemExit('expected exactly 2 bounded zero-terrain heap wake polls')

p.write_text(s, encoding='utf-8')
print('R1-136 integrated: 4 stable generic terrain-dormancy sites + 2 bounded zero-terrain heap wake polls')
