#!/usr/bin/env python3
from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text()
old='''\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkCompletionAllowed(chunk)) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n\t\t// R1-191: tryCompletePregenTarget has just completed the same exhaustive\n\t\t// physical profile proof used by the lighting finalizer. Preserve that proof\n\t\t// only as an in-memory current-epoch certificate so fresh Pregen does not\n\t\t// immediately rescan every block before lighting. Restart/legacy paths still\n\t\t// re-prove, and any real physical mutation clears cooperative finalizer state.\n\t\tif (wholeChunkCompletionAllowed(chunk)) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n'''
new='''\t\t// R1-194: R1-191 accidentally evaluated the same exhaustive fail-closed\n\t\t// physical completion predicate twice back-to-back. Evaluate it once and reuse\n\t\t// only this same-call result for the durable seal and ephemeral light proof.\n\t\tboolean wholeChunkComplete = wholeChunkCompletionAllowed(chunk);\n\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkComplete) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n\t\t// R1-191: tryCompletePregenTarget has just completed the same exhaustive\n\t\t// physical profile proof used by the lighting finalizer. Preserve that proof\n\t\t// only as an in-memory current-epoch certificate so fresh Pregen does not\n\t\t// immediately rescan every block before lighting. Restart/legacy paths still\n\t\t// re-prove, and any real physical mutation clears cooperative finalizer state.\n\t\tif (wholeChunkComplete) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n'''
marker='R1-194: R1-191 accidentally evaluated the same exhaustive fail-closed'
if marker not in s:
    if s.count(old)!=1: raise SystemExit(f'expected one R1-191 duplicate-proof block, found {s.count(old)}')
    s=s.replace(old,new,1)
elif s.count(marker)!=1: raise SystemExit('R1-194 marker count mismatch')
for needle in ['boolean wholeChunkComplete = wholeChunkCompletionAllowed(chunk);','lightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);','LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L','LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48']:
    if needle not in s: raise SystemExit(f'missing safety anchor: {needle}')
p.write_text(s)
print('R1-194 valid: unchanged exhaustive physical completion predicate evaluated once per publication')
