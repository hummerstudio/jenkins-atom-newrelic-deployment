package com.jenkinschina.atom.newrelicdeployment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the two answers this atom reads: the NerdGraph envelope and the v2 deployment document.
 *
 * <p>It exists because the atom ships one jar and no JSON library: the plugin carries Gson, an atom cannot. The
 * grammar covered is the whole of RFC 8259 minus nothing that matters here (objects, arrays, strings with escapes,
 * numbers, booleans, null); anything else is a {@link IllegalArgumentException} with the offset, which the caller
 * reports as 40008 instead of pretending the answer was fine.
 */
final class JsonReader {

    private final String text;
    private int index;

    JsonReader(String text) {
        this.text = text == null ? "" : text;
    }

    Object parseDocument() {
        skipWhitespace();
        Object value = readValue();
        skipWhitespace();
        if (index != text.length()) {
            throw error("trailing characters");
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (index >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(index);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        index++;
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("object key must be a string");
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':' after object key");
            }
            index++;
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
                continue;
            }
            if (c == '}') {
                index++;
                return map;
            }
            throw error("expected ',' or '}' in object");
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        index++;
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
                continue;
            }
            if (c == ']') {
                index++;
                return list;
            }
            throw error("expected ',' or ']' in array");
        }
    }

    private String readString() {
        index++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (index >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index >= text.length()) {
                throw error("unterminated escape");
            }
            char esc = text.charAt(index++);
            switch (esc) {
                case '"', '\\', '/' -> out.append(esc);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (index + 4 > text.length()) {
                        throw error("truncated \\u escape");
                    }
                    String hex = text.substring(index, index + 4);
                    index += 4;
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw error("bad \\u escape '" + hex + "'");
                    }
                }
                default -> throw error("bad escape '\\" + esc + "'");
            }
        }
    }

    private Number readNumber() {
        int start = index;
        if (peek() == '-') {
            index++;
        }
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        boolean fractional = false;
        if (index < text.length() && text.charAt(index) == '.') {
            fractional = true;
            index++;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
        if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            fractional = true;
            index++;
            if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
        String raw = text.substring(start, index);
        if (raw.isEmpty() || "-".equals(raw)) {
            throw error("bad number");
        }
        try {
            return fractional ? (Number) Double.parseDouble(raw) : (Number) Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw error("bad number '" + raw + "'");
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, index)) {
            throw error("expected " + literal);
        }
        index += literal.length();
    }

    private char peek() {
        if (index >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(index);
    }

    private void skipWhitespace() {
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                index++;
            } else {
                return;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + index);
    }
}
