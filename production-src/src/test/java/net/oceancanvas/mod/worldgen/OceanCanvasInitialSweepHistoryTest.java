package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OceanCanvasInitialSweepHistoryTest {
    @Test void firstSweepAndReplacementChunkCannotReuseHistory() {
        var history = new OceanCanvasInitialSweepHistory<Object>();
        var original = new Object(); var replacement = new Object();
        assertFalse(history.canReuse(17, original, true, true));
        assertSame(OceanCanvasLightProofPurpose.REPAIR_ADMISSION,
                OceanCanvasLightProofPurpose.initial(true, history.matches(17, original)));
        history.completed(17, original);
        assertTrue(history.canReuse(17, original, true, true));
        assertFalse(history.canReuse(17, replacement, true, true));
        assertFalse(history.canReuse(18, original, true, true));
        assertSame(OceanCanvasLightProofPurpose.REPAIR_ADMISSION,
                OceanCanvasLightProofPurpose.initial(true, history.matches(17, replacement)));
    }

    @Test void completedSubmissionNeverSuppliesPhysicalOrChangedEpochPublicationCredit() {
        var history = new OceanCanvasInitialSweepHistory<Object>(); var chunk = new Object();
        history.completed(17, chunk);
        var purpose = OceanCanvasLightProofPurpose.initial(true, history.matches(17, chunk));
        assertSame(OceanCanvasLightProofPurpose.PUBLICATION, purpose);
        assertFalse(purpose.afterReadyUpperPath(() -> false));
        assertFalse(history.canReuse(17, chunk, false, true));
        assertFalse(history.canReuse(17, chunk, true, false));
        assertTrue(history.canReuse(17, chunk, true, true));
        // A later real mutation discards both current proofs, but not submission history.
        assertFalse(history.canReuse(17, chunk, false, false));
        assertTrue(history.matches(17, chunk));
        assertFalse(purpose.afterReadyUpperPath(() -> false));
    }

    @Test void retirementRequiresAnotherInitialSweepEvenForSameChunk() {
        var history = new OceanCanvasInitialSweepHistory<Object>(); var chunk = new Object();
        history.completed(17, chunk); history.retire(17);
        assertFalse(history.matches(17, chunk));
        assertFalse(history.canReuse(17, chunk, true, true));
    }
}
