"""Execute the production ordinary-load branch without resetting existing proof debt."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[2] / 'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
start = source.index('\t\t\tif (!ownedByPregen && !forcedRegeneration) {')
end = source.index('\n\t\t\t// Migration safety', start)
branch = source[start:end]
branch = branch.replace('net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).get(chunk.getPos())', 'intent')
branch = branch.replace('net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.CANVAS', 'State.CANVAS')
branch = branch.replace('net.oceancanvas.mod.OceanCanvas.VERSION', '"test"')
branch = branch.replace('chunk.getPos().x()', '0').replace('chunk.getPos().z()', '0')
start = source.index('\tpublic static boolean hasPendingLightFinalization(long packed) {')
end = source.index('\n\t}', start) + len('\n\t}')
membership = source[start:end]
header = r'''
import java.util.*;
class LoadLightRegression {
 enum State {CANVAS, VANILLA}
 static class World {}
 record Chunk(long packed) {Long getPos(){return packed;}}
 static class ProtectedData {boolean physical=true, lighting=false; boolean isChunkProcessedPhysicallyVerified(Long p){return physical;}boolean isChunkLightingVerified(Long p){return lighting;}}
 static class Finalizer {Map<Long,Integer> pendingTicks=new HashMap<>();}
 static class Recovery {Map<Long,Long> skyBackoffUntilTick=new HashMap<>(),pressureParkUntilTick=new HashMap<>();}
 static class Pregen {Set<Long> QUEUED_CHUNK_POSITIONS=new HashSet<>();}
 static class Log {void debug(String s,Object... a){}}
 static class OceanCanvas {static Log LOGGER=new Log();}
 static Finalizer fin;static Recovery recovery;static Pregen preg;static State intent;static ProtectedData protectedData;static int arms;
 static Finalizer lightFinalizerSession(){return fin;}static Recovery lightRecoverySession(){return recovery;}static Pregen pregenSession(){return preg;}
 static void scheduleLightSync(World w,Long p,boolean physical){if(physical)throw new AssertionError("reload gained physical authority");arms++;fin.pendingTicks.put(p,4);}
 static void setup(){fin=new Finalizer();recovery=new Recovery();preg=new Pregen();intent=State.CANVAS;protectedData=new ProtectedData();arms=0;preg.QUEUED_CHUNK_POSITIONS.add(7L);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void load(){World world=new World();Chunk chunk=new Chunk(7);long packedChunkPos=7;boolean ownedByPregen=false,forcedRegeneration=false;do {
'''
footer = r'''
 }while(false);check(preg.QUEUED_CHUNK_POSITIONS.isEmpty(),"transport notification not retired");}
 public static void main(String[] args){
  setup();fin.pendingTicks.put(7L,16);load();check(arms==0&&fin.pendingTicks.get(7L)==16,"settling proof was reset by reload");
  setup();fin.pendingTicks.put(7L,0);load();check(arms==0&&fin.pendingTicks.get(7L)==0,"due proof was restarted by reload");
  setup();recovery.skyBackoffUntilTick.put(7L,900L);load();check(arms==0&&recovery.skyBackoffUntilTick.get(7L)==900L,"strict backoff was bypassed by reload");
  setup();recovery.pressureParkUntilTick.put(7L,900L);load();check(arms==0&&recovery.pressureParkUntilTick.get(7L)==900L,"pressure debt was duplicated by reload");
  setup();load();check(arms==1&&fin.pendingTicks.get(7L)==4&&!protectedData.lighting,"missing proof was not repaired or was falsely certified");
  setup();protectedData.lighting=true;load();check(arms==0,"certified chunk was dirtied");
  setup();protectedData.physical=false;load();check(arms==0,"unproven terrain gained lighting repair");
  setup();intent=State.VANILLA;load();check(arms==0,"vanilla terrain gained canvas work");
  System.out.println("PASS: ordinary reload preserves active/due/backoff/pressure proof debt; missing proof still arms light-only repair");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='oc-load-light-') as tmp:
    folder = Path(tmp)
    # Membership belongs inside the fixture class, before main's closing class brace.
    text = header + branch + footer
    text = text.rsplit('\n}', 1)[0] + '\n' + membership + '\n}\n'
    (folder / 'LoadLightRegression.java').write_text(text, encoding='utf-8')
    subprocess.run(['javac', 'LoadLightRegression.java'], cwd=folder, check=True)
    subprocess.run(['java', 'LoadLightRegression'], cwd=folder, check=True)
