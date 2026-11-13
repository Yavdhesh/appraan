package dev.appraan.ui;

/**
 * Manual test for {@link Json}: string escaping rules and builder output.
 * Run via {@code ./run-tests.sh}; exits non-zero (AssertionError) on failure.
 */
public final class JsonTest {

    private static int checks;

    public static void main(String[] args) {
        escaping();
        stringLiterals();
        builders();
        System.out.println("JsonTest: all " + checks + " checks passed");
    }

    private static void escaping() {
        check("plain".equals(Json.escape("plain")), "plain string untouched");
        check("say \\\"hi\\\"".equals(Json.escape("say \"hi\"")), "quotes escaped");
        check("a\\\\b".equals(Json.escape("a\\b")), "backslash escaped");
        check("l1\\nl2".equals(Json.escape("l1\nl2")), "newline escaped");
        check("a\\tb".equals(Json.escape("a\tb")), "tab escaped");
        check("a\\rb".equals(Json.escape("a\rb")), "carriage return escaped");
        check("\\u0001".equals(Json.escape("\u0001")), "control char -> \\u00XX");
        check("\\u001f".equals(Json.escape("\u001f")), "0x1f -> \\u001f");
        check("café ☃".equals(Json.escape("café ☃")), "non-ASCII passes through unescaped");
        check("".equals(Json.escape("")), "empty string");
        // A realistic hostile payload: method name with quote + newline + backslash.
        check("a.B\\\";\\n\\\\".equals(Json.escape("a.B\";\n\\")), "combined hostile input");
    }

    private static void stringLiterals() {
        check("\"x\"".equals(Json.str("x")), "str quotes");
        check("null".equals(Json.str(null)), "str null -> null literal");
        check("\"a\\\"b\"".equals(Json.str("a\"b")), "str escapes");
    }

    private static void builders() {
        String flat = Json.obj()
                .put("pid", 8806)
                .put("gc", "G1")
                .put("rate", 1.5)
                .put("ok", true)
                .put("missing", (String) null)
                .toString();
        check("{\"pid\":8806,\"gc\":\"G1\",\"rate\":1.5,\"ok\":true,\"missing\":null}".equals(flat),
                "flat object, got " + flat);

        String nested = Json.obj()
                .putRaw("young", Json.obj().put("count", 72).toString())
                .putRaw("top", Json.arr().add("a").add(3).addRaw(Json.obj().put("m", "x").toString()).toString())
                .toString();
        check("{\"young\":{\"count\":72},\"top\":[\"a\",3,{\"m\":\"x\"}]}".equals(nested),
                "nested object/array, got " + nested);

        check("{}".equals(Json.obj().toString()), "empty object");
        check("[]".equals(Json.arr().toString()), "empty array");
        check("{\"v\":null}".equals(Json.obj().put("v", Double.NaN).toString()),
                "NaN double -> null (not valid JSON otherwise)");
        check("{\"v\":null}".equals(Json.obj().put("v", Double.POSITIVE_INFINITY).toString()),
                "infinite double -> null");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError("JsonTest FAILED: " + label);
        }
    }
}
