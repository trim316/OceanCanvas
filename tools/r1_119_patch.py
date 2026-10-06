from pathlib import Path

root = Path('production-src')
src = root / 'src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java'
main = root / 'src/main/java/net/oceancanvas/mod/OceanCanvas.java'
gradle = root / 'gradle.properties'
test = root / 'src/test/java/net/oceancanvas/mod/worldgen/OceanCanvasLateralFloorSupportTest.java'

text = src.read_text()
old = text

# The remaining R1-117 quarantine is a level-2 floor value supported by two
# cardinal peers. Source tables agree live vs recomputed, so this is bounded
# lateral inflow around varied floor geometry, not an open sky-source shaft.
# Keep the existing +1 tolerance and only exempt the next single light level
# when at least two immediate cardinal peers independently support it.
text = text.replace(
    'sky>ceiling&&!hasProvenLateralSkySource(world,x,y,z,sky,st.lateralSkySourceCache)&&st.layerBadCount<st.layerBadPos.length',
    'sky>ceiling&&!hasProvenLateralSkySource(world,x,y,z,sky,st.lateralSkySourceCache)&&!hasBoundedImmediateLateralSkySupport(world,x,y,z,sky)&&st.layerBadCount<st.layerBadPos.length')
text = text.replace(
    'sky>DEEP_SKY_OVERBRIGHT_TOLERANCE&&!hasProvenLateralSkySource(world,x,sampleY,z,sky,st.lateralSkySourceCache)&&st.layerBadCount<st.layerBadPos.length',
    'sky>DEEP_SKY_OVERBRIGHT_TOLERANCE&&!hasProvenLateralSkySource(world,x,sampleY,z,sky,st.lateralSkySourceCache)&&!hasBoundedImmediateLateralSkySupport(world,x,sampleY,z,sky)&&st.layerBadCount<st.layerBadPos.length')
text = text.replace(
    'sky > ceiling && !hasProvenLateralSkySource(world,x,y,z,sky,lateralSkySourceCache)',
    'sky > ceiling && !hasProvenLateralSkySource(world,x,y,z,sky,lateralSkySourceCache) && !hasBoundedImmediateLateralSkySupport(world,x,y,z,sky)')
text = text.replace(
    'sky > DEEP_SKY_OVERBRIGHT_TOLERANCE && !hasProvenLateralSkySource(world,x,sampleY,z,sky,lateralSkySourceCache)',
    'sky > DEEP_SKY_OVERBRIGHT_TOLERANCE && !hasProvenLateralSkySource(world,x,sampleY,z,sky,lateralSkySourceCache) && !hasBoundedImmediateLateralSkySupport(world,x,sampleY,z,sky)')

marker = '\tstatic boolean shouldAttributeDeepSeamFaultToCenter('
helper = '''\tprivate static boolean hasBoundedImmediateLateralSkySupport(ServerLevel world, int x, int y, int z, int sky) {\n\t\tBlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();\n\t\tint px = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, cursor.set(x + 1, y, z));\n\t\tint nx = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, cursor.set(x - 1, y, z));\n\t\tint pz = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, cursor.set(x, y, z + 1));\n\t\tint nz = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, cursor.set(x, y, z - 1));\n\t\treturn shouldAcceptBoundedImmediateLateralSkySupport(sky, px, nx, pz, nz);\n\t}\n\n\tstatic boolean shouldAcceptBoundedImmediateLateralSkySupport(int sky, int positiveX, int negativeX, int positiveZ, int negativeZ) {\n\t\tif (sky != DEEP_SKY_OVERBRIGHT_TOLERANCE + 1) return false;\n\t\tint supportingPeers = 0;\n\t\tif (positiveX >= sky) supportingPeers++;\n\t\tif (negativeX >= sky) supportingPeers++;\n\t\tif (positiveZ >= sky) supportingPeers++;\n\t\tif (negativeZ >= sky) supportingPeers++;\n\t\treturn supportingPeers >= 2;\n\t}\n\n'''
if marker not in text:
    raise SystemExit('seam helper marker not found')
text = text.replace(marker, helper + marker, 1)

if text == old:
    raise SystemExit('no validator replacements applied')
if 'hasBoundedImmediateLateralSkySupport' not in text:
    raise SystemExit('bounded lateral helper missing')
src.write_text(text)

g = gradle.read_text()
if 'mod_version=26.2-v253.125.73' not in g:
    raise SystemExit('expected v73 gradle identity missing')
gradle.write_text(g.replace('mod_version=26.2-v253.125.73', 'mod_version=26.2-v253.125.74', 1))

m = main.read_text()
if 'public static final String VERSION = "v253.125.73";' not in m:
    raise SystemExit('expected v73 code identity missing')
main.write_text(m.replace('public static final String VERSION = "v253.125.73";', 'public static final String VERSION = "v253.125.74";', 1))

test.write_text('''package net.oceancanvas.mod.worldgen;\n\nimport org.junit.jupiter.api.Test;\n\nimport static org.junit.jupiter.api.Assertions.*;\n\nfinal class OceanCanvasLateralFloorSupportTest {\n    @Test\n    void acceptsOnlyLevelTwoWhenTwoCardinalPeersSupportIt() {\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAcceptBoundedImmediateLateralSkySupport(2, 2, 0, 2, 0));\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAcceptBoundedImmediateLateralSkySupport(2, 3, 2, 0, 0));\n    }\n\n    @Test\n    void oneSupportingPeerIsNotEnough() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAcceptBoundedImmediateLateralSkySupport(2, 2, 0, 0, 0));\n    }\n\n    @Test\n    void brighterResidualsRemainFailuresEvenWithPeerSupport() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAcceptBoundedImmediateLateralSkySupport(3, 3, 3, 3, 3));\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAcceptBoundedImmediateLateralSkySupport(4, 4, 4, 4, 4));\n    }\n\n    @Test\n    void canonicalToleranceLevelDoesNotUseTheExemption() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAcceptBoundedImmediateLateralSkySupport(1, 1, 1, 1, 1));\n    }\n}\n''')

print('r1-119 patch staged: bounded level-2 lateral floor support + v253.125.74')
