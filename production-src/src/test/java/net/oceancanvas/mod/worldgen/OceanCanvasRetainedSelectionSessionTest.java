package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasRetainedSelectionSessionTest {
    @Test void retirementMembershipDoesNotGrantOrEraseRetainedSelection() {
        var finalizer = new OceanCanvasLightFinalizerSession();
        var pregen = new OceanCanvasPregenFlattenerSession(1,1);
        long key=7L;
        pregen.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.add(key);
        finalizer.postJobLightOnlySelectionScope.add(key);
        pregen.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(key);
        assertTrue(finalizer.postJobLightOnlySelectionScope.contains(key));
        assertFalse(finalizer.allowPhysicalRepair.contains(key));
        assertFalse(finalizer.postJobPhysicalRepairAuthority.contains(key));
        finalizer.postJobLightOnlySelectionScope.clear();
        assertFalse(finalizer.postJobLightOnlySelectionScope.contains(key));
    }
}
