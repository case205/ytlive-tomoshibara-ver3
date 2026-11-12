package dev.ytstream.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ytstream.config.YouTubeSettings;
import dev.ytstream.util.Json;
import dev.ytstream.util.Secret;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** ローカルの模擬 Google/YouTube API サーバーで、配信枠の用意 → ライブ切替 → 終了の流れを検証する。 */
class BroadcastControllerTest {
    private HttpServer server;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile String lifecycle = "ready";
    private volatile String streamStatus = "inactive";
    private volatile boolean streamExists;
    private volatile boolean broadcastExists;

    private BroadcastController start(Path dir) throws IOException {
        Files.writeString(dir.resolve(GoogleOAuth.TOKEN_FILE), "refresh_token=rt-123456\n");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        BroadcastController c = new BroadcastController(dir, Logger.getLogger("test"),
                URI.create(base + "/oauth"), URI.create(base + "/yt"));
        YouTubeSettings d = YouTubeSettings.defaults();
        YouTubeSettings s = new YouTubeSettings(true, "client-id-123", "own", "YtStream", "reuse",
                "{server} <live>", "desc", "unlisted", false, "low", true, true, false, true,
                20, true, true, 60, true, true, 30, true, true, d.chatFormat(), 10000, 600, "Asia/Tokyo", 5);
        c.configure(s, new Secret("client-secret-xyz"), "TestServer");
        return c;
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(method + " " + path + (query.isEmpty() ? "" : "?" + query));
        int code = 200;
        Object res;
        if (path.equals("/oauth/token")) {
            res = Map.of("access_token", "at-abcdef", "expires_in", 3600);
        } else if (!"Bearer at-abcdef".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            code = 401;
            res = Map.of("error", Map.of("message", "auth"));
        } else if (path.equals("/yt/liveStreams") && method.equals("GET")) {
            res = Map.of("items", streamExists && (query.contains("mine=true") || query.contains("id=s1"))
                    ? List.of(stream()) : List.of());
        } else if (path.equals("/yt/liveStreams") && method.equals("POST")) {
            streamExists = true;
            assertTrue(body.contains("\"isReusable\":true"));
            res = stream();
        } else if (path.equals("/yt/liveBroadcasts") && method.equals("GET")) {
            res = Map.of("items", broadcastExists ? List.of(broadcast()) : List.of());
        } else if (path.equals("/yt/liveBroadcasts") && method.equals("POST")) {
            broadcastExists = true;
            Map<String, Object> b = Json.parseObject(body);
            assertEquals("TestServer (live)", Json.pathStr(b, "snippet.title"), "< > は置換される");
            assertEquals("unlisted", Json.pathStr(b, "status.privacyStatus"));
            assertEquals(Boolean.TRUE, Json.path(b, "contentDetails.enableAutoStart"));
            assertEquals(Boolean.FALSE, Json.path(b, "contentDetails.monitorStream.enableMonitorStream"));
            res = broadcastUnbound();
        } else if (path.equals("/yt/liveBroadcasts/bind")) {
            res = broadcast();
        } else if (path.equals("/yt/liveBroadcasts/transition")) {
            if (query.contains("broadcastStatus=live")) {
                if (!streamStatus.equals("active")) {
                    code = 403;
                    res = Map.of("error", Map.of("errors", List.of(Map.of("reason", "errorStreamInactive"))));
                } else {
                    lifecycle = "live";
                    res = broadcast();
                }
            } else {
                lifecycle = "complete";
                res = broadcast();
            }
        } else if (path.equals("/yt/videos")) {
            res = Map.of("items", List.of(Map.of("liveStreamingDetails", Map.of("concurrentViewers", "42"))));
        } else {
            code = 404;
            res = Map.of("error", Map.of("message", "not found"));
        }
        byte[] out = Json.write(res).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private Map<String, Object> stream() {
        return Map.of("id", "s1", "snippet", Map.of("title", "YtStream"),
                "cdn", Map.of("ingestionType", "rtmp", "ingestionInfo", Map.of(
                        "streamName", "api-key-0001",
                        "ingestionAddress", "rtmp://a.rtmp.youtube.com/live2",
                        "backupIngestionAddress", "rtmp://b.rtmp.youtube.com/live2?backup=1")),
                "status", Map.of("streamStatus", streamStatus, "healthStatus", Map.of("status", "good")));
    }

    private Map<String, Object> broadcastUnbound() {
        return Map.of("id", "b1", "snippet", Map.of("liveChatId", "chat1", "title", "TestServer (live)"),
                "status", Map.of("lifeCycleStatus", lifecycle), "contentDetails", Map.of());
    }

    private Map<String, Object> broadcast() {
        return Map.of("id", "b1", "snippet", Map.of("liveChatId", "chat1", "title", "TestServer (live)"),
                "status", Map.of("lifeCycleStatus", lifecycle), "contentDetails", Map.of("boundStreamId", "s1"));
    }

    @Test
    void preparesStreamAndBroadcastThenGoesLiveAndEnds(@TempDir Path dir) throws Exception {
        BroadcastController c = start(dir);
        assertTrue(c.oauth().authorized());

        BroadcastController.Target t = c.prepare(false, Secret.EMPTY);
        assertTrue(t.viaApi());
        assertEquals("rtmp://a.rtmp.youtube.com/live2", t.rtmpBase());
        assertEquals("rtmp://b.rtmp.youtube.com/live2?backup=1", t.backupBase());
        assertEquals("api-key-0001", t.key().reveal());
        assertEquals("https://www.youtube.com/watch?v=b1", t.watchUrl());
        assertTrue(calls.stream().anyMatch(s -> s.startsWith("POST /yt/liveStreams")), "専用ストリームを作成");
        assertTrue(calls.stream().anyMatch(s -> s.startsWith("POST /yt/liveBroadcasts/bind")), "枠をストリームへ紐付け");

        // 2回目(再接続)は作り直さず同じ枠を使う
        calls.clear();
        BroadcastController.Target t2 = c.prepare(false, Secret.EMPTY);
        assertEquals("b1", t2.broadcastId());
        assertTrue(calls.stream().noneMatch(s -> s.startsWith("POST /yt/liveBroadcasts")), "既存の枠を再利用");

        // 映像が届くまでは live にならず、届いたら切り替わる
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                // test
            }
            streamStatus = "active";
        }).start();
        assertEquals(BroadcastController.GoLive.LIVE, c.goLive(t, () -> false));
        assertEquals("live", lifecycle);

        // 視聴者数
        List<Long> viewers = new ArrayList<>();
        c.tick(t, new BroadcastController.Sink() {
            @Override
            public void viewers(long count) {
                viewers.add(count);
            }

            @Override
            public void chat(String a, String m, boolean mod, boolean owner, boolean sc) {}

            @Override
            public void notice(String message) {}
        });
        assertEquals(List.of(42L), viewers);
        assertEquals("good", c.health());

        c.end(t);
        assertEquals("complete", lifecycle);
    }

    @Test
    void goLiveIsCancellable(@TempDir Path dir) throws Exception {
        BroadcastController c = start(dir);
        BroadcastController.Target t = c.prepare(false, Secret.EMPTY);
        long t0 = System.currentTimeMillis();
        assertEquals(BroadcastController.GoLive.CANCELLED, c.goLive(t, () -> System.currentTimeMillis() - t0 > 500));
        assertTrue(System.currentTimeMillis() - t0 < 5000);
    }

    @Test
    void notAuthorizedWithoutToken(@TempDir Path dir) throws Exception {
        BroadcastController c = start(dir);
        c.oauth().revoke();
        assertFalse(c.oauth().authorized());
        ApiException e = assertThrows(ApiException.class, () -> c.prepare(false, Secret.EMPTY));
        assertTrue(e.isAuth());
    }

    @Test
    void quotaReserveBlocksOptionalCalls() {
        QuotaMeter q = new QuotaMeter(1000, 200);
        q.charge(790);
        assertTrue(q.allowOptional(5));
        q.charge(10);
        assertFalse(q.allowOptional(5), "予約分には手を付けない");
        q.markExhausted();
        assertFalse(q.allowOptional(1));
    }
}
