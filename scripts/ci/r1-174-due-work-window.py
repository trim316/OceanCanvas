from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()

repls = [
('''\t\t\tsession.visibleWorkOrder.offer(packed);\n\t\t\tsampled++;\n\t\t\tvisiblePacked[visibleCount] = packed;''', '''\t\t\tsession.visibleWorkOrder.offer(packed);\n\t\t\t// R1-174: delayed retry is live debt, not executable work this tick.\n\t\t\t// Do not let it consume the bounded sample window; the existing raw\n\t\t\t// poll cap still hard-bounds scheduler administration.\n\t\t\tif (session.pendingTicks.get(packed) > 0) continue;\n\t\t\tsampled++;\n\t\t\tvisiblePacked[visibleCount] = packed;'''),
('''\t\t\tsession.terrainWorkOrder.offer(packed);\n\t\t\tterrainScanned++; sampled++;\n\t\t\tint pass = session.pendingPasses.getOrDefault(packed, 0);''', '''\t\t\tsession.terrainWorkOrder.offer(packed);\n\t\t\t// R1-174: countdown/debounce entries remain authoritative debt but are\n\t\t\t// not execution candidates until their game-time deadline is due.\n\t\t\tif (session.pendingTicks.get(packed) > 0) continue;\n\t\t\tterrainScanned++; sampled++;\n\t\t\tint pass = session.pendingPasses.getOrDefault(packed, 0);'''),
('''\t\t\tsession.pendingWorkOrder.offer(packed);\n\t\t\tordinaryScanned++; sampled++;\n\t\t\tint pass = session.pendingPasses.getOrDefault(packed, 0);''', '''\t\t\tsession.pendingWorkOrder.offer(packed);\n\t\t\t// R1-174: only due retries consume the sampled execution cohort. Raw\n\t\t\t// polling remains capped, so delayed backlog cannot create an unbounded scan.\n\t\t\tif (session.pendingTicks.get(packed) > 0) continue;\n\t\t\tordinaryScanned++; sampled++;\n\t\t\tint pass = session.pendingPasses.getOrDefault(packed, 0);''')]

for old, new in repls:
    if new in s:
        continue
    if s.count(old) != 1:
        raise SystemExit(f'expected exactly one scheduler anchor, found {s.count(old)}')
    s = s.replace(old, new)

required = [
    'private static final long LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L;',
    'private static final int LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_NORMAL = 64;',
    'private static final int LIGHT_FINALIZER_ACTIVE_WORK_BUDGET_BACKLOG = 64;',
    'private static final int LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48;',
    'private static final double LIGHT_HISTORICAL_RESIDENCY_HEAP_HOLD_FRACTION = 0.88D;',
    'visiblePollCap = Math.max(LIGHT_SCHEDULER_STALE_POLL_FLOOR,',
    'terrainPollCap = Math.max(LIGHT_SCHEDULER_STALE_POLL_FLOOR,',
    'ordinaryPollCap = Math.max(LIGHT_SCHEDULER_STALE_POLL_FLOOR,'
]
for needle in required:
    if needle not in s:
        raise SystemExit(f'missing unchanged safety contract: {needle}')

p.write_text(s)
