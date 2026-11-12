package dev.ytstream;

import dev.ytstream.util.TextUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilTest {
    @Test
    void sanitizeStripsColorCodesControlAndBidiOverrides() {
        String in = "\u00A7aHello\u0000  \u202Eworld\n\tfoo";
        assertEquals("Hello world foo", TextUtil.sanitize(in, 100));
    }

    @Test
    void sanitizeTruncatesByCodePoints() {
        String out = TextUtil.sanitize("a".repeat(500), 100);
        assertEquals(101, out.length()); // 100文字 + …
        assertTrue(out.endsWith("\u2026"));
    }
}
