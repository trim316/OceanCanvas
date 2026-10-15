"""Execute the authority predicate used by falling placement at partial edges."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[2] / 'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
start = source.index('    private static boolean fallingCellHasPhysicalAuthority(')
end = source.index('\n    /**', start)
method = source[start:end]
body = '''
 public static void main(String[] args) {
  for (int flags=0; flags<8; flags++) {
   boolean sealed=(flags&1)!=0, owner=(flags&2)!=0, audit=(flags&4)!=0;
   boolean expected=sealed || flags==6;
   if (fallingCellHasPhysicalAuthority(sealed,owner,audit)!=expected)
    throw new AssertionError("authority flags="+flags);
  }
 }
'''
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / 'FallingEdgeAuthority.java'
    path.write_text('class FallingEdgeAuthority {\n' + method + body + '\n}', encoding='utf-8')
    subprocess.run(['java', str(path)], check=True)

guard = source[source.index('    private static boolean selectedAuthoredFallingCell('):source.index('    /** Returning false from vanilla landing')]
for required in ['pregenIncludesChunk', 'columnInMutationScope', 'CanvasZone.INSIDE',
                 'fallingCellHasPhysicalAuthority(sealedCanvas,', 'allowPhysicalRepair.contains(packed)',
                 'preLightPhysicalAuditComplete.contains(packed)', 'pendingOriginalProtectionTouchesChunkRing',
                 'isPhysicalAuditProtected', 'isProtected(x,y,z)']:
    assert required in guard, required
print('Falling edge authority: all 8 states and selection/protection wiring passed')
