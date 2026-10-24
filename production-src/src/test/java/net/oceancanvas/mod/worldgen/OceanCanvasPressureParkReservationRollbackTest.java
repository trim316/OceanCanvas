package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1-257 regression: a newly-created pressure reservation must not survive failed pending retirement. */
final class OceanCanvasPressureParkReservationRollbackTest {
    @Test
    void failedPendingRetirementRollsBackOnlyTheReservationCreatedByThatAttempt() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java"));
        String anchor = "if (session.pendingTicks.remove(packed) == OceanCanvasPrimitiveLongIntMap.ABSENT) {";
        int start = source.indexOf(anchor);
        assertTrue(start >= 0, "generic pressure parking must retain the pending-retirement failure path");

        int end = source.indexOf("continue;", start);
        assertTrue(end > start, "failed pending retirement must terminate the current parking attempt");
        String failurePath = source.substring(start, end);

        String newlyReserved = "if (previous == OceanCanvasPrimitiveLongLongMap.ABSENT)";
        String rollback = "pressureParkUntilTick.remove(packed, due)";
        String requeue = "session.pendingWorkOrder.offer(packed)";
        assertTrue(failurePath.contains(newlyReserved),
                "rollback must be conditional on this attempt having created the reservation");
        assertTrue(failurePath.contains(rollback),
                "failed retirement must compare-and-remove the newly-created authoritative reservation");
        assertTrue(failurePath.contains(requeue),
                "failed retirement must return the chunk to pending work");
        assertTrue(failurePath.indexOf(newlyReserved) < failurePath.indexOf(rollback),
                "pre-existing reservations must never be removed by this failure path");
        assertTrue(failurePath.indexOf(rollback) < failurePath.indexOf(requeue),
                "unbacked authoritative pressure debt must be retired before the chunk is requeued");

        assertTrue(source.contains(
                        "if (previous == OceanCanvasPrimitiveLongLongMap.ABSENT) session.retryLedger.offerPressurePark(packed, due);"),
                "successful newly-created pressure reservations must still publish a wakeable scheduler node");
    }
}
