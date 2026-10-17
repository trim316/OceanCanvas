package net.oceancanvas.mod.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader. This codebase has never taken a JSON library
 * dependency anywhere (see {@link OceanCanvasProjectPackageExporter}, which hand-writes
 * its own JSON rather than pulling one in) - matching that choice here rather than
 * introducing Gson for a single reader is deliberate, per the v113/v121 "next
 * implementation slice" note in docs/roadmap.md.
 *
 * <p>Only supports what {@code manifest.json} ever actually contains: objects, arrays,
 * strings (with the standard backslash escapes), numbers, booleans, and null. Not a
 * general-purpose JSON library - no streaming, no comments, no trailing commas.</p>
 *
 * <p>Numbers parse to {@link Long} when they have no fractional/exponent part and fit,
 * otherwise {@link Double} - callers that need an int/long should go through a helper
 * that unwraps {@link Number} rather than casting directly, since which of the two
 * subtypes comes back depends on the literal's own text.</p>
 */
final class OceanCanvasJson {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 250_000;
    private static final int MAX_STRING_CHARS = 1_000_000;
    private OceanCanvasJson() { }

    /** Parses a complete JSON document. Throws {@link IllegalArgumentException} on any malformed input. */
    static Object parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        Object value = parser.readValue(0);
        parser.skipWs();
        if (!parser.atEnd()) throw new IllegalArgumentException("Unexpected trailing content at position " + parser.i);
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;
        private int nodes;

        Parser(String s) { this.s = s; this.i = 0; }

        boolean atEnd() { return i >= s.length(); }
        void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        char peek() {
            if (atEnd()) throw new IllegalArgumentException("Unexpected end of JSON at position " + i);
            return s.charAt(i);
        }

        void expect(char c) {
            skipWs();
            if (atEnd() || s.charAt(i) != c) throw new IllegalArgumentException("Expected '" + c + "' at position " + i);
            i++;
        }

        void expectLiteral(String literal) {
            if (i + literal.length() > s.length() || !s.regionMatches(i, literal, 0, literal.length()))
                throw new IllegalArgumentException("Invalid literal at position " + i);
            i += literal.length();
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH);
            if (++nodes > MAX_NODES) throw new IllegalArgumentException("JSON node count exceeds " + MAX_NODES);
            skipWs();
            char c = peek();
            if (c == '{') return readObject(depth + 1);
            if (c == '[') return readArray(depth + 1);
            if (c == '"') return readString();
            if (c == 't') { expectLiteral("true"); return Boolean.TRUE; }
            if (c == 'f') { expectLiteral("false"); return Boolean.FALSE; }
            if (c == 'n') { expectLiteral("null"); return null; }
            return readNumber();
        }

        Map<String, Object> readObject(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (!atEnd() && peek() == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                expect(':');
                map.put(key, readValue(depth));
                skipWs();
                if (!atEnd() && peek() == ',') { i++; continue; }
                expect('}');
                break;
            }
            return map;
        }

        List<Object> readArray(int depth) {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (!atEnd() && peek() == ']') { i++; return list; }
            while (true) {
                list.add(readValue(depth));
                skipWs();
                if (!atEnd() && peek() == ',') { i++; continue; }
                expect(']');
                break;
            }
            return list;
        }

        String readString() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (atEnd()) throw new IllegalArgumentException("Unterminated string starting before position " + i);
                char c = s.charAt(i++);
                if (c == '"') break;
                if (b.length() >= MAX_STRING_CHARS) throw new IllegalArgumentException("JSON string exceeds " + MAX_STRING_CHARS + " characters");
                if (c == '\\') {
                    if (atEnd()) throw new IllegalArgumentException("Unterminated escape at position " + i);
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape at position " + i);
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape '\\" + e + "' at position " + (i - 1));
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        Object readNumber() {
            int start = i;
            if (!atEnd() && s.charAt(i) == '-') i++;
            while (!atEnd() && Character.isDigit(s.charAt(i))) i++;
            boolean isDouble = false;
            if (!atEnd() && s.charAt(i) == '.') {
                isDouble = true; i++;
                while (!atEnd() && Character.isDigit(s.charAt(i))) i++;
            }
            if (!atEnd() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDouble = true; i++;
                if (!atEnd() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (!atEnd() && Character.isDigit(s.charAt(i))) i++;
            }
            if (start == i || (start + 1 == i && s.charAt(start) == '-'))
                throw new IllegalArgumentException("Invalid number at position " + start);
            String token = s.substring(start, i);
            if (isDouble) return Double.parseDouble(token);
            try { return Long.parseLong(token); } catch (NumberFormatException ex) { return Double.parseDouble(token); }
        }
    }
}
