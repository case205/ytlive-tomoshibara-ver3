package dev.ytstream.stream;

import dev.ytstream.config.Settings;
import dev.ytstream.config.UrlRules;
import dev.ytstream.util.Redactor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegCommandTest {
    @Test
    void defaultsUseChangedFramesCbrAndSafeFlvFlags() {
        Settings s = Settings.defaults();
        List<String> c = FfmpegCommand.build(Path.of("ffmpeg"), s, "rtmp://a.rtmp.youtube.com/live2/abcd-efgh", 12345);
        String joined = String.join(" ", c);
        assertTrue(joined.contains("-use_wallclock_as_timestamps 1"));
        assertTrue(joined.contains("-fps_mode cfr"));
        assertTrue(joined.contains("nal-hrd=cbr"));
        assertTrue(joined.contains("-flvflags no_duration_filesize"));
        assertTrue(joined.contains("tcp://127.0.0.1:12345"));
        assertEquals("rtmp://a.rtmp.youtube.com/live2/abcd-efgh", c.get(c.size() - 1));
        // fps_mode は出力オプション(入力 pipe:0 より後)
        assertTrue(c.indexOf("-fps_mode") > c.indexOf("pipe:0"));
    }

    @Test
    void backupIngestUrlKeepsQueryBeforeKey() {
        assertEquals("rtmp://b.rtmp.youtube.com/live2?backup=1/KEY-1",
                FfmpegCommand.target("rtmp://b.rtmp.youtube.com/live2?backup=1", "KEY-1"));
        assertEquals("rtmp://a.rtmp.youtube.com/live2/KEY-1", FfmpegCommand.target("rtmp://a.rtmp.youtube.com/live2/", "KEY-1"));
        String red = new Redactor().redact("to 'rtmp://b.rtmp.youtube.com/live2?backup=1/secret-key-123'");
        assertFalse(red.contains("secret-key-123"));
    }

    @Test
    void urlRulesAllowOnlyYoutubeAndBackupQuery() {
        assertNull(UrlRules.checkRtmp("rtmp://a.rtmp.youtube.com/live2", false, false));
        assertNull(UrlRules.checkRtmp("rtmps://a.rtmps.youtube.com:443/live2", false, false));
        assertNull(UrlRules.checkRtmp("rtmp://b.rtmp.youtube.com/live2?backup=1", false, true));
        assertNotNull(UrlRules.checkRtmp("rtmp://b.rtmp.youtube.com/live2?backup=1", false, false));
        assertNotNull(UrlRules.checkRtmp("rtmp://evil.example.com/live2", false, true));
        assertNull(UrlRules.checkRtmp("rtmp://evil.example.com/live2", true, true));
        assertNotNull(UrlRules.checkRtmp("http://a.rtmp.youtube.com/live2", false, true));
        assertNotNull(UrlRules.checkRtmp("rtmp://user:pw@a.rtmp.youtube.com/live2", false, true));
    }
}
