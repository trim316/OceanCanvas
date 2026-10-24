"""Execute actual retained-scope decisions across all ownership combinations."""
from pathlib import Path
import subprocess
import tempfile
source=(Path(__file__).resolve().parents[2]/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
start=source.index('\tprivate static boolean operationHasRetainedSelectionScope(')
end=source.index('\n    private static boolean strictCanvasColumnSelected(',start)
methods=source[start:end]
header='''import java.util.*;
class SelectionScanScope {
 static class Pos {int x(){return 0;}int z(){return 0;}}
 static class LevelChunk {Pos getPos(){return new Pos();}}
 static class ChunkPos {static long pack(int x,int z){return 1L;}}
 static class Pregen {Set<Long> PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED=new HashSet<>(),PREGEN_TARGET_CHUNKS=new HashSet<>(),FORCE_REPROCESS_CHUNKS=new HashSet<>();}
 static class Finalizer {Set<Long> postJobLightOnlySelectionScope=new HashSet<>(),allowPhysicalRepair=new HashSet<>(),postJobPhysicalRepairAuthority=new HashSet<>();}
 static Pregen p=new Pregen();static Finalizer f=new Finalizer();
 static Pregen pregenSession(){return p;}static Finalizer lightFinalizerSession(){return f;}
 static class OceanCanvasActiveTerrainOperationBridge {static boolean scope;static boolean columnInMutationScope(int cx,int cz,int x,int z){return scope;}}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args) {
  for(int bits=0;bits<64;bits++) {
   List<Set<Long>> sets=List.of(p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED,f.postJobLightOnlySelectionScope,p.PREGEN_TARGET_CHUNKS,p.FORCE_REPROCESS_CHUNKS,f.allowPhysicalRepair,f.postJobPhysicalRepairAuthority);
   for(int i=0;i<6;i++){sets.get(i).clear();if((bits&(1<<i))!=0)sets.get(i).add(1L);}
   check(operationHasRetainedSelectionScope(new LevelChunk())==(bits!=0));
   for(boolean scope:new boolean[]{false,true}) {
    OceanCanvasActiveTerrainOperationBridge.scope=scope;
    check(operationColumnSelected(new LevelChunk(),3,4)==(bits==0||scope));
   }
  }
 }
'''
with tempfile.TemporaryDirectory() as d:
    path=Path(d)/'SelectionScanScope.java';path.write_text(header+methods+'\n}',encoding='utf-8')
    subprocess.run(['java',str(path)],check=True)
scan=source[source.index('\tprivate static FluidSettleRepair repairUnexpectedFluidReactionProducts('):source.index('\n\tprivate static BlockPos firstUnexpectedFluidReactionProduct(')]
assert scan.count('operationHasRetainedSelectionScope(chunk)')==1
assert 'selectedOperation && !OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(' in scan
assert 'config.canvasZone(x, z) != OceanCanvasConfig.CanvasZone.INSIDE' in scan
assert 'zones.isProtected(x, y, z)' in scan
print('Selection scope: 64 ownership combinations and live scope changes passed')
