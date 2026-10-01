package com.mraibo.cminsight.web.http;

import java.util.Collection;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer: correct escaping, no reflection, no library.
 *
 * <p>Values are dispatched on their Java type. Anything that is not a string, number, boolean, null,
 * {@link Map}, {@link Collection}, array or {@link Raw} fragment is rejected with
 * {@link IllegalArgumentException} rather than being rendered with {@code toString()}: a stray object
 * in a response body is how a password or a connection URL leaks into JSON.
 *
 * <p>Because {@link #object(Object...)} and {@link #array(Object...)} return {@link String}, a nested
 * document must be wrapped in {@link #raw(String)}:
 *
 * <pre>{@code
 * String body = JsonWriter.object(
 *         "itemType", "ICMREPORT",
 *         "count", 42,
 *         "stats", JsonWriter.raw(JsonWriter.object("pages", 7)),
 *         "tags", JsonWriter.raw(JsonWriter.array("a", "b")));
 * }</pre>
 */
public final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonWriter() {
    }

    /** A pre-serialised JSON fragment emitted verbatim. Only build this from trusted, typed data. */
    public static final class Raw {

        private final String json;

        private Raw(String json) {
            this.json = json;
        }

        /** The fragment exactly as it will appear in the output. */
        public String json() {
            return json;
        }
    }

    /** Wraps an already-serialised fragment so nesting it does not double-escape it. */
    public static Raw raw(String json) {
        return new Raw(json == null ? "null" : json);
    }

    /** Escapes a string for use inside JSON quotes. Null becomes the empty string. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Control characters, DEL and the JavaScript line separators U+2028 / U+2029 are
                    // escaped as backslash-u followed by four hex digits.
                    if (c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029') {
                        appendUnicode(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** A JSON string literal. Null becomes the JSON literal {@code null}. */
    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escape(value) + "\"";
    }

    /** A JSON integer literal. */
    public static String number(long value) {
        return Long.toString(value);
    }

    /** A JSON boolean literal. */
    public static String bool(boolean value) {
        return Boolean.toString(value);
    }

    /** A JSON object from alternating key/value arguments. Keys must be non-null strings. */
    public static String object(Object... keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.length == 0) {
            return "{}";
        }
        if ((keyValuePairs.length & 1) != 0) {
            throw new IllegalArgumentException("JSON object needs an even number of key/value arguments");
        }
        StringBuilder out = new StringBuilder(64);
        out.append('{');
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object key = keyValuePairs[i];
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("JSON object key " + (i / 2) + " is not a String");
            }
            if (i > 0) {
                out.append(',');
            }
            out.append(string(name)).append(':').append(value(keyValuePairs[i + 1]));
        }
        return out.append('}').toString();
    }

    /** A JSON array from the given values. A null array is the empty array. */
    public static String array(Object... values) {
        if (values == null || values.length == 0) {
            return "[]";
        }
        StringBuilder out = new StringBuilder(64);
        out.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(value(values[i]));
        }
        return out.append(']').toString();
    }

    /** The standard error envelope: {@code {"error":{"code":"...","message":"..."}}}. */
    public static String error(String code, String message) {
        return "{\"error\":{\"code\":" + string(code == null ? "" : code)
                + ",\"message\":" + string(message == null ? "" : message) + "}}";
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return string(text);
        }
        if (value instanceof CharSequence text) {
            return string(text.toString());
        }
        if (value instanceof Boolean flag) {
            return bool(flag);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return number(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            return decimal(((Number) value).doubleValue());
        }
        if (value instanceof Raw fragment) {
            return fragment.json();
        }
        if (value instanceof Map<?, ?> map) {
            return map(map);
        }
        if (value instanceof Collection<?> collection) {
            return array(collection.toArray());
        }
        if (value instanceof Object[] elements) {
            return array(elements);
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName()
                + " (box primitive arrays before passing them in)");
    }

    private static String map(Map<?, ?> map) {
        StringBuilder out = new StringBuilder(64);
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("JSON object map key is not a String");
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(string(name)).append(':').append(value(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String decimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Non-finite numbers cannot be encoded as JSON");
        }
        if (value == Math.rint(value) && Math.abs(value) < 9.007199254740992E15) {
            return number((long) value);
        }
        return Double.toString(value);
    }

    private static void appendUnicode(StringBuilder out, char c) {
        out.append("\\u")
                .append(HEX[(c >> 12) & 0xF])
                .append(HEX[(c >> 8) & 0xF])
                .append(HEX[(c >> 4) & 0xF])
                .append(HEX[c & 0xF]);
    }
}
