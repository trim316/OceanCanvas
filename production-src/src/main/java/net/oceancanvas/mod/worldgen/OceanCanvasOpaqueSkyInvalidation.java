package net.oceancanvas.mod.worldgen;
/** One native light recomputation request; never a successful source proof. */
final class OceanCanvasOpaqueSkyInvalidation {
 private OceanCanvasOpaqueSkyInvalidation() {}
 static boolean shouldQueue(int dampening,int sky,boolean alreadyQueued) {
  return !alreadyQueued && dampening==15 && sky>0 && sky<=15;
 }
}
