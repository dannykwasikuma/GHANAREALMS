package com.ghanarealms.paystack.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small, dependency-free JSON parser/writer. This exists only so the plugin
 * doesn't need to pull in Gson/Jackson (which would need to be verified against
 * a Maven repository this build environment could not reach). It supports the
 * flat/nested-object shapes Paystack actually sends - it is NOT a general
 * purpose JSON library. If you later add Gson yourself, swap this out.
 */
public final class MinimalJson {

    private final String src;
    private int pos = 0;

    private MinimalJson(String src) {
        this.src = src;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        MinimalJson p = new MinimalJson(json);
        p.skipWs();
        Object result = p.parseValue();
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }
        throw new IllegalArgumentException("Top-level JSON value was not an object");
    }

    private Object parseValue() {
        skipWs();
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObjectInternal();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObjectInternal() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWs();
            char c = src.charAt(pos++);
            if (c == '}') break;
            if (c != ',') throw new IllegalArgumentException("Expected , or } at " + pos);
        }
        return map;
    }

    private Object parseArray() {
        java.util.List<Object> list = new java.util.ArrayList<>();
        expect('[');
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWs();
            char c = src.charAt(pos++);
            if (c == ']') break;
            if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + pos);
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = src.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object parseNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) pos++;
        String num = src.substring(start, pos);
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return Double.parseDouble(num);
        }
    }

    private Object parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid literal at " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid literal at " + pos);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peek() {
        return src.charAt(pos);
    }

    private void expect(char c) {
        skipWs();
        if (src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at " + pos + " got '" + src.charAt(pos) + "'");
        }
        pos++;
    }

    /** Dot-path getter, e.g. get(root, "data.customer.email"). Returns null if any segment is missing. */
    @SuppressWarnings("unchecked")
    public static Object getPath(Map<String, Object> root, String dotPath) {
        Object current = root;
        for (String part : dotPath.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
