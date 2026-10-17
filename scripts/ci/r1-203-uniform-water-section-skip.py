from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text(encoding='utf-8')
old='''\t\t\tBlockState bs = uniformSourceWater ? Blocks.WATER.defaultBlockState()\n\t\t\t\t\t: chunk.getBlockState(state.cursor.set(state.x, y, state.z));\n\t\t\tprocessedBlocks++;\n\t\t\tif (!isCanonicalCanvasWaterAuditState(bs, y, waterTop)\n\t\t\t\t\t&& !isPhysicalAuditProtected(world, state.x, y, state.z, bs, state.preservedWholeBounds)) {\n'''
new='''\t\t\tif (uniformSourceWater) {\n\t\t\t\t// Palette membership proves every remaining cell in this 16-high section is\n\t\t\t\t// canonical source water. Advance to the next section boundary as one bounded\n\t\t\t\t// proof unit instead of revisiting the same palette fact for every Y cell.\n\t\t\t\t// The fact is invocation-local, so a cooperative yield re-reads the live palette.\n\t\t\t\tint nextSectionY = (Math.floorDiv(y, 16) + 1) * 16;\n\t\t\t\tstate.yCursor = Math.min(waterTop + 1, nextSectionY);\n\t\t\t\tprocessedBlocks++;\n\t\t\t\tcontinue;\n\t\t\t}\n\t\t\tBlockState bs = chunk.getBlockState(state.cursor.set(state.x, y, state.z));\n\t\t\tprocessedBlocks++;\n\t\t\tif (!isCanonicalCanvasWaterAuditState(bs, y, waterTop)\n\t\t\t\t\t&& !isPhysicalAuditProtected(world, state.x, y, state.z, bs, state.preservedWholeBounds)) {\n'''
if s.count(old)!=1: raise SystemExit(f'expected one R1-203 audit block, found {s.count(old)}')
s=s.replace(old,new)
p.write_text(s,encoding='utf-8')
print('R1-203 staged: uniform source-water sections advance by section boundary')
