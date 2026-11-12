package dev.ytstream.youtube;

import dev.ytstream.config.YouTubeSettings;
import dev.ytstream.util.Json;
import dev.ytstream.util.Secret;
import dev.ytstream.util.TextUtil;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * YouTube上の「配信枠(liveBroadcast)」と「ストリーム(liveStream)」を管理する。
 *
 * <p>ストリームキーだけで送信する従来方式では、YouTube側で配信枠が「自動開始」されないと公開されず、
 * YouTube Studio の配信ページを開いたときに初めて枠が用意される、という挙動に左右されていた。
 * ここでは API で枠を用意・紐付けし、送信が届いたことを確認してから自分で「ライブ」へ切り替えるため、
 * 配信ページを開かなくても確実に公開される。
 *
 * <p>すべてのメソッドはブロッキング(呼び出し側の専用スレッドで実行すること)。
 */
public final class BroadcastController {
    public record Target(String rtmpBase, String backupBase, Secret key, String broadcastId, String streamId,
                         String liveChatId, String watchUrl, boolean viaApi) {
        public static Target plain(String rtmpBase, String backupBase, Secret key) {
            return new Target(rtmpBase, backupBase, key, "", "", "", "", false);
        }
    }

    public enum GoLive { LIVE, TIMEOUT, ENDED, CANCELLED }

    /** 取得した情報の受け取り先(任意スレッドから呼ばれる)。 */
    public interface Sink {
        void viewers(long count);

        void chat(String author, String message, boolean moderator, boolean owner, boolean superChat);

        void notice(String message);
    }

    private static final Set<String> USABLE_LIFECYCLE = Set.of("created", "ready", "testStarting", "testing", "liveStarting", "live");

    private final Logger log;
    private final GoogleOAuth oauth;
    private final QuotaMeter quota = new QuotaMeter(10000, 600);
    private final YouTubeApi api;
    private final ApiHttp http;
    private volatile YouTubeSettings cfg = YouTubeSettings.defaults();
    private volatile String serverName = "";

    // 現在の枠のポーリング状態(APIスレッドのみが触る)
    private String polledBroadcast = "";
    private long nextViewersMs;
    private long nextChatMs;
    private String chatPageToken;
    private boolean chatPrimed;
    private boolean chatStopped;
    private boolean quotaWarned;
    private volatile String health = "-";
    private volatile long viewers = -1;
    private volatile String lastHealthIssue = "";

    public BroadcastController(Path dataDir, Logger log) {
        this(dataDir, log, URI.create("https://oauth2.googleapis.com"), URI.create("https://www.googleapis.com/youtube/v3"));
    }

    BroadcastController(Path dataDir, Logger log, URI oauthBase, URI apiBase) {
        this.log = log;
        this.http = new ApiHttp(15);
        this.oauth = new GoogleOAuth(http, oauthBase, dataDir);
        this.api = new YouTubeApi(http, apiBase, oauth, quota);
    }

    public synchronized void configure(YouTubeSettings s, Secret clientSecret, String serverName) {
        this.cfg = s;
        this.serverName = serverName;
        oauth.setCredentials(s.clientId(), clientSecret);
        quota.configure(s.quotaBudget(), s.quotaReserve());
        http.setTimeout(s.httpTimeoutSec());
    }

    /** 使っていた枠を忘れる(外部で終了された等)。次回の prepare で探し直す/作り直す。 */
    public void forget() {
        resetPolling("");
    }

    public GoogleOAuth oauth() {
        return oauth;
    }

    public String[] secrets() {
        return oauth.secrets();
    }

    public String health() {
        return health;
    }

    public long viewers() {
        return viewers;
    }

    public int quotaUsed() {
        return quota.used();
    }

    public int quotaBudget() {
        return quota.budget();
    }

    // ---------------------------------------------------------------- 準備

