"""Execute actual carve and physical-audit guards at expanded-border coordinates."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[2] / 'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
start = source.index('private static boolean isPhysicalAuditProtected(')
opening = source.index('{', start)
depth = 1
end = opening + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
method = source[start:end].replace('net.minecraft.world.level.levelgen.structure.BoundingBox', 'BoundingBox')
guard_start = source.index('if (playerProtection.isProtected(x, y, z)) {')
guard_end = source.index('}', guard_start) + 1
guard = source[guard_start:guard_end]

fixture = '''import java.util.*;
public class BorderProtectionRegression {
 static class BoundingBox {}
 static class ServerLevel {Set<String> protectedCells=new HashSet<>();boolean structureProtected;}
 static class Blocks {static final String WATER="water",LAVA="lava";}
 static class BlockState {String kind;BlockState(String k){kind=k;}boolean is(String k){return kind.equals(k);}}
 static class OceanCanvasPlayerZones {ServerLevel world;OceanCanvasPlayerZones(ServerLevel w){world=w;}static OceanCanvasPlayerZones get(ServerLevel w){return new OceanCanvasPlayerZones(w);}boolean isProtected(int x,int y,int z){return world.protectedCells.contains(x+","+y+","+z);}}
 static class ProtectedRegions {static boolean isProtected(ServerLevel w,int x,int y,int z){return w.structureProtected;}}
 static class OceanCanvasProtectedData {static OceanCanvasProtectedData get(ServerLevel w){return new OceanCanvasProtectedData();}boolean isProtected(int x,int y,int z){return false;}}
 static boolean isInsideAny(List<BoundingBox> bounds,int x,int y,int z){return !bounds.isEmpty();}
 static boolean isShipMaterial(BlockState state){return state.kind.equals("planks");}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 METHOD
 static int actualCarveGuard(ServerLevel world,int x,int y,int z) {
   OceanCanvasPlayerZones playerProtection=OceanCanvasPlayerZones.get(world);
   int writes=0;
   for(int once=0;once<1;once++) { GUARD writes++; }
   return writes;
 }
 public static void main(String[] args) {
   ServerLevel world=new ServerLevel();
   // Requested half-open range ends at z=500; these coordinates are in the
   // explicitly expanded 16-block border, including its final owned column.
   for(int z:new int[]{500,515}) {
     world.protectedCells.add("-475,79,"+z);
     for(String kind:new String[]{"water","lava","stone","dirt","planks"}) {
       check(isPhysicalAuditProtected(world,-475,79,z,new BlockState(kind),List.of()),"protected border material rejected: "+kind);
       check(actualCarveGuard(world,-475,79,z)==0,"protected border cell reached raw write");
     }
     world.protectedCells.clear();
     check(actualCarveGuard(world,-475,79,z)==1,"unprotected cell incorrectly exempted");
     world.structureProtected=true;
     for(String kind:new String[]{"water","lava"})
       check(!isPhysicalAuditProtected(world,-475,79,z,new BlockState(kind),List.of()),"unowned fluid silently exempted by structure region");
     world.structureProtected=false;
   }
   System.out.println("PASS: actual carve/audit guards preserve protected border terrain and fluids; unowned fluids remain auditable");
 }
}
'''.replace('METHOD', method).replace('GUARD', guard)

with tempfile.TemporaryDirectory(prefix='oc-border-protection-') as directory:
    root = Path(directory)
    (root / 'BorderProtectionRegression.java').write_text(fixture)
    subprocess.run(['javac', 'BorderProtectionRegression.java'], cwd=root, check=True)
    subprocess.run(['java', 'BorderProtectionRegression'], cwd=root, check=True)
