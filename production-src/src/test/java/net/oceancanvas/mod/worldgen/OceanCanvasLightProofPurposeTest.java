package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
class OceanCanvasLightProofPurposeTest {
 @Test void repairCanAdvanceWithoutRepeatedlyScanningFieldsItsOwnNativeWritesInvalidate() {
  for(int slice=0;slice<3;slice++) {
   assertTrue(OceanCanvasLightProofPurpose.REPAIR_ADMISSION.afterReadyUpperPath(()->{throw new AssertionError("Repair admission must not drain a pre-repair publication proof");}));
  }
 }
 @Test void admissionNeverSuppliesPublicationCreditAndEpochInvalidationStillRefusesPublication() {
  AtomicBoolean fullCurrentFields=new AtomicBoolean(false);
  assertTrue(OceanCanvasLightProofPurpose.REPAIR_ADMISSION.afterReadyUpperPath(fullCurrentFields::get));
  assertFalse(OceanCanvasLightProofPurpose.PUBLICATION.afterReadyUpperPath(fullCurrentFields::get));
  fullCurrentFields.set(true);assertTrue(OceanCanvasLightProofPurpose.PUBLICATION.afterReadyUpperPath(fullCurrentFields::get));
  fullCurrentFields.set(false);assertFalse(OceanCanvasLightProofPurpose.PUBLICATION.afterReadyUpperPath(fullCurrentFields::get));
 }
 @Test void lightOnlyInitialProofWaitsForCurrentFieldsInsteadOfManufacturingSweepPublications() {
  AtomicBoolean fields=new AtomicBoolean(false);
  var purpose=OceanCanvasLightProofPurpose.initial(false);
  assertSame(OceanCanvasLightProofPurpose.PUBLICATION,purpose);
  assertFalse(purpose.afterReadyUpperPath(fields::get));
  fields.set(true);assertTrue(purpose.afterReadyUpperPath(fields::get));
  fields.set(false);assertFalse(purpose.afterReadyUpperPath(fields::get));
 }
 @Test void physicalAwareInitialAdmissionRetainsBoundedRepairWithoutPreRepairFieldDrain() {
  var purpose=OceanCanvasLightProofPurpose.initial(true);
  assertSame(OceanCanvasLightProofPurpose.REPAIR_ADMISSION,purpose);
  assertTrue(purpose.afterReadyUpperPath(()->{throw new AssertionError("Do not drain proof before physical-aware repair");}));
  assertFalse(OceanCanvasLightProofPurpose.PUBLICATION.afterReadyUpperPath(()->false));
 }
 @Test void sameChunkLiveSweepContinuesWithoutDrainingItsOwnPublicationProof() {
  var purpose=OceanCanvasLightProofPurpose.initial(false,false,true);
  assertSame(OceanCanvasLightProofPurpose.REPAIR_ADMISSION,purpose);
  for(int slice=0;slice<3;slice++)
   assertTrue(purpose.afterReadyUpperPath(()->{throw new AssertionError("Native sweep continuation must precede publication reproof");}));
  assertFalse(OceanCanvasLightProofPurpose.PUBLICATION.afterReadyUpperPath(()->false));
 }
 @Test void missingOrDifferentChunkSweepStillRequiresFirstLightOnlyPublicationProof() {
  var purpose=OceanCanvasLightProofPurpose.initial(false,false,false);
  assertSame(OceanCanvasLightProofPurpose.PUBLICATION,purpose);
  assertFalse(purpose.afterReadyUpperPath(()->false));
 }
 @Test void completedHistoryAlwaysRequiresCurrentPublicationProof() {
  for(boolean physicalAllowed:new boolean[]{false,true})
   for(boolean liveSweep:new boolean[]{false,true}) {
    var purpose=OceanCanvasLightProofPurpose.initial(physicalAllowed,true,liveSweep);
    assertSame(OceanCanvasLightProofPurpose.PUBLICATION,purpose);
    assertFalse(purpose.afterReadyUpperPath(()->false));
    assertTrue(purpose.afterReadyUpperPath(()->true));
   }
 }
}
