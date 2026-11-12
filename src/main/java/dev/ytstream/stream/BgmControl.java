package dev.ytstream.stream;

import java.util.concurrent.atomic.AtomicBoolean;

/** BGMの実行時操作(音量・ミュート・スキップ)。配信セッションをまたいで保持される。 */
public final class BgmControl {
    private volatile int volume = 40;
    private volatile boolean muted;
    private final AtomicBoolean skip = new AtomicBoolean();

    public void setVolume(int percent) {
        volume = Math.max(0, Math.min(100, percent));
    }

    public int volume() {
        return volume;
    }

    public void setMuted(boolean m) {
        muted = m;
    }

    public boolean muted() {
        return muted;
    }

    public void requestSkip() {
        skip.set(true);
    }

    boolean consumeSkip() {
        return skip.getAndSet(false);
    }

    /** 0〜100% を聴感に合う二乗カーブのゲインへ変換。 */
    float gain() {
        if (muted) return 0f;
        double v = volume / 100.0;
        return (float) (v * v);
    }
}
