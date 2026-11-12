package dev.ytstream.config;

import java.net.URI;

/** 送信先URLの安全性ルール(Bukkit非依存)。 */
public final class UrlRules {
    private UrlRules() {}

    /** 送信先URLの検証(誤送信・キー漏えい防止)。問題なければ null。 */
    public static String checkRtmp(String url, boolean allowCustom, boolean allowBackupQuery) {
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            if (!(scheme.equals("rtmp") || scheme.equals("rtmps")) || host.isEmpty()) {
                return "rtmp:// または rtmps:// で始まる必要があります";
            }
            if (!allowCustom && !(host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
                return "ホスト(" + host + ")は youtube.com ではありません。"
                        + "他サービスへ送信する場合のみ youtube.allow-custom-host: true にしてください";
            }
            if (u.getUserInfo() != null) return "ユーザー情報は指定できません";
            if (u.getQuery() != null && !(allowBackupQuery && u.getQuery().equals("backup=1"))) {
                return "クエリは指定できません(ストリームキーは別項目です)";
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "不正なURLです";
        }
    }

}
