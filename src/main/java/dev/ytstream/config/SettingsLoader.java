package dev.ytstream.config;

import dev.ytstream.config.Settings.Rect;
import dev.ytstream.util.Secret;
import dev.ytstream.util.TextUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** config.yml を検証し、不正値は安全な値に補正して警告する。致命的な問題は fatalProblems に積み、配信開始を拒否する。 */
public final class SettingsLoader {
    private SettingsLoader() {}

    public record Result(Settings settings, Secret key, Secret clientSecret, List<String> warnings) {}

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.\\-]{3,127}$");
    private static final Set<String> CODECS = Set.of("libx264", "h264_nvenc", "h264_qsv", "h264_videotoolbox", "h264_amf");
    private static final Set<String> THEMES = Set.of("default", "nether", "end", "ocean", "sakura", "midnight", "light");
    private static final Set<String> HEADER_COLUMNS = Set.of("online", "viewers", "uptime", "tps", "clock");
    private static final Pattern X264_PARAMS = Pattern.compile("^[A-Za-z0-9_=:.,+\\-]{0,200}$");
    private static final Pattern PRESET = Pattern.compile("^(ultrafast|superfast|veryfast|faster|fast|medium|slow)$");
    private static final Pattern TUNE = Pattern.compile("^(|zerolatency|stillimage|fastdecode|film|animation)$");

    public static Result load(FileConfiguration c, Path dataDir) {
        List<String> w = new ArrayList<>();
        List<String> fatal = new ArrayList<>();

        String serverName = TextUtil.sanitize(c.getString("server-name", "My Minecraft Server"), 40);
        if (serverName.isBlank()) serverName = "My Minecraft Server";

        // --- ストリームキー: 環境変数 > secrets.yml > config.yml ---
        String key = "";
        String env = System.getenv("YTSTREAM_KEY");
        if (env != null && !env.isBlank()) key = env.trim();
        java.io.File secretsFile = dataDir.resolve("secrets.yml").toFile();
        YamlConfiguration secrets = secretsFile.isFile() ? YamlConfiguration.loadConfiguration(secretsFile) : null;
        if (key.isEmpty() && secrets != null) key = secrets.getString("stream-key", "").trim();
        if (key.isEmpty()) {
            key = c.getString("youtube.stream-key", "").trim();
            if (!key.isEmpty() && !key.equals("YOUR_STREAM_KEY")) {
                w.add("ストリームキーが config.yml に平文で保存されています。環境変数 YTSTREAM_KEY か secrets.yml(権限600)の利用を推奨します。");
            }
        }
        if (key.equals("YOUR_STREAM_KEY")) key = "";
        if (!key.isEmpty() && !KEY_PATTERN.matcher(key).matches()) {
            fatal.add("ストリームキーの形式が不正です(英数字と - _ . のみ、4〜128文字)");
            key = "";
        }

        // --- RTMP送信先(通信先の制限) ---
        String rtmp = c.getString("youtube.rtmp-url", "rtmp://a.rtmp.youtube.com/live2").trim();
        boolean allowCustom = c.getBoolean("youtube.allow-custom-host", false);
        String rtmpErr = UrlRules.checkRtmp(rtmp, allowCustom, false);
        if (rtmpErr != null) fatal.add("youtube.rtmp-url: " + rtmpErr);

        // --- 映像・音声 ---
        int width = num(c, "video.width", 1280, 320, 3840, w) & ~1;
        int height = num(c, "video.height", 720, 180, 2160, w) & ~1;
        int fps = num(c, "video.fps", 30, 5, 60, w);
        int vk = num(c, "video.bitrate-kbps", 2500, 300, 20000, w);
        int ak = num(c, "audio.bitrate-kbps", 128, 32, 320, w);
        boolean audio = c.getBoolean("audio.enabled", true);

        String codec = c.getString("video.codec", "libx264").trim();
        if (!CODECS.contains(codec)) {
            w.add("video.codec「" + codec + "」は未対応のため libx264 を使用します");
            codec = "libx264";
        }
        String preset = c.getString("video.preset", "veryfast").trim();
        if (!PRESET.matcher(preset).matches()) {
            w.add("video.preset が不正なため veryfast を使用します");
            preset = "veryfast";
        }
        String tune = c.getString("video.tune", "zerolatency").trim();
        if (!TUNE.matcher(tune).matches()) {
            w.add("video.tune が不正なため zerolatency を使用します");
            tune = "zerolatency";
        }
        int threads = num(c, "video.threads", 2, 0, 16, w);
        String rateControl = c.getString("video.rate-control", "cbr").trim().toLowerCase();
        if (!rateControl.equals("cbr") && !rateControl.equals("vbr")) {
            w.add("video.rate-control は cbr / vbr のいずれかです。cbr を使用します");
            rateControl = "cbr";
        }
        String x264Params = c.getString("video.x264-params", "").trim();
        if (!X264_PARAMS.matcher(x264Params).matches()) {
            w.add("video.x264-params に使えない文字が含まれています。無視します");
            x264Params = "";
        }
        Settings.Video video = new Settings.Video(rateControl,
                c.getBoolean("video.send-only-changed-frames", true),
                num(c, "video.keepalive-fps", 5, 1, 30, w), x264Params);
        boolean lowPrio = c.getBoolean("video.low-priority", true);

        String ffmpeg = c.getString("ffmpeg.path", "ffmpeg").trim();
        if (ffmpeg.isEmpty()) ffmpeg = "ffmpeg";

        boolean rcEnabled = c.getBoolean("reconnect.enabled", true);
        int rcMax = num(c, "reconnect.max-attempts", 0, 0, 1000, w);
        int rcInit = num(c, "reconnect.initial-delay-seconds", 5, 1, 600, w);
        int rcMaxDelay = Math.max(rcInit, num(c, "reconnect.max-delay-seconds", 60, 1, 3600, w));

        String backupUrl = c.getString("youtube.backup-ingest.url", "rtmp://b.rtmp.youtube.com/live2?backup=1").trim();
        boolean backupEnabled = c.getBoolean("youtube.backup-ingest.enabled", false);
        if (backupEnabled) {
            String err = UrlRules.checkRtmp(backupUrl, allowCustom, true);
            if (err != null) {
                w.add("youtube.backup-ingest.url: " + err + "。バックアップへの切替を無効にします");
                backupEnabled = false;
            }
        }
        Settings.Net net = new Settings.Net(
                num(c, "reconnect.connect-timeout-seconds", 25, 10, 120, w),
                c.getBoolean("reconnect.preflight-check", true),
                num(c, "reconnect.preflight-timeout-seconds", 5, 1, 30, w),
                c.getBoolean("reconnect.jitter", true),
                num(c, "reconnect.stable-seconds", 60, 10, 3600, w),
                backupEnabled, backupUrl,
                num(c, "youtube.backup-ingest.switch-after-failures", 2, 1, 20, w),
                c.getBoolean("monitor.slow-encode-warning", true),
                num(c, "monitor.slow-encode-seconds", 60, 15, 3600, w));

        // --- 見た目 ---
        String bg = insideDataDir(c.getString("background.path", "background.png"), dataDir, "background.path", w);
        String fontFile = insideDataDir(c.getString("theme.font-file", ""), dataDir, "theme.font-file", w);
        String fontName = c.getString("theme.font", "SansSerif").trim();
        if (fontName.isEmpty()) fontName = "SansSerif";
        String theme = c.getString("theme.name", "default").trim().toLowerCase();
        if (!THEMES.contains(theme)) {
            w.add("theme.name「" + theme + "」は未対応です(default / nether / end)。default を使用します");
            theme = "default";
        }
        String accent = c.getString("theme.accent", "").trim();
        if (!accent.isEmpty() && !accent.matches("#[0-9a-fA-F]{6}")) {
            w.add("theme.accent は #RRGGBB 形式で指定してください。無視します");
            accent = "";
        }
        int opacity = num(c, "theme.panel-opacity", 62, 0, 100, w);
        String style = c.getString("theme.style", "glass").trim().toLowerCase();
        if (!style.equals("glass") && !style.equals("flat")) {
            w.add("theme.style は glass / flat のいずれかです。glass を使用します");
            style = "glass";
        }
        String panelColor = color(c, "theme.panel-color", w);
        String textColor = color(c, "theme.text-color", w);
        String logo = insideDataDir(c.getString("overlay.logo", ""), dataDir, "overlay.logo", w);
        Settings.Visual visual = new Settings.Visual(style,
                num(c, "theme.blur-radius", 14, 0, 40, w),
                c.getBoolean("theme.shadow", true),
                c.getBoolean("theme.accent-line", true),
                num(c, "theme.corner-radius", 16, 0, 40, w),
                c.getBoolean("theme.text-shadow", true),
                panelColor, textColor, logo,
                num(c, "overlay.logo-opacity", 90, 0, 100, w),
                c.getBoolean("chat.youtube-badge", true),
                c.getBoolean("performance.partial-redraw", true),
                c.getBoolean("performance.static-layer-cache", true));

        List<String> columns = new ArrayList<>();
        if (c.isList("header.columns")) {
            for (String col : c.getStringList("header.columns")) {
                String k = col == null ? "" : col.trim().toLowerCase();
                if (!HEADER_COLUMNS.contains(k)) {
                    w.add("header.columns の「" + col + "」は未対応です(online / viewers / uptime / tps / clock)");
                } else if (!columns.contains(k) && columns.size() < 4) {
                    columns.add(k);
                }
            }
        } else {
            columns.addAll(List.of("online", "viewers", "uptime"));
        }
        if (!c.getBoolean("display.uptime", true)) columns.remove("uptime");
        String clockFormat = c.getString("header.clock-format", "HH:mm").trim();
        try {
            java.time.format.DateTimeFormatter.ofPattern(clockFormat);
        } catch (IllegalArgumentException e) {
            w.add("header.clock-format が不正なため HH:mm を使用します");
            clockFormat = "HH:mm";
        }
        String timezone = zone(c.getString("header.timezone", ""), "header.timezone", w);
        Settings.Header header = new Settings.Header(List.copyOf(columns), clockFormat, timezone);

        boolean showChat = c.getBoolean("display.chat", true);
        boolean showJoinLeave = c.getBoolean("display.join-leave", true);
        boolean showPlayers = c.getBoolean("display.player-list", true);
        boolean showUptime = c.getBoolean("display.uptime", true);

        int chatLines = num(c, "chat.max-lines", 10, 1, 30, w);
        int chatLen = num(c, "chat.max-length", 100, 10, 200, w);
        int chatLife = num(c, "chat.lifetime-seconds", 0, 0, 3600, w);
        List<Pattern> blocked = new ArrayList<>();
        for (String p : c.getStringList("chat.blocked-patterns")) {
            if (blocked.size() >= 50 || p == null || p.isBlank() || p.length() > 200) continue;
            try {
                blocked.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                w.add("chat.blocked-patterns の正規表現が不正なため無視します: " + p);
            }
        }
        int toastLife = num(c, "notifications.lifetime-seconds", 6, 2, 60, w);

        String bgmDir = insideDataDir(c.getString("bgm.folder", "bgm"), dataDir, "bgm.folder", w);
        if (bgmDir.isEmpty()) bgmDir = "bgm";
        Settings.Bgm bgm = new Settings.Bgm(
                c.getBoolean("bgm.enabled", true),
                bgmDir,
                c.getBoolean("bgm.shuffle", true),
                num(c, "bgm.volume", 50, 0, 100, w),
                c.getBoolean("bgm.show-now-playing", true));

        List<String> infoMsgs = new ArrayList<>();
        for (String m : c.getStringList("info.messages")) {
            String t = TextUtil.sanitize(m, 90);
            if (!t.isBlank() && infoMsgs.size() < 10) infoMsgs.add(t);
        }
        Settings.Info info = new Settings.Info(c.getBoolean("info.enabled", true), List.copyOf(infoMsgs),
                num(c, "info.interval-seconds", 8, 3, 120, w), c.getBoolean("info.show-progress", true));

        String liveUrl = c.getString("promo.live-url", "").trim();
        if (!liveUrl.isEmpty() && !liveUrl.matches("^https://[A-Za-z0-9.\\-]+(/[^\\s]*)?$")) {
            w.add("promo.live-url は https:// で始まるURLを指定してください。無視します");
            liveUrl = "";
        }
        Settings.Promo promo = new Settings.Promo(liveUrl,
                c.getBoolean("promo.broadcast-on-live", true),
                num(c, "promo.broadcast-interval-minutes", 30, 0, 1440, w),
                TextUtil.sanitize(c.getString("promo.broadcast-message", "サーバーをYouTubeでライブ配信中です！ {url}"), 200),
                c.getBoolean("promo.join-notice", true),
                TextUtil.sanitize(c.getString("promo.join-notice-message",
                        "このサーバーはYouTubeでライブ配信中です。チャットとプレイヤー名が配信に映ります。"), 200));
        boolean showEvents = c.getBoolean("display.events", true);
        boolean autoStart = c.getBoolean("auto-start.enabled", false);
        int autoDelay = num(c, "auto-start.delay-seconds", 15, 0, 600, w);
        int stall = num(c, "reconnect.stall-timeout-seconds", 20, 10, 300, w);

        boolean hideVanished = c.getBoolean("players.hide-vanished", true);
        String bedrockPrefix = c.getString("players.bedrock-prefix", ".");
        if (bedrockPrefix == null) bedrockPrefix = ".";
        bedrockPrefix = bedrockPrefix.trim();
        String sort = c.getString("players.sort", "join").trim().toLowerCase();
        if (!sort.equals("join") && !sort.equals("name")) {
            w.add("players.sort は join / name のいずれかです。join を使用します");
            sort = "join";
        }
        Settings.PlayersView playersView = new Settings.PlayersView(
                c.getBoolean("players.show-ping", true), c.getBoolean("players.show-platform", true), sort);

        YouTubeSettings yt = youtube(c, secrets, w);
        String clientSecretVal = env("YTSTREAM_CLIENT_SECRET");
        if (clientSecretVal.isEmpty() && secrets != null) clientSecretVal = secrets.getString("youtube-client-secret", "").trim();
        if (clientSecretVal.isEmpty()) {
            clientSecretVal = c.getString("youtube.api.client-secret", "").trim();
            if (!clientSecretVal.isEmpty()) {
                w.add("OAuthクライアントシークレットが config.yml に平文で保存されています。環境変数 YTSTREAM_CLIENT_SECRET か secrets.yml の利用を推奨します。");
            }
        }
        if (yt.enabled() && (yt.clientId().isEmpty() || clientSecretVal.isEmpty())) {
            w.add("youtube.api.enabled が true ですが OAuthクライアントID/シークレットが未設定です。"
                    + (yt.fallbackToKey() ? "ストリームキーのみで配信します。" : ""));
        }
        int noticeLife = num(c, "notifications.notice-lifetime-seconds", 10, 3, 120, w);

        // --- レイアウト(相対座標) ---
        Map<String, Rect> layout = new LinkedHashMap<>();
        for (Map.Entry<String, Rect> e : Settings.DEFAULT_LAYOUT.entrySet()) {
            String p = "layout." + e.getKey();
            Rect d = e.getValue();
            if (!c.isConfigurationSection(p)) {
                layout.put(e.getKey(), d);
                continue;
            }
            double x = c.getDouble(p + ".x", d.x());
            double y = c.getDouble(p + ".y", d.y());
            double rw = c.getDouble(p + ".w", d.w());
            double rh = c.getDouble(p + ".h", d.h());
            boolean ok = x >= 0 && y >= 0 && rw > 0.02 && rh > 0.02 && x + rw <= 1.0001 && y + rh <= 1.0001;
            if (ok) {
                layout.put(e.getKey(), new Rect(x, y, rw, rh));
            } else {
                w.add(p + " の座標が不正です(0〜1、はみ出し不可)。既定値を使用します");
                layout.put(e.getKey(), d);
            }
        }

        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : Settings.DEFAULT_LABELS.entrySet()) {
            String v = TextUtil.sanitize(c.getString("labels." + e.getKey(), e.getValue()), 60);
            labels.put(e.getKey(), v.isEmpty() ? e.getValue() : v);
        }

        Settings s = new Settings(serverName, rtmp, width, height, fps, vk, ak, audio,
                codec, preset, tune, threads, lowPrio, ffmpeg,
                rcEnabled, rcMax, rcInit, rcMaxDelay,
                bg, fontName, fontFile, theme, accent, opacity,
                showChat, showJoinLeave, showPlayers, showUptime,
                chatLines, chatLen, chatLife, List.copyOf(blocked), toastLife,
                bgm, info, promo, showEvents, autoStart, autoDelay, stall,
                hideVanished, bedrockPrefix, layout, labels,
                video, net, visual, header, playersView, yt, noticeLife, List.copyOf(fatal));
        return new Result(s, new Secret(key), new Secret(clientSecretVal), w);
    }

    private static int num(FileConfiguration c, String path, int def, int min, int max, List<String> w) {
        int v = c.getInt(path, def);
        int clamped = Math.max(min, Math.min(max, v));
        if (clamped != v) w.add(path + " は " + min + "〜" + max + " の範囲で指定してください(" + v + " → " + clamped + " に補正)");
        return clamped;
    }

    private static YouTubeSettings youtube(FileConfiguration c, YamlConfiguration secrets, List<String> w) {
        YouTubeSettings d = YouTubeSettings.defaults();
        String p = "youtube.api.";
        String clientId = env("YTSTREAM_CLIENT_ID");
        if (clientId.isEmpty() && secrets != null) clientId = secrets.getString("youtube-client-id", "").trim();
        if (clientId.isEmpty()) clientId = c.getString(p + "client-id", "").trim();
        if (!clientId.isEmpty() && !clientId.matches("^[A-Za-z0-9._\\-]{8,200}$")) {
            w.add("youtube.api.client-id の形式が不正です。無視します");
            clientId = "";
        }
        String source = pick(c, p + "stream-source", "own", Set.of("own", "key"), w);
        String mode = pick(c, p + "broadcast.mode", "reuse", Set.of("reuse", "create"), w);
        String privacy = pick(c, p + "broadcast.privacy", "public", Set.of("public", "unlisted", "private"), w);
        String latency = c.getString(p + "broadcast.latency", "normal").trim();
        if (latency.equalsIgnoreCase("ultralow")) latency = "ultraLow";
        if (!Set.of("normal", "low", "ultraLow").contains(latency)) {
            w.add(p + "broadcast.latency は normal / low / ultraLow のいずれかです。normal を使用します");
            latency = "normal";
        }
        String streamTitle = TextUtil.sanitize(c.getString(p + "stream-title", d.streamTitle()), 100);
        if (streamTitle.isBlank()) streamTitle = d.streamTitle();
        String title = TextUtil.sanitize(c.getString(p + "broadcast.title", d.title()), 200);
        if (title.isBlank()) title = d.title();
        String desc = c.getString(p + "broadcast.description", d.description());
        if (desc == null) desc = "";
        if (desc.length() > 4900) desc = desc.substring(0, 4900);
        String format = TextUtil.sanitize(c.getString(p + "chat.format", d.chatFormat()), 100);
        if (format.isBlank()) format = d.chatFormat();
        return new YouTubeSettings(
                c.getBoolean(p + "enabled", false), clientId, source, streamTitle, mode,
                title, desc, privacy,
                c.getBoolean(p + "broadcast.made-for-kids", false), latency,
                c.getBoolean(p + "broadcast.dvr", true),
                c.getBoolean(p + "broadcast.embed", true),
                c.getBoolean(p + "broadcast.auto-stop", false),
                c.getBoolean(p + "end-on-stop", true),
                num(c, p + "go-live-timeout-seconds", 90, 20, 600, w),
                c.getBoolean(p + "fallback-to-stream-key", true),
                c.getBoolean(p + "viewers.enabled", true),
                num(c, p + "viewers.interval-seconds", 60, 15, 3600, w),
                c.getBoolean(p + "health-check", true),
                c.getBoolean(p + "chat.enabled", false),
                num(c, p + "chat.interval-seconds", 30, 5, 600, w),
                c.getBoolean(p + "chat.relay-to-game", true),
                c.getBoolean(p + "chat.show-on-stream", true),
                format,
                num(c, p + "quota.daily-budget", 10000, 500, 10_000_000, w),
                num(c, p + "quota.reserve", 600, 0, 100_000, w),
                zone(c.getString(p + "timezone", ""), p + "timezone", w),
                num(c, p + "http-timeout-seconds", 15, 5, 60, w));
    }

    private static String pick(FileConfiguration c, String path, String def, Set<String> allowed, List<String> w) {
        String v = c.getString(path, def);
        v = v == null ? def : v.trim().toLowerCase();
        if (!allowed.contains(v)) {
            w.add(path + " は " + String.join(" / ", allowed) + " のいずれかです。" + def + " を使用します");
            return def;
        }
        return v;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }

    private static String color(FileConfiguration c, String path, List<String> w) {
        String v = c.getString(path, "");
        v = v == null ? "" : v.trim();
        if (!v.isEmpty() && !v.matches("#[0-9a-fA-F]{6}")) {
            w.add(path + " は #RRGGBB 形式で指定してください。無視します");
            return "";
        }
        return v;
    }

    private static String zone(String v, String path, List<String> w) {
        if (v == null || v.isBlank()) return "";
        try {
            java.time.ZoneId.of(v.trim());
            return v.trim();
        } catch (RuntimeException e) {
            w.add(path + "「" + v + "」は不明なタイムゾーンです(例: Asia/Tokyo)。サーバーの既定を使用します");
            return "";
        }
    }

    /** データフォルダ外(../ や絶対パス)を指すパスは拒否する。 */
    private static String insideDataDir(String value, Path dataDir, String name, List<String> w) {
        if (value == null || value.isBlank()) return "";
        try {
            Path base = dataDir.toAbsolutePath().normalize();
            Path p = base.resolve(value.trim()).normalize();
            if (!p.startsWith(base)) {
                w.add(name + " はプラグインフォルダ内のパスのみ指定できます。無視します");
                return "";
            }
            return value.trim();
        } catch (RuntimeException e) {
            w.add(name + " が不正なパスです。無視します");
            return "";
        }
    }
}
