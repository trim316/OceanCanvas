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
}
