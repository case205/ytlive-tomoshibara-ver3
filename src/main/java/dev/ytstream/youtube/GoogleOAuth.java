package dev.ytstream.youtube;

import dev.ytstream.util.Json;
import dev.ytstream.util.Secret;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Google OAuth 2.0「TV・入力制限デバイス向け」フロー。
 * サーバーにはブラウザが無いため、表示したURLとコードを管理者が自分のPC/スマホで入力して許可する。
 * 得られたリフレッシュトークンは plugins/YtStream/youtube-token.properties(権限600)に保存し、
 * アクセストークンはメモリ上でのみ保持・自動更新する。
 */
public final class GoogleOAuth {
    public static final String SCOPE = "https://www.googleapis.com/auth/youtube";
    static final String TOKEN_FILE = "youtube-token.properties";

    public record DeviceCode(String deviceCode, String userCode, String verificationUrl, int expiresInSec, int intervalSec) {}

    public enum PollResult { PENDING, SLOW_DOWN, SUCCESS, DENIED, EXPIRED }

    private final ApiHttp http;
    private final URI base;
    private final Path tokenFile;

    private volatile String clientId = "";
    private volatile Secret clientSecret = Secret.EMPTY;
    private volatile Secret refreshToken = Secret.EMPTY;
    private volatile Secret accessToken = Secret.EMPTY;
    private volatile long accessExpiresMs;

    GoogleOAuth(ApiHttp http, URI base, Path dataDir) {
        this.http = http;
        this.base = base;
        this.tokenFile = dataDir.resolve(TOKEN_FILE);
        loadToken();
    }

    private URI endpoint(String path) {
        String b = base.toString();
        return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path);
    }

    void setCredentials(String id, Secret secret) {
        boolean changed = !id.equals(clientId);
        this.clientId = id == null ? "" : id;
        this.clientSecret = secret == null ? Secret.EMPTY : secret;
        if (changed) accessToken = Secret.EMPTY;
    }

    public boolean hasCredentials() {
        return !clientId.isEmpty() && clientSecret.isSet();
    }

    public boolean authorized() {
        return refreshToken.isSet();
    }

    /** ログ伏せ字用(現在のトークン類)。 */
    String[] secrets() {
        return new String[]{clientSecret.reveal(), refreshToken.reveal(), accessToken.reveal()};
    }

    public DeviceCode requestDeviceCode() throws ApiException {
        requireCredentials();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_id", clientId);
        p.put("scope", SCOPE);
        Map<String, Object> r = http.postForm(endpoint("/device/code"), p);
        String url = Json.str(r, "verification_url");
        if (url.isEmpty()) url = Json.str(r, "verification_uri");
        DeviceCode dc = new DeviceCode(Json.str(r, "device_code"), Json.str(r, "user_code"), url,
                (int) Json.num(r, "expires_in", 1800), (int) Math.max(5, Json.num(r, "interval", 5)));
        if (dc.deviceCode().isEmpty() || dc.userCode().isEmpty()) {
            throw new ApiException(200, "badResponse", "認証コードを取得できませんでした");
        }
        return dc;
    }

    public PollResult poll(DeviceCode dc) throws ApiException {
        requireCredentials();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_id", clientId);
        p.put("client_secret", clientSecret.reveal());
        p.put("device_code", dc.deviceCode());
        p.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        try {
            Map<String, Object> r = http.postForm(endpoint("/token"), p);
            String refresh = Json.str(r, "refresh_token");
            if (refresh.isEmpty()) throw new ApiException(200, "noRefreshToken", "リフレッシュトークンが返されませんでした");
            refreshToken = new Secret(refresh);
            acceptAccess(r);
            saveToken();
            return PollResult.SUCCESS;
        } catch (ApiException e) {
            return switch (e.reason()) {
                case "authorization_pending" -> PollResult.PENDING;
                case "slow_down" -> PollResult.SLOW_DOWN;
                case "access_denied" -> PollResult.DENIED;
                case "expired_token" -> PollResult.EXPIRED;
                default -> throw e;
            };
        }
    }

    /** 有効なアクセストークン(期限60秒前に自動更新)。 */
    public synchronized String accessToken() throws ApiException {
        if (accessToken.isSet() && System.currentTimeMillis() < accessExpiresMs - 60_000) return accessToken.reveal();
        refresh();
        return accessToken.reveal();
    }

    /** 401を受けたときに呼ぶ(次回 accessToken() で必ず更新)。 */
    public void invalidateAccess() {
        accessToken = Secret.EMPTY;
    }

    private void refresh() throws ApiException {
        requireCredentials();
        if (!refreshToken.isSet()) {
            throw new ApiException(401, "notAuthorized", "YouTubeアカウントが未連携です。/ytstream auth で連携してください");
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_id", clientId);
        p.put("client_secret", clientSecret.reveal());
        p.put("refresh_token", refreshToken.reveal());
        p.put("grant_type", "refresh_token");
        try {
            acceptAccess(http.postForm(endpoint("/token"), p));
        } catch (ApiException e) {
            if (e.reason().equals("invalid_grant")) {
                // 取り消し・期限切れ(テスト公開中のアプリは7日で失効)。再認証が必要
                throw new ApiException(401, "invalid_grant",
                        "YouTube連携が失効しました。/ytstream auth で再連携してください(" + e.getMessage() + ")");
            }
            throw e;
        }
    }

    private void acceptAccess(Map<String, Object> r) throws ApiException {
        String at = Json.str(r, "access_token");
        if (at.isEmpty()) throw new ApiException(200, "noAccessToken", "アクセストークンが返されませんでした");
        accessToken = new Secret(at);
        accessExpiresMs = System.currentTimeMillis() + Math.max(120, Json.num(r, "expires_in", 3600)) * 1000L;
    }

    /** 連携解除(Google側でも取り消しを試み、ローカルのトークンを削除)。 */
    public void revoke() {
        Secret rt = refreshToken;
        refreshToken = Secret.EMPTY;
        accessToken = Secret.EMPTY;
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
            // 次回起動時に再試行される
        }
        if (rt.isSet()) {
            try {
                http.postForm(endpoint("/revoke"), Map.of("token", rt.reveal()));
            } catch (ApiException ignored) {
                // 既に無効でも問題ない
            }
        }
    }

    private void requireCredentials() throws ApiException {
        if (!hasCredentials()) {
            throw new ApiException(400, "noClient", "OAuthクライアントID/シークレットが未設定です"
                    + "(環境変数 YTSTREAM_CLIENT_ID / YTSTREAM_CLIENT_SECRET か secrets.yml)");
        }
    }

    private void loadToken() {
        if (!Files.isRegularFile(tokenFile)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(tokenFile)) {
            p.load(in);
            String rt = p.getProperty("refresh_token", "").trim();
            if (!rt.isEmpty()) refreshToken = new Secret(rt);
        } catch (IOException | IllegalArgumentException ignored) {
            // 壊れていれば未連携扱い
        }
    }

    private void saveToken() throws ApiException {
        Properties p = new Properties();
        p.setProperty("refresh_token", refreshToken.reveal());
        p.setProperty("scope", SCOPE);
        p.setProperty("saved_at", String.valueOf(System.currentTimeMillis()));
        try {
            Files.createDirectories(tokenFile.getParent());
            Path tmp = tokenFile.resolveSibling(TOKEN_FILE + ".tmp");
            Files.deleteIfExists(tmp);
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp); // Windows
            }
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "YtStream YouTube OAuth token - DO NOT SHARE");
            }
            try {
                Files.move(tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ApiException(0, "io", "トークンを保存できません: " + e.getMessage());
        }
    }
}
