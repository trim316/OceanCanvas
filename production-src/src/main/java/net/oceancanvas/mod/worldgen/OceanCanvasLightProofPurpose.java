package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;
/** Repair admission cannot grant a certificate. Publication always observes the full current proof. */
enum OceanCanvasLightProofPurpose {
 REPAIR_ADMISSION, PUBLICATION;
 /** A light-only entry must get a real clean-proof attempt before generating a native sweep. */
 static OceanCanvasLightProofPurpose initial(boolean physicalRepairAllowed) {
  return physicalRepairAllowed ? REPAIR_ADMISSION : PUBLICATION;
 }
 boolean afterReadyUpperPath(BooleanSupplier fullCurrentNativeFields) {
  return this==REPAIR_ADMISSION || fullCurrentNativeFields.getAsBoolean();
 }
}
