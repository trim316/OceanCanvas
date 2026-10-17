#!/usr/bin/env python3
from pathlib import Path

src = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')

start = src.index('private static int fastCertifyResidentPressureParkedInTicketNeighborhoods')
end = src.index('\n\n\n\t/**', start)
method = src[start:end]

assert 'relightResidencyLedger.activeSnapshot()' in method, 'resident shortcut must remain bounded to already-owned relight ticket neighborhoods'
assert 'world.getChunkSource().getChunkNow' in method, 'resident shortcut must remain non-loading'
assert 'tryFastCertifyResidentCanvasLighting' in method, 'resident shortcut must retain strict current-field proof'
assert 'allowPhysicalRepair.contains(packed)' in method, 'generic classification must continue excluding physical-repair debt'
assert 'PREGEN_TARGET_CHUNKS.contains(packed)' in method, 'generic classification must continue excluding live terrain targets'
assert 'outstandingPregenTargets() > 0) return 0' not in method, 'R1-205 must permit already-resident strict LIGHT_ONLY proof during terrain phase'

# The broad generic dormant scanner remains terrain-dormant: R1-205 only reuses
# residency already paid for by the bounded relight-ticket pool.
generic_start = src.index('private static int fastCertifyResidentPressureParked(ServerLevel world')
generic_end = src.index('private static int fastCertifyResidentPressureParkedInTicketNeighborhoods', generic_start)
generic = src[generic_start:generic_end]
assert 'outstandingPregenTargets() > 0' in generic, 'generic dormant pressure debt must remain parked during terrain phase'
assert 'offerGenericDormant(packed)' in generic, 'generic dormant lane must remain physically separate'

print('R1-205 resident pressure proof regression: PASS')
