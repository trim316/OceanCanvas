package net.oceancanvas.mod.worldgen;

public final class OceanCanvasPressureTailDiagnosticLineTest {
    private OceanCanvasPressureTailDiagnosticLineTest() {}

    public static void main(String[] args) {
        long terminal = pack(-23, -23);
        var authoritative = new OceanCanvasPrimitiveLongLongMap();
        var retry = new OceanCanvasLightRetryLedger();

        assertEquals("pressureTail=none",
                OceanCanvasPressureTailDiagnosticLine.format(100L, authoritative, retry),
                "empty authority must report no tail");

        authoritative.put(terminal, 80L);
        retry.offerPressurePark(terminal, 80L);
        retry.offerPressurePark(terminal, 80L);
        String line = OceanCanvasPressureTailDiagnosticLine.format(100L, authoritative, retry);
        assertContains(line, "pressureTail=-23,-23", "terminal key");
        assertContains(line, "state=AUTHORITATIVE_OVERDUE", "authoritative state");
        assertContains(line, "dueTick=80", "deadline");
        assertContains(line, "overdueTicks=20", "overdue age");
        assertContains(line, "schedulerNodes=2", "per-key scheduler identity");
        assertContains(line, "duplicateNodes=true", "duplicate-node attribution");

        // Scheduler identity is diagnostic only: removing authority must make the
        // aggregate report no authoritative tail even while stale heap nodes exist.
        authoritative.remove(terminal);
        assertEquals("pressureTail=none",
                OceanCanvasPressureTailDiagnosticLine.format(100L, authoritative, retry),
                "stale scheduler nodes must not manufacture authority");

        System.out.println("OceanCanvasPressureTailDiagnosticLineTest PASS");
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static void assertContains(String actual, String expected, String label) {
        if (!actual.contains(expected)) {
            throw new AssertionError(label + ": expected <" + expected + "> in <" + actual + ">");
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
