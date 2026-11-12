package dev.ytstream.stream;

import dev.ytstream.config.Settings;
import dev.ytstream.config.UrlRules;
import dev.ytstream.model.ServerModel;
import dev.ytstream.render.FrameRenderer;
import dev.ytstream.util.Redactor;
import dev.ytstream.util.Secret;
import dev.ytstream.youtube.ApiException;
import dev.ytstream.youtube.BroadcastController;
import dev.ytstream.youtube.BroadcastController.Target;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 配信のライフサイクル管理。状態遷移はすべて単一の制御スレッド上で直列実行するため、
 * ロック不要かつレースが起きない。Bukkitのメインスレッドは待たせない。
 *
 * <p>通信を伴う準備(YouTube APIでの配信枠の用意・送信先への事前接続確認)は別の「通信スレッド」で行い、
 * 結果だけを制御スレッドへ戻す。これにより通信が遅くても stop などの操作は即座に効く。
 *
 * <p>開始の流れ: 設定/FFmpeg確認 → [通信スレッド] 配信枠の用意(API)・事前接続確認 → FFmpeg起動・映像送出
 * → 送信開始を確認 → [通信スレッド] YouTube側を「ライブ」へ切替(API)。
 */
public final class StreamManager {
    public enum State { STOPPED, STARTING, LIVE, RECONNECTING, ERROR }

    public interface Reporter {
        void info(String message);

        void error(String message);
    }

    public record Status(State state, long liveSinceMs, int attempt, int reconnects,
                         String speed, String bitrate, String ffmpegVersion, String lastError,
                         long framesSent, long framesReused, long framesDropped, long framesSkipped, long renderMicros,
                         int width, int height, int fps, List<String> problems,
                         String youtubeMode, String youtubePhase, String watchUrl, long viewers, String health,
                         int quotaUsed, int quotaBudget, boolean backupIngest) {}

    private final Path dataDir;
    private final ServerModel model;
    private final Logger log;
    private final Redactor redactor;
    private final Consumer<String> notifier;
    private final long jvmStartMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final ScheduledExecutorService control = daemonExecutor("ytstream-control");
    private final ScheduledExecutorService net = daemonExecutor("ytstream-net");
    private final BroadcastController youtube;

    private volatile Settings settings = Settings.defaults();
    private volatile Secret key = Secret.EMPTY;
    private volatile State state = State.STOPPED;
    private volatile String lastError;
    private volatile long liveSince;
    private volatile FramePump pump;
    private volatile FfmpegSession session;
    private volatile String ffmpegVersion = "-";
    private volatile int attempt;
    private volatile int reconnects;
    private volatile BgmFeeder feeder;
    private volatile Consumer<State> stateListener;
    private volatile Target target;
    private volatile String ytPhase = "-";
    private volatile boolean onBackup;
    private volatile BroadcastController.Sink ytSink;
    private final BgmControl bgm = new BgmControl();

    // 制御スレッドのみが触る
    private ScheduledFuture<?> pending;
    private ScheduledFuture<?> ytPoll;
    private boolean stopRequested;
    private FfmpegLocator.Info ffInfo;
    private long launchGen;
    private int failStreak;
    private long slowSinceMs;
    private boolean slowWarned;
    private boolean fallbackWarned;

    public StreamManager(Path dataDir, ServerModel model, Logger log, Redactor redactor, Consumer<String> notifier) {
        this.dataDir = dataDir;
        this.model = model;
        this.log = log;
        this.redactor = redactor;
        this.notifier = notifier;
        this.youtube = new BroadcastController(dataDir, log);
        // 死活監視: 送信停止・スレッド停止を検知して自動復旧(サーバー稼働中は配信を維持する)
        control.scheduleWithFixedDelay(this::watchdog, 10, 5, TimeUnit.SECONDS);
    }

    private static ScheduledExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    public BgmControl bgm() {
        return bgm;
    }

    public BroadcastController youtube() {
        return youtube;
    }

    public void setStateListener(Consumer<State> l) {
        this.stateListener = l;
    }

    /** YouTubeのチャット・視聴者数などの受け取り先。 */
    public void setYouTubeSink(BroadcastController.Sink sink) {
        this.ytSink = sink;
    }

