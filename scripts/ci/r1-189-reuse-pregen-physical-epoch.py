#!/usr/bin/env python3
from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = '''\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkCompletionAllowed(chunk)) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n'''
new = '''\t\tif (!protectedData.isChunkProcessedPhysicallyVerified(chunk.getPos()) && wholeChunkCompletionAllowed(chunk)) {\n\t\t\tprotectedData.markChunkProcessedPhysicallyVerified(chunk.getPos());\n\t\t}\n\t\t// R1-189: tryCompletePregenTarget has just completed the same exhaustive\n\t\t// physical profile proof used by the lighting finalizer. Preserve that proof\n\t\t// only as an in-memory current-epoch certificate so fresh Pregen does not\n\t\t// immediately rescan every block before lighting. This is deliberately not\n\t\t// reconstructed from the durable physical seal: restart/legacy paths still\n\t\t// re-prove. Any real physical mutation clears cooperative finalizer state.\n\t\tif (wholeChunkCompletionAllowed(chunk)) {\n\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(packed);\n\t\t}\n'''
if s.count(old) != 1:
    raise SystemExit(f'expected one physical-seal publication block, found {s.count(old)}')
s = s.replace(old, new, 1)
# Fail closed if the current-epoch certificate is not mutation-invalidated or if
# the finalizer no longer gates its expensive audit on this exact certificate.
required = [
    'lightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);',
    '&& !lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed);',
    'lightFinalizerSession().preLightPhysicalAuditComplete.add(packed);',
]
for needle in required:
    if needle not in s:
        raise SystemExit(f'missing required R1-189 safety anchor: {needle}')
# The new seed must remain ephemeral; never persist or infer it from the durable seal.
if s.count('R1-189: tryCompletePregenTarget') != 1:
    raise SystemExit('R1-189 marker count mismatch')
p.write_text(s)
print('R1-189 source patch applied: fresh exhaustive Pregen proof seeds only the mutation-invalidated in-memory finalizer certificate')
