#!/usr/bin/env python3
from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = '''\t\tboolean terrainCompleteForResidentProof =
\t\t\t\tnet.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets() == 0;
\t\tint residentProofBudget = Math.min(64, Math.max(0, maxLightOnlyWake * 16));
\t\tint fastCompleted = 0;
\t\tif (terrainCompleteForResidentProof && residentProofBudget > 0) {
\t\t\tfastCompleted = fastCertifyResidentPressureParkedInTicketNeighborhoods(world, residentProofBudget);
\t\t\tint remainingResidentProofBudget = Math.max(0, residentProofBudget - fastCompleted);
\t\t\tif (remainingResidentProofBudget > 0) {
\t\t\t\tfastCompleted += fastCertifyResidentPressureParked(world, remainingResidentProofBudget);
\t\t\t}
\t\t}
'''
new = '''\t\tboolean terrainCompleteForResidentProof =
\t\t\t\tnet.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets() == 0;
\t\tint residentProofBudget = Math.min(64, Math.max(0, maxLightOnlyWake * 16));
\t\tint fastCompleted = 0;
\t\tif (residentProofBudget > 0) {
\t\t\t// R1-206: R1-205 made the ticket-neighborhood lane safe during terrain,
\t\t\t// but this caller still accidentally suppressed it until terrain completed.
\t\t\t// Always reuse already-owned relight residency; keep only the broad generic
\t\t\t// dormant scanner behind the terrain-complete gate.
\t\t\tfastCompleted = fastCertifyResidentPressureParkedInTicketNeighborhoods(world, residentProofBudget);
\t\t\tint remainingResidentProofBudget = Math.max(0, residentProofBudget - fastCompleted);
\t\t\tif (terrainCompleteForResidentProof && remainingResidentProofBudget > 0) {
\t\t\t\tfastCompleted += fastCertifyResidentPressureParked(world, remainingResidentProofBudget);
\t\t\t}
\t\t}
'''
if new in s:
    print('R1-206 product mutation already present')
elif s.count(old) == 1:
    p.write_text(s.replace(old, new, 1))
    print('R1-206 product mutation applied')
else:
    raise SystemExit('R1-206 refused: expected exact caller block not found uniquely')
