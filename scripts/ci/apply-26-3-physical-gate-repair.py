from pathlib import Path

src = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
text = src.read_text(encoding='utf-8')
old = '''    private static boolean fallingCellHasPhysicalAuthority(boolean sealedCanvas,
            boolean currentPhysicalOwner, boolean currentEpochAuditComplete, boolean authoredWaterHistory) {
        // Falling placement is a physical write. A historical whole-chunk seal or
        // authored-water prevention marker may prove provenance, but neither is
        // permission to mutate after the active operation relinquishes physical
        // ownership. Fail closed unless current physical repair authority exists.
        return currentPhysicalOwner && (sealedCanvas || currentEpochAuditComplete || authoredWaterHistory);
    }
'''
new = '''    static boolean fallingCellHasPhysicalAuthority(boolean sealedCanvas,
            boolean currentPhysicalOwner, boolean currentEpochAuditComplete, boolean authoredWaterHistory) {
        // The caller already proves that an active OceanCanvas operation owns this
        // chunk/column and later rejects player/structure protections. A physically
        // verified CANVAS seal is durable proof that the water column was authored by
        // OceanCanvas. Falling gravel/sand can settle after the short-lived physical
        // repair lease is released; requiring that expired lease here creates a
        // permanent post-job loop: the integrity gate can see the defect but has no
        // legal repair path. Permit only that sealed-canvas recovery. Unsealed
        // historical chunks still require current physical ownership and provenance.
        return (currentPhysicalOwner || sealedCanvas)
                && (sealedCanvas || currentEpochAuditComplete || authoredWaterHistory);
    }
'''
if old in text:
    src.write_text(text.replace(old, new, 1), encoding='utf-8')
elif new not in text:
    raise SystemExit('Expected fallingCellHasPhysicalAuthority source block not found; refusing broad patch')

test = Path('production-src/src/test/java/net/oceancanvas/mod/worldgen/OceanCanvasPostJobIntegrityTest.java')
t = test.read_text(encoding='utf-8')
marker = '''    @Test void allVisualGatesMustDrainBeforeCompletion() {
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,1,0,0,0,0).loadedLightingClean());
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,0,0,1,0).loadedLightingClean());
        assertFalse(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,0,0,0,1).loadedLightingClean());
        assertTrue(new OceanCanvasSurfaceFlattener.PostJobVisualIntegrityDiagnostics(1024,361,663,361,0,0,0,0,0).loadedLightingClean());
    }
'''
addition = marker + '''    @Test void sealedCanvasCanRepairSettledFallingDebrisAfterLeaseEnds() {
        assertTrue(OceanCanvasSurfaceFlattener.fallingCellHasPhysicalAuthority(true,false,false,false));
    }
    @Test void unsealedHistoricalChunkStillCannotAcquirePhysicalRepairAuthority() {
        assertFalse(OceanCanvasSurfaceFlattener.fallingCellHasPhysicalAuthority(false,false,true,true));
    }
    @Test void currentPhysicalOwnerStillRequiresAuthoredProvenance() {
        assertFalse(OceanCanvasSurfaceFlattener.fallingCellHasPhysicalAuthority(false,true,false,false));
        assertTrue(OceanCanvasSurfaceFlattener.fallingCellHasPhysicalAuthority(false,true,true,false));
    }
'''
if 'sealedCanvasCanRepairSettledFallingDebrisAfterLeaseEnds' not in t:
    if marker not in t:
        raise SystemExit('Expected post-job integrity test anchor not found')
    test.write_text(t.replace(marker, addition, 1), encoding='utf-8')

gp = Path('production-src/gradle.properties')
g = gp.read_text(encoding='utf-8')
if 'mod_version=26.3-v253.125.83' in g:
    gp.write_text(g.replace('mod_version=26.3-v253.125.83', 'mod_version=26.3-v253.125.84', 1), encoding='utf-8')
elif 'mod_version=26.3-v253.125.84' not in g:
    raise SystemExit('Unexpected production version in gradle.properties')

oc = Path('production-src/src/main/java/net/oceancanvas/mod/OceanCanvas.java')
o = oc.read_text(encoding='utf-8')
if 'public static final String VERSION = "v253.125.83";' in o:
    oc.write_text(o.replace('public static final String VERSION = "v253.125.83";', 'public static final String VERSION = "v253.125.84";', 1), encoding='utf-8')
elif 'public static final String VERSION = "v253.125.84";' not in o:
    raise SystemExit('Unexpected runtime VERSION constant')
