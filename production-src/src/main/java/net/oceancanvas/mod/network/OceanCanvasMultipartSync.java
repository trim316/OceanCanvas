package net.oceancanvas.mod.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Length-safe transport framing for the large, aggregate Ocean Canvas metadata feeds.
 *
 * <p>Minecraft's normal STRING_UTF8 packet codec rejects a single string above its
 * 32,767-character ceiling. Workspace/Planning/Project snapshots are intentionally
 * aggregate text formats and can grow beyond that during long-running worlds, so the
 * server frames them into independently safe strings and the client replaces its cache
 * only after a complete transfer has been reassembled.</p>
 *
 * <p>This class deliberately has no Minecraft/Fabric dependencies so its byte-limit and
 * reassembly behavior can be regression-tested with plain {@code javac}.</p>
 */
public final class OceanCanvasMultipartSync {
    /** Keep generous headroom below Minecraft's 32,767 STRING_UTF8 ceiling. */
    public static final int MAX_WIRE_BYTES = 24_000;
    /** Body target leaves ample room for transfer metadata even with long transfer ids. */
    private static final int MAX_BODY_BYTES = 23_000;
    private static final String PREFIX = "\u0001OCMP1\t";
    private static final int MAX_PARTS = 4096;
    public static final int PROJECT_MAX_LOGICAL_BYTES = 8 * 1024 * 1024;
    public static final int PLANNING_MAX_LOGICAL_BYTES = 16 * 1024 * 1024;
    public static final int WORKSPACE_MAX_LOGICAL_BYTES = 32 * 1024 * 1024;

    private OceanCanvasMultipartSync() {}

    public static List<String> encode(String packed, long transferId) {
        return encode(packed, transferId, WORKSPACE_MAX_LOGICAL_BYTES);
    }

    public static List<String> encode(String packed, long transferId, int maxLogicalBytes) {
        if (maxLogicalBytes < MAX_WIRE_BYTES) throw new IllegalArgumentException("logical multipart cap is smaller than one wire frame");
        String source = packed == null ? "" : packed;
        int logicalBytes = utf8Length(source);
        if (logicalBytes > maxLogicalBytes) {
            throw new IllegalArgumentException("Ocean Canvas metadata snapshot exceeds logical feed budget: " + logicalBytes + " > " + maxLogicalBytes);
        }
        if (logicalBytes <= MAX_WIRE_BYTES) {
            return List.of(source);
        }

        List<String> bodies = splitUtf8(source, MAX_BODY_BYTES);
        if (bodies.size() > MAX_PARTS) {
            throw new IllegalArgumentException("Ocean Canvas metadata snapshot requires too many multipart frames: " + bodies.size());
        }
        ArrayList<String> wires = new ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i++) {
            String wire = PREFIX + transferId + "\t" + i + "\t" + bodies.size() + "\n" + bodies.get(i);
            int bytes = utf8Length(wire);
            if (bytes > MAX_WIRE_BYTES) {
                throw new IllegalStateException("Ocean Canvas multipart frame exceeded safe wire budget: " + bytes);
            }
            wires.add(wire);
        }
        return List.copyOf(wires);
    }

    private static List<String> splitUtf8(String source, int maxBytes) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < source.length();) {
            int cp = source.codePointAt(offset);
            int cpChars = Character.charCount(cp);
            int cpBytes = utf8BytesForCodePoint(cp);
            if (bytes > 0 && bytes + cpBytes > maxBytes) {
                out.add(part.toString());
                part.setLength(0);
                bytes = 0;
            }
            part.appendCodePoint(cp);
            bytes += cpBytes;
            offset += cpChars;
        }
        if (part.length() > 0 || out.isEmpty()) out.add(part.toString());
        return out;
    }

    private static int utf8BytesForCodePoint(int cp) {
        if (cp <= 0x7F) return 1;
        if (cp <= 0x7FF) return 2;
        if (cp <= 0xFFFF) return 3;
        return 4;
    }

    public static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record Part(long transferId, int index, int count, String body) {}

    private static Part decodePart(String wire) {
        if (wire == null || !wire.startsWith(PREFIX)) return null;
        int first = wire.indexOf('\t', PREFIX.length());
        int second = first < 0 ? -1 : wire.indexOf('\t', first + 1);
        int newline = second < 0 ? -1 : wire.indexOf('\n', second + 1);
        if (first < 0 || second < 0 || newline < 0) return null;
        try {
            long id = Long.parseLong(wire.substring(PREFIX.length(), first));
            int index = Integer.parseInt(wire.substring(first + 1, second));
            int count = Integer.parseInt(wire.substring(second + 1, newline));
            if (count <= 0 || count > MAX_PARTS || index < 0 || index >= count) return null;
            return new Part(id, index, count, wire.substring(newline + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Reassembles exactly one logical feed. Plain (small) packets pass through
     * immediately. Multipart transfers may arrive in any order; incomplete data is
     * never exposed to the caller.
     */
    public static final class Assembler {
        private final int maxLogicalBytes;
        private long transferId = Long.MIN_VALUE;
        private int expectedParts;
        private int receivedBytes;
        private final Map<Integer, String> parts = new HashMap<>();

        public Assembler() { this(WORKSPACE_MAX_LOGICAL_BYTES); }

        public Assembler(int maxLogicalBytes) {
            if (maxLogicalBytes < MAX_WIRE_BYTES) throw new IllegalArgumentException("logical multipart cap is smaller than one wire frame");
            this.maxLogicalBytes = maxLogicalBytes;
        }

        public synchronized String accept(String wire) {
            Part part = decodePart(wire);
            if (part == null) {
                reset();
                return wire == null ? "" : wire;
            }
            if (part.transferId() != transferId || part.count() != expectedParts) {
                transferId = part.transferId();
                expectedParts = part.count();
                parts.clear();
                receivedBytes = 0;
            }
            int bodyBytes = utf8Length(part.body());
            String previous = parts.put(part.index(), part.body());
            if (previous != null) receivedBytes -= utf8Length(previous);
            receivedBytes += bodyBytes;
            if (receivedBytes > maxLogicalBytes) { reset(); return null; }
            if (parts.size() != expectedParts) return null;

            StringBuilder joined = new StringBuilder(Math.min(receivedBytes, maxLogicalBytes));
            for (int i = 0; i < expectedParts; i++) {
                String body = parts.get(i);
                if (body == null) return null;
                joined.append(body);
            }
            String complete = joined.toString();
            reset();
            return complete;
        }

        public synchronized void reset() {
            transferId = Long.MIN_VALUE;
            expectedParts = 0;
            receivedBytes = 0;
            parts.clear();
        }
    }
}
