package dev.ytstream.youtube;

import dev.ytstream.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * HTTPS通信の薄いラッパ(JDK標準HttpClientのみ)。
 * タイムアウト・応答サイズ上限・リダイレクト無効を強制し、エラーは ApiException に正規化する。
 */
final class ApiHttp {
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final HttpClient client;
    private volatile Duration timeout;

    ApiHttp(int timeoutSec) {
        setTimeout(timeoutSec);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    void setTimeout(int timeoutSec) {
        this.timeout = Duration.ofSeconds(Math.max(3, Math.min(120, timeoutSec)));
    }

    static String form(Map<String, String> params) {
        StringJoiner j = new StringJoiner("&");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            j.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return j.toString();
    }

    Map<String, Object> postForm(URI uri, Map<String, String> params) throws ApiException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", "YtStream")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        return send(req);
    }

    Map<String, Object> request(String method, URI uri, String bearer, Object jsonBody) throws ApiException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "YtStream");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (jsonBody != null) {
            b.header("Content-Type", "application/json; charset=UTF-8");
            b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(jsonBody), StandardCharsets.UTF_8));
        } else if (method.equals("GET")) {
            b.GET();
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return send(b.build());
    }

    private Map<String, Object> send(HttpRequest req) throws ApiException {
        HttpResponse<InputStream> res;
        try {
            res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ApiException(0, "network", "APIサーバーに接続できません: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted", "通信が中断されました");
        }
        String body;
        try (InputStream in = res.body()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) throw new ApiException(res.statusCode(), "tooLarge", "APIの応答が大きすぎます");
            body = new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(0, "network", "APIの応答を読み取れません: " + e.getMessage());
        }
        int code = res.statusCode();
        Map<String, Object> json;
        try {
            json = body.isBlank() ? Map.of() : Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            if (code >= 200 && code < 300) throw new ApiException(code, "badJson", "APIの応答を解析できません");
            json = Map.of();
        }
        if (code >= 200 && code < 300) return json;
        throw toError(code, json);
    }

    /** Google OAuth形式({"error":"invalid_grant"})とAPI形式({"error":{"errors":[{"reason":..}]}})の両方に対応。 */
    static ApiException toError(int code, Map<String, Object> json) {
        Object err = json.get("error");
        if (err instanceof String s) {
            String desc = Json.str(json, "error_description");
            return new ApiException(code, s, "HTTP " + code + " " + s + (desc.isEmpty() ? "" : " (" + desc + ")"));
        }
        String reason = "";
        String message = "";
        if (err instanceof Map<?, ?> m) {
            message = Json.str(m, "message");
            List<Object> errors = Json.arr(m, "errors");
            if (!errors.isEmpty()) reason = Json.str(errors.get(0), "reason");
            if (reason.isEmpty()) reason = Json.str(m, "status");
        }
        if (code == 401 && reason.isEmpty()) reason = "authError";
        return new ApiException(code, reason, "HTTP " + code + (reason.isEmpty() ? "" : " " + reason)
                + (message.isEmpty() ? "" : ": " + message));
    }
}
