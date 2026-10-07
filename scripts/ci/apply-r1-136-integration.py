#!/usr/bin/env python3
from pathlib import Path

surface_path = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
ledger_path = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasLightRetryLedger.java')
s = surface_path.read_text(encoding='utf-8')
l = ledger_path.read_text(encoding='utf-8')

# R1-136: generic, non-visible, non-physical LIGHT_ONLY debt becomes dormant
# while terrain remains. Long.MAX_VALUE is only a sentinel in the authoritative
# pressureParkUntilTick map; it MUST NOT enter the mixed pressure deadline heap.
initial = (
    'now + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
    'world.getGameTime() + LIGHT_GLOBAL_BACKGROUND_PARK_TICKS + jitter',
)
if s.count('OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(') == 0:
    for ordinary in initial:
        count = s.count(ordinary)
        if count != 1:
            raise SystemExit(f'expected exactly 1 generic background deadline {ordinary!r}, found {count}')
        s = s.replace(ordinary,
            'OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline('
            f'{ordinary}, outstandingPregenTargets(), false, false)', 1)

    old = 'long postponed = now + 40L;'
    count = s.count(old)
    if count != 2:
        raise SystemExit(f'expected exactly 2 generic terrain repark sites, found {count}')
    s = s.replace(old,
        'long postponed = OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(\n'
        '\t\t\t\t\tnow + 40L, outstandingPregenTargets(), false, false);')

# Dedicated generic-dormant heap. This heap is scheduler-only/JVM-local just
# like the existing retry heaps. The authoritative pressureParkUntilTick map
# remains the completion blocker and is already deliberately re-armed from
# persisted missing certificates after restart.
if 'genericDormantDue' not in l:
    l = l.replace(
        'private final OceanCanvasPrimitiveLongDeadlineHeap pressureParkDue = new OceanCanvasPrimitiveLongDeadlineHeap();',
        'private final OceanCanvasPrimitiveLongDeadlineHeap pressureParkDue = new OceanCanvasPrimitiveLongDeadlineHeap();\n'
        '    private final OceanCanvasPrimitiveLongDeadlineHeap genericDormantDue = new OceanCanvasPrimitiveLongDeadlineHeap();\n'
        '    private boolean preferGenericDormant;')
    l = l.replace(
        'void offerPressurePark(long packed, long dueTick) { pressureParkDue.offer(packed, dueTick); }',
        'void offerPressurePark(long packed, long dueTick) {\n'
        '        if (dueTick == Long.MAX_VALUE) genericDormantDue.offer(packed, dueTick);\n'
        '        else pressureParkDue.offer(packed, dueTick);\n'
        '    }')
    l = l.replace(
        'OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDuePressurePark(long nowTick) { return pressureParkDue.pollDue(nowTick); }',
        'OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDuePressurePark(long nowTick) { return pressureParkDue.pollDue(nowTick); }\n'
        '    OceanCanvasPrimitiveLongDeadlineHeap.DueEntry pollDuePressureOrGenericDormant(long nowTick, boolean terrainOutstanding) {\n'
        '        if (!terrainOutstanding && preferGenericDormant) {\n'
        '            OceanCanvasPrimitiveLongDeadlineHeap.DueEntry dormant = genericDormantDue.pollDue(Long.MAX_VALUE);\n'
        '            if (dormant != null) { preferGenericDormant = false; return dormant; }\n'
        '        }\n'
        '        OceanCanvasPrimitiveLongDeadlineHeap.DueEntry ordinary = pressureParkDue.pollDue(nowTick);\n'
        '        if (ordinary != null) { if (!terrainOutstanding) preferGenericDormant = true; return ordinary; }\n'
        '        if (!terrainOutstanding) {\n'
        '            OceanCanvasPrimitiveLongDeadlineHeap.DueEntry dormant = genericDormantDue.pollDue(Long.MAX_VALUE);\n'
        '            if (dormant != null) { preferGenericDormant = false; return dormant; }\n'
        '        }\n'
        '        return null;\n'
        '    }')
    l = l.replace(
        'int pressureParkSize() { return pressureParkDue.size(); }',
        'int pressureParkSize() { return pressureParkDue.size() + genericDormantDue.size(); }')

# The two existing bounded wake loops keep their existing caps/budgets, but
# select from the dedicated generic lane only after terrain reaches zero. This
# never widens the mixed recovery heap's finite-deadline eligibility.
old_poll = 'lightFinalizerSession().retryLedger.pollDuePressurePark(now)'
new_poll = ('lightFinalizerSession().retryLedger.pollDuePressureOrGenericDormant(\n'
            '\t\t\t\tnow, outstandingPregenTargets() > 0)')
if s.count(new_poll) == 0:
    count = s.count(old_poll)
    if count != 2:
        raise SystemExit(f'expected exactly 2 bounded pressure-park polls, found {count}')
    s = s.replace(old_poll, new_poll)

# Reject the previously unsafe design explicitly.
if 'outstandingPregenTargets() == 0 ? Long.MAX_VALUE : now' in s:
    raise SystemExit('shared recovery heap must never be widened to Long.MAX_VALUE')
if s.count('OceanCanvasTerrainPhaseLightDormancyPolicy.schedulerDeadline(') != 4:
    raise SystemExit('expected exactly 4 integrated generic dormancy deadlines')
if s.count('pollDuePressureOrGenericDormant(') != 2:
    raise SystemExit('expected exactly 2 bounded mixed/dedicated wake selections')
if l.count('genericDormantDue') < 5:
    raise SystemExit('dedicated generic-dormant heap integration incomplete')

ledger_path.write_text(l, encoding='utf-8')
surface_path.write_text(s, encoding='utf-8')
print('R1-136 integrated: generic terrain debt uses a dedicated dormant heap; mixed recovery deadlines remain finite')
