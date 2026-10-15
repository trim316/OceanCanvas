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
# The cache must be allocated inside the audit invocation, not shared across calls.
assert audit.index('byte[] sourceWaterSectionFacts = new byte[chunk.getSections().length];') < audit.index('while (state.x < 16)')
# Arithmetic boundary regression, including negative build heights and clipping at waterTop + 1.
def next_boundary(y): return ((y//16)+1)*16
def clipped_advance(y, water_top): return min(water_top+1, next_boundary(y))
for y,expected in [(-65,-64),(-64,-48),(-1,0),(0,16),(15,16),(16,32),(63,64)]:
 assert next_boundary(y)==expected,(y,next_boundary(y),expected)
for y,water_top,expected in [(-65,-60,-64),(-64,-60,-60+1),(0,7,8),(15,15,16),(16,20,21),(63,63,64)]:
 assert clipped_advance(y,water_top)==expected,(y,water_top,clipped_advance(y,water_top),expected)
print('R1-203 section-skip regression passed: invocation-local palette proof, mixed fallback, clipped and negative-Y boundaries')
