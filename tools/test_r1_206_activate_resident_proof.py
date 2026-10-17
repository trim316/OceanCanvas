#!/usr/bin/env python3
from pathlib import Path
import re

src = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
start = src.index('public static int wakePressureParkedRecoveryForPressure(')
end = src.index('private static int wakePressureParkedRepairCohort(', start)
body = src[start:end]

assert 'fastCertifyResidentPressureParkedInTicketNeighborhoods(world, residentProofBudget)' in body
assert 'if (residentProofBudget > 0)' in body
assert 'if (terrainCompleteForResidentProof && remainingResidentProofBudget > 0)' in body
assert body.index('fastCertifyResidentPressureParkedInTicketNeighborhoods') < body.index('if (terrainCompleteForResidentProof && remainingResidentProofBudget > 0)')
assert body.index('wakePressureParkedRepairCohort') < body.index('fastCertifyResidentPressureParkedInTicketNeighborhoods')

# The broad generic scanner must remain terrain-gated; only the already-owned
# ticket-neighborhood lane is permitted while terrain is active.
generic_call = 'fastCertifyResidentPressureParked(world, remainingResidentProofBudget)'
assert generic_call in body
assert body.index('if (terrainCompleteForResidentProof && remainingResidentProofBudget > 0)') < body.index(generic_call)

# Preserve the R1-205 lane's no-force-load / no-new-ticket shape and exclusions.
mstart = src.index('private static int fastCertifyResidentPressureParkedInTicketNeighborhoods(')
mend = src.index('public static int wakePressureParkedRecoveryForPressure(', mstart)
method = src[mstart:mend]
assert 'relightResidencyLedger.activeSnapshot()' in method
assert 'getChunkNow(' in method
assert 'tryFastCertifyResidentCanvasLighting(' in method
assert 'PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains' in method
assert '!pregenSession().PREGEN_TARGET_CHUNKS.contains' in method
for forbidden in ('addRegionTicket(', 'getChunk(', 'forceLoad', 'setChunkForced'):
    assert forbidden not in method, forbidden

print('R1-206 terrain caller activation regression PASS')
