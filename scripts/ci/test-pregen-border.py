"""Compile the production border geometry and check exact optional command wiring."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[2]
base=root/"production-src/src/main/java/net/oceancanvas/mod/command"
helper=(base/"PregenBorderFootprint.java").read_text()
command=(base/"PregenCommand.java").read_text()
assert command.count('Commands.literal("border")') == 4, "border start/dryrun paths missing"
assert "confirmed, false" in command, "default commands must preserve original radius"
assert "border ? footprint.operationRadius() : radiusBlocks" in command, "expanded operation not routed explicitly"
assert 'PREGEN-BORDER-REQUEST requestedWidthBlocks={}' in command, "requested size telemetry missing"
assert 'centerXArg(ctx), centerZArg(ctx), true, true)' in command, "explicit-center confirmed border missing"
fixture=r"""
import net.oceancanvas.mod.command.PregenBorderFootprint;
public class PregenBorderRegression {
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 public static void main(String[] args){
 var f=PregenBorderFootprint.plan(64,-464,436,0,0,10000);
 check(f.requestedRadius()==64&&f.operationRadius()==80,"border applied more than once");
 check(f.width()==160&&f.height()==160&&f.chunks()==110,"frontier expanded geometry/count wrong");
 check(f.minX()==-544&&f.maxX()==-385&&f.minZ()==356&&f.maxZ()==515,"frontier bounds wrong");
 check(f.minZ()<=500&&f.maxZ()>=500,"natural inflow source excluded from border");
 check(f.maxZ()<516,"operation widened beyond border");
 var centered=PregenBorderFootprint.plan(250,0,0,0,0,10000);
 check(centered.width()==532&&centered.chunks()==1156,"500 inner border geometry wrong");
 var edge=PregenBorderFootprint.plan(64,9990,0,0,0,10000);
 check(edge.maxX()==9999&&edge.minX()==9910&&edge.width()==90&&edge.height()==160,"global canvas clip wrong");
 var full=PregenBorderFootprint.plan(10000,0,0,0,0,10000);
 check(full.width()==20000&&full.height()==20000,"full global canvas expanded outside authorized configuration");
 var capped=PregenBorderFootprint.plan(Integer.MAX_VALUE,0,0,0,0,10000);
 check(capped.operationRadius()==30000000&&capped.width()==20000,"radius overflow or cap mismatch");
 var outside=PregenBorderFootprint.plan(64,20000,0,0,0,10000);
 check(outside.width()==0&&outside.chunks()==0,"empty clipped footprint claimed targets");
 check(f.description().contains("128 x 128")&&f.description().contains("160 x 160")&&f.description().contains("player-protection"),"footprint disclosure incomplete");
 System.out.println("PASS: optional border exact geometry, clipping, chunk counts, overflow and request disclosure");
 }
}
"""
with tempfile.TemporaryDirectory(prefix="oc-border-") as tmp:
 folder=Path(tmp);(folder/"PregenBorderFootprint.java").write_text(helper);(folder/"PregenBorderRegression.java").write_text(fixture)
 subprocess.run(["javac","-d",".","PregenBorderFootprint.java","PregenBorderRegression.java"],cwd=folder,check=True)
 subprocess.run(["java","PregenBorderRegression"],cwd=folder,check=True)
