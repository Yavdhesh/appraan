package dev.appraan.ui;

/**
 * Minimal JSON writer — just enough to emit Appraan's API payloads with
 * correct string escaping, zero dependencies. Objects preserve insertion
 * order. Not a general-purpose library.
 */
public final class Json {

    private Json() {
    }

    /** Escapes a string's content per RFC 8259 (without the surrounding quotes). */
    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** A JSON string literal (quoted + escaped), or {@code null} for a null input. */
    public static String str(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    public static Obj obj() {
        return new Obj();
    }

    public static Arr arr() {
        return new Arr();
    }

    /** JSON object builder. */
    public static final class Obj {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private Obj raw(String key, String jsonValue) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(str(key)).append(':').append(jsonValue);
            return this;
        }

        public Obj put(String key, String value) {
            return raw(key, str(value));
        }

        public Obj put(String key, long value) {
            return raw(key, Long.toString(value));
        }

        public Obj put(String key, double value) {
            return raw(key, Double.isFinite(value) ? Double.toString(value) : "null");
        }

        public Obj put(String key, boolean value) {
            return raw(key, Boolean.toString(value));
        }

        /** Inserts a pre-rendered JSON fragment (from {@link Obj} / {@link Arr}). */
        public Obj putRaw(String key, String jsonFragment) {
            return raw(key, jsonFragment);
        }

        @Override
        public String toString() {
            return sb + "}";
        }
    }

    /** JSON array builder. */
    public static final class Arr {
        private final StringBuilder sb = new StringBuilder("[");
        private boolean first = true;

        private Arr raw(String jsonValue) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonValue);
            return this;
        }

        public Arr add(String value) {
            return raw(str(value));
        }

        public Arr add(long value) {
            return raw(Long.toString(value));
        }

        public Arr add(double value) {
            return raw(Double.isFinite(value) ? Double.toString(value) : "null");
        }

        public Arr addRaw(String jsonFragment) {
            return raw(jsonFragment);
        }

        @Override
        public String toString() {
            return sb + "]";
        }
    }
}
