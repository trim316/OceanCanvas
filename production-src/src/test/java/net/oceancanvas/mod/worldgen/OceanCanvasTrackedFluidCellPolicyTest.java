package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasTrackedFluidCellPolicyTest {
    @Test void trackedUnprotectedAboveSurfaceFluidIsRecoverableWithoutGeneralLease() {
        assertTrue(OceanCanvasTrackedFluidCellPolicy.permits(true,true,true,true,true,true,false));
    }
    @Test void everyAuthorityPrerequisiteIsIndependentlyRequired() {
        for (int missing=0;missing<6;missing++) {
            boolean[] facts={true,true,true,true,true,true};facts[missing]=false;
            assertFalse(OceanCanvasTrackedFluidCellPolicy.permits(facts[0],facts[1],facts[2],facts[3],facts[4],facts[5],false),"missing prerequisite "+missing);
        }
    }
    @Test void playerOrPreservedWholeStructureProtectionAlwaysWins() {
        assertFalse(OceanCanvasTrackedFluidCellPolicy.permits(true,true,true,true,true,true,true));
    }
    @Test void surfaceIceAndUndergroundFluidCannotAcquireThisAuthority() {
        assertFalse(OceanCanvasTrackedFluidCellPolicy.permits(true,true,true,true,false,true,false));
        assertFalse(OceanCanvasTrackedFluidCellPolicy.permits(true,true,true,true,true,false,false));
    }
}

