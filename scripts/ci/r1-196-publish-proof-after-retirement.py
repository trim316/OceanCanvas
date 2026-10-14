#!/usr/bin/env python3
from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text()
old='''\t\t// R1-191: tryCompletePregenTarget has just completed the same exhaustive\n\t\t// physical profile proof used by the lighting finalizer. Preserve that proof\n\t\t// only as an in-memory current-epoch certificate so fresh Pregen does not\n\t\t// immediately rescan every block before lighting. Restart/legacy paths still\n\t\t// re-prove, and any real physical mutation clears cooperative finalizer state.\n\t\tif (wholeChunkComplete) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n'''
if s.count(old) != 1:
    raise SystemExit(f'expected one pre-retirement R1-191 certificate publication, found {s.count(old)}')
s=s.replace(old,'''\t\t// R1-196: do not publish the ephemeral current-epoch proof yet. Retirement\n\t\t// releases Pregen-owned tickets/leases and may clear cooperative finalizer\n\t\t// state as part of ownership cleanup. Publish only after that cleanup has\n\t\t// completed so the already-proven fresh chunk reaches stage 0 with its proof.\n''',1)
anchor='''\t\tretireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);\n\t\tif (recoveryLightOnlyFinalizer) {\n'''
replacement='''\t\tretireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);\n\t\t// R1-196: retirement is the last ownership-cleanup boundary before the\n\t\t// lighting finalizer can consume this proof. Keep it in-memory only; restart\n\t\t// still re-proves, and every later real physical mutation still invalidates it.\n\t\tif (wholeChunkComplete) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n\t\tif (recoveryLightOnlyFinalizer) {\n'''
if s.count(anchor) != 1:
    raise SystemExit(f'expected one retirement publication anchor, found {s.count(anchor)}')
s=s.replace(anchor,replacement,1)
for needle in [
    'boolean wholeChunkComplete = wholeChunkCompletionAllowed(chunk);',
    'lightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);',
    'LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L',
    'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48',
    'retireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);',
]:
    if needle not in s: raise SystemExit(f'missing safety anchor: {needle}')
if s.find('retireVerifiedPregenTarget(world, chunk, packed, path, crashRecoveryAtEntry);') > s.find('lightFinalizerSession().preLightPhysicalAuditComplete.add(packed);'):
    raise SystemExit('certificate publication is not after retirement')
p.write_text(s)
print('R1-196 valid: fresh physical proof is published only after Pregen retirement cleanup')