    /**
     * 送信先ストリームと配信枠を用意して紐付ける。
     *
     * @param preferRtmps rtmps(TLS, 443番)の取り込みURLを優先する
     * @param configuredKey config/環境変数のストリームキー(stream-source: key のとき使用)
     */
    public Target prepare(boolean preferRtmps, Secret configuredKey) throws ApiException {
        YouTubeSettings s = cfg;
        YouTubeApi a = api;
        Map<String, Object> stream = resolveStream(a, s, configuredKey);
        String streamId = Json.str(stream, "id");
        Map<String, Object> ingest = Json.obj(Json.obj(stream, "cdn"), "ingestionInfo");
        String key = Json.str(ingest, "streamName");
        String addr = preferRtmps ? Json.str(ingest, "rtmpsIngestionAddress") : "";
        if (addr.isEmpty()) addr = Json.str(ingest, "ingestionAddress");
        String backup = preferRtmps ? Json.str(ingest, "rtmpsBackupIngestionAddress") : "";
        if (backup.isEmpty()) backup = Json.str(ingest, "backupIngestionAddress");
        if (key.isEmpty() || addr.isEmpty()) {
            throw new ApiException(200, "noIngestion", "ストリームの取り込み情報を取得できませんでした");
        }

        Map<String, Object> b = findOrCreateBroadcast(a, s, streamId);
        String id = Json.str(b, "id");
        if (!streamId.equals(Json.pathStr(b, "contentDetails.boundStreamId"))) {
            a.post("/liveBroadcasts/bind", YouTubeApi.q("id", id, "part", "id,contentDetails", "streamId", streamId), null, 1);
        }
        String chatId = Json.pathStr(b, "snippet.liveChatId");
        if (!id.equals(polledBroadcast)) resetPolling(id);
        return new Target(addr, backup, new Secret(key), id, streamId, chatId, "https://www.youtube.com/watch?v=" + id, true);
    }

