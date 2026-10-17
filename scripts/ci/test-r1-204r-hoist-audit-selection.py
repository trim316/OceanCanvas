from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text(encoding='utf-8')
start=s.index('private static PhysicalAuditAdvance advancePhysicalProfileAudit(')
end=s.index('private static PhysicalProfileMismatch firstPhysicalProfileMismatch(', start)
body=s[start:end]
assert body.count('operationHasRetainedSelectionScope(chunk)') == 1, 'cooperative audit must read retained selection authority once per invocation'
assert 'boolean retainedSelectionScope = operationHasRetainedSelectionScope(chunk);' in body
assert 'retainedSelectionScope' in body
assert '? (config.canvasZone(state.x, state.z) == OceanCanvasConfig.CanvasZone.INSIDE' in body
assert 'strictCanvasColumnSelected(chunk, config, state.x, state.z)' in body, 'fallback authority must remain for chunks without retained operation scope'
assert 'operationHasRetainedSelectionScope(chunk)\n\t\t\t\t\t\t?' not in body, 'per-column retained-scope probe must not remain in cooperative audit'
assert 'chunk.getPos().x(), chunk.getPos().z(), state.x, state.z' in body
state=s[s.index('static final class PhysicalAuditState'):start]
assert 'retainedSelectionScope' not in state, 'authority snapshot must not cross cooperative yield boundary'
print('R1-204R regression PASS: retained selection authority is hoisted per invocation without crossing yield boundary')
