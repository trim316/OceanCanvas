"""Execute the actual optimized scans with protected-block and position fixtures."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[2] / 'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
a = source.index('private static FluidSettleRepair repairUnexpectedFluidReactionProducts')
b = source.index('/** v253.72.3 narrowly-scoped repair result', a)
methods = source[a:b]
header = '''import java.util.*;
public class FluidScanRegression {
 static class BlockPos {int x,y,z;BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;} public String toString(){return x+","+y+","+z;} BlockPos immutable(){return new BlockPos(x,y,z);} static class MutableBlockPos extends BlockPos {MutableBlockPos(){super(0,0,0);} MutableBlockPos set(int x,int y,int z){this.x=x;this.y=y;this.z=z;return this;}}}
 static class Blocks {static final String WATER="water",STONE="stone",COBBLESTONE="cobble",OBSIDIAN="obsidian";}
 static class BlockState {String kind;BlockState(String k){kind=k;} boolean is(String b){return kind.equals(b);}}
 static class Water {BlockState defaultBlockState(){return new BlockState("water");}}
 static class OceanCanvasConfig {static final int WATER_SURFACE_Y=3; int oceanFloorY(){return 0;}int oceanFloorVariation(){return 0;}}
 static class ChunkPos {int getMinBlockX(){return 0;}int getMinBlockZ(){return 0;}}
 static class LevelChunk {Map<String,BlockState> blocks=new HashMap<>();ChunkPos getPos(){return new ChunkPos();}BlockState getBlockState(BlockPos p){return blocks.getOrDefault(p.toString(),new BlockState("water"));}}
 static class OceanCanvasPlayerZones {Set<String> protectedCells=new HashSet<>();int checks;static int gets;static OceanCanvasPlayerZones get(ServerLevel w){gets++;return w.zones;}boolean isProtected(int x,int y,int z){checks++;return protectedCells.contains(x+","+y+","+z);}}
 static class Light {List<BlockPos> checked=new ArrayList<>();void checkBlock(BlockPos p){checked.add(p);}}
 static class ChunkSource {Light light=new Light();Light getLightEngine(){return light;}}
 static class ServerLevel {OceanCanvasPlayerZones zones=new OceanCanvasPlayerZones();ChunkSource source=new ChunkSource();void removeBlockEntity(BlockPos p){}void sendBlockUpdated(BlockPos p,BlockState a,BlockState b,int n){}ChunkSource getChunkSource(){return source;}}
 record FluidSettleRepair(int repaired,BlockPos first,BlockState old) {}
 static int floorOffset(int x,int z,int v){return 0;}static boolean selected=true;
 static boolean strictCanvasColumnSelected(LevelChunk c,OceanCanvasConfig cfg,int x,int z){return selected;}
 static void setBlockStateRawSafe(ServerLevel w,LevelChunk c,BlockPos p,BlockState s){c.blocks.put(p.toString(),s);}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
'''.replace('static final String WATER="water",STONE=', 'static final Water WATER=new Water();static final String STONE=')
# Minecraft's water constant is a block object; all is() comparisons here are
# against reaction products, so the fixture only needs its default-state API.
footer = '''
 public static void main(String[] args){
 ServerLevel w=new ServerLevel();LevelChunk c=new LevelChunk();OceanCanvasConfig cfg=new OceanCanvasConfig();
 OceanCanvasPlayerZones.gets=0;check(repairUnexpectedFluidReactionProducts(w,c,cfg).repaired()==0,"empty ocean changed");check(OceanCanvasPlayerZones.gets==1&&w.zones.checks==0,"healthy cells paid protection lookup");
 c.blocks.put("0,1,0",new BlockState("stone"));w.zones.protectedCells.add("0,1,0");check(repairUnexpectedFluidReactionProducts(w,c,cfg).repaired()==0,"protected solid changed");check(firstUnexpectedFluidReactionProduct(w,c,cfg)==null,"protected solid reported");
 w.zones.protectedCells.clear();c.blocks.put("0,2,0",new BlockState("obsidian"));check(firstUnexpectedFluidReactionProduct(w,c,cfg).toString().equals("0,1,0"),"wrong first product");
 FluidSettleRepair r=repairUnexpectedFluidReactionProducts(w,c,cfg);check(r.repaired()==2,"products not repaired");check(r.first().toString().equals("0,1,0"),"first position mutated");check(w.source.light.checked.get(0).toString().equals("0,1,0")&&w.source.light.checked.get(1).toString().equals("0,2,0"),"mutable position escaped into light queue");
 selected=false;c.blocks.put("0,1,0",new BlockState("stone"));check(repairUnexpectedFluidReactionProducts(w,c,cfg).repaired()==0,"unselected column changed");
 System.out.println("PASS: healthy fast path, protected blocks, selected scope and immutable light positions");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='oc-fluid-scan-') as tmp:
    root = Path(tmp)
    (root/'FluidScanRegression.java').write_text(header+methods+footer)
    subprocess.run(['javac','FluidScanRegression.java'],cwd=root,check=True)
    subprocess.run(['java','FluidScanRegression'],cwd=root,check=True)
