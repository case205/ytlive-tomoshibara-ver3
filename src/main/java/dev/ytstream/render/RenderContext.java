package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;

import java.awt.*;

/** 描画中の共有情報。レンダラにつき1つを使い回す(毎フレームの生成をしない)。 */
final class RenderContext {
    final Graphics2D g;
    final Settings s;
    final Settings.Visual v;
    final Theme theme;
    final ServerModel model;
    final Fonts fonts;
    final double sc; // 720pを基準にした拡大率
    /** ガラス調パネルの中身(ぼかした背景)。flat のときは null。 */
    final Paint glass;
    long now;
    int uptimeSec;

    RenderContext(Graphics2D g, Settings s, Theme theme, ServerModel model, Fonts fonts, Paint glass) {
        this.g = g;
        this.s = s;
        this.v = s.visual();
        this.theme = theme;
        this.model = model;
        this.fonts = fonts;
        this.glass = glass;
        this.sc = s.height() / 720.0;
    }

    Rectangle rect(String key) {
        return s.rect(key).toPixels(s.width(), s.height());
    }

    /** 720p基準のpx指定をスケールしたフォント。 */
    Font font(int style, double designPx) {
        return fonts.get(style, (float) (designPx * sc));
    }

    /** 実ピクセル指定のフォント。 */
    Font fontPx(int style, double px) {
        return fonts.get(style, (float) Math.max(6, px));
    }

    int px(double design) {
        return (int) Math.round(design * sc);
    }

    /** 角丸の大きさ(theme.corner-radius、720p基準)。 */
    int arc() {
        return px(v.cornerRadius());
    }

    static double easeOutCubic(double t) {
        double c = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - c, 3);
    }
}
