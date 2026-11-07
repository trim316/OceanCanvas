package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;
/** Repair admission cannot grant a certificate. Publication always observes the full current proof. */
enum OceanCanvasLightProofPurpose {
 REPAIR_ADMISSION, PUBLICATION;
 /** A light-only entry must get a real clean-proof attempt before generating a native sweep. */
 static OceanCanvasLightProofPurpose initial(boolean physicalRepairAllowed) {
  return initial(physicalRepairAllowed, false);
 }
 static OceanCanvasLightProofPurpose initial(boolean physicalRepairAllowed, boolean completedInitialSweep) {
  return initial(physicalRepairAllowed, completedInitialSweep, false);
 }
 /** An already submitted same-chunk sweep must finish before its publication proof.
  * Native writes from each slice invalidate that proof. This grants no completion credit. */
 static OceanCanvasLightProofPurpose initial(boolean physicalRepairAllowed, boolean completedInitialSweep,
         boolean sameChunkSweepInFlight) {
  return !completedInitialSweep && (physicalRepairAllowed || sameChunkSweepInFlight)
          ? REPAIR_ADMISSION : PUBLICATION;
 }
 boolean afterReadyUpperPath(BooleanSupplier fullCurrentNativeFields) {
  return this==REPAIR_ADMISSION || fullCurrentNativeFields.getAsBoolean();
 }
}
