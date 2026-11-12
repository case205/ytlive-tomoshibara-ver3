package dev.ytstream.stream;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * FFmpeg起動前の接続確認(名前解決+TCP接続)。
 * ネットワーク断の間にFFmpegを起動しては25秒待って失敗、を繰り返さず、原因を即座に特定して再試行間隔へ回す。
 */
final class Preflight {
    private Preflight() {}

    /** 問題なければ null、問題があれば利用者向けの説明を返す。 */
    static String check(String rtmpBase, int timeoutSec) {
        URI u;
        try {
            u = URI.create(rtmpBase.replace("?backup=1", ""));
        } catch (IllegalArgumentException e) {
            return "送信先URLが不正です";
        }
        String host = u.getHost();
        if (host == null) return "送信先URLにホストがありません";
        int port = u.getPort() > 0 ? u.getPort() : "rtmps".equalsIgnoreCase(u.getScheme()) ? 443 : 1935;
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "送信先(" + host + ")の名前解決に失敗しました。DNS・インターネット接続を確認してください。";
        }
        IOException last = null;
        for (InetAddress a : addrs) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(a, port), timeoutSec * 1000);
                return null;
            } catch (IOException e) {
                last = e;
            }
        }
        return "送信先(" + host + ":" + port + ")へ接続できません(" + (last == null ? "不明" : last.getClass().getSimpleName())
                + ")。ファイアウォールで TCP " + port + " の送信が許可されているか確認してください。";
    }
}
