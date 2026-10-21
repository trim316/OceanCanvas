from pathlib import Path

source = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
start = source.index('private static boolean operationHasRetainedSelectionScope(LevelChunk chunk)')
end = source.index('private static boolean operationColumnSelected', start)
method = source[start:end]
needle = 'OceanCanvasActiveTerrainOperationBridge.pregenIncludesChunk(\n                        chunk.getPos().x(), chunk.getPos().z())'
assert needle in method, 'completed active-job selection must remain visible to post-job audits'
assert method.index(needle) < method.index('PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED'), 'active exact selection must participate in fresh-operation scope'
assert 'allowPhysicalRepair.add' not in method and 'postJobPhysicalRepairAuthority.add' not in method, 'selection observation must not grant repair authority'
assert 'return freshOperation;' in method
print('retained-selection scope regression PASS')
