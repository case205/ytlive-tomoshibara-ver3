package dev.ytstream.render;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** 背景画像の読み込み(cover拡縮+可読性用グラデーション)。無い場合はMinecraft風の背景を自動生成。 */
final class BackgroundProvider {
    private BackgroundProvider() {}

    record Result(BufferedImage image, String note) {}

    static Result load(Path file, int w, int h) {
        String note = null;
        BufferedImage base = null;
        if (file != null) {
            if (!Files.isRegularFile(file)) {
                note = "背景画像が見つからないため内蔵の背景を使用します: " + file.getFileName();
            } else {
                try {
                    BufferedImage src = ImageIO.read(file.toFile());
                    if (src == null) {
                        note = "背景画像の形式を読めないため内蔵の背景を使用します: " + file.getFileName();
                    } else if ((long) src.getWidth() * src.getHeight() > 64_000_000L) {
                        note = "背景画像が大きすぎるため内蔵の背景を使用します(最大 約64MP)";
                    } else {
                        base = cover(src, w, h);
                    }
                } catch (IOException | RuntimeException e) {
                    note = "背景画像の読み込みに失敗したため内蔵の背景を使用します: " + e.getMessage();
                }
            }
        }
        if (base == null) base = procedural(w, h);
        shade(base);
        return new Result(base, note);
    }

    private static BufferedImage cover(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double scale = Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
        int dw = (int) Math.ceil(src.getWidth() * scale);
        int dh = (int) Math.ceil(src.getHeight() * scale);
        g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        g.dispose();
        return out;
    }

    /** 上下を暗くして文字を読みやすくする。 */
    private static void shade(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 110), 0, h * 0.28f, new Color(0, 0, 0, 0)));
        g.fillRect(0, 0, w, (int) (h * 0.28f));
        g.setPaint(new GradientPaint(0, h * 0.60f, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 130)));
        g.fillRect(0, (int) (h * 0.60f), w, h - (int) (h * 0.60f));
        g.dispose();
    }

    /** 著作権フリーのブロック風風景(固定シードなので毎回同じ)。 */
    static BufferedImage procedural(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x4F8FE8), 0, h * 0.72f, new Color(0xBFE0FF)));
        g.fillRect(0, 0, w, h);

        int b = Math.max(8, h / 30);
        Random rnd = new Random(20241108L);

        g.setColor(new Color(0xFFF3B0));
        g.fillRect((w * 3 / 4) / b * b, b * 3, b * 3, b * 3);

        g.setColor(new Color(255, 255, 255, 205));
        for (int i = 0; i < 7; i++) {
            int cx = rnd.nextInt(Math.max(1, w / b)) * b;
            int cy = (2 + rnd.nextInt(7)) * b;
            int wb = 4 + rnd.nextInt(5);
            g.fillRect(cx, cy, wb * b, b);
            g.fillRect(cx + b, cy - b, (wb - 2) * b, b);
        }

        int cols = w / b + 1;
        int rows = h / b + 1;
        int baseRow = (int) (rows * 0.62);
        int cur = baseRow;
        for (int c = 0; c < cols; c++) {
            if (rnd.nextInt(10) < 4) cur += rnd.nextInt(3) - 1;
            cur = Math.max(baseRow - 4, Math.min(baseRow + 3, cur));
            for (int r = cur; r < rows; r++) {
                int jitter = rnd.nextInt(17) - 8;
                Color col;
                if (r == cur) col = shift(new Color(0x5DAA45), jitter);
                else if (r < cur + 4) col = shift(new Color(0x8A5A32), jitter);
                else col = shift(new Color(0x7D7D7D), jitter);
                g.setColor(col);
                g.fillRect(c * b, r * b, b, b);
                g.setColor(new Color(0, 0, 0, 28));
                g.drawRect(c * b, r * b, b - 1, b - 1);
            }
            if (rnd.nextInt(14) == 0 && cur > 6) { // 木
                g.setColor(new Color(0x6B4A2B));
                g.fillRect(c * b, (cur - 3) * b, b, 3 * b);
                g.setColor(new Color(0x2F7A34));
                g.fillRect((c - 1) * b, (cur - 5) * b, 3 * b, 2 * b);
                g.fillRect(c * b, (cur - 6) * b, b, b);
            }
        }
        g.dispose();
        return img;
    }

    private static Color shift(Color c, int d) {
        return new Color(clamp(c.getRed() + d), clamp(c.getGreen() + d), clamp(c.getBlue() + d));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
