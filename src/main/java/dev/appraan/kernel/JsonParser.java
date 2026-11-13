package dev.appraan.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON parser (RFC 8259) producing plain Java values:
 * {@code Map<String,Object>} (insertion-ordered), {@code List<Object>},
 * {@code String}, {@code Double}, {@code Boolean} and {@code null}.
 *
 * <p>The project is zero-dependency and its {@code ui.Json} is writer-only;
 * this parser exists for exactly one job — reading appraanagent kernel
 * snapshots (see {@link KernelData}) — and is deliberately strict: trailing
 * content, unterminated strings and bad numbers are all errors, so a
 * malformed ingest body is rejected rather than half-parsed.
 */
public final class JsonParser {

    /** Thrown on any malformed input. */
    public static final class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    private final String text;
    private int pos;

    private JsonParser(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new ParseException("null input");
        }
        JsonParser p = new JsonParser(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (p.pos != p.text.length()) {
            throw new ParseException("trailing content at position " + p.pos);
        }
        return value;
    }

    private Object parseValue() {
        if (pos >= text.length()) {
            throw new ParseException("unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw new ParseException("expected object key at position " + pos);
            }
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, parseValue());
            skipWhitespace();
            if (peek(',')) {
                pos++;
            } else if (peek('}')) {
                pos++;
                return map;
            } else {
                throw new ParseException("expected ',' or '}' at position " + pos);
            }
        }
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            if (peek(',')) {
                pos++;
            } else if (peek(']')) {
                pos++;
                return list;
            } else {
                throw new ParseException("expected ',' or ']' at position " + pos);
            }
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw new ParseException("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= text.length()) {
                    throw new ParseException("unterminated escape");
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new ParseException("bad \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException nfe) {
                            throw new ParseException("bad \\u escape");
                        }
                        pos += 4;
                    }
                    default -> throw new ParseException("bad escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object parseLiteral(String literal, Object value) {
        if (text.startsWith(literal, pos)) {
            pos += literal.length();
            return value;
        }
        throw new ParseException("bad literal at position " + pos);
    }

    private Double parseNumber() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        if (pos == start) {
            throw new ParseException("expected a value at position " + start);
        }
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new ParseException("bad number at position " + start);
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new ParseException("expected '" + c + "' at position " + pos);
        }
        pos++;
    }
}
