package dev.ytstream.render;

import dev.ytstream.model.ServerModel.PlayerEntry;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/** 描画ヘルパ。 */
final class Draw {
    private Draw() {}

    /** 静的レイヤー用のパネル(やわらかい影・ガラス・縁・アクセント線)。起動時に1回だけ描かれる。 */
    static void staticPanel(RenderContext c, Rectangle r, boolean accentLine) {
        Graphics2D g = c.g;
        int arc = c.arc();
        if (c.v.shadow()) {
            Effects.dropShadow(g, r, arc, c.px(14), c.px(4), c.theme.light() ? 60 : 110);
        }
        fill(c, r, arc);
        if (accentLine && c.v.accentLine()) {
            Shape old = g.getClip();
            g.clip(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, arc, arc));
            Color a = c.theme.accent();
            int h = Math.max(2, c.px(3));
            g.setPaint(new GradientPaint(r.x, 0, new Color(a.getRed(), a.getGreen(), a.getBlue(), 235),
                    r.x + r.width, 0, new Color(a.getRed(), a.getGreen(), a.getBlue(), 0)));
            g.fillRect(r.x, r.y, r.width, h);
            g.setClip(old);
        }
    }

    /** 毎フレーム描く小さなパネル(通知など)。影は軽量版。 */
    static void panel(RenderContext c, Rectangle r, int arc) {
        Graphics2D g = c.g;
        if (c.v.shadow()) {
            g.setColor(new Color(0, 0, 0, c.theme.light() ? 30 : 60));
            g.fillRoundRect(r.x, r.y + c.px(3), r.width, r.height, arc, arc);
        }
        fill(c, r, arc);
    }

    private static void fill(RenderContext c, Rectangle r, int arc) {
        Graphics2D g = c.g;
        RoundRectangle2D shape = new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, arc, arc);
        if (c.glass != null) {
            g.setPaint(c.glass);
            g.fill(shape);
        }
        g.setColor(c.theme.panel());
        g.fill(shape);
        g.setColor(c.theme.border());
        g.setStroke(new BasicStroke(Math.max(1f, (float) c.sc)));
        g.draw(new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, arc, arc));
        g.setStroke(new BasicStroke(1f));
    }

    static void text(RenderContext c, String s, int x, int baseline, Color color) {
        Graphics2D g = c.g;
        if (c.v.textShadow()) {
            int o = Math.max(1, (int) Math.round(c.sc));
            g.setColor(c.theme.shadow());
            g.drawString(s, x + o, baseline + o);
        }
        g.setColor(color);
        g.drawString(s, x, baseline);
    }

    static Color nameColor(String name) {
        float hue = (name.hashCode() & 0xFFFF) / 65535f;
        return Color.getHSBColor(hue, 0.42f, 1f);
    }

    /** 明るいテーマでも読める名前色。 */
    static Color nameColor(RenderContext c, String name) {
        float hue = (name.hashCode() & 0xFFFF) / 65535f;
        return c.theme.light() ? Color.getHSBColor(hue, 0.75f, 0.55f) : Color.getHSBColor(hue, 0.42f, 1f);
    }

    static void head(Graphics2D g, PlayerEntry e, int x, int y, int size, Font fallbackFont) {
        var head = e.head;
        if (head != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(head, x, y, size, size, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        } else {
            g.setColor(nameColor(e.name).darker());
            g.fillRoundRect(x, y, size, size, size / 4, size / 4);
            g.setFont(fallbackFont);
            FontMetrics fm = g.getFontMetrics();
            String initial = e.name.isEmpty() ? "?" : e.name.substring(e.name.startsWith(".") && e.name.length() > 1 ? 1 : 0).substring(0, 1).toUpperCase();
            g.setColor(Color.WHITE);
            g.drawString(initial, x + (size - fm.stringWidth(initial)) / 2, y + (size + fm.getAscent() - fm.getDescent()) / 2);
        }
    }

    /** 小さなバッジ(角丸の色付き背景+白文字)。描いた幅を返す。 */
    static int badge(RenderContext c, String label, int x, int baseline, Font f, Color bg) {
        Graphics2D g = c.g;
        FontMetrics fm = g.getFontMetrics(f);
        int padX = Math.max(2, c.px(4));
        int w = fm.stringWidth(label) + padX * 2;
        int h = fm.getAscent() + fm.getDescent() / 2 + c.px(2);
        int top = baseline - fm.getAscent() - c.px(1);
        g.setColor(bg);
        g.fillRoundRect(x, top, w, h, h / 2, h / 2);
        g.setFont(f);
        g.setColor(Color.WHITE);
        g.drawString(label, x + padX, baseline - c.px(1));
        return w;
    }

    /** 電波バー(0〜4本、-1は不明で描かない)。 */
    static void pingBars(RenderContext c, int bars, int x, int bottom, int height) {
        if (bars < 0) return;
        Graphics2D g = c.g;
        int bw = Math.max(2, height / 5);
        int gap = Math.max(1, bw / 2);
        Color on = bars >= 3 ? new Color(96, 214, 104) : bars == 2 ? new Color(240, 200, 60) : new Color(240, 98, 98);
        for (int i = 0; i < 4; i++) {
            int h = height * (i + 1) / 4;
            g.setColor(i < bars ? on : c.theme.track());
            g.fillRect(x + i * (bw + gap), bottom - h, bw, h);
        }
    }

    static int pingBarsWidth(int height) {
        int bw = Math.max(2, height / 5);
        return bw * 4 + Math.max(1, bw / 2) * 3;
    }
}
