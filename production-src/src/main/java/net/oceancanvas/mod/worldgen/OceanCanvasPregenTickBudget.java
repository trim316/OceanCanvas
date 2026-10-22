package net.oceancanvas.mod.worldgen;
/** Queue-attempt budget, independent of whether a chunk completed. No work/proof is retired here. */
final class OceanCanvasPregenTickBudget {
 private OceanCanvasPregenTickBudget() {}
 static boolean shouldYield(boolean pregenActive,int attemptedEntries,long elapsedNs,long budgetNs){
  return pregenActive && attemptedEntries>0 && elapsedNs>=budgetNs;
 }
}
