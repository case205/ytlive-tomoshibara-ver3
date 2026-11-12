package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配信フレームの生成。1インスタンス=1スレッド専用。
 *
 * <p>性能設計:
 * <ul>
 *   <li>出力はTYPE_3BYTE_BGRのバイト配列そのもの(FFmpegのbgr24入力にそのまま書ける。変換ゼロ)</li>
 *   <li>背景・パネル・影・ぼかし・ロゴ・見出しなど変化しない部分は起動時に1回だけ「静的レイヤー」へ焼き込む</li>
 *   <li>部分再描画: 変化した部品の領域だけを静的レイヤーから復元して描き直す(チャットが流れてもヘッダー等は触らない)</li>
 *   <li>状態が変わらず、アニメーションも無いフレームは再描画せず前フレームを再利用(CPUほぼゼロ)</li>
 * </ul>
 */
public final class FrameRenderer implements AutoCloseable {
    private final Settings s;
    private final ServerModel model;
    private final long startMs;
    private final BufferedImage image;
    private final byte[] data;
    private final byte[] baseData;
    private final int width;
    private final Graphics2D g;
    private final RenderContext ctx;
    private final List<Widget> widgets = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final Rectangle[] areas;
    private final boolean[][] overlaps;
    private final boolean[] wasAnimating;
    private final long[] lastTick;
    private final boolean partial;
    private final boolean staticCache;

    private long lastVersion = -1;
    private boolean first = true;
    private long fullRedraws;
    private long partialRedraws;

    public FrameRenderer(Settings s, ServerModel model, Path dataDir, long startMs) {
        this.s = s;
        this.model = model;
        this.startMs = startMs;
        this.width = s.width();
        this.image = new BufferedImage(s.width(), s.height(), BufferedImage.TYPE_3BYTE_BGR);
        this.data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        Settings.Visual v = s.visual();
        this.partial = v.partialRedraw();
        this.staticCache = v.staticCache();

        Path bgPath = s.backgroundPath() == null || s.backgroundPath().isBlank()
                ? null : dataDir.resolve(s.backgroundPath()).normalize();
        BackgroundProvider.Result bg = BackgroundProvider.load(bgPath, s.width(), s.height());
        if (bg.note() != null) notes.add(bg.note());
        BufferedImage base = bg.image();

        Fonts fonts = Fonts.load(s, dataDir, notes);
        Theme theme = Theme.byName(s.themeName(), s.accentColor(), s.panelOpacity(), v.panelColor(), v.textColor());
        Paint glass = null;
        if (v.style().equals("glass") && v.blurRadius() > 0) {
            BufferedImage blurred = Effects.blur(base, (int) Math.round(v.blurRadius() * s.height() / 720.0));
            glass = new TexturePaint(blurred, new Rectangle(0, 0, s.width(), s.height()));
        }

        widgets.add(new HeaderWidget(s));
        widgets.add(new FooterWidget(s));
        if (s.showPlayerList()) widgets.add(new PlayersWidget(s));
        widgets.add(new ToastWidget(s)); // 参加/退出・実績・お知らせ(/ytstream notice)
        if (s.showChat()) widgets.add(new ChatWidget(s));
        LogoWidget logo = LogoWidget.load(s, dataDir, notes);

        // 静的レイヤー(背景+パネル+ロゴ)
        Graphics2D bgG = base.createGraphics();
        hints(bgG);
        RenderContext staticCtx = new RenderContext(bgG, s, theme, model, fonts, glass);
        if (logo != null) logo.renderStatic(staticCtx);
        if (staticCache) {
            for (Widget w : widgets) {
                w.renderStatic(staticCtx);
                bgG.setClip(null);
                bgG.setComposite(AlphaComposite.SrcOver);
            }
        }
        bgG.dispose();
        this.baseData = ((DataBufferByte) base.getRaster().getDataBuffer()).getData();

        this.g = image.createGraphics();
        hints(g);
        this.ctx = new RenderContext(g, s, theme, model, fonts, glass);
        // fontconfig未導入環境をここで早期に検出(失敗時は例外→配信開始時に分かりやすく表示)
        g.getFontMetrics(fonts.get(Font.PLAIN, 12f)).getHeight();

        int n = widgets.size();
        areas = new Rectangle[n];
        Rectangle screen = new Rectangle(0, 0, s.width(), s.height());
        for (int i = 0; i < n; i++) areas[i] = widgets.get(i).area(ctx).intersection(screen);
        overlaps = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) overlaps[i][j] = i != j && areas[i].intersects(areas[j]);
        }
        wasAnimating = new boolean[n];
        lastTick = new long[n];
        java.util.Arrays.fill(lastTick, -1);
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public List<String> notes() {
        return notes;
    }

    /** FFmpegへ書き込むフレームのバイト配列(同一配列を使い回す)。 */
    public byte[] data() {
        return data;
    }

    public BufferedImage image() {
        return image;
    }

    public long fullRedraws() {
        return fullRedraws;
    }

    public long partialRedraws() {
        return partialRedraws;
    }

    /** @return 再描画したら true。false の場合 data() は前フレームのまま有効。 */
    public boolean render(long now, boolean force) {
        int upSec = (int) Math.max(0, (now - startMs) / 1000);
        long version = model.version();
        int n = widgets.size();
        boolean[] dirty = new boolean[n];
        boolean any = false;
        boolean all = force || first || version != lastVersion || !partial;
        for (int i = 0; i < n; i++) {
            Widget w = widgets.get(i);
            boolean anim = w.isAnimating(now, model);
            boolean d = anim || wasAnimating[i]; // アニメ終了直後の1回で最終状態に落ち着かせる
            int hz = w.tickHz();
            if (hz > 0) {
                long tick = now * hz / 1000;
                if (tick != lastTick[i]) {
                    lastTick[i] = tick;
                    d = true;
                }
            }
            wasAnimating[i] = anim;
            dirty[i] = d;
            any |= d;
        }
        if (!all && !any) return false;

        ctx.now = now;
        ctx.uptimeSec = upSec;
        if (all) {
            System.arraycopy(baseData, 0, data, 0, data.length);
            for (Widget w : widgets) draw(w, null);
            fullRedraws++;
        } else {
            // 重なっている部品は一緒に描き直す(復元で相手の絵が消えるため)
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < n; i++) {
                    if (!dirty[i]) continue;
                    for (int j = 0; j < n; j++) {
                        if (overlaps[i][j] && !dirty[j]) {
                            dirty[j] = true;
                            changed = true;
                        }
                    }
                }
            }
            for (int i = 0; i < n; i++) if (dirty[i]) restore(areas[i]);
            for (int i = 0; i < n; i++) if (dirty[i]) draw(widgets.get(i), areas[i]);
            partialRedraws++;
        }
        lastVersion = version;
        first = false;
        return true;
    }

    private void draw(Widget w, Rectangle clip) {
        if (clip != null) g.setClip(clip);
        if (!staticCache) w.renderStatic(ctx);
        w.render(ctx);
        g.setComposite(AlphaComposite.SrcOver);
        g.setClip(null);
    }

    /** 静的レイヤーから矩形領域を行単位でコピーして復元。 */
    private void restore(Rectangle r) {
        int rowBytes = width * 3;
        int x0 = r.x * 3;
        int len = r.width * 3;
        if (len <= 0) return;
        for (int y = r.y; y < r.y + r.height; y++) {
            int off = y * rowBytes + x0;
            System.arraycopy(baseData, off, data, off, len);
        }
    }

    @Override
    public void close() {
        g.dispose();
    }
}
