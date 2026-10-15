from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text(encoding='utf-8')
old='''\t\tint minX = chunk.getPos().getMinBlockX();\n\t\tint minZ = chunk.getPos().getMinBlockZ();\n\t\t// Bound work by elapsed time at every block, with a finite read cap as\n'''
new='''\t\tint minX = chunk.getPos().getMinBlockX();\n\t\tint minZ = chunk.getPos().getMinBlockZ();\n\t\t// Selection ownership is stable only for this cooperative invocation. Hoist the\n\t\t// session-map probes once per slice; a later yielded slice re-reads ownership.\n\t\tboolean retainedSelectionScope = operationHasRetainedSelectionScope(chunk);\n\t\t// Bound work by elapsed time at every block, with a finite read cap as\n'''
if s.count(old)!=1: raise SystemExit(f'expected audit setup anchor once, found {s.count(old)}')
s=s.replace(old,new)
old2='''\t\t\t\tif (!strictCanvasColumnSelected(chunk, config, state.x, state.z)) {\n\t\t\t\t\tstate.columnCursor++;\n\t\t\t\t\tcontinue;\n\t\t\t\t}\n'''
new2='''\t\t\t\tboolean selected = config.canvasZone(state.x, state.z) == OceanCanvasConfig.CanvasZone.INSIDE\n\t\t\t\t\t\t&& (!retainedSelectionScope || OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(\n\t\t\t\t\t\t\t\tchunk.getPos().x(), chunk.getPos().z(), state.x, state.z));\n\t\t\t\tif (!selected) {\n\t\t\t\t\tstate.columnCursor++;\n\t\t\t\t\tcontinue;\n\t\t\t\t}\n'''
if s.count(old2)!=1: raise SystemExit(f'expected one cooperative audit selection call, found {s.count(old2)}')
s=s.replace(old2,new2)
p.write_text(s,encoding='utf-8')
print('R1-204 staged: retained operation selection scope is read once per cooperative audit slice')
