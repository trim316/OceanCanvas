package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;
/** Repair admission cannot grant a certificate. Publication always observes the full current proof. */
enum OceanCanvasLightProofPurpose {
 REPAIR_ADMISSION, PUBLICATION;
 boolean afterReadyUpperPath(BooleanSupplier fullCurrentNativeFields) {
  return this==REPAIR_ADMISSION || fullCurrentNativeFields.getAsBoolean();
 }
}