    public void configure(Settings s, Secret k, Secret clientSecret) {
        this.settings = s;
        this.key = k;
        redactor.setSecret(k.reveal());
        redactor.addSecrets(clientSecret.reveal());
        youtube.configure(s.youtube(), clientSecret, s.serverName());
        redactor.addSecrets(youtube.secrets());
        bgm.setVolume(s.bgm().volume());
    }

    public State state() {
        return state;
    }

    /** 視聴用URL(API連携時は自動取得したURL、無ければ空)。 */
    public String watchUrl() {
        Target t = target;
        return t == null ? "" : t.watchUrl();
    }

    /** APIでの配信が利用可能な状態か(有効・クライアント設定済み・連携済み)。 */
    public boolean apiReady() {
        Settings s = settings;
        return s.youtube().enabled() && youtube.oauth().hasCredentials() && youtube.oauth().authorized();
    }

    // ---------------- 公開操作(すべて非同期) ----------------

    public void start(Reporter r) {
        execute(() -> startInternal(r));
    }

    public void stop(Reporter r) {
        execute(() -> {
            if (state == State.STOPPED) {
                r.info("配信はすでに停止しています。");
                return;
            }
            haltInternal(true);
            r.info("配信を停止しました。");
            notifier.accept("配信を停止しました。");
        });
    }

    public void restart(Reporter r) {
        execute(() -> {
            // 再起動では YouTube の配信枠を終了しない(同じURLのまま継続する)
            if (state != State.STOPPED) haltInternal(false);
            r.info("配信を再起動します…");
            startInternal(r);
        });
    }

