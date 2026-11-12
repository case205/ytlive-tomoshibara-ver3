package dev.ytstream.youtube;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * YouTube Data API のクォータ消費の見積もり(既定 10,000ユニット/日、太平洋時間0時リセット)。
 * 視聴者数・チャット取得などの「任意」の呼び出しは予約分を残して止め、
 * 配信開始/終了に必要な呼び出しが枯渇で失敗しないようにする。
 */
final class QuotaMeter {
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    private volatile int budget;
    private volatile int reserve;
    private LocalDate day = LocalDate.now(PACIFIC);
    private int used;
    private boolean exhausted;

    QuotaMeter(int budget, int reserve) {
        configure(budget, reserve);
    }

    void configure(int budget, int reserve) {
        this.budget = Math.max(100, budget);
        this.reserve = Math.max(0, Math.min(this.budget / 2, reserve));
    }

    private void rollover() {
        LocalDate today = LocalDate.now(PACIFIC);
        if (!today.equals(day)) {
            day = today;
            used = 0;
            exhausted = false;
        }
    }

    synchronized void charge(int units) {
        rollover();
        used += units;
    }

    /** API側で quotaExceeded が返ったとき(日付が変わるまで任意の呼び出しを止める)。 */
    synchronized void markExhausted() {
        rollover();
        exhausted = true;
        used = Math.max(used, budget);
    }

    /** 任意の呼び出し(視聴者数・チャット等)をしてよいか。 */
    synchronized boolean allowOptional(int units) {
        rollover();
        return !exhausted && used + units <= budget - reserve;
    }

    synchronized int used() {
        rollover();
        return used;
    }

    int budget() {
        return budget;
    }
}
