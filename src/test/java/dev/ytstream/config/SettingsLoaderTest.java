package dev.ytstream.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 同梱の config.yml がそのまま警告なしで読み込め、新しい項目が既定値どおりに反映されること。 */
class SettingsLoaderTest {
    private static final Path BUNDLED = Path.of("src/main/resources/config.yml");

    @Test
    void bundledConfigLoadsWithoutWarnings(@TempDir Path dir) {
        SettingsLoader.Result r = SettingsLoader.load(YamlConfiguration.loadConfiguration(BUNDLED.toFile()), dir);
        assertEquals(List.of(), r.warnings());
        Settings s = r.settings();
        assertEquals(List.of(), s.fatalProblems());
        assertFalse(s.youtube().enabled());
        assertEquals("own", s.youtube().streamSource());
        assertEquals("reuse", s.youtube().broadcastMode());
        assertEquals(List.of("online", "viewers", "uptime"), s.header().columns());
        assertEquals("glass", s.visual().style());
        assertTrue(s.video().sendChangedOnly());
        assertEquals("cbr", s.video().rateControl());
        assertTrue(s.net().preflight());
        assertFalse(s.net().backupEnabled());
        assertNotNull(s.rect("logo"));
    }

    @Test
    void invalidValuesAreCorrectedWithWarnings(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.yml");
        Files.writeString(cfg, String.join("\n",
                "youtube:",
                "  rtmp-url: \"rtmp://evil.example.com/live2\"",
                "  backup-ingest: { enabled: true, url: \"rtmp://evil.example.com/x?backup=1\" }",
                "  api: { enabled: true, broadcast: { privacy: secret, latency: ULTRALOW } }",
                "theme: { name: rainbow, style: neon, panel-color: red }",
                "header: { columns: [online, weather, tps], timezone: Mars/Base }",
                "video: { rate-control: abr, x264-params: \"a=b;rm -rf\" }",
                ""));
        SettingsLoader.Result r = SettingsLoader.load(YamlConfiguration.loadConfiguration(cfg.toFile()), dir);
        Settings s = r.settings();
        assertFalse(s.fatalProblems().isEmpty(), "YouTube以外の送信先は拒否");
        assertFalse(s.net().backupEnabled(), "不正なバックアップURLは無効化");
        assertEquals("public", s.youtube().privacy());
        assertEquals("ultraLow", s.youtube().latency());
        assertEquals("default", s.themeName());
        assertEquals("glass", s.visual().style());
        assertEquals("", s.visual().panelColor());
        assertEquals(List.of("online", "tps"), s.header().columns());
        assertEquals("", s.header().timezone());
        assertEquals("cbr", s.video().rateControl());
        assertEquals("", s.video().x264Params());
        assertTrue(r.warnings().size() >= 8, "補正内容が警告される: " + r.warnings());
    }
}
