package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasBlockProofAdmissionTest {
 @Test void coldBurstCannotCreateHundredsOfSimultaneousScansOutsideExistingBudget(){var a=new OceanCanvasBlockProofAdmission();int accepted=0;for(long key=0;key<229;key++)if(a.acquire(key,48))accepted++;assertEquals(48,accepted);assertEquals(48,a.size());}
 @Test void admittedSlicesKeepCreditWhenExistingCapacityDownshifts(){var a=new OceanCanvasBlockProofAdmission();assertTrue(a.acquire(1,2));assertTrue(a.acquire(2,2));assertTrue(a.acquire(1,1));assertFalse(a.acquire(3,1));a.release(1);assertFalse(a.acquire(3,1));a.release(2);assertTrue(a.acquire(3,1));}
 @Test void completionUnloadFailureAndColdSessionCannotLeakSlots(){var a=new OceanCanvasBlockProofAdmission();assertTrue(a.acquire(1,1));a.release(1);assertTrue(a.acquire(2,1));a.clear();assertEquals(0,a.size());assertTrue(a.acquire(3,1));assertEquals(0,new OceanCanvasBlockProofAdmission().size());}
 @Test void rejectedAdmissionCreatesNoEntryOrCompletionCredit(){var a=new OceanCanvasBlockProofAdmission();assertFalse(a.acquire(1,0));assertEquals(0,a.size());assertTrue(a.acquire(1,1));assertFalse(a.acquire(2,1));assertEquals(1,a.size());}
}
