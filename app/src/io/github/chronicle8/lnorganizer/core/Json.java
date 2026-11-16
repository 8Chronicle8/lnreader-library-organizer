package io.github.chronicle8.lnorganizer.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict JSON (RFC 8259) reader and a writer that matches JavaScript's
 * JSON.stringify, so a backup written by LNReader can be reproduced byte for byte
 * (SPEC §6).
 *
 * Value model: objects are LinkedHashMap (key order kept), arrays ArrayList,
 * strings String, numbers {@link Num} (the literal text, never re-formatted),
 * booleans Boolean, null {@link #NULL}.
 */
public final class Json {
    private Json() {}

    public static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    /** A JSON number kept as written, so serialising it reproduces the input. */
    public static final class Num {
        public final String raw;

        public Num(String raw) { this.raw = raw; }

        public static Num of(long value) { return new Num(Long.toString(value)); }

        public boolean isInteger() {
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') return false;
            }
            return true;
        }

        /** The value as an int, or null when it is not an integer in int range. */
        public Integer intValue() {
            if (!isInteger()) return null;
            try {
                return Integer.valueOf(raw);
            } catch (NumberFormatException e) {
                // Out of int range: the caller reports it with its own context.
                return null;
            }
        }

        @Override public boolean equals(Object o) { return o instanceof Num && ((Num) o).raw.equals(raw); }
        @Override public int hashCode() { return raw.hashCode(); }
        @Override public String toString() { return raw; }
    }

    public static final class JsonException extends Exception {
        public final int offset;

        JsonException(String message, int offset) {
            super(message + " at character " + offset);
            this.offset = offset;
        }
    }

    private static final int MAX_DEPTH = 256;

    /** Decodes UTF-8 strictly: malformed bytes are an error, never replaced. */
    public static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer cb = d.decode(ByteBuffer.wrap(bytes));
        return cb.toString();
    }

    public static Object parse(String text) throws JsonException {
        return parse(text, null);
    }

    /**
     * Parses text; values of top-level object keys named in skipTopLevelKeys are
     * checked for syntax but not built (a novel's chapter list can be large and is
     * not needed).
     */
    public static Object parse(String text, Set<String> skipTopLevelKeys) throws JsonException {
        Parser p = new Parser(text, skipTopLevelKeys);
        p.ws();
        Object v = p.value(0, true);
        p.ws();
        if (p.pos != text.length()) throw new JsonException("unexpected text after the JSON value", p.pos);
        return v;
    }

    private static final class Parser {
        final String s;
        final Set<String> skip;
        int pos;

        Parser(String s, Set<String> skip) {
            this.s = s;
            this.skip = skip;
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        JsonException err(String msg) { return new JsonException(msg, pos); }

        Object value(int depth, boolean build) throws JsonException {
            if (depth > MAX_DEPTH) throw err("nesting deeper than " + MAX_DEPTH);
            if (pos >= s.length()) throw err("unexpected end of text");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object(depth, build);
                case '[': return array(depth, build);
                case '"': return string(build);
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return NULL;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("unexpected character '" + c + "'");
            }
        }

        void literal(String word) throws JsonException {
            if (!s.startsWith(word, pos)) throw err("expected " + word);
            pos += word.length();
        }

        Object object(int depth, boolean build) throws JsonException {
            pos++; // {
            Map<String, Object> map = build ? new LinkedHashMap<>() : null;
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"') throw err("expected a key string");
                int keyAt = pos;
                String key = (String) string(true);
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') throw err("expected ':'");
                pos++;
                ws();
                boolean buildValue = build && !(depth == 0 && skip != null && skip.contains(key));
                Object v = value(depth + 1, buildValue);
                if (map != null) {
                    if (map.containsKey(key)) throw new JsonException("duplicate key \"" + key + "\"", keyAt);
                    if (buildValue) map.put(key, v);
                }
                ws();
                if (pos >= s.length()) throw err("unexpected end of text in object");
                char c = s.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw new JsonException("expected ',' or '}'", pos - 1);
            }
        }

        Object array(int depth, boolean build) throws JsonException {
            pos++; // [
            List<Object> list = build ? new ArrayList<>() : null;
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                ws();
                Object v = value(depth + 1, build);
                if (list != null) list.add(v);
                ws();
                if (pos >= s.length()) throw err("unexpected end of text in array");
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw new JsonException("expected ',' or ']'", pos - 1);
            }
        }

        Object string(boolean build) throws JsonException {
            pos++; // opening quote
            StringBuilder sb = build ? new StringBuilder() : null;
            while (true) {
                if (pos >= s.length()) throw err("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb == null ? null : sb.toString();
                if (c < 0x20) throw new JsonException("control character in string", pos - 1);
                if (c != '\\') {
                    if (sb != null) sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw err("unterminated escape");
                char e = s.charAt(pos++);
                char out;
                switch (e) {
                    case '"': out = '"'; break;
                    case '\\': out = '\\'; break;
                    case '/': out = '/'; break;
                    case 'b': out = '\b'; break;
                    case 'f': out = '\f'; break;
                    case 'n': out = '\n'; break;
                    case 'r': out = '\r'; break;
                    case 't': out = '\t'; break;
                    case 'u': {
                        if (pos + 4 > s.length()) throw err("short \\u escape");
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            if (d < 0) throw err("bad \\u escape");
                            v = v * 16 + d;
                        }
                        pos += 4;
                        out = (char) v;
                        break;
                    }
                    default:
                        throw new JsonException("bad escape \\" + e, pos - 1);
                }
                if (sb != null) sb.append(out);
            }
        }

        Object number() throws JsonException {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            if (pos >= s.length()) throw err("bad number");
            if (s.charAt(pos) == '0') {
                pos++;
            } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            } else {
                throw err("bad number");
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                if (pos >= s.length() || !isDigit(s.charAt(pos))) throw err("bad number fraction");
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                if (pos >= s.length() || !isDigit(s.charAt(pos))) throw err("bad number exponent");
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            return new Num(s.substring(start, pos));
        }

        static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    }

    /** Serialises like JavaScript JSON.stringify(value) with no indentation. */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null || v == NULL) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Num) {
            sb.append(((Num) v).raw);
        } else if (v instanceof Integer || v instanceof Long) {
            sb.append(v.toString());
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); continue;
                case '\\': sb.append("\\\\"); continue;
                case '\b': sb.append("\\b"); continue;
                case '\f': sb.append("\\f"); continue;
                case '\n': sb.append("\\n"); continue;
                case '\r': sb.append("\\r"); continue;
                case '\t': sb.append("\\t"); continue;
                default: break;
            }
            if (c < 0x20) {
                unicodeEscape(sb, c);
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                } else {
                    unicodeEscape(sb, c);
                }
            } else if (Character.isLowSurrogate(c)) {
                unicodeEscape(sb, c);
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void unicodeEscape(StringBuilder sb, char c) {
        sb.append("\\u")
                .append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
                .append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
    }
}
