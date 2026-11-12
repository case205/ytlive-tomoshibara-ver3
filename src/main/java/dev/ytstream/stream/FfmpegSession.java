package dev.ytstream.stream;

import dev.ytstream.util.Redactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** 1回分のFFmpegプロセス。stderr/stdoutは専用スレッドで常に読み捨て(パイプ詰まり防止)、キーは必ず伏せる。 */
final class FfmpegSession {
    record Progress(long frames, String fps, String bitrate, String speed) {
        /** "1.01x" → 1.01。不明なら -1。 */
        double speedValue() {
            try {
                String t = speed.trim();
                if (t.endsWith("x")) t = t.substring(0, t.length() - 1);
                return Double.parseDouble(t);
            } catch (RuntimeException e) {
                return -1;
            }
        }
    }

    private final Process process;
    private final Redactor redactor;
    private final Logger log;
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private final CompletableFuture<Void> firstProgress = new CompletableFuture<>();
    private volatile Progress progress;
    private volatile long lastProgressMs = System.currentTimeMillis();

    private FfmpegSession(Process p, Redactor redactor, Logger log) {
        this.process = p;
        this.redactor = redactor;
        this.log = log;
    }

    static FfmpegSession start(List<String> cmd, Redactor redactor, Logger log) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("YTSTREAM_KEY"); // 子プロセスへキーを環境変数経由で渡さない
        Process p = pb.start();
        FfmpegSession s = new FfmpegSession(p, redactor, log);
        daemon("ytstream-ffmpeg-err", s::drainErr);
        daemon("ytstream-ffmpeg-out", s::drainOut);
        return s;
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    OutputStream stdin() {
        return process.getOutputStream();
    }

    CompletableFuture<Void> firstProgress() {
        return firstProgress;
    }

    CompletableFuture<Integer> exit() {
        return process.onExit().thenApply(Process::exitValue);
    }

    /** 最後にFFmpegが進行状況を出した時刻。止まっていれば送信が詰まっている。 */
    long lastProgressMs() {
        return lastProgressMs;
    }

    Progress progress() {
        return progress;
    }

    List<String> tail() {
        synchronized (tail) {
            return new ArrayList<>(tail);
        }
    }

    private void drainErr() {
        int logged = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("Guessed Channel Layout")) continue; // 無害な通知
                String safe = redactor.redact(line);
                synchronized (tail) {
                    tail.addLast(safe);
                    while (tail.size() > 30) tail.removeFirst();
                }
                if (logged < 20) { // ログ洪水防止
                    logged++;
                    log.warning("[ffmpeg] " + safe);
                }
            }
        } catch (IOException ignored) {
            // プロセス終了
        }
    }

    private void drainOut() {
        Map<String, String> cur = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq);
                String v = line.substring(eq + 1).trim();
                if (k.equals("progress")) {
                    long frames = 0;
                    try {
                        frames = Long.parseLong(cur.getOrDefault("frame", "0").trim());
                    } catch (NumberFormatException ignored) {
                        // keep 0
                    }
                    progress = new Progress(frames, cur.getOrDefault("fps", "?"),
                            cur.getOrDefault("bitrate", "?"), cur.getOrDefault("speed", "?"));
                    lastProgressMs = System.currentTimeMillis();
                    firstProgress.complete(null); // 出力初期化=RTMP接続完了
                    cur.clear();
                } else {
                    cur.put(k, v);
                }
            }
        } catch (IOException ignored) {
            // プロセス終了
        }
    }

    void kill() {
        process.destroyForcibly();
    }

    /** stdinを閉じて自然終了を待ち(FLVを正常に閉じる)、ダメなら段階的に強制終了。 */
    void close() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // すでに閉じている
        }
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
