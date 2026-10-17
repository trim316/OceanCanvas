import java.util.*;
class RetainedScopeRegression77 {
 static class LevelChunk { Pos getPos(){return new Pos();} } static class Pos {int x(){return 63;} int z(){return 0;}}
 static class ChunkPos {static long pack(int x,int z){return 1L;}}
 static class Pregen {Set<Long> PREGEN_TARGET_CHUNKS=new HashSet<>(),FORCE_REPROCESS_CHUNKS=new HashSet<>(),PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED=new HashSet<>();}
 static class Finalizer {Set<Long> allowPhysicalRepair=new HashSet<>(),postJobPhysicalRepairAuthority=new HashSet<>(),postJobLightOnlySelectionScope=new HashSet<>();}
 static Pregen p=new Pregen(); static Finalizer f=new Finalizer();
 static Pregen pregenSession(){return p;} static Finalizer lightFinalizerSession(){return f;}
 static class OceanCanvasActiveTerrainOperationBridge {static boolean running=true; static boolean columnInMutationScope(int cx,int cz,int x,int z){return !running || (x>=-1016&&x<=1015&&z>=-1016&&z<=1015);}}
 private static boolean operationColumnSelected(LevelChunk chunk, int x, int z) {
        long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        // Reuse only within this invocation: each later proof slice still observes
        // current operation ownership and the current retained end-gate authority.
        var pregen = pregenSession();
        boolean freshOperation = pregen.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(packed)
                || lightFinalizerSession().postJobLightOnlySelectionScope.contains(packed)
                || pregen.PREGEN_TARGET_CHUNKS.contains(packed)
                || pregen.FORCE_REPROCESS_CHUNKS.contains(packed);
        if (!freshOperation) {
            var finalizer = lightFinalizerSession();
            freshOperation = finalizer.allowPhysicalRepair.contains(packed)
                    // The transient repair flag is removed at certification; retain
                    // exact partial-edge scope until this operation resets.
                    || finalizer.postJobPhysicalRepairAuthority.contains(packed);
        }
        if (!freshOperation) return true;
        return OceanCanvasActiveTerrainOperationBridge.columnInMutationScope(
                chunk.getPos().x(), chunk.getPos().z(), x, z);
    }
 static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
 public static void main(String[] args){
 LevelChunk c=new LevelChunk();
 p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.add(1L); f.postJobLightOnlySelectionScope.add(1L);
 require(!operationColumnSelected(c,1016,0),"tracked edge excluded");
 require(operationColumnSelected(c,1015,0),"tracked interior selected");
 require(f.allowPhysicalRepair.isEmpty()&&f.postJobPhysicalRepairAuthority.isEmpty(),"no physical authority granted");
 OceanCanvasActiveTerrainOperationBridge.running=false;
 require(operationColumnSelected(c,1016,0),"no-job fallback");
 OceanCanvasActiveTerrainOperationBridge.running=true;
 p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(1L); // exact retirement clears tracking
 require(!operationColumnSelected(c,1016,0),"retained light-only scope survives retirement");
 require(f.allowPhysicalRepair.isEmpty()&&f.postJobPhysicalRepairAuthority.isEmpty(),"retained selection grants no physical permission");
 f.postJobLightOnlySelectionScope.clear(); // operation reset/completion clears retained identity
 require(operationColumnSelected(c,1016,0),"reset restores support behavior");
 f.postJobPhysicalRepairAuthority.add(1L);
 require(!operationColumnSelected(c,1016,0),"existing retained physical authority keeps end-gate scope");
 require(operationColumnSelected(c,1015,0),"retained interior");
 f.postJobPhysicalRepairAuthority.clear();
 require(operationColumnSelected(c,1016,0),"unrelated support unchanged");
 System.out.println("PASS: active and retired light-only scope bounded; no physical permission; reset/no-job/support/physical preserved");
 }
}
