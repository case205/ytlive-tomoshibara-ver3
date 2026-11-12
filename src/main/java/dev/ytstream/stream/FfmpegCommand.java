package dev.ytstream.stream;

import dev.ytstream.config.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** FFmpeg起動引数の組み立て。シェルを介さず引数配列で渡すためコマンドインジェクションは起きない。 */
final class FfmpegCommand {
    private FfmpegCommand() {}

    /** 送信先URL(ベース + "/" + キー)。YouTubeのバックアップ取り込み(…/live2?backup=1)もこの形式。 */
    static String target(String base, String streamKey) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b + "/" + streamKey;
    }

    static List<String> build(Path exe, Settings s, String url, int audioPort) {
        List<String> c = new ArrayList<>();
        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        if (s.lowPriority() && linux && Files.isExecutable(Path.of("/usr/bin/nice"))) {
            c.add("/usr/bin/nice");
            c.add("-n");
            c.add("10");
        }
        c.add(exe.toString());
        c.addAll(List.of("-hide_banner", "-loglevel", "warning", "-nostats", "-progress", "pipe:1"));

        // 入力0: プラグインが生成する生フレーム(BGR24)をstdinから受け取る
        // send-only-changed-frames: 変化したフレームだけ送り、時刻は受信時刻で付ける(FFmpeg側で一定fpsへ補完)。
        // 静止画面中のパイプ転送と色変換がほぼ不要になり、CPU使用量が下がる。
        Settings.Video v = s.video();
        c.addAll(List.of("-f", "rawvideo", "-pix_fmt", "bgr24",
                "-video_size", s.width() + "x" + s.height()));
        if (v.sendChangedOnly()) c.addAll(List.of("-use_wallclock_as_timestamps", "1"));
        else c.addAll(List.of("-framerate", String.valueOf(s.fps())));
        c.addAll(List.of("-thread_queue_size", "512", "-i", "pipe:0"));
        // 入力1: BGM(プラグインがループバックTCPで送るPCM)。BGM無効/準備失敗時は無音
        // (YouTubeは音声トラックが無いと警告を出すため、常に音声トラックを付ける)
        if (s.audioEnabled()) {
            if (audioPort > 0) {
                c.addAll(List.of("-f", "s16le", "-ar", "44100", "-ac", "2", "-thread_queue_size", "1024",
                        "-i", "tcp://127.0.0.1:" + audioPort));
            } else {
                c.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"));
            }
        }
        c.addAll(List.of("-map", "0:v:0"));
        if (s.audioEnabled()) c.addAll(List.of("-map", "1:a:0"));

        int gop = s.fps() * 2; // YouTube推奨: キーフレーム間隔2秒
        boolean cbr = v.rateControl().equals("cbr");
        c.addAll(List.of("-c:v", s.codec()));
        if (s.codec().equals("libx264")) {
            c.addAll(List.of("-preset", s.preset()));
            if (!s.tune().isEmpty()) c.addAll(List.of("-tune", s.tune()));
            if (s.threads() > 0) c.addAll(List.of("-threads", String.valueOf(s.threads())));
            c.addAll(List.of("-sc_threshold", "0", "-keyint_min", String.valueOf(gop)));
            // YouTube推奨のCBR。静止画面でもビットレートが一定になり、受信側の「低ビットレート」警告を防ぐ
            String params = (cbr ? "nal-hrd=cbr:force-cfr=1" : "")
                    + (!v.x264Params().isEmpty() ? (cbr ? ":" : "") + v.x264Params() : "");
            if (!params.isEmpty()) c.addAll(List.of("-x264-params", params));
        } else if (s.codec().equals("h264_nvenc")) {
            c.addAll(List.of("-rc", cbr ? "cbr" : "vbr", "-forced-idr", "1"));
        }
        int max = cbr ? s.videoKbps() : s.videoKbps() * 5 / 4;
        if (v.sendChangedOnly()) c.addAll(List.of("-fps_mode", "cfr"));
        c.addAll(List.of("-pix_fmt", "yuv420p", "-r", String.valueOf(s.fps()), "-g", String.valueOf(gop),
                "-b:v", s.videoKbps() + "k", "-maxrate", max + "k", "-bufsize", (s.videoKbps() * 2) + "k"));
        if (s.audioEnabled()) {
            if (audioPort > 0) c.addAll(List.of("-af", "aresample=async=1000")); // 映像とのわずかなズレを吸収
            c.addAll(List.of("-c:a", "aac", "-b:a", s.audioKbps() + "k", "-ar", "44100", "-ac", "2"));
        }
        // 送信キューの詰まりで落ちないよう余裕を持たせ、FLVヘッダの後書き(ライブでは不要)を止める
        c.addAll(List.of("-max_muxing_queue_size", "1024", "-flvflags", "no_duration_filesize"));
        c.addAll(List.of("-f", "flv", url));
        return c;
    }
}
