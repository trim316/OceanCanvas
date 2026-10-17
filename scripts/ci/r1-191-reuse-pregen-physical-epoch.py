#!/usr/bin/env python3
from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = '''\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkCompletionAllowed(chunk)) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n'''
new = '''\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkCompletionAllowed(chunk)) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n\t\t// R1-191: tryCompletePregenTarget has just completed the same exhaustive\n\t\t// physical profile proof used by the lighting finalizer. Preserve that proof\n\t\t// only as an in-memory current-epoch certificate so fresh Pregen does not\n\t\t// immediately rescan every block before lighting. Restart/legacy paths still\n\t\t// re-prove, and any real physical mutation clears cooperative finalizer state.\n\t\tif (wholeChunkCompletionAllowed(chunk)) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n'''
marker = 'R1-191: tryCompletePregenTarget'
if marker not in s:
    if s.count(old) != 1:
        raise SystemExit(f'expected one physical-seal publication block, found {s.count(old)}')
    s = s.replace(old, new, 1)
elif s.count(marker) != 1:
    raise SystemExit('R1-191 marker count mismatch')

required = [
    'lightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);',
    '&& !lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed);',
    'lightFinalizerSession().preLightPhysicalAuditComplete.add(packed);',
    'pendingTicks.sumKeys(',
    'pendingTicks.get(packed)',
]
for needle in required:
    if needle not in s:
        raise SystemExit(f'missing required R1-191 safety/compile anchor: {needle}')
if 'pendingTicks.countValuesAtLeast(' in s:
    raise SystemExit('R1-185 compile repair regressed: unsupported countValuesAtLeast call present')
if s.count(marker) != 1:
    raise SystemExit('R1-191 marker count mismatch after patch')
p.write_text(s)
print('R1-191 source valid: R1-189 proof reuse rebased on R1-185 delayed-count compile repair')
