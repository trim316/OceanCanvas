package net.oceancanvas.mod.diagnostic;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Small in-memory flight recorder for Ocean Canvas failures that are intentionally contained.
 *
 * <p>Optional compatibility and client/render fallbacks must fail soft, but "fail soft" must not
 * mean "fail silently". This recorder keeps the most recent incidents bounded in memory so support
 * bundles can show what degraded even when normal gameplay continued.</p>
 */
public final class OceanCanvasIncidentRecorder {
    private static final int MAX_INCIDENTS = 256;
    private static final ArrayDeque<Incident> INCIDENTS = new ArrayDeque<>(MAX_INCIDENTS);

    public record Incident(long epochMillis, String component, String summary, String exceptionType) { }

    private OceanCanvasIncidentRecorder() { }

    public static synchronized void record(String component, String summary, Throwable failure) {
        String type = failure == null ? "" : failure.getClass().getName();
        record(component, summary, type);
    }

    public static synchronized void record(String component, String summary, String exceptionType) {
        while (INCIDENTS.size() >= MAX_INCIDENTS) INCIDENTS.removeFirst();
        INCIDENTS.addLast(new Incident(System.currentTimeMillis(), clean(component, 96), clean(summary, 512), clean(exceptionType, 160)));
    }

    public static synchronized List<Incident> snapshot() {
        return List.copyOf(new ArrayList<>(INCIDENTS));
    }

    public static synchronized void clearSession() {
        INCIDENTS.clear();
    }

    public static String text() {
        StringBuilder out = new StringBuilder();
        for (Incident i : snapshot()) {
            out.append(Instant.ofEpochMilli(i.epochMillis())).append('\t')
                    .append(i.component()).append('\t').append(i.exceptionType()).append('\t')
                    .append(i.summary()).append('\n');
        }
        return out.length() == 0 ? "No contained Ocean Canvas incidents recorded this session.\n" : out.toString();
    }

    private static String clean(String value, int cap) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return oneLine.length() <= cap ? oneLine : oneLine.substring(0, cap);
    }
}
