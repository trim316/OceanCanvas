"""Compile the actual cooperative audit predicates and canonical-state helper."""
from pathlib import Path
import re
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[2] / "production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java").read_text()
a = source.index("private static PhysicalAuditAdvance advancePhysicalProfileAudit(")
b = source.index("private static PhysicalProfileMismatch firstPhysicalProfileMismatch(", a)
method = source[a:b]
transition = re.search(r"if \(([^\n]*!bs.is\(Blocks.STONE\)[^\n]*)\) \{", method).group(1)
water = re.search(r"if \(([^;]*?isCanonicalCanvasWaterAuditState\(bs, y, waterTop\)[^;]*?)\) \{\s*stateMap.remove", method).group(1).strip()
start = source.index("private static PhysicalProfileMismatch firstPhysicalProfileMismatch(")
end = source.index("private static void repairCanonicalCanvasProfile(", start)
monolithic = source[start:end]
mono_transition = re.search(r"if \((!state.is\(Blocks.STONE\)[^;]*?)\) \{", monolithic).group(1)
mono_water = re.search(r"if \((!isCanonicalCanvasWaterAuditState\(state, y, waterTop\)[^;]*?)\) \{", monolithic).group(1)
def adapt(expression):
    expression = re.sub(r"\s+", " ", expression).strip()
    expression = re.sub(r"\bstate\b", "bs", expression)
    return expression.replace("world, x, y, z, bs, preservedWholeBounds", "world, state.x, y, state.z, bs, state.preservedWholeBounds")
a = source.index("private static boolean isCanonicalCanvasWaterState(")
b = source.index("private static boolean isPhysicalAuditProtected(", a)
canonical = source[a:b]
# This fixture exercises acceptance predicates; fingerprint policy has its own Java test.
canonical = re.sub(r"static int physicalAuditFingerprintStateHash\(.*?\n\t\}", "", canonical, flags=re.S)
header = r"""
import java.util.*;
public class PhysicalAuditFastpathRegression {
 static class Block {String name;BlockState canonical;Block(String n){name=n;}BlockState defaultBlockState(){if(canonical==null)canonical=new BlockState(this,true);return canonical;}}
 static class Blocks {static final Block ICE=new Block("ice"),WATER=new Block("water"),STONE=new Block("stone"),LAVA=new Block("lava"),SEAGRASS=new Block("grass"),TALL_SEAGRASS=new Block("tall"),KELP=new Block("kelp"),KELP_PLANT=new Block("kelpplant");}
 record Fluid(boolean source) {boolean isSource(){return source;}}
 static class BlockState {Block kind;boolean source;int fluidReads;BlockState(Block k,boolean s){kind=k;source=s;}boolean is(Block block){return kind==block;}Fluid getFluidState(){fluidReads++;return new Fluid(source);}}
 static class ServerLevel {boolean protectedCell;int checks;}
 static class State {int x,y,z;List<Object> preservedWholeBounds=List.of();}
 static boolean isPhysicalAuditProtected(ServerLevel world,int x,int y,int z,BlockState block,List<Object> bounds){world.checks++;return world.protectedCell;}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
"""
footer = r"""
 public static void main(String[] args){
 Block[] kinds={Blocks.STONE,Blocks.WATER,Blocks.LAVA,Blocks.SEAGRASS,Blocks.TALL_SEAGRASS,Blocks.KELP,Blocks.KELP_PLANT,Blocks.ICE,new Block("glass"),new Block("air")};
 int cases=0;
 for(Block kind:kinds)for(boolean source:new boolean[]{false,true})for(boolean protectedCell:new boolean[]{false,true})for(int y:new int[]{0,-1}){
  ServerLevel w=new ServerLevel();w.protectedCell=protectedCell;BlockState block=new BlockState(kind,source);
  boolean priorCanonical=(block.is(Blocks.WATER)&&source)||block.is(Blocks.SEAGRASS)||block.is(Blocks.TALL_SEAGRASS)||block.is(Blocks.KELP)||block.is(Blocks.KELP_PLANT);
  check(isCanonicalCanvasWaterState(block)==priorCanonical,"canonical predicate changed");
  boolean expectedTransition=!protectedCell&&!block.is(Blocks.STONE);
  boolean expectedWater=!protectedCell&&!(isCanonicalCanvasWaterState(block)||(y==0&&block.is(Blocks.ICE)));
  check(transition(w,block)==expectedTransition,"transition acceptance changed: "+block);
  check(w.checks==(block.is(Blocks.STONE)?0:1),"healthy stone paid protection lookup");
  w.checks=0;check(water(w,block,y)==expectedWater,"water acceptance changed: "+block);
  check(w.checks==((isCanonicalCanvasWaterState(block)||(y==0&&block.is(Blocks.ICE)))?0:1),"healthy water/vegetation paid protection lookup");cases++;
  w.checks=0;check(monoTransition(w,block)==expectedTransition,"monolithic transition acceptance changed");
  check(w.checks==(block.is(Blocks.STONE)?0:1),"monolithic healthy stone paid protection lookup");
  w.checks=0;check(monoWater(w,block,y)==expectedWater,"monolithic water acceptance changed");
  check(w.checks==((isCanonicalCanvasWaterState(block)||(y==0&&block.is(Blocks.ICE)))?0:1),"monolithic healthy water paid protection lookup");
 }
 BlockState canonical=Blocks.WATER.defaultBlockState();
 check(isCanonicalCanvasWaterState(canonical),"default source water rejected");
 check(canonical.fluidReads==0,"default source water paid fluid lookup");
 check(!isCanonicalCanvasWaterState(new BlockState(Blocks.WATER,false)),"flowing water accepted");
 System.out.println("PASS: "+cases+" state/protection combinations preserve acceptance and skip healthy-cell protection lookups");
 }
}
"""
methods = "static boolean transition(ServerLevel world,BlockState bs){State state=new State();int y=0;return " + transition + ";}\n"
methods += "static boolean water(ServerLevel world,BlockState bs,int y){State state=new State();int waterTop=0;return " + water + ";}\n"
methods += "static boolean monoTransition(ServerLevel world,BlockState bs){State state=new State();int y=0;return " + adapt(mono_transition) + ";}\n"
methods += "static boolean monoWater(ServerLevel world,BlockState bs,int y){State state=new State();int waterTop=0;return " + adapt(mono_water) + ";}\n"
with tempfile.TemporaryDirectory(prefix="oc-physical-audit-") as tmp:
 root=Path(tmp)
 (root/"PhysicalAuditFastpathRegression.java").write_text(header+canonical+methods+footer)
 subprocess.run(["javac","PhysicalAuditFastpathRegression.java"],cwd=root,check=True)
 subprocess.run(["java","PhysicalAuditFastpathRegression"],cwd=root,check=True)
