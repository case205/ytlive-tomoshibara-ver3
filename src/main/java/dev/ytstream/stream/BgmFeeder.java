package dev.ytstream.stream;

import dev.ytstream.model.ServerModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * BGM送出。ループバック(127.0.0.1)のTCPで、配信用FFmpegへ「44.1kHz/16bit/ステレオのPCM」を実時間で流し続ける。
 *
 * <p>曲ごとに別のFFmpegでデコードするため、mp3/ogg/flac等が混在していても継ぎ目なく再生でき、
 * 曲の追加・音量変更・スキップを配信を止めずに行える。曲が無い/壊れている場合は無音を送り続け、
 * 音声入力が途切れて配信が落ちることはない。
 */
final class BgmFeeder {
    private static final int RATE = 44100;
    private static final int FRAME = 4; // 16bit x 2ch
    private static final int BYTES_PER_SEC = RATE * FRAME;
    private static final int CHUNK = BYTES_PER_SEC / 50; // 20ms

    private final ServerSocket server;
    private final Thread thread;
    private final Path ffmpeg;
    private final Path dir;
    private final boolean shuffle;
    private final boolean showTitle;
    private final BgmControl ctl;
    private final ServerModel model;
    private final Logger log;
    private final Deque<Path> queue = new ArrayDeque<>();

    private volatile boolean running = true;
    private volatile Process decoder;
    private Path last;
    private long nextNs;
    private float curGain = -1f;
    private boolean warnedEmpty;

    private BgmFeeder(ServerSocket server, Path ffmpeg, Path dir, boolean shuffle, boolean showTitle,
                      BgmControl ctl, ServerModel model, Logger log) {
        this.server = server;
        this.ffmpeg = ffmpeg;
        this.dir = dir;
        this.shuffle = shuffle;
        this.showTitle = showTitle;
        this.ctl = ctl;
        this.model = model;
        this.log = log;
        this.thread = new Thread(this::run, "ytstream-bgm");
        this.thread.setDaemon(true);
    }

    static BgmFeeder start(Path ffmpeg, Path dir, boolean shuffle, boolean showTitle,
                           BgmControl ctl, ServerModel model, Logger log) throws IOException {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ss.setSoTimeout(30_000);
        BgmFeeder f = new BgmFeeder(ss, ffmpeg, dir, shuffle, showTitle, ctl, model, log);
        f.thread.start();
        return f;
    }

