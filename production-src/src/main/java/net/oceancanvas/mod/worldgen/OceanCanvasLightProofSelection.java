package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;import java.util.function.IntPredicate;
/** One invocation snapshot only. Every later proof slice observes current scope again. */
final class OceanCanvasLightProofSelection {
 static long[] snapshot(BooleanSupplier retainedScope,IntPredicate insideCanvas,IntPredicate operationSelected){
  boolean retained=retainedScope.getAsBoolean();long[] bits=new long[4];
  for(int c=0;c<256;c++)if(insideCanvas.test(c)&&(!retained||operationSelected.test(c)))bits[c>>>6]|=1L<<(c&63);
  return bits;
 }
}
