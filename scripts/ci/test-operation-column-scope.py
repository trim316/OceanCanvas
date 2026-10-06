"""Run the production operation-column helper across certification/reset states."""
from pathlib import Path
import subprocess
import tempfile
source=(Path(__file__).resolve().parents[2]/"production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
a=source.index("private static boolean operationColumnSelected(")
b=source.index("private static boolean strictCanvasColumnSelected(",a)
method=source[a:b]
assert "if (instabilityStreak == 1) logFluidFrontierProbe(world," in source, "first physical instability lacks read-only fluid probe"
header=r"""
import java.util.*;
public class OperationAuditScopeRegression {
 record ChunkPos(int x,int z){static long pack(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}}
 record LevelChunk(ChunkPos pos){ChunkPos getPos(){return pos;}}
 static class Pregen {Set<Long> PREGEN_TARGET_CHUNKS=new HashSet<>(),FORCE_REPROCESS_CHUNKS=new HashSet<>();}
 static class Finalizer {Set<Long> allowPhysicalRepair=new HashSet<>(),postJobPhysicalRepairAuthority=new HashSet<>();}
 static Pregen pregen=new Pregen();static Finalizer finalizer=new Finalizer();
 static int pregenGets,finalizerGets;
 static Pregen pregenSession(){pregenGets++;return pregen;}static Finalizer lightFinalizerSession(){finalizerGets++;return finalizer;}
 static class OceanCanvasActiveTerrainOperationBridge {static int calls;static boolean columnInMutationScope(int cx,int cz,int x,int z){calls++;return x>=-528&&x<=-401&&z>=372&&z<=499;}}
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static boolean selected(LevelChunk chunk,int x,int z){
  long packed=ChunkPos.pack(chunk.getPos().x(),chunk.getPos().z());
  boolean direct=pregen.PREGEN_TARGET_CHUNKS.contains(packed)||pregen.FORCE_REPROCESS_CHUNKS.contains(packed);
  pregenGets=0;finalizerGets=0;
  boolean result=operationColumnSelected(chunk,x,z);
  check(pregenGets==1,"pregen session lookup duplicated");
  check(finalizerGets==(direct?0:1),"finalizer lookup duplicated or fetched on direct ownership");
  return result;
 }

"""
footer=r"""
 public static void main(String[] args){
 LevelChunk chunk=new LevelChunk(new ChunkPos(-30,31));long packed=ChunkPos.pack(-30,31);
 pregen.PREGEN_TARGET_CHUNKS.add(packed);finalizer.allowPhysicalRepair.add(packed);finalizer.postJobPhysicalRepairAuthority.add(packed);
 check(selected(chunk,-475,499),"owned edge excluded during generation");
 check(!selected(chunk,-475,500),"outside source included during generation");
 pregen.PREGEN_TARGET_CHUNKS.clear();finalizer.allowPhysicalRepair.clear();
 check(selected(chunk,-475,499),"owned edge excluded after seal");
 check(!selected(chunk,-475,500),"scope widened after seal");
 finalizer.postJobPhysicalRepairAuthority.clear();OceanCanvasActiveTerrainOperationBridge.calls=0;
 check(selected(chunk,-475,500),"historical chunk retained finished job scope");
 check(OceanCanvasActiveTerrainOperationBridge.calls==0,"historical chunk consulted stale scope");
 pregen.FORCE_REPROCESS_CHUNKS.add(packed);check(!selected(chunk,-475,500),"force operation widened scope");
 pregen.FORCE_REPROCESS_CHUNKS.clear();finalizer.allowPhysicalRepair.add(packed);check(!selected(chunk,-475,500),"repair operation widened scope");
 System.out.println("PASS: exact current-job edge scope survives seal; reset clears historical scope; one lookup per required session");
 }
}
"""
with tempfile.TemporaryDirectory(prefix="oc-operation-scope-") as tmp:
 root=Path(tmp);(root/"OperationAuditScopeRegression.java").write_text(header+method+footer)
 subprocess.run(["javac","OperationAuditScopeRegression.java"],cwd=root,check=True)
 subprocess.run(["java","OperationAuditScopeRegression"],cwd=root,check=True)
