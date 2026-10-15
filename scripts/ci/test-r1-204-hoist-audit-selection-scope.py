from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text(encoding='utf-8')
start=s.index('private static PhysicalAuditAdvance advancePhysicalProfileAudit(')
end=s.index('private static PhysicalProfileMismatch firstPhysicalProfileMismatch(', start)
body=s[start:end]
assert body.count('operationHasRetainedSelectionScope(chunk)') == 1, 'cooperative audit must read retained selection authority once per invocation'
assert 'boolean retainedSelectionScope = operationHasRetainedSelectionScope(chunk);' in body
assert '(!retainedSelectionScope || OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(' in body
assert 'strictCanvasColumnSelected(chunk, config, state.x, state.z)' not in body, 'per-column session-map authority probe must not remain in cooperative audit'
assert 'config.canvasZone(state.x, state.z) == OceanCanvasConfig.CanvasZone.INSIDE' in body
assert 'chunk.getPos().x(), chunk.getPos().z(), state.x, state.z' in body
state=s[s.index('static final class PhysicalAuditState'):start]
assert 'retainedSelectionScope' not in state
print('R1-204 regression PASS: selection authority hoisted per slice without crossing yield boundary')
