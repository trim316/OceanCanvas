package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-258 regression: every source path that can create authoritative pressure-park debt must
 * either publish a wakeable retry obligation on success or explicitly roll the reservation back
 * before returning the chunk to ordinary pending work.
 *
 * This is intentionally a source-contract test. The flattener owns the atomic relationship
 * between pressureParkUntilTick and the retry ledger; adding a new reservation site without
 * making its publication/rollback policy explicit must fail CI rather than silently strand debt.
 */
final class OceanCanvasPressureParkPublicationCoherenceTest {
    @Test
    void everyPressureReservationSiteDeclaresItsWakeOrRollbackPolicy() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java"));

        String reservation = "pressureParkUntilTick.putIfAbsent(";
        int cursor = 0;
        int sites = 0;
        while ((cursor = source.indexOf(reservation, cursor)) >= 0) {
            sites++;
            int windowEnd = Math.min(source.length(), cursor + 2200);
            String window = source.substring(cursor, windowEnd);

            assertTrue(window.contains("retryLedger.offerPressurePark("),
                    "pressure reservation site #" + sites
                            + " must publish a wakeable retry obligation on its success path");

            if (window.contains("pendingTicks.remove(")) {
                int failedRetirement = window.indexOf(
                        "pendingTicks.remove(packed) == OceanCanvasPrimitiveLongIntMap.ABSENT");
                if (failedRetirement >= 0) {
                    int requeue = window.indexOf("pendingWorkOrder.offer(packed)", failedRetirement);
                    assertTrue(requeue > failedRetirement,
                            "failed pending retirement must return the chunk to pending work");
                    String failurePath = window.substring(failedRetirement, requeue);
                    assertTrue(failurePath.contains("pressureParkUntilTick.remove(packed, due)"),
                            "a newly-created reservation must be compare-and-removed before pending requeue");
                }
            }
            cursor += reservation.length();
        }

        assertTrue(sites >= 3,
                "expected the current global-background, prewarm, and generic pressure reservation sites");
    }
}