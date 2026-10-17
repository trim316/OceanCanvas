from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()

old = '''\t\t\tif (session.pendingTicks.get(packed) > 0) continue;'''
new = '''\t\t\tif (session.pendingTicks.get(packed) > 0) {\n\t\t\t\t// R1-176: delayed proof debt is not executable this tick (R1-174), so it\n\t\t\t\t// must not pin one of the bounded radius-1 residency tickets while waiting.\n\t\t\t\t// Correctness debt remains in pendingTicks and the scheduler lane; when due,\n\t\t\t\t// ordinary admission reacquires residency under the unchanged hard cap.\n\t\t\t\tif (session.relightResidencyLedger.contains(packed))\n\t\t\t\t\treleaseLightRelightResidencyTicket(world, packed);\n\t\t\t\tcontinue;\n\t\t\t}'''

if s.count(new) == 3:
    pass
elif s.count(old) == 3:
    s = s.replace(old, new)
else:
    raise SystemExit(f'expected exactly three R1-174 delayed-debt anchors; old={s.count(old)} new={s.count(new)}')

required = [
    'private static final long LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L;',
    'private static final int LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_NORMAL = 64;',
    'private static final int LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_BACKLOG = 64;',
    'private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48;',
    'private static final double LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION = 0.88D;',
    'if (session.pendingTicks.get(packed) > 0) {',
    'releaseLightRelightResidencyTicket(world, packed);',
]
for needle in required:
    if needle not in s:
        raise SystemExit(f'missing unchanged safety/repair contract: {needle}')

# Fail closed if the repair accidentally removes authoritative delayed debt.
for forbidden in ['session.pendingTicks.remove(packed); // R1-176', 'retireCompletedLightState(world, packed); // R1-176']:
    if forbidden in s:
        raise SystemExit(f'forbidden debt retirement in delayed-residency repair: {forbidden}')

p.write_text(s)
