from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()

helper_name = 'fastCertifyResidentPressureParkedInTicketNeighborhoods'
helper = '''
\t/**
\t * R1-159: consume strict LIGHT_ONLY pressure debt from residency that is already
\t * owned by the bounded relight-ticket pool before scanning the generic dormant
\t * scheduler. A radius-1 relight ticket already pays for a 3x3 chunk neighborhood;
\t * using getChunkNow here recycles that residency without installing, widening, or
\t * force-loading any ticket. Every completion still requires the same strict
\t * current-field proof and centralized retirement used by the existing fast lane.
\t */
\tprivate static int fastCertifyResidentPressureParkedInTicketNeighborhoods(ServerLevel world, int maxProofs) {
\t\tif (world == null || maxProofs <= 0 || lightRecoverySession().pressureParkUntilTick.isEmpty()) return 0;
\t\tif (net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets() > 0) return 0;
\t\tlong[] ticketAnchors = lightFinalizerSession().relightResidencyLedger.activeSnapshot();
\t\tif (ticketAnchors.length == 0) return 0;
\t\tint attempted = 0, completed = 0, scanned = 0;
\t\tint maxScans = Math.max(maxProofs, maxProofs * 9);
\t\tit.unimi.dsi.fastutil.longs.LongOpenHashSet seen =
\t\t\t\tnew it.unimi.dsi.fastutil.longs.LongOpenHashSet(Math.min(maxScans, ticketAnchors.length * 9));
\t\touter:
\t\tfor (long anchor : ticketAnchors) {
\t\t\tint ax = ChunkPos.getX(anchor), az = ChunkPos.getZ(anchor);
\t\t\tfor (int dx = -1; dx <= 1; dx++) {
\t\t\t\tfor (int dz = -1; dz <= 1; dz++) {
\t\t\t\t\tif (attempted >= maxProofs || scanned >= maxScans) break outer;
\t\t\t\t\tlong packed = ChunkPos.pack(ax + dx, az + dz);
\t\t\t\t\tif (!seen.add(packed)) continue;
\t\t\t\t\tscanned++;
\t\t\t\t\tlong due = lightRecoverySession().pressureParkUntilTick.get(packed);
\t\t\t\t\tif (due == OceanCanvasPrimitiveLongLongMap.ABSENT) continue;
\t\t\t\t\tboolean physical = pregenSession().PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed);
\t\t\t\t\tboolean trackedLightOnly = !physical
\t\t\t\t\t\t\t&& pregenSession().PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed);
\t\t\t\t\tboolean genericLightOnly = !physical && !trackedLightOnly
\t\t\t\t\t\t\t&& !lightFinalizerSession().allowPhysicalRepair.contains(packed)
\t\t\t\t\t\t\t&& !pregenSession().PREGEN_TARGET_CHUNKS.contains(packed);
\t\t\t\t\tif (!trackedLightOnly && !genericLightOnly) continue;
\t\t\t\t\tLevelChunk live = world.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed));
\t\t\t\t\tif (live == null) continue;
\t\t\t\t\tattempted++;
\t\t\t\t\tif (tryFastCertifyResidentCanvasLighting(
\t\t\t\t\t\t\tworld, live, false, Long.MAX_VALUE, "PRESSURE_PARK_TICKET_NEIGHBORHOOD")) {
\t\t\t\t\t\tlightTelemetrySession().LIGHT_DIAG_PRESSURE_FAST_CERTIFIED.incrementAndGet();
\t\t\t\t\t\tcompleted++;
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
\t\t}
\t\treturn completed;
\t}
'''

if helper_name not in s:
    marker = '\n\tprivate static int fastCertifyResidentPressureParked(ServerLevel world, int maxProofs) {'
    start = s.index(marker) + 1
    brace = s.index('{', start)
    depth = 0
    end = None
    in_str = False
    esc = False
    quote = ''
    for i in range(brace, len(s)):
        c = s[i]
        if in_str:
            if esc:
                esc = False
            elif c == '\\':
                esc = True
            elif c == quote:
                in_str = False
            continue
        if c in ('"', "'"):
            in_str = True
            quote = c
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        raise SystemExit('could not bound existing resident pressure certification method')
    s = s[:end] + '\n' + helper + s[end:]

old_call = '''\t\tint fastCompleted = terrainCompleteForResidentProof && residentProofBudget > 0
\t\t\t\t? fastCertifyResidentPressureParked(world, residentProofBudget)
\t\t\t\t: 0;'''
new_call = '''\t\tint fastCompleted = 0;
\t\tif (terrainCompleteForResidentProof && residentProofBudget > 0) {
\t\t\tfastCompleted = fastCertifyResidentPressureParkedInTicketNeighborhoods(world, residentProofBudget);
\t\t\tint remainingResidentProofBudget = Math.max(0, residentProofBudget - fastCompleted);
\t\t\tif (remainingResidentProofBudget > 0) {
\t\t\t\tfastCompleted += fastCertifyResidentPressureParked(world, remainingResidentProofBudget);
\t\t\t}
\t\t}'''
if new_call not in s:
    if s.count(old_call) != 1:
        raise SystemExit(f'expected one resident proof call site, found {s.count(old_call)}')
    s = s.replace(old_call, new_call, 1)

p.write_text(s)

s = p.read_text()
start = s.index('private static int ' + helper_name)
brace = s.index('{', start)
depth = 0
end = None
for i in range(brace, len(s)):
    if s[i] == '{':
        depth += 1
    elif s[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
method = s[start:end]
required = [
    'outstandingPregenTargets() > 0',
    'relightResidencyLedger.activeSnapshot()',
    'maxProofs * 9',
    'dx = -1; dx <= 1',
    'dz = -1; dz <= 1',
    'LongOpenHashSet',
    'pressureParkUntilTick.get(packed)',
    'getChunkNow',
    'tryFastCertifyResidentCanvasLighting',
    'PRESSURE_PARK_TICKET_NEIGHBORHOOD',
]
for needle in required:
    if needle not in method:
        raise SystemExit(f'missing R1-159 invariant: {needle}')
for needle in [
    'ensureLightRelightResidencyTicket',
    'addForcedTicket',
    'getChunk(',
    'getChunkFuture',
    'offerPressurePark',
    'offerGenericDormant',
]:
    if needle in method:
        raise SystemExit(f'R1-159 illegally mutates/loads scheduler residency: {needle}')
if 'fastCertifyResidentPressureParkedInTicketNeighborhoods(world, residentProofBudget)' not in s:
    raise SystemExit('R1-159 ticket-neighborhood lane is not wired ahead of generic resident scan')
if 'fastCertifyResidentPressureParked(world, remainingResidentProofBudget)' not in s:
    raise SystemExit('R1-158 fallback lane was not preserved under remaining proof budget')
if 'int residentProofBudget = Math.min(64, Math.max(0, maxLightOnlyWake * 16));' not in s:
    raise SystemExit('R1-156 64-proof ceiling changed unexpectedly')
print('R1-159 deterministic source regression PASS')
