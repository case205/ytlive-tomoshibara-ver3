package dev.ytstream.stream;

import java.util.List;
import java.util.Locale;

/** FFmpegのエラー出力から、利用者向けの原因ヒントを作る。 */
final class Diagnose {
    private Diagnose() {}

    static String of(List<String> tail) {
        String all = String.join("\n", tail).toLowerCase(Locale.ROOT);
        String last = tail.isEmpty() ? "(出力なし)" : tail.get(tail.size() - 1);
        String hint;
        if (all.contains("could not resolve") || all.contains("name or service not known")
                || all.contains("no address associated") || all.contains("network is unreachable")) {
            hint = "RTMPサーバーの名前解決/ネットワークに失敗しました。DNS・ネットワークを確認してください。";
        } else if (all.contains("connection refused") || all.contains("timed out") || all.contains("connection timed out")) {
            hint = "RTMPサーバーに接続できません。ファイアウォールでTCP 1935(rtmps は443)の送信が許可されているか確認してください。";
        } else if (all.contains("unknown encoder") || all.contains("encoder not found")
                || all.contains("error initializing output stream") || all.contains("cannot load")) {
            hint = "選択したエンコーダが使えません。config.yml の video.codec を libx264 にしてください。";
        } else if (all.contains("input/output error") || all.contains("connection reset")
                || all.contains("broken pipe") || all.contains("handshake") || all.contains("rtmp_")
                || all.contains("server error") || all.contains("end of file")) {
            hint = "YouTubeに接続を拒否/切断されました。ストリームキーの誤り、YouTube Studioでライブ配信が未有効、"
                    + "または同じキーで別の配信が行われている可能性があります。";
        } else if (all.contains("cannot allocate memory") || all.contains("out of memory")) {
            hint = "メモリ不足です。解像度/ビットレートを下げてください。";
        } else {
            hint = "原因を特定できませんでした。";
        }
        return hint + " [FFmpeg: " + last + "]";
    }
}
