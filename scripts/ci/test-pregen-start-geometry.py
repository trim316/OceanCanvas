"""Exercise production clipping geometry used by native START telemetry."""
from pathlib import Path
import subprocess
import tempfile
source=(Path(__file__).resolve().parents[2]/"production-src/src/main/java/net/oceancanvas/mod/pregen/PregenManager.java").read_text()
a=source.index("private static Region clipRegion(")
b=source.index("/** Small, purely-computed result",a)
method=source[a:b]
assert "long actualWidthBlocks = (long) region.maxBlockX - region.minBlockX + 1L;" in source
assert "long actualHeightBlocks = (long) region.maxBlockZ - region.minBlockZ + 1L;" in source
assert "widthBlocks={} centerX={} centerZ={} heightBlocks={}" in source
assert "actualWidthBlocks, centerBlockX, centerBlockZ, actualHeightBlocks);" in source
header=r"""
public class PregenActualGeometryRegression {
 static final int MAX_RADIUS_BLOCKS=30000000;
 record OceanCanvasConfig(int centerX,int centerZ,int radius) {}
 record Region(String error,int minChunkX,int maxChunkX,int minChunkZ,int maxChunkZ,int minBlockX,int maxBlockX,int minBlockZ,int maxBlockZ,int clampedRadiusBlocks){
  static Region error(String text){return new Region(text,0,0,0,0,0,0,0,0,0);}
  static Region of(int a,int b,int c,int d,int e,int f,int g,int h,int i){return new Region(null,a,b,c,d,e,f,g,h,i);}
 }
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static long width(Region r){return (long)r.maxBlockX-r.minBlockX+1L;}
 static long height(Region r){return (long)r.maxBlockZ-r.minBlockZ+1L;}
"""
footer=r"""
 public static void main(String[] args){
 var config=new OceanCanvasConfig(0,0,10000);
 var small=clipRegion(config,0,0,250);check(width(small)==500&&height(small)==500,"500 geometry changed");
 var thousand=clipRegion(config,0,0,500);check(width(thousand)==1000&&height(thousand)==1000,"1k geometry changed");
 var fullBorder=clipRegion(config,0,0,10016);check(width(fullBorder)==20000&&height(fullBorder)==20000,"clipped20k width inflated");
 var edge=clipRegion(config,9990,0,80);check(width(edge)==90&&height(edge)==160,"asymmetric clip lost actualheight");
 var outside=clipRegion(config,20000,0,80);check(outside.error!=null,"outside operation accepted");
 var invalid=clipRegion(config,0,0,0);check(invalid.error!=null,"zero radius accepted");
 System.out.println("PASS: native actual authored dimensions match clipping; unchanged500/1k and invalid requests reject");
 }
}
"""
with tempfile.TemporaryDirectory(prefix="oc-actual-geometry-") as tmp:
 folder=Path(tmp);(folder/"PregenActualGeometryRegression.java").write_text(header+method+footer)
 subprocess.run(["javac","PregenActualGeometryRegression.java"],cwd=folder,check=True)
 subprocess.run(["java","PregenActualGeometryRegression"],cwd=folder,check=True)