    int port() {
        return server.getLocalPort();
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        Process d = decoder;
        if (d != null) d.destroyForcibly();
        try {
            thread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        model.setNowPlaying("");
    }

    private void run() {
        try (ServerSocket ss = server; Socket sock = ss.accept()) {
            sock.setTcpNoDelay(true);
            OutputStream out = sock.getOutputStream();
            // FFmpegの入力解析を即座に終わらせるため、最初に1秒分の無音を一気に送る
            out.write(new byte[BYTES_PER_SEC]);
            nextNs = System.nanoTime();
            int failures = 0;
            while (running) {
                Path track = nextTrack();
                if (track == null) {
                    model.setNowPlaying("");
                    silence(out, 1000);
                    continue;
                }
                boolean ok;
                try {
                    ok = playTrack(track, out);
                } catch (IOException e) {
                    throw e; // FFmpeg側が閉じた
                } catch (RuntimeException e) {
                    ok = false;
                }
                failures = ok ? 0 : failures + 1;
                if (failures >= 3) { // 壊れた曲ばかりでも空回りさせない
                    model.setNowPlaying("");
                    silence(out, 2000);
                }
            }
        } catch (IOException ignored) {
            // 接続されなかった/FFmpegが終了した
        } finally {
            model.setNowPlaying("");
        }
    }

    private Path nextTrack() {
        if (queue.isEmpty()) {
            List<Path> all = BgmLibrary.scan(dir);
            if (all.isEmpty()) {
                if (!warnedEmpty) {
                    warnedEmpty = true;
                    log.info("BGMフォルダに音声ファイルがありません(" + dir.getFileName() + "/)。無音で配信します。追加すると自動で再生されます。");
                }
                return null;
            }
            warnedEmpty = false;
            if (shuffle) {
                Collections.shuffle(all);
                if (all.size() > 1 && all.get(0).equals(last)) Collections.swap(all, 0, all.size() - 1);
            }
            queue.addAll(all);
        }
        last = queue.pollFirst();
        return last;
    }

    /**
     * 1曲を再生する。デコード結果は別スレッドでキューに溜め、送出側は常に実時間で動く。
     * ディスクやデコーダが一時的に詰まっても無音を挿入するだけで、配信用FFmpegへの音声入力は途切れない。
     */
    private boolean playTrack(Path track, OutputStream out) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-v", "error",
                "-i", track.toString(), "-vn", "-f", "s16le", "-ar", String.valueOf(RATE), "-ac", "2", "pipe:1");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        decoder = p;

        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64);
        AtomicBoolean eof = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                while (true) {
                    byte[] b = new byte[CHUNK];
                    int n = readFully(in, b);
                    n -= n % FRAME;
                    if (n <= 0) break;
                    queue.put(n == CHUNK ? b : java.util.Arrays.copyOf(b, n));
                }
            } catch (IOException | InterruptedException ignored) {
                // 終了/スキップ
            } finally {
                eof.set(true);
            }
        }, "ytstream-bgm-read");
        reader.setDaemon(true);
        reader.start();

        byte[] zero = new byte[CHUNK];
        long total = 0;
        long fadeStart = 0;
        long underrunChunks = 0;
        boolean announced = false;
        try {
            while (running) {
                if (ctl.consumeSkip()) break;
                byte[] chunk = queue.poll();
                if (chunk == null) {
                    if (eof.get() && queue.isEmpty()) break;
                    java.util.Arrays.fill(zero, (byte) 0);
                    write(out, zero, CHUNK, 0f); // 詰まり中は無音で時間を進める
                    if (++underrunChunks > 500) break; // 10秒以上詰まったら曲を諦める
                    continue;
                }
                underrunChunks = 0;
                if (!announced) {
                    announced = true;
                    fadeStart = System.nanoTime();
                    if (showTitle) model.setNowPlaying(BgmLibrary.title(track));
                }
                float fade = Math.min(1f, (System.nanoTime() - fadeStart) / 5e8f); // 0.5秒フェードイン
                write(out, chunk, chunk.length, ctl.gain() * fade);
                total += chunk.length;
            }
        } finally {
            p.destroyForcibly();
            reader.interrupt();
            decoder = null;
        }
        return total > BYTES_PER_SEC;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private void silence(OutputStream out, int ms) throws IOException {
        byte[] z = new byte[CHUNK];
        for (int i = 0; i < ms / 20 && running; i++) {
            java.util.Arrays.fill(z, (byte) 0);
            write(out, z, CHUNK, 0f);
        }
    }

    /** ゲイン適用→送信→実時間ペーシング(遅れすぎたら時刻を再同期)。 */
    private void write(OutputStream out, byte[] buf, int len, float gain) throws IOException {
        applyGain(buf, len, curGain < 0 ? gain : curGain, gain);
        curGain = gain;
        out.write(buf, 0, len);
        nextNs += len * 1_000_000_000L / BYTES_PER_SEC;
        long wait = nextNs - System.nanoTime();
        if (wait > 0) LockSupport.parkNanos(wait);
        else if (wait < -1_000_000_000L) nextNs = System.nanoTime();
    }

    /** 16bit LE PCMへ from→to の線形ゲインを適用(クリップ付き)。 */
    static void applyGain(byte[] b, int len, float from, float to) {
        if (from == 1f && to == 1f) return;
        for (int i = 0; i + 1 < len; i += 2) {
            float g = from + (to - from) * (i / (float) len);
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            int v = Math.round(s * g);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            b[i] = (byte) v;
            b[i + 1] = (byte) (v >> 8);
        }
    }
}
