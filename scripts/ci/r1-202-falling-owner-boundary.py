from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')
old = '''    private static boolean fallingCellHasPhysicalAuthority(boolean sealedCanvas,
            boolean currentPhysicalOwner, boolean currentEpochAuditComplete, boolean authoredWaterHistory) {
        return sealedCanvas || (currentPhysicalOwner && (currentEpochAuditComplete || authoredWaterHistory));
    }'''
new = '''    private static boolean fallingCellHasPhysicalAuthority(boolean sealedCanvas,
            boolean currentPhysicalOwner, boolean currentEpochAuditComplete, boolean authoredWaterHistory) {
        // Falling placement is a physical write. A historical whole-chunk seal or
        // authored-water prevention marker may prove provenance, but neither is
        // permission to mutate after the active operation relinquishes physical
        // ownership. Fail closed unless current physical repair authority exists.
        return currentPhysicalOwner && (sealedCanvas || currentEpochAuditComplete || authoredWaterHistory);
    }'''
if s.count(new) == 1:
    pass
elif s.count(old) != 1:
    raise SystemExit(f'expected one falling authority predicate, found {s.count(old)}')
else:
    s = s.replace(old, new)
p.write_text(s, encoding='utf-8')
assert 'return currentPhysicalOwner && (sealedCanvas || currentEpochAuditComplete || authoredWaterHistory);' in s
print('R1-202 falling write authority transform PASS')
