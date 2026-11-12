package dev.ytstream;

import dev.ytstream.util.Redactor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {
    @Test
    void masksKeyAndRtmpUrlPaths() {
        Redactor r = new Redactor();
        r.setSecret("abcd-efgh-ijkl-mnop");
        String out = r.redact("Output #0, flv, to 'rtmp://a.rtmp.youtube.com/live2/abcd-efgh-ijkl-mnop': error abcd-efgh-ijkl-mnop");
        assertFalse(out.contains("abcd-efgh"));
        assertTrue(out.contains("rtmp://a.rtmp.youtube.com/live2/****"));
    }

    @Test
    void masksUnknownKeysInUrlsEvenWithoutSecret() {
        String out = new Redactor().redact("rtmps://a.rtmps.youtube.com:443/live2/zzzz-yyyy-xxxx");
        assertFalse(out.contains("zzzz"));
    }
}
