package dev.ytstream.render;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** 起動時に1回だけ使う重めの画像処理(ぼかし・影)。毎フレームの処理では使わない。 */
final class Effects {
    private Effects() {}

    /** 3回の箱ぼかし(≒ガウスぼかし)。返り値は TYPE_INT_RGB。 */
    static BufferedImage blur(BufferedImage src, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        if (radius <= 0) return out;
        int[] px = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        int[] tmp = new int[px.length];
        int r = Math.max(1, radius / 2);
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, r);
            boxV(tmp, px, w, h, r);
        }
        return out;
    }

    /** アルファ付き画像(影用)をぼかす。 */
    static void blurArgb(BufferedImage img, int radius) {
        if (radius <= 0) return;
        int w = img.getWidth(), h = img.getHeight();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] tmp = new int[px.length];
        int r = Math.max(1, radius / 2);
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, r);
            boxV(tmp, px, w, h, r);
        }
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, w)];
                sa += p >>> 24;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[row + clamp(x - r, w)];
                int pIn = in[row + clamp(x + r + 1, w)];
                sa += (pIn >>> 24) - (pOut >>> 24);
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, h) * w + x];
                sa += p >>> 24;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[clamp(y - r, h) * w + x];
                int pIn = in[clamp(y + r + 1, h) * w + x];
                sa += (pIn >>> 24) - (pOut >>> 24);
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : v >= n ? n - 1 : v;
    }

    /** 角丸パネルのやわらかい影を描く(静的レイヤー用)。 */
    static void dropShadow(Graphics2D g, Rectangle r, int arc, int blur, int offsetY, int alpha) {
        if (alpha <= 0 || r.width <= 0 || r.height <= 0) return;
        int m = blur * 2 + 2;
        BufferedImage s = new BufferedImage(r.width + m * 2, r.height + m * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = s.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.setColor(new Color(0, 0, 0, Math.min(255, alpha)));
        sg.fill(new RoundRectangle2D.Float(m, m, r.width, r.height, arc, arc));
        sg.dispose();
        blurArgb(s, blur);
        g.drawImage(s, r.x - m, r.y - m + offsetY, null);
    }
}
