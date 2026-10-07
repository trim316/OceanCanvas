#!/usr/bin/env python3
from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text(encoding='utf-8')
old='lightRecoverySession().pressureParkUntilTick.put(packed, now + LIGHT_PRESSURE_PARK_RETRY_TICKS);'
new='''lightRecoverySession().pressureParkUntilTick.put(packed,\n\t\t\t\t\tOceanCanvasTerrainPhaseLightDormancyPolicy.deadlineForGenericDebt(\n\t\t\t\t\t\t\tnow, LIGHT_PRESSURE_PARK_RETRY_TICKS,\n\t\t\t\t\t\t\toutstandingPregenTargets(), false, false));'''
if s.count(old)!=2: raise SystemExit(f'expected 2 generic pressure-park sites, found {s.count(old)}')
s=s.replace(old,new)
old='lightRecoverySession().pressureParkUntilTick.drainKeysAtOrBelow(now,'
new='''lightRecoverySession().pressureParkUntilTick.drainKeysAtOrBelow(\n\t\t\t\toutstandingPregenTargets() == 0 ? Long.MAX_VALUE : now,'''
if s.count(old)!=2: raise SystemExit(f'expected 2 pressure-park drain sites, found {s.count(old)}')
s=s.replace(old,new)
p.write_text(s,encoding='utf-8')
print('R1-136 integrated: stable terrain dormancy plus bounded zero-terrain wake')
