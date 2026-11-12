package dev.ytstream;

import dev.ytstream.util.Json;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test
    void parsesNestedStructuresAndEscapes() {
        Map<String, Object> m = Json.parseObject("{\"a\":{\"b\":[1,2.5,\"x\\u3042\\n\"],\"t\":true,\"n\":null},\"s\":\"q\\\"\"}");
        assertEquals(1L, Json.arr(Json.obj(m, "a"), "b").get(0));
        assertEquals(2.5, Json.arr(Json.obj(m, "a"), "b").get(1));
        assertEquals("xあ\n", Json.arr(Json.obj(m, "a"), "b").get(2));
        assertTrue(Json.bool(Json.obj(m, "a"), "t", false));
        assertEquals("q\"", Json.str(m, "s"));
        assertEquals("", Json.pathStr(m, "a.missing.deep"));
    }

    @Test
    void writeRoundTrips() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "テスト \"live\"\n<ok>");
        m.put("flag", false);
        m.put("list", List.of(1, 2L, "z"));
        m.put("nested", Map.of("k", 3.25));
        Map<String, Object> back = Json.parseObject(Json.write(m));
        assertEquals("テスト \"live\"\n<ok>", back.get("title"));
        assertEquals(Boolean.FALSE, back.get("flag"));
        assertEquals(3.25, Json.obj(back, "nested").get("k"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(200) + "]".repeat(200)));
    }
}
