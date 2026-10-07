#!/usr/bin/env python3
from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')

# R1-136 applies ONLY to generic, non-visible, non-physical LIGHT_ONLY debt.
# The four schedulerDeadline calls may already be present when this script is
# re-run after the integration commit.  Keep the transformation idempotent.
initial = (
    'now + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
    'world.getGameTime() + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
)
if s.count('OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(') == 0:
    for ordinary in initial:
        count = s.count(ordinary)
        if count != 1:
            raise SystemExit(f'expected exactly 1 generic background deadline {ordinary!r}, found {count}')
        replacement = (
            'OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline('
            f'{ordinary}, outstandingPregenTargets(), false, false)'
        )
        s = s.replace(ordinary, replacement, 1)

    old = 'long postponed = now + 40L;'
    count = s.count(old)
    if count != 2:
        raise SystemExit(f'expected exactly 2 generic terrain repark sites, found {count}')
    s = s.replace(
        old,
        'long postponed = OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(\n'
        '\t\t\t\t\tnow + 40L, outstandingPregenTargets(), false, false);'
    )

# SAFETY: never widen the shared deadline heap to Long.MAX_VALUE at terrain-zero.
# That heap also carries physical/tracked-light recovery backoff.  Widening the
# poll makes unrelated future finite deadlines eligible early.  R1-136 must use
# a dedicated generic-dormant wake lane before terrain-zero wake can be enabled.
widened = (
    'lightFinalizerSession().retryLedger.pollDuePressurePark(\n'
    '\t\t\t\toutstandingPregenTargets() == 0 ? Long.MAX_VALUE : now)'
)
if s.count(widened) not in (0, 2):
    raise SystemExit('ambiguous widened pressure-park poll count')
s = s.replace(widened, 'lightFinalizerSession().retryLedger.pollDuePressurePark(now)')

if s.count('OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(') != 4:
    raise SystemExit('expected exactly 4 integrated generic dormancy deadlines')
if 'outstandingPregenTargets() == 0 ? Long.MAX_VALUE : now' in s:
    raise SystemExit('shared recovery heap must retain finite deadline semantics')
if s.count('lightFinalizerSession().retryLedger.pollDuePressurePark(now)') != 2:
    raise SystemExit('expected exactly 2 ordinary bounded pressure-park heap polls')

p.write_text(s, encoding='utf-8')
print('R1-136 integrated safely: generic terrain dormancy retained; shared recovery deadlines preserved')
