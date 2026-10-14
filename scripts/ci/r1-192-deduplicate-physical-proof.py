#!/usr/bin/env python3
from pathlib import Path
p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = '''\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkCompletionAllowed(chunk)) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n\t\t// R1-189: tryCompletePregenTarget has just completed the same exhaustive\n\t\t// physical profile proof used by the lighting finalizer. Preserve that proof\n\t\t// only as an in-memory current-epoch certificate so fresh Pregen does not\n\t\t// immediately rescan every block before lighting. This is deliberately not\n\t\t// reconstructed from the durable physical seal: restart/legacy paths still\n\t\t// re-prove. Any real physical mutation clears cooperative finalizer state.\n\t\tif (wholeChunkCompletionAllowed(chunk)) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n'''
new = '''\t\t// R1-192: R1-189 accidentally invoked the same exhaustive physical proof twice\n\t\t// back-to-back for each completed fresh-Pregen chunk. Evaluate the unchanged\n\t\t// fail-closed predicate once and reuse only that same-call result for both the\n\t\t// durable physical seal and the mutation-invalidated in-memory light certificate.\n\t\tboolean wholeChunkComplete = wholeChunkCompletionAllowed(chunk);\n\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkComplete) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n\t\t// R1-189: tryCompletePregenTarget has just completed the same exhaustive\n\t\t// physical profile proof used by the lighting finalizer. Preserve that proof\n\t\t// only as an in-memory current-epoch certificate so fresh Pregen does not\n\t\t// immediately rescan every block before lighting. This is deliberately not\n\t\t// reconstructed from the durable physical seal: restart/legacy paths still\n\t\t// re-prove. Any real physical mutation clears cooperative finalizer state.\n\t\tif (wholeChunkComplete) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n'''
marker = 'R1-192: R1-189 accidentally invoked the same exhaustive physical proof twice'
if marker not in s:
    if s.count(old) != 1:
        raise SystemExit(f'expected exactly one R1-189 double-proof block, found {s.count(old)}')
    s = s.replace(old, new, 1)
elif s.count(marker) != 1:
    raise SystemExit('R1-192 marker count mismatch')
required = [
    'boolean wholeChunkComplete = wholeChunkCompletionAllowed(chunk);',
    'lightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);',
    'LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L',
    'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48',
]
for needle in required:
    if needle not in s:
        raise SystemExit(f'missing safety anchor: {needle}')
if s.count(marker) != 1:
    raise SystemExit('R1-192 marker mismatch after patch')
p.write_text(s)
print('R1-192 source valid: unchanged exhaustive predicate evaluated once per completion publication')
