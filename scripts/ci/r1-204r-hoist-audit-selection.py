from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')
start = s.index('private static PhysicalAuditAdvance advancePhysicalProfileAudit(')
end = s.index('private static PhysicalProfileMismatch firstPhysicalProfileMismatch(', start)
body = s[start:end]
old = '''boolean inSelectedCanvasColumn = operationHasRetainedSelectionScope(chunk)\n\t\t\t\t\t\t? (config.canvasZone(state.x, state.z) == OceanCanvasConfig.CanvasZone.INSIDE\n\t\t\t\t\t\t\t\t&& OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(\n\t\t\t\t\t\t\t\t\t\tchunk.getPos().x(), chunk.getPos().z(), state.x, state.z))\n\t\t\t\t\t\t: strictCanvasColumnSelected(chunk, config, state.x, state.z);'''
new = '''boolean inSelectedCanvasColumn = retainedSelectionScope\n\t\t\t\t\t\t? (config.canvasZone(state.x, state.z) == OceanCanvasConfig.CanvasZone.INSIDE\n\t\t\t\t\t\t\t\t&& OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(\n\t\t\t\t\t\t\t\t\t\tchunk.getPos().x(), chunk.getPos().z(), state.x, state.z))\n\t\t\t\t\t\t: strictCanvasColumnSelected(chunk, config, state.x, state.z);'''
strict_old = '''if (!strictCanvasColumnSelected(chunk, config, state.x, state.z)) {'''
strict_new = '''boolean inSelectedCanvasColumn = retainedSelectionScope\n\t\t\t\t\t\t? (config.canvasZone(state.x, state.z) == OceanCanvasConfig.CanvasZone.INSIDE\n\t\t\t\t\t\t\t\t&& OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(\n\t\t\t\t\t\t\t\t\t\tchunk.getPos().x(), chunk.getPos().z(), state.x, state.z))\n\t\t\t\t\t\t: strictCanvasColumnSelected(chunk, config, state.x, state.z);\n\t\t\t\tif (!inSelectedCanvasColumn) {'''
if 'boolean retainedSelectionScope = operationHasRetainedSelectionScope(chunk);' not in body:
    anchor = '\t\tlong started = System.nanoTime();\n'
    if anchor not in body:
        raise SystemExit('R1-204R refused: audit-slice start anchor missing')
    body = body.replace(anchor, anchor + '\t\tboolean retainedSelectionScope = operationHasRetainedSelectionScope(chunk);\n', 1)
if old in body:
    body = body.replace(old, new, 1)
elif new not in body:
    if body.count(strict_old) != 1:
        raise SystemExit('R1-204R refused: expected selection-authority expression missing/drifted')
    body = body.replace(strict_old, strict_new, 1)
if body.count('operationHasRetainedSelectionScope(chunk)') != 1:
    raise SystemExit('R1-204R refused: retained-selection authority must be read exactly once per audit invocation')
s = s[:start] + body + s[end:]
p.write_text(s, encoding='utf-8')
print('R1-204R transform applied/idempotent')
