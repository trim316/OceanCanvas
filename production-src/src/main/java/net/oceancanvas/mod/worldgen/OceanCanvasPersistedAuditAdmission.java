package net.oceancanvas.mod.worldgen;
/** Admission only; never removes pending audit debt or grants proof authority. */
final class OceanCanvasPersistedAuditAdmission {
 private OceanCanvasPersistedAuditAdmission() {}
 static boolean canAttempt(double heapFraction,double existingHeavyPhaseBoundary) {
  return Double.isFinite(heapFraction) && Double.isFinite(existingHeavyPhaseBoundary)
    && heapFraction>=0.0D && existingHeavyPhaseBoundary>0.0D
    && existingHeavyPhaseBoundary<=1.0D && heapFraction<existingHeavyPhaseBoundary;
 }
}
