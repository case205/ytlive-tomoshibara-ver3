package dev.ytstream.stream;

import dev.ytstream.render.FrameRenderer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

/**
 * 専用スレッドでフレームを生成しFFmpegのstdinへ書き込む。Bukkitメインスレッドは一切使わない。
 * 遅れた場合は最大5フレームまで追いつき、それ以上は時刻を再同期(=落とす)する。
 */
final class FramePump implements Runnable {
    private final FrameRenderer renderer;
    private final OutputStream out;
    private final int fps;
    private final long keepaliveNs;
    private final Logger log;
    private final Thread thread;
    private volatile boolean running = true;

    final AtomicLong sent = new AtomicLong();
    final AtomicLong reused = new AtomicLong();
    final AtomicLong dropped = new AtomicLong();
    final AtomicLong skipped = new AtomicLong();
    /** 直近の1フレーム描画時間(マイクロ秒)。 */
    volatile long lastRenderMicros;
    private volatile long lastWriteNs = System.nanoTime();

    /**
     * @param keepaliveFps 0 なら毎フレーム送信(固定fps入力)。正の値なら変化したフレームだけを送り、
     *                     変化が無い間もこの頻度で前フレームを再送する(FFmpeg側で一定fpsへ補完)
     */
    FramePump(FrameRenderer renderer, OutputStream out, int fps, int keepaliveFps, Logger log) {
        this.renderer = renderer;
        this.out = out;
        this.fps = fps;
        this.keepaliveNs = keepaliveFps <= 0 ? 0 : 1_000_000_000L / Math.min(fps, keepaliveFps);
        this.log = log;
        this.thread = new Thread(this, "ytstream-frames");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    /** 最後にFFmpegへ書き込めた時刻(stdinの詰まり検知用)。 */
    long lastWriteNs() {
        return lastWriteNs;
    }

    boolean join(long ms) {
        try {
            thread.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    @Override
    public void run() {
        long frameNs = 1_000_000_000L / fps;
        long next = System.nanoTime();
        byte[] data = renderer.data();
        boolean renderErrorLogged = false;
        try {
            while (running) {
                boolean rendered = false;
                long t0 = System.nanoTime();
                try {
                    rendered = renderer.render(System.currentTimeMillis(), false);
                    if (rendered) lastRenderMicros = (System.nanoTime() - t0) / 1000;
                } catch (RuntimeException e) {
                    // 描画の不具合で配信を落とさない: 直前のフレームを送り続ける
                    if (!renderErrorLogged) {
                        renderErrorLogged = true;
                        log.warning("映像描画でエラーが発生しました(直前のフレームを継続します): " + e);
                    }
                }
                long nowNs = System.nanoTime();
                boolean first = sent.get() == 0;
                if (keepaliveNs == 0 || rendered || first || nowNs - lastWriteNs >= keepaliveNs) {
                    out.write(data);
                    out.flush();
                    lastWriteNs = System.nanoTime();
                    sent.incrementAndGet();
                    if (!rendered) reused.incrementAndGet();
                } else {
                    skipped.incrementAndGet();
                }

                next += frameNs;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                } else if (wait < -5 * frameNs) {
                    dropped.addAndGet((-wait) / frameNs);
                    next = System.nanoTime();
                }
            }
        } catch (IOException ignored) {
            // FFmpeg側が閉じた。終了検知は FfmpegSession.exit() が担当
        } finally {
            renderer.close();
        }
    }
}
