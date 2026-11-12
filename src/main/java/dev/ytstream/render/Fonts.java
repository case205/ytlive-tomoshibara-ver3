package dev.ytstream.render;

import dev.ytstream.config.Settings;

import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** フォント読み込みとサイズ別キャッシュ。映像スレッド専用(非スレッドセーフ)。 */
final class Fonts {
    private final Font base;
    private final Map<Long, Font> cache = new HashMap<>();

    private Fonts(Font base) {
        this.base = base;
    }

    static Fonts load(Settings s, Path dataDir, List<String> notes) {
        Font base = null;
        if (s.fontFile() != null && !s.fontFile().isBlank()) {
            Path p = dataDir.resolve(s.fontFile()).normalize();
            if (Files.isRegularFile(p)) {
                try {
                    base = Font.createFont(Font.TRUETYPE_FONT, p.toFile());
                } catch (Exception e) {
                    notes.add("フォントファイルを読み込めません(" + p.getFileName() + "): " + e.getMessage());
                }
            } else {
                notes.add("フォントファイルが見つかりません: " + p.getFileName());
            }
        }
        if (base == null) base = new Font(s.fontName(), Font.PLAIN, 12);
        return new Fonts(base);
    }

    Font get(int style, float px) {
        long key = ((long) style << 32) | Float.floatToIntBits(px);
        return cache.computeIfAbsent(key, k -> base.deriveFont(style, px));
    }
}
