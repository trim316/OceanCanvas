#!/usr/bin/env python3
from pathlib import Path

path = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
src = path.read_text(encoding='utf-8')
start = src.index('private static int fastCertifyResidentPressureParkedInTicketNeighborhoods')
end = src.index('\n\n\n\t/**', start)
method = src[start:end]
guard = '\t\tif (net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.outstandingPregenTargets() > 0) return 0;\n'
if guard not in method:
    raise SystemExit('R1-205 guard not found exactly once in resident-ticket-neighborhood method')
if method.count(guard) != 1:
    raise SystemExit('R1-205 guard count changed; refusing ambiguous transform')
replacement = ('\t\t// R1-205: this lane installs no ticket and never force-loads. During long terrain\n'
               '\t\t// operations, reuse residency already paid for by the bounded relight-ticket\n'
               '\t\t// pool to strictly certify LIGHT_ONLY pressure debt before eviction. The broad\n'
               '\t\t// generic dormant scanner remains terrain-dormant; physical/target debt remains\n'
               '\t\t// excluded below and every completion still uses the strict current-field proof.\n')
method = method.replace(guard, replacement)
path.write_text(src[:start] + method + src[end:], encoding='utf-8')
print('R1-205 transform applied')