    /** プラグイン無効化/サーバー停止時: 完全に後始末して全スレッドを終了する。 */
    public void shutdown() {
        try {
            control.submit(() -> haltInternal(true)).get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warning("配信停止処理が時間内に完了しませんでした: " + e);
        }
        control.shutdownNow();
        net.shutdown(); // 配信終了のAPI呼び出しが積まれていれば完了を待つ
        try {
            if (!net.awaitTermination(8, TimeUnit.SECONDS)) net.shutdownNow();
        } catch (InterruptedException e) {
            net.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public Status status() {
        FramePump p = pump;
        FfmpegSession s = session;
        FfmpegSession.Progress pr = s == null ? null : s.progress();
        Settings st = settings;
        Target t = target;
        String mode;
        if (!st.youtube().enabled()) mode = "ストリームキー方式";
        else if (!youtube.oauth().hasCredentials()) mode = "API(クライアント未設定)";
        else if (!youtube.oauth().authorized()) mode = "API(未連携: /ytstream auth)";
        else mode = t != null && !t.viaApi() ? "API→キー方式へフォールバック中" : "API連携";
        return new Status(state, liveSince, attempt, reconnects,
                pr == null ? "-" : pr.speed(), pr == null ? "-" : pr.bitrate(), ffmpegVersion, lastError,
                p == null ? 0 : p.sent.get(), p == null ? 0 : p.reused.get(), p == null ? 0 : p.dropped.get(),
                p == null ? 0 : p.skipped.get(), p == null ? 0 : p.lastRenderMicros,
                st.width(), st.height(), st.fps(), st.fatalProblems(),
                mode, ytPhase, t == null ? "" : t.watchUrl(), youtube.viewers(), youtube.health(),
                youtube.quotaUsed(), youtube.quotaBudget(), onBackup);
    }

    // ---------------- 内部(制御スレッド) ----------------

    private void startInternal(Reporter r) {
        if (state != State.STOPPED && state != State.ERROR) {
            r.error("すでに配信中です(状態: " + state + ")。");
            return;
        }
        stopRequested = false;
        attempt = 0;
        reconnects = 0;
        failStreak = 0;
        lastError = null;
        fallbackWarned = false;
        setState(State.STARTING);
        r.info("配信を開始しています…");
        try {
            launch(r);
        } catch (StreamException e) {
            lastError = redactor.redact(e.getMessage());
            setState(State.ERROR);
            log.warning("配信を開始できません: " + lastError);
            r.error("配信を開始できません: " + lastError);
        }
    }

    /** 同期で確認できる問題(設定・キー・FFmpeg)を確認し、通信を伴う準備を通信スレッドへ渡す。 */
    private void launch(Reporter r) throws StreamException {
        Settings s = settings;
        if (!s.fatalProblems().isEmpty()) {
            throw new StreamException("設定エラー: " + String.join(" / ", s.fatalProblems()), true);
        }
        boolean useApi = apiReady();
        if (!useApi && s.youtube().enabled() && !(s.youtube().fallbackToKey() && key.isSet())) {
            throw new StreamException(!youtube.oauth().hasCredentials()
                    ? "YouTube API連携が有効ですが、OAuthクライアントID/シークレットが未設定です(README「YouTube API連携」参照)。"
                    : "YouTubeアカウントが未連携です。/ytstream auth で連携してください。", true);
        }
        if (!useApi && !key.isSet()) {
            throw new StreamException("ストリームキーが未設定です。環境変数 YTSTREAM_KEY、plugins/YtStream/secrets.yml、"
                    + "または config.yml の youtube.stream-key に設定するか、YouTube API連携(/ytstream auth)を使ってください。", true);
        }
        if (!useApi && s.youtube().enabled() && !fallbackWarned) {
            fallbackWarned = true;
            notifier.accept("YouTube API が使えないため、ストリームキー方式で配信します"
                    + "(この場合、YouTube側で自動開始が有効でないと公開されないことがあります)。");
        }
        if (ffInfo == null || attempt == 0) ffInfo = verifyFfmpeg(s);
        ffmpegVersion = ffInfo.version();

        long gen = ++launchGen;
        boolean backup = useBackup(s);
        ytPhase = useApi ? "配信枠を準備中" : "-";
        Target cached = target;
        net.execute(() -> prepareAsync(gen, s, useApi, backup, cached, r));
    }

    /** 連続失敗が続いたらプライマリとバックアップの取り込みサーバーを交互に使う。 */
    private boolean useBackup(Settings s) {
        if (!s.net().backupEnabled()) return false;
        return (failStreak / s.net().backupAfterFailures()) % 2 == 1;
    }

    /** [通信スレッド] 送信先の決定と事前接続確認。 */
    private void prepareAsync(long gen, Settings s, boolean useApi, boolean backup, Target cached, Reporter r) {
        Target t;
        String fallbackReason = null;
        if (useApi) {
            try {
                t = youtube.prepare(s.rtmpUrl().toLowerCase().startsWith("rtmps"), key);
                redactor.addSecrets(t.key().reveal());
                redactor.addSecrets(youtube.secrets());
            } catch (ApiException e) {
                String msg = redactor.redact(e.getMessage());
                if (e.isTransient() && cached != null && cached.viaApi()) {
                    log.warning("YouTube APIに一時的に接続できないため、前回の送信先で再接続します: " + msg);
                    t = cached;
                } else if (s.youtube().fallbackToKey() && key.isSet()) {
                    fallbackReason = msg;
                    t = Target.plain(s.rtmpUrl(), s.net().backupUrl(), key);
                } else {
                    boolean fatal = e.isAuth() || (!e.isTransient() && !e.isQuota());
                    execute(() -> onPrepareFailed(gen, "YouTubeの配信枠を準備できません: " + msg, fatal));
                    return;
                }
            }
        } else {
            t = Target.plain(s.rtmpUrl(), s.net().backupUrl(), key);
        }
        String base = backup && !t.backupBase().isEmpty() ? t.backupBase() : t.rtmpBase();
        if (t.viaApi()) {
            // APIが返した送信先がYouTube以外を指すことは通常ないが、キー漏えい防止のため必ず検証する
            String err = UrlRules.checkRtmp(base, false, true);
            if (err != null) {
                execute(() -> onPrepareFailed(gen, "APIが返した送信先URLを検証できません: " + err, true));
                return;
            }
        }
        if (s.net().preflight()) {
            String pf = Preflight.check(base, s.net().preflightTimeoutSec());
            if (pf != null) {
                execute(() -> onPrepareFailed(gen, "接続の事前確認に失敗しました: " + pf, false));
                return;
            }
        }
        Target ft = t;
        String fr = fallbackReason;
        execute(() -> {
            if (gen != launchGen || stopRequested) return; // 停止/再起動済み
            if (fr != null) {
                log.warning("YouTube API の準備に失敗したため、ストリームキー方式で配信します: " + fr);
                if (!fallbackWarned) {
                    fallbackWarned = true;
                    notifier.accept("YouTube API の準備に失敗したため、ストリームキー方式で配信します。原因: " + fr);
                }
                ytPhase = "フォールバック(キー方式)";
            }
            try {
                launchWith(ft, base, s);
                if (r != null) r.info("FFmpegを起動しました。YouTubeへの接続を待っています(通常10〜30秒)。");
            } catch (StreamException e) {
                onPrepareFailed(gen, e.getMessage(), e.fatal());
            }
        });
    }

    private void onPrepareFailed(long gen, String reason, boolean fatal) {
        if (gen != launchGen || stopRequested) return;
        if (fatal) {
            lastError = redactor.redact(reason);
            ytPhase = "-";
            setState(State.ERROR);
            log.warning("配信を開始できません: " + lastError);
            notifier.accept("配信を開始できません。原因: " + lastError);
        } else {
            handleFailure(reason);
        }
    }

    private void launchWith(Target t, String base, Settings s) throws StreamException {
        FrameRenderer renderer;
        try {
            renderer = new FrameRenderer(s, model, dataDir, jvmStartMs);
        } catch (Exception | LinkageError e) {
            throw new StreamException("映像生成の初期化に失敗しました: " + e
                    + "(Linuxでは fontconfig と日本語フォント(例: fonts-noto-cjk)が必要な場合があります)");
        }
        renderer.notes().forEach(log::info);

        BgmFeeder f = null;
        int audioPort = 0;
        if (s.audioEnabled() && s.bgm().enabled()) {
            try {
                f = BgmFeeder.start(ffInfo.path(), dataDir.resolve(s.bgm().dir()).normalize(),
                        s.bgm().shuffle(), s.bgm().showNowPlaying(), bgm, model, log);
                audioPort = f.port();
            } catch (java.io.IOException e) {
                log.warning("BGMの準備に失敗したため無音で配信します: " + e.getMessage());
            }
        }

        FfmpegSession sess;
        try {
            String url = FfmpegCommand.target(base, t.key().reveal());
            sess = FfmpegSession.start(FfmpegCommand.build(ffInfo.path(), s, url, audioPort), redactor, log);
        } catch (java.io.IOException e) {
            renderer.close();
            if (f != null) f.close();
            throw new StreamException("FFmpegを起動できません: " + redactor.redact(e.getMessage()));
        }
        this.feeder = f;
        int keepalive = s.video().sendChangedOnly() ? s.video().keepaliveFps() : 0;
        FramePump p = new FramePump(renderer, sess.stdin(), s.fps(), keepalive, log);
        this.session = sess;
        this.pump = p;
        this.target = t;
        boolean backup = !base.equals(t.rtmpBase());
        if (backup != onBackup) log.info(backup ? "バックアップ取り込みサーバーへ送信します: " + redactor.redact(base)
                : "プライマリ取り込みサーバーへ送信します。");
        this.onBackup = backup;
        slowSinceMs = 0;
        slowWarned = false;
        p.start();

        int timeout = s.net().connectTimeoutSec();
        sess.exit().thenAccept(code -> execute(() -> onExit(sess, code)));
        sess.firstProgress().orTimeout(timeout, TimeUnit.SECONDS)
                .whenComplete((v, ex) -> execute(() -> onFirstProgress(sess, ex, timeout)));
    }

    private FfmpegLocator.Info verifyFfmpeg(Settings s) throws StreamException {
        Path exe = FfmpegLocator.find(s.ffmpegPath(), dataDir);
        if (exe == null) {
            throw new StreamException("FFmpegが見つかりません。" + FfmpegLocator.installHint(), true);
        }
        try {
            FfmpegLocator.Info info = FfmpegLocator.verify(exe, s.codec());
            if (!info.hasCodec()) {
                throw new StreamException("このFFmpegはエンコーダ「" + s.codec() + "」に対応していません。"
                        + "config.yml の video.codec を libx264 にするか、対応ビルドを導入してください。", true);
            }
            return info;
        } catch (java.io.IOException e) {
            throw new StreamException("FFmpegを実行できません: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StreamException("FFmpegの確認が中断されました。");
        }
    }

    private void onFirstProgress(FfmpegSession sess, Throwable timeout, int timeoutSec) {
        if (sess != session || stopRequested) return;
        if (timeout == null) {
            liveSince = System.currentTimeMillis();
            setState(State.LIVE);
            Target t = target;
            if (t != null && t.viaApi()) {
                notifier.accept("YouTubeへの送信を開始しました。ライブへの切り替えを確認しています…");
                goLiveAsync(sess, t);
                startPolling(t);
            } else {
                notifier.accept("YouTubeへの送信を開始しました(配信中)。");
            }
        } else {
            String reason = "YouTubeへの接続が" + timeoutSec + "秒以内に完了しませんでした。" + Diagnose.of(sess.tail());
            teardown();
            handleFailure(reason);
        }
    }

    private void goLiveAsync(FfmpegSession sess, Target t) {
        ytPhase = "ライブへ切替中";
        net.execute(() -> {
            BroadcastController.GoLive res = youtube.goLive(t, () -> session != sess || stopRequested);
            execute(() -> {
                if (sess != session || stopRequested) return;
                switch (res) {
                    case LIVE -> {
                        ytPhase = "公開中";
                        notifier.accept("YouTubeで公開中です: " + t.watchUrl());
                    }
                    case TIMEOUT -> {
                        ytPhase = "未公開(YouTube Studioで確認)";
                        notifier.accept("送信は届いていますが、YouTube側がライブになりません。YouTube Studioで状態を確認してください: "
                                + t.watchUrl());
                    }
                    case ENDED -> {
                        // YouTube Studio等で配信枠が終了された → 新しい枠で配信し直す
                        youtube.forget();
                        teardown();
                        handleFailure("YouTube側で配信枠が終了されました。新しい配信枠で再開します。");
                    }
                    default -> {
                        // CANCELLED: 停止/切断済み
                    }
                }
            });
        });
    }

    private void startPolling(Target t) {
        stopPolling();
        BroadcastController.Sink sink = ytSink;
        if (sink == null) return;
        ytPoll = net.scheduleWithFixedDelay(() -> {
            try {
                if (state == State.LIVE && target == t) youtube.tick(t, sink);
            } catch (RuntimeException e) {
                log.fine("YouTube情報の取得で例外: " + e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (ytPoll != null) {
            ytPoll.cancel(false);
            ytPoll = null;
        }
    }

    private void onExit(FfmpegSession sess, int code) {
        if (sess != session) return; // 意図的に停止済みの古いセッション
        String reason = "FFmpegが終了しました(終了コード " + code + ")。" + Diagnose.of(sess.tail());
        teardown();
        if (stopRequested) {
            setState(State.STOPPED);
            return;
        }
        handleFailure(reason);
    }

    private void handleFailure(String reason) {
        lastError = redactor.redact(reason);
        log.warning(lastError);
        Settings s = settings;
        long liveFor = liveSince > 0 ? System.currentTimeMillis() - liveSince : 0;
        boolean wasLive = liveSince > 0;
        if (liveFor > s.net().stableSec() * 1000L) { // しばらく安定して配信できていたら回数をリセット
            attempt = 0;
            failStreak = 0;
        }
        failStreak++;
        liveSince = 0;
        if (s.reconnectEnabled() && !stopRequested && (s.maxAttempts() == 0 || attempt < s.maxAttempts())) {
            attempt++;
            reconnects++;
            long base = Math.min(s.maxDelaySec(), (long) s.initialDelaySec() << Math.min(attempt - 1, 10));
            long delayMs = base * 1000L;
            if (s.net().jitter()) { // 再接続が一斉に集中しないよう ±20% ゆらす
                delayMs = Math.max(1000, (long) (delayMs * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4)));
            }
            setState(State.RECONNECTING);
            if (attempt <= 3 || attempt % 10 == 0) { // 失敗が続く間、通知が洪水にならないよう間引く
                notifier.accept((wasLive ? "配信が切断されました。" : "接続できませんでした。") + Math.round(delayMs / 1000.0)
                        + "秒後に" + (wasLive ? "再接続" : "再試行") + "します(" + attempt
                        + "/" + (s.maxAttempts() == 0 ? "∞" : s.maxAttempts()) + ")。原因: " + lastError);
            }
            pending = control.schedule(this::reconnect, delayMs, TimeUnit.MILLISECONDS);
        } else {
            setState(State.ERROR);
            notifier.accept("配信を継続できません。原因: " + lastError);
        }
    }

    private void reconnect() {
        pending = null;
        if (stopRequested || state != State.RECONNECTING) return;
        try {
            launch(null);
        } catch (StreamException e) {
            if (e.fatal()) { // 再試行しても直らない問題は即エラー確定
                lastError = redactor.redact(e.getMessage());
                setState(State.ERROR);
                notifier.accept("配信を継続できません。原因: " + lastError);
            } else {
                handleFailure(e.getMessage());
            }
        }
    }

    /** 送信停止(ネットワーク断でFFmpegが詰まる等)・内部スレッド停止・エンコード遅延を検知する。 */
    private void watchdog() {
        try {
            if (state != State.LIVE || stopRequested) return;
            FfmpegSession sess = session;
            FramePump p = pump;
            BgmFeeder f = feeder;
            if (sess == null) return;
            Settings s = settings;
            long now = System.currentTimeMillis();
            String reason = null;
            if (now - sess.lastProgressMs() > s.stallTimeoutSec() * 1000L) {
                reason = "送信が" + s.stallTimeoutSec() + "秒以上進んでいません(ネットワーク断の可能性)。";
            } else if (p != null && !p.isAlive()) {
                reason = "映像生成スレッドが停止しました。";
            } else if (p != null && (System.nanoTime() - p.lastWriteNs()) / 1_000_000L > s.stallTimeoutSec() * 1000L) {
                reason = "FFmpegへの映像入力が" + s.stallTimeoutSec() + "秒以上詰まっています。";
            } else if (f != null && !f.isAlive()) {
                reason = "BGM送出スレッドが停止しました。";
            }
            if (reason != null) {
                teardown();
                handleFailure(reason);
                return;
            }
            checkSlowEncode(sess, s, now);
        } catch (RuntimeException e) {
            log.warning("死活監視で例外が発生しました: " + e);
        }
    }

    private void checkSlowEncode(FfmpegSession sess, Settings s, long now) {
        if (!s.net().slowWarn() || slowWarned) return;
        FfmpegSession.Progress pr = sess.progress();
        double sp = pr == null ? -1 : pr.speedValue();
        if (sp < 0 || now - liveSince < 20_000) return;
        if (sp < 0.95) {
            if (slowSinceMs == 0) slowSinceMs = now;
            if (now - slowSinceMs > s.net().slowSec() * 1000L) {
                slowWarned = true;
                notifier.accept("エンコードが実時間に追いついていません(速度 " + pr.speed() + ")。視聴側で途切れる原因になります。"
                        + "video.fps / 解像度 / video.preset(ultrafast等)を見直すか、GPUエンコーダを使ってください。");
            }
        } else {
            slowSinceMs = 0;
        }
    }

    private void haltInternal(boolean endBroadcast) {
        stopRequested = true;
        launchGen++;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        Target t = target;
        teardown();
        if (endBroadcast && t != null && t.viaApi()) {
            try {
                net.execute(() -> youtube.end(t));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // シャットダウン済み
            }
            target = null;
        }
        liveSince = 0;
        ytPhase = "-";
        onBackup = false;
        model.setViewers(-1);
        setState(State.STOPPED);
    }

    /** 映像スレッド停止 → FFmpegへEOF通知 → 必要なら強制終了。 */
    private void teardown() {
        stopPolling();
        FramePump p = pump;
        FfmpegSession s = session;
        BgmFeeder f = feeder;
        pump = null;
        session = null;
        feeder = null;
        if (p != null) {
            p.stop();
            if (!p.join(1500)) { // stdin書き込みで詰まっている場合
                if (s != null) s.kill();
                p.join(1000);
            }
        }
        if (f != null) f.close();
        if (s != null) s.close();
    }

    private void setState(State n) {
        State old = state;
        state = n;
        if (old != n) {
            log.info("配信状態: " + old + " → " + n);
            Consumer<State> l = stateListener;
            if (l != null) {
                try {
                    l.accept(n);
                } catch (RuntimeException e) {
                    log.warning("状態通知で例外: " + e);
                }
            }
        }
    }

    private void execute(Runnable r) {
        try {
            control.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // シャットダウン済み
        }
    }
}
