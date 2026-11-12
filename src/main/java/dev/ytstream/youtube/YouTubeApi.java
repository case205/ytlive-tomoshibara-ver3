package dev.ytstream.youtube;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** YouTube Data API v3 の呼び出し(認証ヘッダ付与・401時の1回再試行・クォータ計上)。 */
final class YouTubeApi {
    private final ApiHttp http;
    private final URI base;
    private final GoogleOAuth auth;
    private final QuotaMeter quota;

    YouTubeApi(ApiHttp http, URI base, GoogleOAuth auth, QuotaMeter quota) {
        this.http = http;
        this.base = base;
        this.auth = auth;
        this.quota = quota;
    }

    static Map<String, String> q(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    Map<String, Object> get(String path, Map<String, String> query, int cost) throws ApiException {
        return call("GET", path, query, null, cost);
    }

    Map<String, Object> post(String path, Map<String, String> query, Object body, int cost) throws ApiException {
        return call("POST", path, query, body, cost);
    }

    private Map<String, Object> call(String method, String path, Map<String, String> query, Object body, int cost)
            throws ApiException {
        URI uri = uri(path, query);
        quota.charge(cost);
        try {
            return http.request(method, uri, auth.accessToken(), body);
        } catch (ApiException e) {
            if (e.status() == 401 && !e.reason().equals("invalid_grant") && !e.reason().equals("notAuthorized")) {
                auth.invalidateAccess(); // アクセストークン期限切れ等 → 更新して1回だけ再試行
                quota.charge(cost);
                return http.request(method, uri, auth.accessToken(), body);
            }
            if (e.isQuota()) quota.markExhausted();
            throw e;
        }
    }

    private URI uri(String path, Map<String, String> query) {
        String b = base.toString();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        StringJoiner j = new StringJoiner("&");
        for (Map.Entry<String, String> e : query.entrySet()) {
            j.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(b + path + (query.isEmpty() ? "" : "?" + j));
    }
}
