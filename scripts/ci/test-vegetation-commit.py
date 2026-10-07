"""Inject decoration failure into the actual production commit bridge."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
bridge = root / 'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasActiveTerrainOperationBridge.java'
sources = {
    'net/minecraft/server/level/ServerLevel.java': 'package net.minecraft.server.level; public class ServerLevel {}',
    'net/minecraft/world/level/ChunkPos.java': 'package net.minecraft.world.level; public record ChunkPos(int x,int z) {}',
    'net/minecraft/world/level/levelgen/structure/BoundingBox.java': 'package net.minecraft.world.level.levelgen.structure; public class BoundingBox {}',
    'net/oceancanvas/mod/worldgen/OceanCanvasActiveTerrainOperationBridge.java': bridge.read_text(),
    'net/oceancanvas/mod/worldgen/OceanCanvasOceanVegetation.java': '''package net.oceancanvas.mod.worldgen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
public class OceanCanvasOceanVegetation {
 public static boolean success;
 public static boolean decorateCommittedChunk(ServerLevel world, ChunkPos pos) { return success; }
}''',
    'VegetationCommitTest.java': '''
import java.lang.reflect.Proxy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.worldgen.*;
public class VegetationCommitTest {
 public static void main(String[] args) {
  int[] commits = {0};
  var controller = (OceanCanvasActiveTerrainOperationBridge.Controller) Proxy.newProxyInstance(
    VegetationCommitTest.class.getClassLoader(),
    new Class<?>[]{OceanCanvasActiveTerrainOperationBridge.Controller.class},
    (proxy, method, values) -> {
     if (method.getName().equals("authoritativeCommit")) commits[0]++;
     return method.getReturnType() == boolean.class ? false : null;
    });
  OceanCanvasActiveTerrainOperationBridge.install(controller);
  var world = new ServerLevel(); var pos = new ChunkPos(-29,-32);
  for (int i=0;i<3;i++) {
   if (OceanCanvasActiveTerrainOperationBridge.authoritativeCommit(world,pos))
    throw new AssertionError("failed decoration reported success");
  }
  if (commits[0] != 0) throw new AssertionError("failed decoration committed");
  OceanCanvasOceanVegetation.success=true;
  if (!OceanCanvasActiveTerrainOperationBridge.authoritativeCommit(world,pos) || commits[0]!=1)
   throw new AssertionError("successful retry did not commit exactly once");
  System.out.println("PASS: repeated decoration failure withholds commit; successful retry commits once");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='oc-vegetation-commit-') as directory:
    tmp = Path(directory)
    files = []
    for name, text in sources.items():
        p = tmp / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        files.append(str(p))
    subprocess.run(['javac', '-d', str(tmp), *files], check=True)
    subprocess.run(['java', '-cp', str(tmp), 'VegetationCommitTest'], check=True)
