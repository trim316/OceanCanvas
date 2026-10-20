package net.oceancanvas.mod.worldgen;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
final class OceanCanvasSettleResidencyPolicyTest {
    @Test void onlyBoundedStageOneWithBothExistingProofBindingsRetains() {
        for (int delay=-1; delay<=12; delay++) for (int pass=0; pass<=3; pass++) {
            assertEquals(delay>=1 && delay<=4 && pass==1,
                    OceanCanvasSettleResidencyPolicy.retainOwnedStageOne(delay,pass,4,true,true));
        }
        assertFalse(OceanCanvasSettleResidencyPolicy.retainOwnedStageOne(Integer.MAX_VALUE,1,4,true,true));
    }
    @Test void MutationOrIncompletePhysicalProofNeverRetains() {
        assertFalse(OceanCanvasSettleResidencyPolicy.retainOwnedStageOne(4,1,4,false,true));
        assertFalse(OceanCanvasSettleResidencyPolicy.retainOwnedStageOne(4,1,4,true,false));
        assertFalse(OceanCanvasSettleResidencyPolicy.retainOwnedStageOne(4,1,4,false,false));
    }
    @Test void retainingOwnedTicketCannotCreateMoreCapacity() throws Throwable {
        var ledger=new OceanCanvasLightRelightResidencyLedger();
        ledger.install(1,0,1,p->{});
        assertTrue(OceanCanvasSettleResidencyPolicy.retainOwnedStageOne(4,1,4,true,true));
        assertEquals(OceanCanvasLightRelightResidencyLedger.InstallResult.CAP_REJECTED,
                ledger.install(2,0,1,p->fail("Cap bypass")));
        assertEquals(1,ledger.activeCount());
    }
}