    private Map<String, Object> resolveStream(YouTubeApi a, YouTubeSettings s, Secret key) throws ApiException {
        boolean byKey = s.streamSource().equals("key") && key.isSet();
        List<Map<String, Object>> all = listAll(a, "/liveStreams",
                YouTubeApi.q("part", "id,snippet,cdn,status,contentDetails", "mine", "true", "maxResults", "50"), 4);
        for (Map<String, Object> st : all) {
            if (byKey) {
                if (key.reveal().equals(Json.pathStr(st, "cdn.ingestionInfo.streamName"))) return st;
            } else if (s.streamTitle().equals(Json.pathStr(st, "snippet.title"))
                    && "rtmp".equals(Json.pathStr(st, "cdn.ingestionType"))) {
                return st;
            }
        }
        if (byKey) {
            throw new ApiException(404, "streamNotFound", "設定されたストリームキーに一致するストリームがこのチャンネルにありません"
                    + "(youtube.api.stream-source を own にすると専用ストリームを自動作成します)");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snippet", Map.of("title", s.streamTitle(), "description", "Created by YtStream plugin"));
        body.put("cdn", Map.of("ingestionType", "rtmp", "resolution", "variable", "frameRate", "variable"));
        body.put("contentDetails", Map.of("isReusable", true));
        log.info("YouTubeに専用ストリーム「" + s.streamTitle() + "」を作成します。");
        return a.post("/liveStreams", YouTubeApi.q("part", "id,snippet,cdn,contentDetails,status"), body, 50);
    }

    private Map<String, Object> findOrCreateBroadcast(YouTubeApi a, YouTubeSettings s, String streamId) throws ApiException {
        // 1. 直前に使っていた枠がまだ使えるなら最優先(再接続時に同じ配信URLを保つ)
        if (!polledBroadcast.isEmpty()) {
            Map<String, Object> r = a.get("/liveBroadcasts",
                    YouTubeApi.q("part", "id,snippet,contentDetails,status", "id", polledBroadcast), 1);
            for (Object o : Json.arr(r, "items")) {
                if (USABLE_LIFECYCLE.contains(Json.pathStr(o, "status.lifeCycleStatus"))) return cast(o);
            }
        }
        // 2. 再利用モード: このストリームに紐付いた配信中/予定の枠
        if (s.broadcastMode().equals("reuse")) {
            for (String status : List.of("active", "upcoming")) {
                List<Map<String, Object>> list = listAll(a, "/liveBroadcasts", YouTubeApi.q(
                        "part", "id,snippet,contentDetails,status", "broadcastStatus", status,
                        "broadcastType", "event", "maxResults", "50"), 2);
                Map<String, Object> unbound = null;
                for (Map<String, Object> b : list) {
                    if (!USABLE_LIFECYCLE.contains(Json.pathStr(b, "status.lifeCycleStatus"))) continue;
                    String bound = Json.pathStr(b, "contentDetails.boundStreamId");
                    if (streamId.equals(bound)) return b;
                    if (bound.isEmpty() && unbound == null && status.equals("upcoming")
                            && Json.pathStr(b, "snippet.title").equals(title(s))) unbound = b;
                }
                if (unbound != null) return unbound;
            }
        }
        // 3. 新規作成(自動開始ON・モニターストリームOFF=ready から直接 live へ遷移できる)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snippet", Map.of(
                "title", title(s),
                "description", description(s),
                "scheduledStartTime", Instant.now().toString()));
        body.put("status", Map.of(
                "privacyStatus", s.privacy(),
                "selfDeclaredMadeForKids", s.madeForKids()));
        Map<String, Object> cd = new LinkedHashMap<>();
        cd.put("enableAutoStart", true);
        cd.put("enableAutoStop", s.autoStop());
        cd.put("enableDvr", s.dvr());
        cd.put("enableEmbed", s.embed());
        cd.put("latencyPreference", s.latency());
        cd.put("monitorStream", Map.of("enableMonitorStream", false));
        body.put("contentDetails", cd);
        Map<String, Object> created = a.post("/liveBroadcasts", YouTubeApi.q("part", "id,snippet,contentDetails,status"), body, 50);
        log.info("YouTubeに配信枠を作成しました: " + Json.pathStr(created, "snippet.title") + " (" + Json.str(created, "id") + ")");
        return created;
    }

    private static List<Map<String, Object>> listAll(YouTubeApi a, String path, Map<String, String> q, int maxPages)
            throws ApiException {
        List<Map<String, Object>> out = new ArrayList<>();
        String page = null;
        for (int i = 0; i < maxPages; i++) {
            Map<String, String> qq = new LinkedHashMap<>(q);
            if (page != null) qq.put("pageToken", page);
            Map<String, Object> r = a.get(path, qq, 1);
            for (Object o : Json.arr(r, "items")) {
                if (o instanceof Map) out.add(cast(o));
            }
            page = Json.str(r, "nextPageToken");
            if (page.isEmpty()) break;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    String title(YouTubeSettings s) {
        return clean(placeholders(s.title(), s), 100);
    }

    private String description(YouTubeSettings s) {
        return clean(placeholders(s.description(), s), 4900);
    }

    private String placeholders(String t, YouTubeSettings s) {
        ZoneId zone;
        try {
            zone = s.timezone().isBlank() ? ZoneId.systemDefault() : ZoneId.of(s.timezone());
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        return t.replace("{server}", serverName)
                .replace("{date}", now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT)))
                .replace("{time}", now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)));
    }

    /** YouTubeはタイトル/説明に < > を許可しない。 */
    private static String clean(String t, int max) {
        String s = TextUtil.sanitize(t.replace('<', '(').replace('>', ')'), max);
        return s.isBlank() ? "Live" : s;
    }

    // ---------------------------------------------------------------- ライブ開始/終了

    /**
     * 送信が YouTube に届いたことを確認して「ライブ」へ切り替える。自動開始で先に live になった場合もそのまま成功。
     * 失敗しても例外は投げず、結果を返す(配信の送信自体は継続させるため)。
     */
    public GoLive goLive(Target t, BooleanSupplier cancelled) {
        if (!t.viaApi()) return GoLive.LIVE;
        YouTubeApi a = api;
        long deadline = System.currentTimeMillis() + cfg.goLiveTimeoutSec() * 1000L;
        String lastLifecycle = "";
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.getAsBoolean()) return GoLive.CANCELLED;
            try {
                Map<String, Object> b = first(a.get("/liveBroadcasts",
                        YouTubeApi.q("part", "id,status,contentDetails", "id", t.broadcastId()), 1));
                if (b == null) return GoLive.ENDED;
                String lc = Json.pathStr(b, "status.lifeCycleStatus");
                lastLifecycle = lc;
                switch (lc) {
                    case "live" -> {
                        return GoLive.LIVE;
                    }
                    case "complete", "revoked" -> {
                        return GoLive.ENDED;
                    }
                    case "liveStarting", "testStarting" -> {
                        // YouTube側で遷移中
                    }
                    default -> {
                        String ss = streamStatus(a, t.streamId());
                        if (ss.equals("active")) {
                            // モニターストリームが有効な既存の枠は testing を経由する必要がある(API仕様)
                            boolean monitor = Json.bool(Json.obj(Json.obj(b, "contentDetails"), "monitorStream"),
                                    "enableMonitorStream", false);
                            String to = lc.equals("ready") && monitor ? "testing" : "live";
                            try {
                                a.post("/liveBroadcasts/transition", YouTubeApi.q("broadcastStatus", to,
                                        "id", t.broadcastId(), "part", "id,status"), null, 1);
                                if (to.equals("live")) log.info("YouTubeの配信を「ライブ」に切り替えました。");
                            } catch (ApiException e) {
                                switch (e.reason()) {
                                    case "redundantTransition" -> {
                                        // 自動開始で既に遷移済み
                                    }
                                    case "invalidTransition", "errorStreamInactive" -> {
                                        // 自動開始による遷移中、またはまだ映像が届いていない → 次の周期で再確認
                                    }
                                    default -> {
                                        if (!e.isTransient()) log.warning("ライブ開始の切替に失敗: " + e.getMessage());
                                        if (e.isQuota() || e.isAuth()) return GoLive.TIMEOUT;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (ApiException e) {
                if (e.isQuota() || e.isAuth()) {
                    log.warning("ライブ開始の確認を中止します: " + e.getMessage());
                    return GoLive.TIMEOUT;
                }
                // 通信の一時的な失敗は次の周期で再試行
            }
            if (!sleep(3000, cancelled)) return GoLive.CANCELLED;
        }
        log.warning("YouTube側が" + cfg.goLiveTimeoutSec() + "秒以内にライブになりませんでした(状態: "
                + (lastLifecycle.isEmpty() ? "?" : lastLifecycle) + ")。送信は継続します。");
        return GoLive.TIMEOUT;
    }

    private static String streamStatus(YouTubeApi a, String streamId) throws ApiException {
        Map<String, Object> st = first(a.get("/liveStreams", YouTubeApi.q("part", "id,status", "id", streamId), 1));
        return st == null ? "" : Json.pathStr(st, "status.streamStatus");
    }

    private static Map<String, Object> first(Map<String, Object> r) {
        List<Object> items = Json.arr(r, "items");
        return items.isEmpty() || !(items.get(0) instanceof Map) ? null : cast(items.get(0));
    }

    private static boolean sleep(long ms, BooleanSupplier cancelled) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelled.getAsBoolean()) return false;
            try {
                Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 配信終了(end-on-stop のとき)。ライブ前の枠は次回の再利用のため残す。 */
    public void end(Target t) {
        if (!t.viaApi() || !cfg.endOnStop()) return;
        try {
            Map<String, Object> b = first(api.get("/liveBroadcasts", YouTubeApi.q("part", "id,status", "id", t.broadcastId()), 1));
            if (b == null) return;
            String lc = Json.pathStr(b, "status.lifeCycleStatus");
            if (lc.equals("live") || lc.equals("testing") || lc.equals("liveStarting")) {
                api.post("/liveBroadcasts/transition", YouTubeApi.q("broadcastStatus", "complete",
                        "id", t.broadcastId(), "part", "id,status"), null, 1);
                log.info("YouTubeの配信を終了しました。");
                resetPolling("");
            }
        } catch (ApiException e) {
            log.warning("YouTubeの配信終了処理に失敗しました(YouTube Studioで終了してください): " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 配信中の情報取得

    private void resetPolling(String broadcastId) {
        polledBroadcast = broadcastId;
        chatPageToken = null;
        chatPrimed = false;
        chatStopped = false;
        nextViewersMs = 0;
        nextChatMs = 0;
        viewers = -1;
        health = "-";
    }

    /** 配信中に定期的(数秒ごと)に呼ぶ。設定の間隔に達した項目だけ取得する。 */
    public void tick(Target t, Sink sink) {
        if (!t.viaApi()) return;
        YouTubeSettings s = cfg;
        long now = System.currentTimeMillis();
        if ((s.viewers() || s.health()) && now >= nextViewersMs) {
            nextViewersMs = now + s.viewersIntervalSec() * 1000L;
            if (allow(s.viewers() && s.health() ? 2 : 1, sink)) {
                try {
                    if (s.viewers()) {
                        Map<String, Object> v = first(api.get("/videos",
                                YouTubeApi.q("part", "liveStreamingDetails", "id", t.broadcastId()), 1));
                        long n = v == null ? -1 : Json.num(Json.obj(v, "liveStreamingDetails"), "concurrentViewers", -1);
                        if (n >= 0 && n != viewers) {
                            viewers = n;
                            sink.viewers(n);
                        }
                    }
                    if (s.health()) updateHealth(t, sink);
                } catch (ApiException e) {
                    onPollError(e, sink);
                }
            }
        }
        if (s.chat() && !chatStopped && !t.liveChatId().isEmpty() && now >= nextChatMs) {
            nextChatMs = now + s.chatIntervalSec() * 1000L;
            if (allow(5, sink)) {
                try {
                    pollChat(t, sink, s);
                } catch (ApiException e) {
                    if (e.reason().equals("liveChatEnded") || e.reason().equals("liveChatNotFound")
                            || e.reason().equals("liveChatDisabled")) {
                        chatStopped = true;
                    } else {
                        onPollError(e, sink);
                    }
                }
            }
        }
    }

    private boolean allow(int units, Sink sink) {
        if (quota.allowOptional(units)) return true;
        if (!quotaWarned) {
            quotaWarned = true;
            sink.notice("YouTube APIの1日のクォータ(" + quota.budget() + ")が残り少ないため、視聴者数/チャットの取得を一時停止しました"
                    + "(配信の開始・終了用に予約分を残しています。太平洋時間0時に再開)。");
        }
        return false;
    }

    private void onPollError(ApiException e, Sink sink) {
        if (e.isQuota()) {
            allow(Integer.MAX_VALUE / 2, sink);
        } else if (!e.isTransient()) {
            log.fine("YouTube情報の取得に失敗: " + e.getMessage());
        }
    }

    private void updateHealth(Target t, Sink sink) throws ApiException {
        Map<String, Object> st = first(api.get("/liveStreams", YouTubeApi.q("part", "status", "id", t.streamId()), 1));
        if (st == null) return;
        String h = Json.pathStr(st, "status.healthStatus.status");
        if (h.isEmpty()) h = Json.pathStr(st, "status.streamStatus");
        String prev = health;
        health = h;
        String issue = "";
        for (Object o : Json.arr(Json.obj(Json.obj(st, "status"), "healthStatus"), "configurationIssues")) {
            if ("error".equals(Json.str(o, "severity"))) {
                issue = Json.str(o, "description");
                if (issue.isEmpty()) issue = Json.str(o, "type");
                break;
            }
        }
        if (h.equals("bad") && !prev.equals("bad")) {
            sink.notice("YouTubeの受信状態が「不良」です" + (issue.isEmpty() ? "" : ": " + TextUtil.sanitize(issue, 160))
                    + "(回線速度・ビットレート設定を確認してください)");
        }
        if (!issue.equals(lastHealthIssue) && !issue.isEmpty()) log.warning("YouTubeの受信診断: " + issue);
        lastHealthIssue = issue;
    }

    private void pollChat(Target t, Sink sink, YouTubeSettings s) throws ApiException {
        Map<String, String> q = YouTubeApi.q("liveChatId", t.liveChatId(), "part", "snippet,authorDetails",
                "maxResults", "200", "pageToken", chatPageToken);
        Map<String, Object> r = api.get("/liveChat/messages", q, 5);
        String next = Json.str(r, "nextPageToken");
        if (!next.isEmpty()) chatPageToken = next;
        long hint = Json.num(r, "pollingIntervalMillis", 0);
        if (hint > s.chatIntervalSec() * 1000L) nextChatMs = System.currentTimeMillis() + hint;
        if (!chatPrimed) { // 接続前の古いチャットは流さない
            chatPrimed = true;
            return;
        }
        int n = 0;
        for (Object o : Json.arr(r, "items")) {
            String type = Json.pathStr(o, "snippet.type");
            boolean sc = type.equals("superChatEvent") || type.equals("superStickerEvent");
            if (!type.equals("textMessageEvent") && !sc) continue;
            String msg = Json.pathStr(o, "snippet.displayMessage");
            String author = Json.pathStr(o, "authorDetails.displayName");
            if (author.isEmpty() || (msg.isEmpty() && !sc)) continue;
            sink.chat(author, msg, Json.bool(Json.obj(o, "authorDetails"), "isChatModerator", false),
                    Json.bool(Json.obj(o, "authorDetails"), "isChatOwner", false), sc);
            if (++n >= 30) break; // 1回の取得で溢れさせない
        }
    }

    /** 連携状態の確認用(チャンネル名)。 */
    public String channelTitle() throws ApiException {
        Map<String, Object> c = first(api.get("/channels", YouTubeApi.q("part", "snippet", "mine", "true"), 1));
        return c == null ? "(チャンネルなし)" : Json.pathStr(c, "snippet.title");
    }
}
