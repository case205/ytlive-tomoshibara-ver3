package dev.ytstream.command;

import dev.ytstream.YtStreamPlugin;
import dev.ytstream.config.Settings;
import dev.ytstream.render.FrameRenderer;
import dev.ytstream.stream.BgmLibrary;
import dev.ytstream.stream.FfmpegLocator;
import dev.ytstream.stream.StreamManager;
import dev.ytstream.youtube.ApiException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import javax.imageio.ImageIO;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class YtStreamCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "ytstream.admin";
    private static final List<String> SUBS = List.of("start", "stop", "restart", "status", "reload", "preview", "ffmpeg", "bgm", "auth", "notice");
    private static final List<String> BGM_SUBS = List.of("skip", "volume", "mute", "unmute", "list");
    private static final List<String> AUTH_SUBS = List.of("start", "status", "cancel", "logout");

    private final YtStreamPlugin plugin;
    private final AtomicInteger authGen = new AtomicInteger();

    public YtStreamCommand(YtStreamPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("このコマンドを使う権限がありません。", NamedTextColor.RED));
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        StreamManager.Reporter rep = reporter(sender);
        switch (sub) {
            case "start" -> plugin.manager().start(rep);
            case "stop" -> plugin.manager().stop(rep);
            case "restart" -> plugin.manager().restart(rep);
            case "status" -> status(sender);
            case "reload" -> {
                plugin.applyConfig();
                sender.sendMessage(Component.text("設定を再読み込みしました。", NamedTextColor.GREEN));
                if (plugin.manager().state() != StreamManager.State.STOPPED) {
                    sender.sendMessage(Component.text("実行中の配信には /ytstream restart 後に反映されます。", NamedTextColor.YELLOW));
                }
                var problems = plugin.settings().fatalProblems();
                for (String p : problems) sender.sendMessage(Component.text("設定エラー: " + p, NamedTextColor.RED));
            }
            case "preview" -> preview(sender);
            case "ffmpeg" -> ffmpeg(sender);
            case "bgm" -> bgm(sender, args);
            case "auth" -> auth(sender, args);
            case "notice" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("使い方: /ytstream notice <配信画面に出すお知らせ>", NamedTextColor.YELLOW));
                } else {
                    plugin.showNotice(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                    sender.sendMessage(Component.text("[YtStream] 配信画面にお知らせを表示しました。", NamedTextColor.GREEN));
                }
            }
            default -> sender.sendMessage(Component.text(
                    "使い方: /" + label + " <start|stop|restart|status|reload|preview|ffmpeg|bgm|auth|notice>", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("bgm")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return BGM_SUBS.stream().filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auth")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return AUTH_SUBS.stream().filter(s -> s.startsWith(p)).toList();
        }
        if (args.length != 1) return List.of();
        String p = args[0].toLowerCase(Locale.ROOT);
        return SUBS.stream().filter(s -> s.startsWith(p)).toList();
    }

    private StreamManager.Reporter reporter(CommandSender s) {
        return new StreamManager.Reporter() {
            @Override
            public void info(String m) {
                send(s, m, NamedTextColor.GREEN);
            }

            @Override
            public void error(String m) {
                send(s, m, NamedTextColor.RED);
            }
        };
    }

    /** 制御/非同期スレッドから呼ばれるため、メインスレッドへ戻して送信する。 */
    private void send(CommandSender s, String m, NamedTextColor color) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Component.text("[YtStream] " + m, color)));
        } catch (RuntimeException ignored) {
            // 無効化処理中
        }
    }

    private void status(CommandSender sender) {
        StreamManager.Status st = plugin.manager().status();
        NamedTextColor c = switch (st.state()) {
            case LIVE -> NamedTextColor.GREEN;
            case STARTING, RECONNECTING -> NamedTextColor.YELLOW;
            case ERROR -> NamedTextColor.RED;
            default -> NamedTextColor.GRAY;
        };
        sender.sendMessage(Component.text("===== YtStream 状態 =====", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("状態: " + st.state(), c));
        if (st.state() == StreamManager.State.LIVE && st.liveSinceMs() > 0) {
            long sec = (System.currentTimeMillis() - st.liveSinceMs()) / 1000;
            sender.sendMessage(Component.text(String.format("配信時間: %d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60), NamedTextColor.WHITE));
        }
        sender.sendMessage(Component.text(st.width() + "x" + st.height() + " @" + st.fps() + "fps"
                + " / 速度 " + st.speed() + " / ビットレート " + st.bitrate(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("フレーム: 送信 " + st.framesSent() + " / 再利用 " + st.framesReused()
                + " / 省略 " + st.framesSkipped() + " / 破棄 " + st.framesDropped()
                + " / 描画 " + String.format(Locale.ROOT, "%.1fms", st.renderMicros() / 1000.0)
                + "  再接続回数: " + st.reconnects() + (st.backupIngest() ? "(バックアップ取り込み使用中)" : ""), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("YouTube: " + st.youtubeMode()
                + (st.youtubePhase().equals("-") ? "" : " / " + st.youtubePhase())
                + (st.viewers() >= 0 ? " / 視聴者 " + st.viewers() : "")
                + (st.health().equals("-") ? "" : " / 受信状態 " + st.health())
                + (plugin.settings().youtube().enabled() ? " / API使用量 " + st.quotaUsed() + "/" + st.quotaBudget() : ""),
                NamedTextColor.GRAY));
        String url = plugin.liveUrl();
        if (!url.isEmpty()) {
            sender.sendMessage(Component.text("配信URL: " + url, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)));
        }
        sender.sendMessage(Component.text("FFmpeg: " + st.ffmpegVersion(), NamedTextColor.GRAY));
        String np = plugin.model().nowPlaying();
        sender.sendMessage(Component.text("BGM: " + (np.isEmpty() ? "-" : np) + " / 音量 " + plugin.manager().bgm().volume() + "%"
                + (plugin.manager().bgm().muted() ? "(ミュート中)" : ""), NamedTextColor.GRAY));
        if (st.lastError() != null) {
            sender.sendMessage(Component.text("直近のエラー: " + st.lastError(), NamedTextColor.RED));
        }
        for (String p : st.problems()) sender.sendMessage(Component.text("設定エラー: " + p, NamedTextColor.RED));
        if (st.speed() != null && st.speed().matches("0\\.[0-8].*x.*")) {
            sender.sendMessage(Component.text("エンコード速度が1.0xを下回っています。解像度/FPS/ビットレートを下げるか、preset を上げてください。", NamedTextColor.YELLOW));
        }
    }

    /** 配信せずに現在の配信画面をPNG保存(レイアウト調整用)。 */
    private void preview(CommandSender sender) {
        StreamManager.Reporter rep = reporter(sender);
        Settings s = plugin.settings();
        Path dir = plugin.getDataFolder().toPath();
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FrameRenderer r = new FrameRenderer(s, plugin.model(), dir, start)) {
                r.render(System.currentTimeMillis(), true);
                Path out = dir.resolve("preview.png");
                ImageIO.write(r.image(), "png", out.toFile());
                rep.info("プレビューを保存しました: " + out.toAbsolutePath());
            } catch (Exception | LinkageError e) {
                rep.error("プレビュー生成に失敗しました: " + e
                        + "(Linuxでは fontconfig と日本語フォントが必要な場合があります)");
            }
        });
    }

    private void bgm(CommandSender sender, String[] args) {
        var ctl = plugin.manager().bgm();
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "skip" -> {
                ctl.requestSkip();
                sender.sendMessage(Component.text("[YtStream] 次の曲へスキップします。", NamedTextColor.GREEN));
            }
            case "mute", "unmute" -> {
                ctl.setMuted(sub.equals("mute"));
                sender.sendMessage(Component.text("[YtStream] BGMを" + (sub.equals("mute") ? "ミュート" : "ミュート解除") + "しました。", NamedTextColor.GREEN));
            }
            case "volume" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("[YtStream] 現在の音量: " + ctl.volume() + "% (使い方: /ytstream bgm volume <0-100>)", NamedTextColor.YELLOW));
                    return;
                }
                try {
                    ctl.setVolume(Integer.parseInt(args[2]));
                    sender.sendMessage(Component.text("[YtStream] BGM音量を " + ctl.volume() + "% にしました(再読込で設定値へ戻ります)。", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("[YtStream] 0〜100の数値で指定してください。", NamedTextColor.RED));
                }
            }
            case "list" -> {
                Settings s = plugin.settings();
                var tracks = BgmLibrary.scan(plugin.getDataFolder().toPath().resolve(s.bgm().dir()).normalize());
                sender.sendMessage(Component.text("[YtStream] BGM " + tracks.size() + "曲 (" + s.bgm().dir() + "/)"
                        + (tracks.isEmpty() ? " — mp3/ogg/wav/flac/m4a/opus を置くと自動で再生されます" : ""), NamedTextColor.AQUA));
                for (int i = 0; i < Math.min(15, tracks.size()); i++) {
                    sender.sendMessage(Component.text(" - " + BgmLibrary.title(tracks.get(i)), NamedTextColor.GRAY));
                }
            }
            default -> sender.sendMessage(Component.text("使い方: /ytstream bgm <skip|volume|mute|unmute|list>", NamedTextColor.YELLOW));
        }
    }

    /**
     * YouTubeアカウント連携(OAuth デバイスフロー)。表示されたURLを開き、コードを入力して許可する。
     * サーバー側でブラウザは不要。トークンは plugins/YtStream/youtube-token.properties(権限600)に保存。
     */
    private void auth(CommandSender sender, String[] args) {
        StreamManager.Reporter rep = reporter(sender);
        var yt = plugin.manager().youtube();
        var oauth = yt.oauth();
        String sub = args.length < 2 ? "start" : args[1].toLowerCase(Locale.ROOT);
        if (!plugin.settings().youtube().enabled() && !sub.equals("logout")) {
            sender.sendMessage(Component.text("[YtStream] 注意: config.yml の youtube.api.enabled が false です。連携後に true にして /ytstream reload してください。",
                    NamedTextColor.YELLOW));
        }
        switch (sub) {
            case "status" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!oauth.hasCredentials()) {
                    rep.error("OAuthクライアントID/シークレットが未設定です(README「YouTube API連携」参照)。");
                } else if (!oauth.authorized()) {
                    rep.error("YouTubeアカウントは未連携です。/ytstream auth で連携してください。");
                } else {
                    try {
                        rep.info("連携済み: " + yt.channelTitle() + "(API使用量 本日 " + yt.quotaUsed() + "/" + yt.quotaBudget() + ")");
                    } catch (ApiException e) {
                        rep.error("連携の確認に失敗しました: " + e.getMessage());
                    }
                }
            });
            case "cancel" -> {
                authGen.incrementAndGet();
                rep.info("連携の待機を中止しました。");
            }
            case "logout" -> {
                authGen.incrementAndGet();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    oauth.revoke();
                    rep.info("YouTubeとの連携を解除しました(保存されていたトークンを削除しました)。");
                });
            }
            default -> {
                if (!oauth.hasCredentials()) {
                    rep.error("OAuthクライアントID/シークレットが未設定です。Google Cloud で「テレビと入力が限られたデバイス」の"
                            + "OAuthクライアントを作成し、環境変数 YTSTREAM_CLIENT_ID / YTSTREAM_CLIENT_SECRET か secrets.yml に設定してください。");
                    return;
                }
                int gen = authGen.incrementAndGet();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runDeviceFlow(sender, rep, oauth, yt, gen));
            }
        }
    }

    private void runDeviceFlow(CommandSender sender, StreamManager.Reporter rep, dev.ytstream.youtube.GoogleOAuth oauth,
                               dev.ytstream.youtube.BroadcastController yt, int gen) {
        dev.ytstream.youtube.GoogleOAuth.DeviceCode dc;
        try {
            dc = oauth.requestDeviceCode();
        } catch (ApiException e) {
            rep.error("認証コードを取得できません: " + e.getMessage());
            return;
        }
        String url = dc.verificationUrl();
        Component msg = Component.text("[YtStream] YouTube連携: ", NamedTextColor.AQUA)
                .append(Component.text(url, NamedTextColor.WHITE).clickEvent(ClickEvent.openUrl(url)))
                .append(Component.text(" を開き、コード ", NamedTextColor.AQUA))
                .append(Component.text(dc.userCode(), NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.copyToClipboard(dc.userCode())))
                .append(Component.text(" を入力して許可してください(" + Math.max(1, dc.expiresInSec() / 60) + "分以内)。", NamedTextColor.AQUA));
        sendComponent(sender, msg);
        long deadline = System.currentTimeMillis() + dc.expiresInSec() * 1000L;
        int interval = dc.intervalSec();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (authGen.get() != gen || !plugin.isEnabled()) return; // 中止/新しい連携が開始された
            try {
                switch (oauth.poll(dc)) {
                    case SUCCESS -> {
                        String ch;
                        try {
                            ch = yt.channelTitle();
                        } catch (ApiException e) {
                            ch = "(チャンネル名を取得できませんでした: " + e.getMessage() + ")";
                        }
                        rep.info("YouTubeとの連携が完了しました: " + ch
                                + (plugin.settings().youtube().enabled() ? "。次の /ytstream start から配信枠を自動で用意します。"
                                : "。config.yml の youtube.api.enabled を true にして /ytstream reload してください。"));
                        return;
                    }
                    case SLOW_DOWN -> interval += 5;
                    case DENIED -> {
                        rep.error("連携が拒否されました。");
                        return;
                    }
                    case EXPIRED -> {
                        rep.error("コードの有効期限が切れました。もう一度 /ytstream auth を実行してください。");
                        return;
                    }
                    default -> {
                        // 許可待ち
                    }
                }
            } catch (ApiException e) {
                if (!e.isTransient()) {
                    rep.error("連携に失敗しました: " + e.getMessage());
                    return;
                }
            }
        }
        rep.error("コードの有効期限が切れました。もう一度 /ytstream auth を実行してください。");
    }

    private void sendComponent(CommandSender s, Component c) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(c));
        } catch (RuntimeException ignored) {
            // 無効化処理中
        }
    }

    private void ffmpeg(CommandSender sender) {
        StreamManager.Reporter rep = reporter(sender);
        Settings s = plugin.settings();
        Path dir = plugin.getDataFolder().toPath();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Path exe = FfmpegLocator.find(s.ffmpegPath(), dir);
            if (exe == null) {
                rep.error("FFmpegが見つかりません。" + FfmpegLocator.installHint());
                return;
            }
            try {
                FfmpegLocator.Info info = FfmpegLocator.verify(exe, s.codec());
                rep.info("FFmpeg OK: " + info.version() + " (" + exe + ")");
                if (!info.hasCodec()) rep.error("エンコーダ「" + s.codec() + "」に未対応です。video.codec を libx264 にしてください。");
            } catch (Exception e) {
                rep.error("FFmpegを実行できません: " + e.getMessage());
            }
        });
    }
}
