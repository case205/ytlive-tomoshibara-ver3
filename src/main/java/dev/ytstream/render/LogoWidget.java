package dev.ytstream.render;

import dev.ytstream.config.Settings;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** ロゴ/透かし画像(overlay.logo)。layout.logo の枠内に縦横比を保って収め、静的レイヤーへ焼き込む(毎フレームの負荷ゼロ)。 */
final class LogoWidget {
    private final BufferedImage image;
    private final float opacity;

    private LogoWidget(BufferedImage image, float opacity) {
        this.image = image;
        this.opacity = opacity;
    }

    static LogoWidget load(Settings s, Path dataDir, List<String> notes) {
        String p = s.visual().logoPath();
        if (p == null || p.isBlank()) return null;
        Path file = dataDir.resolve(p).normalize();
        if (!Files.isRegularFile(file)) {
            notes.add("ロゴ画像が見つかりません: " + file.getFileName());
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) {
                notes.add("ロゴ画像の形式を読めません: " + file.getFileName());
                return null;
            }
            if ((long) img.getWidth() * img.getHeight() > 16_000_000L) {
                notes.add("ロゴ画像が大きすぎます(最大 約16MP)");
                return null;
            }
            return new LogoWidget(img, s.visual().logoOpacity() / 100f);
        } catch (Exception e) {
            notes.add("ロゴ画像の読み込みに失敗しました: " + e.getMessage());
            return null;
        }
    }

    void renderStatic(RenderContext c) {
        Rectangle r = c.rect("logo");
        double scale = Math.min(r.width / (double) image.getWidth(), r.height / (double) image.getHeight());
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int x = r.x + (r.width - w) / 2;
        int y = r.y + (r.height - h) / 2;
        Graphics2D g = c.g;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, opacity))));
        g.drawImage(image, x, y, w, h, null);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }
}
