package net.oceancanvas.mod.worldgen;
/** Keep an already-owned ticket across a one-tick proof slice, never across genuine backoff.
 * This grants no ticket, physical repair authority, certificate or deadline credit. */
final class OceanCanvasCooperativeProofResidency {
 private OceanCanvasCooperativeProofResidency() {}
 static boolean retain(int delay, boolean existingCooperative, boolean upperSkyProof, boolean nativeFieldsProof) {
  return delay==1 && (existingCooperative || upperSkyProof || nativeFieldsProof);
 }
}
