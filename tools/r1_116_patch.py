from pathlib import Path

source = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
text = source.read_text()
old = '''\t\t\t\t\tif (Math.abs(aSky - bSky) > 1) {
\t\t\t\t\t\tBlockPos aPos = new BlockPos(ax, y, az);
\t\t\t\t\t\tsideBadPositions.add(aPos);
\t\t\t\t\t\tsideBadActual.add(Integer.valueOf(aSky));
\t\t\t\t\t\tsideBadNeighbor.add(Integer.valueOf(bSky));
\t\t\t\t\t}
'''
new = '''\t\t\t\t\tif (Math.abs(aSky - bSky) > 1) {
\t\t\t\t\t\tint canonicalCenterSky = maximumPlainWaterSkyAtDepth(depth);
\t\t\t\t\t\tboolean provenLateralSource = aSky > canonicalCenterSky
\t\t\t\t\t\t\t\t&& hasProvenLateralSkySource(world, ax, y, az, aSky, lateralSkySourceCache);
\t\t\t\t\t\tif (!shouldAttributeDeepSeamFaultToCenter(depth, aSky, bSky, provenLateralSource)) continue;
\t\t\t\t\t\tBlockPos aPos = new BlockPos(ax, y, az);
\t\t\t\t\t\tsideBadPositions.add(aPos);
\t\t\t\t\t\tsideBadActual.add(Integer.valueOf(aSky));
\t\t\t\t\t\tsideBadNeighbor.add(Integer.valueOf(bSky));
\t\t\t\t\t}
'''
if text.count(old) != 1:
    raise SystemExit(f'expected exactly one seam mismatch block, found {text.count(old)}')
text = text.replace(old, new)

marker = '''\tprivate static int minimumPlainWaterSkyAtDepth(int depthBelowSurfaceWater) {
'''
helper = '''\t/**
\t * v253.125.73 seam-fault ownership. A seam disagreement proves that at least one
\t * side needs attention; it does not prove that the chunk currently being audited
\t * is the bad side. The 2k Build72 checkpoint captured repeated quarantines where
\t * the center column itself was exactly canonical (14,13,...,0) and only its
\t * neighbor differed. Blaming the center in that case makes a healthy chunk repair
\t * forever. Attribute the fault to this chunk only when its own value is outside
\t * the deterministic plain-water value and that excess is not explained by a
\t * geometrically proven lateral source. The neighbor will be judged by its own
\t * certificate pass, so a canonical center may safely retire.
\t */
\tstatic boolean shouldAttributeDeepSeamFaultToCenter(
\t\t\tint depthBelowSurfaceWater, int centerSky, int neighborSky, boolean provenLateralSource) {
\t\tif (Math.abs(centerSky - neighborSky) <= 1) return false;
\t\tint canonical = maximumPlainWaterSkyAtDepth(depthBelowSurfaceWater);
\t\tif (centerSky == canonical) return false;
\t\tif (centerSky > canonical && provenLateralSource) return false;
\t\treturn true;
\t}

'''
if text.count(marker) != 1:
    raise SystemExit(f'expected one minimum sky marker, found {text.count(marker)}')
text = text.replace(marker, helper + marker)
source.write_text(text)

props = Path('production-src/gradle.properties')
p = props.read_text()
if 'mod_version=26.2-v253.125.72' not in p:
    raise SystemExit('unexpected mod version; refusing blind bump')
props.write_text(p.replace('mod_version=26.2-v253.125.72', 'mod_version=26.2-v253.125.73', 1))

test = Path('production-src/src/test/java/net/oceancanvas/mod/worldgen/OceanCanvasDeepSeamOwnershipTest.java')
test.write_text('''package net.oceancanvas.mod.worldgen;\n\nimport static org.junit.jupiter.api.Assertions.*;\nimport org.junit.jupiter.api.Test;\n\nfinal class OceanCanvasDeepSeamOwnershipTest {\n    @Test void canonicalCenterDoesNotOwnBadNeighborSeam() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 0, 4, false));\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(24, 0, 7, false));\n    }\n\n    @Test void noncanonicalCenterOwnsItsSideOfBadSeam() {\n        assertTrue(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 4, 0, false));\n    }\n\n    @Test void provenLateralSourceDoesNotCreateFalseCenterDebt() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 4, 0, true));\n    }\n\n    @Test void smallSeamGradientIsAlwaysAcceptable() {\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 0, 1, false));\n        assertFalse(OceanCanvasSurfaceFlattener.shouldAttributeDeepSeamFaultToCenter(16, 1, 0, false));\n    }\n}\n''')
