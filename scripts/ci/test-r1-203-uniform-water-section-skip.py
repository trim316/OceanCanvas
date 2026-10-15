from pathlib import Path
s=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
a=s.index('private static PhysicalAuditAdvance advancePhysicalProfileAudit(')
b=s.index('private static PhysicalProfileMismatch firstPhysicalProfileMismatch(',a)
audit=s[a:b]
required=[
 'byte[] sourceWaterSectionFacts = new byte[chunk.getSections().length];',
 'sourceWaterOnlyAuditSection(chunk.getSection(sectionIndex))',
 'if (uniformSourceWater) {',
 'int nextSectionY = (Math.floorDiv(y, 16) + 1) * 16;',
 'state.yCursor = Math.min(waterTop + 1, nextSectionY);',
 'BlockState bs = chunk.getBlockState(state.cursor.set(state.x, y, state.z));'
]
for token in required:
 assert token in audit, token
# The skip must occur before the mixed-palette cell read and must consume a bounded proof unit.
skip=audit.index('if (uniformSourceWater) {')
read=audit.index('BlockState bs = chunk.getBlockState(state.cursor.set(state.x, y, state.z));',skip)
assert skip < read
block=audit[skip:read]
assert 'processedBlocks++;' in block and 'continue;' in block
# Never persist the palette fact across cooperative yields: later invocations must observe mutation.
state=s[s.index('static final class PhysicalAuditState'):s.index('private record PhysicalAuditAdvance')]
assert 'sourceWaterSectionFacts' not in state
# Arithmetic boundary regression, including negative build heights.
def next_boundary(y): return ((y//16)+1)*16
for y,expected in [(-65,-64),(-64,-48),(-1,0),(0,16),(15,16),(16,32),(63,64)]:
 assert next_boundary(y)==expected,(y,next_boundary(y),expected)
print('R1-203 section-skip regression passed: invocation-local palette proof, mixed fallback, negative-Y boundaries')
