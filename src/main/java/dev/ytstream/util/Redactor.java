package dev.ytstream.util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** ログ・エラーメッセージからストリームキーやトークンを確実に除去する。 */
public final class Redactor {
    private static final Pattern RTMP_URL =
            Pattern.compile("(rtmps?://[^/\\s'\"]+/[^/\\s'\"]+/)[^\\s'\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+");

    private volatile String secret = "";
    private volatile Set<String> extra = Set.of();

    /** 主となるシークレット(設定されたストリームキー)。 */
    public void setSecret(String secret) {
        this.secret = secret == null ? "" : secret;
    }

    /** 追加で伏せる値(APIから得たストリームキー、OAuthトークン等)。短すぎる値は誤爆防止のため無視。 */
    public synchronized void addSecrets(String... values) {
        Set<String> n = new LinkedHashSet<>(extra);
        for (String v : values) {
            if (v != null && v.length() >= 6) n.add(v);
        }
        while (n.size() > 32) n.remove(n.iterator().next());
        extra = Set.copyOf(n);
    }

    public String redact(String input) {
        if (input == null) return null;
        String out = input;
        String s = secret;
        if (!s.isEmpty()) out = out.replace(s, "****");
        for (String e : extra) out = out.replace(e, "****");
        out = BEARER.matcher(out).replaceAll("$1****");
        return RTMP_URL.matcher(out).replaceAll("$1****");
    }
}
