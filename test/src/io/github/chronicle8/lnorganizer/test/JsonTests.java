package io.github.chronicle8.lnorganizer.test;

import io.github.chronicle8.lnorganizer.core.Json;

import java.nio.charset.CharacterCodingException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.github.chronicle8.lnorganizer.test.Check.*;

final class JsonTests {
    private JsonTests() {}

    static void run() {
        System.out.println("Json");

        test("stringify matches JavaScript JSON.stringify on every reference case", () -> {
            String[] lines = Fixtures.text("json-stringify.txt").split("\n");
            int cases = 0;
            int escaped = 0;
            for (String line : lines) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", -1);
                eq(2, parts.length, "fixture line shape: " + line);
                String input = units(parts[0]);
                String expected = units(parts[1]);
                eq(expected, Json.stringify(input), "stringify of " + parts[0]);
                eq(input, Json.parse(expected), "parse of " + parts[1]);
                if (!expected.equals("\"" + input + "\"")) escaped++;
                cases++;
            }
            // The fixture must have loaded, and must exercise escaping, or this test proves nothing (Rule 6).
            yes(cases >= 140, "only " + cases + " reference cases loaded");
            yes(escaped >= 35, "only " + escaped + " cases involve an escape");
        });

        test("numbers keep their literal text", () -> {
            String s = "[1.0,-0,1e5,10,2.50E-3,0]";
            eq(s, Json.stringify(Json.parse(s)), "round trip");
        });

        test("the fixture Category.json round-trips byte for byte", () -> {
            String s = Fixtures.text("library/Category.json");
            eq(s, Json.stringify(Json.parse(s)), "round trip");
        });

        test("skipped top-level keys are checked but not built", () -> {
            Object v = Json.parse(Fixtures.text("library/NovelAndChapters/3.json"), Collections.singleton("chapters"));
            Map<?, ?> m = (Map<?, ?>) v;
            yes(!m.containsKey("chapters"), "chapters should be skipped");
            eq("beta\u2019s Story", m.get("name"), "name");
            throwsWith(Json.JsonException.class, "expected", () ->
                    Json.parse("{\"chapters\":[1,2,}],\"id\":1}", Collections.singleton("chapters")));
        });

        test("nested values parse with key order kept", () -> {
            Object v = Json.parse(" { \"b\" : [true, false, null, \"x\"], \"a\": {} } ");
            Map<?, ?> m = (Map<?, ?>) v;
            eq("[b, a]", m.keySet().toString(), "key order");
            List<?> l = (List<?>) m.get("b");
            eq(Boolean.TRUE, l.get(0), "true");
            eq(Json.NULL, l.get(2), "null");
            eq("{\"b\":[true,false,null,\"x\"],\"a\":{}}", Json.stringify(v), "stringify");
        });

        test("malformed JSON is refused with a position", () -> {
            throwsWith(Json.JsonException.class, "duplicate key", () -> Json.parse("{\"a\":1,\"a\":2}"));
            throwsWith(Json.JsonException.class, "after the JSON value", () -> Json.parse("[1] x"));
            throwsWith(Json.JsonException.class, "control character", () -> Json.parse("\"a\nb\""));
            throwsWith(Json.JsonException.class, "bad escape", () -> Json.parse("\"\\x\""));
            // "0" is a whole number, so the "1" after it is trailing text (JSON.parse refuses it too).
            throwsWith(Json.JsonException.class, "after the JSON value", () -> Json.parse("01"));
            throwsWith(Json.JsonException.class, "bad number fraction", () -> Json.parse("1."));
            throwsWith(Json.JsonException.class, "bad number", () -> Json.parse("-"));
            throwsWith(Json.JsonException.class, "unterminated", () -> Json.parse("\"abc"));
            throwsWith(Json.JsonException.class, "at character", () -> Json.parse("[1,]"));
            StringBuilder deep = new StringBuilder();
            for (int i = 0; i < 300; i++) deep.append('[');
            throwsWith(Json.JsonException.class, "nesting", () -> Json.parse(deep.toString()));
        });

        test("UTF-8 is decoded strictly", () -> {
            eq("\u00e9", Json.decodeUtf8(new byte[] {(byte) 0xC3, (byte) 0xA9}), "valid");
            throwsWith(CharacterCodingException.class, null, () -> Json.decodeUtf8(new byte[] {(byte) 0xC3}));
            throwsWith(CharacterCodingException.class, null, () -> Json.decodeUtf8(new byte[] {(byte) 0xFF}));
        });

        test("Num.intValue accepts only integers in int range", () -> {
            eq(Integer.valueOf(-5), new Json.Num("-5").intValue(), "-5");
            eq(null, new Json.Num("1.0").intValue(), "1.0");
            eq(null, new Json.Num("1e2").intValue(), "1e2");
            eq(null, new Json.Num("99999999999").intValue(), "overflow");
        });
    }

    private static String units(String hex) {
        StringBuilder sb = new StringBuilder();
        if (hex.isEmpty()) return "";
        for (String h : hex.split(" ")) sb.append((char) Integer.parseInt(h, 16));
        return sb.toString();
    }
}
