package dev.ytstream.config;

import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 検証済みの不変設定。ストリームキーは含まない(Secretとして別管理)。 */
public record Settings(
        String serverName,
        String rtmpUrl,
        int width, int height, int fps,
        int videoKbps, int audioKbps, boolean audioEnabled,
        String codec, String preset, String tune, int threads, boolean lowPriority,
        String ffmpegPath,
        boolean reconnectEnabled, int maxAttempts, int initialDelaySec, int maxDelaySec,
        String backgroundPath, String fontName, String fontFile,
        String themeName, String accentColor, int panelOpacity,
        boolean showChat, boolean showJoinLeave, boolean showPlayerList, boolean showUptime,
        int chatMaxLines, int chatMaxLength, int chatLifetimeSec, List<Pattern> chatBlocked,
        int toastLifetimeSec,
        Bgm bgm, Info info, Promo promo, boolean showEvents,
        boolean autoStart, int autoStartDelaySec, int stallTimeoutSec,
        boolean hideVanished, String bedrockPrefix,
        Map<String, Rect> layout, Map<String, String> labels,
        Video video, Net net, Visual visual, Header header, PlayersView playersView,
        YouTubeSettings youtube, int noticeLifetimeSec,
        List<String> fatalProblems) {

    /** 画面に対する相対座標(0.0〜1.0)。解像度を変えてもレイアウトが崩れない。 */
    public record Rect(double x, double y, double w, double h) {
        public Rectangle toPixels(int width, int height) {
            return new Rectangle(
                    (int) Math.round(x * width), (int) Math.round(y * height),
                    (int) Math.round(w * width), (int) Math.round(h * height));
        }
    }

    /** 映像エンコードの追加設定。 */
    public record Video(String rateControl, boolean sendChangedOnly, int keepaliveFps, String x264Params) {}

    /** 通信の安定化。 */
    public record Net(int connectTimeoutSec, boolean preflight, int preflightTimeoutSec, boolean jitter, int stableSec,
                      boolean backupEnabled, String backupUrl, int backupAfterFailures,
                      boolean slowWarn, int slowSec) {}

    /** 見た目の追加設定と描画最適化。 */
    public record Visual(String style, int blurRadius, boolean shadow, boolean accentLine, int cornerRadius,
                         boolean textShadow, String panelColor, String textColor,
                         String logoPath, int logoOpacity, boolean youtubeBadge,
                         boolean partialRedraw, boolean staticCache) {}

    /** ヘッダーに並べる情報(online / viewers / uptime / tps / clock)。 */
    public record Header(List<String> columns, String clockFormat, String timezone) {}

    /** プレイヤー一覧の表示。 */
    public record PlayersView(boolean showPing, boolean showPlatform, String sort) {}

    public record Bgm(boolean enabled, String dir, boolean shuffle, int volume, boolean showNowPlaying) {}

    /** 画面下部で順番に切り替わるお知らせ(参加アドレス・Discord・ルールなど)。 */
    public record Info(boolean enabled, List<String> messages, int intervalSec, boolean showProgress) {}

    /** サーバー内のプレイヤーへ向けた配信告知。 */
    public record Promo(String liveUrl, boolean broadcastOnLive, int intervalMinutes, String message,
                        boolean joinNotice, String joinNoticeMessage) {}

    public static final Map<String, Rect> DEFAULT_LAYOUT = new LinkedHashMap<>();
    public static final Map<String, String> DEFAULT_LABELS = new LinkedHashMap<>();

    static {
        DEFAULT_LAYOUT.put("header", new Rect(0.02, 0.03, 0.96, 0.13));
        DEFAULT_LAYOUT.put("toasts", new Rect(0.02, 0.19, 0.46, 0.26));
        DEFAULT_LAYOUT.put("chat", new Rect(0.02, 0.50, 0.48, 0.38));
        DEFAULT_LAYOUT.put("players", new Rect(0.76, 0.19, 0.22, 0.69));
        DEFAULT_LAYOUT.put("footer", new Rect(0.02, 0.905, 0.96, 0.065));
        DEFAULT_LAYOUT.put("logo", new Rect(0.53, 0.19, 0.20, 0.20));
        DEFAULT_LABELS.put("online", "オンライン");
        DEFAULT_LABELS.put("uptime", "稼働時間");
        DEFAULT_LABELS.put("players", "プレイヤー");
        DEFAULT_LABELS.put("chat", "チャット");
        DEFAULT_LABELS.put("joined", "{name} が参加しました");
        DEFAULT_LABELS.put("left", "{name} が退出しました");
        DEFAULT_LABELS.put("more", "他 {n} 人");
        DEFAULT_LABELS.put("nowplaying", "♪");
        DEFAULT_LABELS.put("viewers", "視聴者");
        DEFAULT_LABELS.put("tps", "TPS");
        DEFAULT_LABELS.put("clock", "時刻");
        DEFAULT_LABELS.put("live", "LIVE");
    }

    public Rect rect(String key) {
        Rect r = layout.get(key);
        return r != null ? r : DEFAULT_LAYOUT.get(key);
    }

    public String label(String key) {
        String v = labels.get(key);
        return v != null ? v : DEFAULT_LABELS.getOrDefault(key, key);
    }

    /** テストやフォールバック用の既定値。 */
    public static Settings defaults() {
        return new Settings(
                "My Minecraft Server", "rtmp://a.rtmp.youtube.com/live2",
                1280, 720, 30, 2500, 128, true,
                "libx264", "veryfast", "zerolatency", 2, true,
                "ffmpeg",
                true, 10, 5, 60,
                "background.png", "SansSerif", "",
                "default", "", 62,
                true, true, true, true,
                10, 100, 0, List.of(),
                6,
                new Bgm(true, "bgm", true, 50, true),
                new Info(true, List.of("ご視聴ありがとうございます！ サーバーへの参加も大歓迎です"), 8, true),
                new Promo("", true, 30, "サーバーをYouTubeでライブ配信中です！ {url}", true,
                        "このサーバーはYouTubeでライブ配信中です。チャットとプレイヤー名が配信に映ります。"),
                true,
                false, 15, 20,
                true, ".",
                new LinkedHashMap<>(DEFAULT_LAYOUT), new LinkedHashMap<>(DEFAULT_LABELS),
                new Video("cbr", true, 5, ""),
                new Net(25, true, 5, true, 60, false, "rtmp://b.rtmp.youtube.com/live2?backup=1", 2, true, 60),
                new Visual("glass", 14, true, true, 16, true, "", "", "", 90, true, true, true),
                new Header(List.of("online", "viewers", "uptime"), "HH:mm", ""),
                new PlayersView(true, true, "join"),
                YouTubeSettings.defaults(), 10,
                List.of());
    }
}
